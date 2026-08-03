/*******************************************************************************
 * PathVisio, a tool for data visualization and analysis using biological pathways
 * Copyright 2006-2026 PathVisio
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License.  You may obtain a copy
 * of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations under
 * the License.
 ******************************************************************************/
package org.pathvisio.githubplugin.service;

import org.pathvisio.githubplugin.util.HttpUtil;
import org.pathvisio.githubplugin.util.JsonParser;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;

/**
 * Service class for syncing a GitHub fork's {@code main} branch with its upstream repository.
 * 
 * <p>This class wraps GitHub's merge-upstream API, which allows a fork's branch to be
 * fast-forwarded (or merged) to match the corresponding branch on the upstream repository,
 * without requiring any local git operations.</p>
 * 
 * <p><strong>Scope:</strong> Per current project requirements, this service only syncs the
 * {@code main} branch. It does not accept or operate on arbitrary branch names.</p>
 * 
 * <p><strong>Workflow:</strong> This service is intended to run after
 * {@link GitHubForkService#ensureForkExists()} confirms the fork exists, and before any new
 * branch is created off of {@code main}. Syncing first ensures new branches are cut from an
 * up-to-date base rather than a stale fork.</p>
 * 
 * <p><strong>Threading Considerations:</strong> {@link #syncWithUpstreamMain()} performs a
 * blocking network call and must be called from a background thread (e.g.,
 * {@link javax.swing.SwingWorker}), never from the Event Dispatch Thread (EDT).</p>
 * 
 * @see GitHubForkService for creating and confirming the fork itself
 * @see GitHubBranchService for creating branches once the fork is synced
 */
public class GitHubForkSyncService
{
    private static final String API_BASE = "https://api.github.com";

    private final String accessToken;
    private final String authenticatedUsername;
    private final String upstreamRepo;

    /**
     * Constructs a new GitHubForkSyncService.
     *
     * @param accessToken           a valid GitHub OAuth access token with repository access
     * @param authenticatedUsername the GitHub username of the token owner (e.g., "alice"),
     *                              i.e. the owner of the fork to be synced
     * @param upstreamRepo          the name of the forked repository (e.g., "sandbox-wp-db")
     */
    public GitHubForkSyncService(String accessToken, String authenticatedUsername, String upstreamRepo)
    {
        this.accessToken = accessToken;
        this.authenticatedUsername = authenticatedUsername;
        this.upstreamRepo = upstreamRepo;
    }

    /**
     * Represents the outcome of a fork sync attempt against upstream {@code main}.
     */
    public enum SyncResult
    {
        /** The fork's main branch was behind and has been updated (fast-forwarded or merged). */
        UPDATED,

        /** The fork's main branch was already up to date with upstream; no changes were made. */
        ALREADY_CURRENT,

        /** The fork's main branch has diverged and cannot be auto-synced; manual resolution is required. */
        CONFLICT
    }

    /**
     * Syncs the authenticated user's fork {@code main} branch with the upstream repository's
     * {@code main} branch, using GitHub's merge-upstream endpoint.
     * 
     * <p>This method distinguishes between three known outcomes:</p>
     * <ul>
     * <li>HTTP 200 with {@code merge_type} of {@code "merge"} or {@code "fast-forward"} &rarr;
     *     {@link SyncResult#UPDATED}</li>
     * <li>HTTP 200 with {@code merge_type} of {@code "none"} &rarr;
     *     {@link SyncResult#ALREADY_CURRENT}</li>
     * <li>HTTP 409 &rarr; {@link SyncResult#CONFLICT} (the fork has diverged and GitHub cannot
     *     auto-merge; this is treated as an expected outcome, not an error)</li>
     * </ul>
     * 
     * <p>Any other status code (e.g. HTTP 422, typically indicating the repository is not
     * actually a fork or the branch does not exist) is treated as an unexpected failure and
     * throws {@link IOException}.</p>
     * 
     * <p><strong>Threading:</strong> This method blocks the calling thread. It must be called
     * from a background thread, never from the EDT.</p>
     *
     * @return the {@link SyncResult} describing what happened
     * @throws IOException if the GitHub API returns an unexpected status code, or the request
     *                      cannot be sent
     */
    public SyncResult syncWithUpstreamMain() throws IOException
    {
        String endpoint = API_BASE + "/repos/" + authenticatedUsername + "/" + upstreamRepo + "/merge-upstream";
        HttpURLConnection connection = HttpUtil.openAuthenticatedConnection(endpoint, "POST", accessToken);
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json");

        String requestBody = "{\"branch\":\"main\"}";
        try (OutputStream os = connection.getOutputStream())
        {
            os.write(requestBody.getBytes(StandardCharsets.UTF_8));
        }

        int status = connection.getResponseCode();
        try
        {
            if (status == 200)
            {
                // Read the response body to determine what kind of merge (if any) happened.
                StringBuilder response = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8)))
                {
                    String line;
                    while ((line = reader.readLine()) != null)
                    {
                        response.append(line);
                    }
                }

                String mergeType = JsonParser.extractValue(response.toString(), "merge_type");
                if ("none".equals(mergeType))
                {
                    return SyncResult.ALREADY_CURRENT;
                }
                return SyncResult.UPDATED;
            }
            if (status == 409)
            {
                return SyncResult.CONFLICT;
            }
            throw new IOException("Unexpected status while syncing fork with upstream: " + status);
        }
        finally
        {
            connection.disconnect();
        }
    }
}