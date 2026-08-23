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

import java.io.IOException;
import java.net.HttpURLConnection;
import org.pathvisio.githubplugin.util.JsonParser;

/**
 * Service class for managing GitHub fork operations.
 * 
 * This class handles the lifecycle of GitHub repository forks, including checking if a fork
 * exists for the authenticated user, creating a new fork from the upstream repository, and
 * waiting for GitHub to complete the fork operation asynchronously.
 * 
 * <p>The fork operation is performed on the official WikiPathways repository (owner:
 * "wikipathways") to the authenticated user's personal account. This service is primarily
 * used in the GitHub plugin workflow to ensure users have their own fork before attempting
 * to create branches and commit changes.</p>
 * 
 * <p><strong>Workflow:</strong></p>
 * <ol>
 * <li>Initialize service with access token, authenticated username, and target repository name</li>
 * <li>Check if a fork already exists using {@link #forkExists()}</li>
 * <li>If not present, create the fork using {@link #createFork()}</li>
 * <li>Poll GitHub until the fork is ready using {@link #waitForFork(long, long)}</li>
 * <li>Confirm readiness using {@link #ensureForkExists()}</li>
 * </ol>
 * 
 * <p><strong>Threading Considerations:</strong> The {@link #waitForFork(long, long)} method
 * is a blocking operation that sleeps the calling thread. This method <em>must</em> be called
 * from a background thread (e.g., {@link javax.swing.SwingWorker}), never from the Event
 * Dispatch Thread (EDT). Blocking the EDT will freeze the user interface and degrade the
 * user experience.</p>
 * 
 * <p><strong>GitHub API Behavior:</strong> Fork creation is asynchronous on GitHub's servers.
 * When a fork request succeeds (returns HTTP 200 or 202), the fork repository may not be
 * immediately available via the GitHub API. The {@link #waitForFork(long, long)} method
 * handles this delay by polling {@link #forkExists()} at regular intervals until the fork
 * becomes queryable.</p>
 * 
 * <p><strong>Usage Example:</strong></p>
 * <pre>
 * String accessToken = "ghp_...";
 * String username = "alice";
 * 
 * 
 * GitHubForkService forkService = new GitHubForkService(accessToken, username, repoName);
 * 
 * // Check if fork exists
 * if (forkService.forkExists()) {
 *     System.out.println("Fork already exists!");
 * } else {
 *     System.out.println("Creating fork...");
 *     forkService.createFork();
 *     System.out.println("Waiting for fork to be ready...");
 *     if (forkService.waitForFork(60_000, 3_000)) {
 *         System.out.println("Fork is ready!");
 *     } else {
 *         System.out.println("Fork creation timed out.");
 *     }
 * }
 * 
 * // Or use the all-in-one method:
 * if (forkService.ensureForkExists()) {
 *     System.out.println("Fork is confirmed ready to use.");
 * }
 * </pre>
 * 
 * @see GitHubBranchService for operations on branches within a fork
 * @see GitHubAuthService for authentication with GitHub OAuth
 */
public class GitHubForkService
{
    private static final String API_BASE       = "https://api.github.com";
    private final String upstreamOwner;
    private final String accessToken;
    private final String authenticatedUsername;
    private final String upstreamRepo;

    /**
     * Constructs a new GitHubForkService.
     *
     * @param accessToken           a valid GitHub OAuth access token with repository access
     * @param authenticatedUsername the GitHub username of the token owner (e.g., "alice")
     * @param upstreamRepo          the name of the repository to fork (e.g., "sandbox-wp-db")
     */
    public GitHubForkService(String accessToken, String authenticatedUsername, String upstreamOwner, String upstreamRepo) 
    {
        this.accessToken = accessToken;
        this.authenticatedUsername = authenticatedUsername;
        this.upstreamOwner = upstreamOwner;
        this.upstreamRepo = upstreamRepo;
    }
    /**
     * Thrown when a repo of the expected name exists under the authenticated
     * user's account, but its actual GitHub-registered fork parent does not
     * match the currently configured upstream owner/repo.
     */
    public static class ForkParentMismatchException extends IOException
    {
        public ForkParentMismatchException(String message)
        {
            super(message);
        }
    }
    /**
     * Checks whether a fork of the upstream repository exists under the authenticated user's account.
     *
     * <p>This method queries the GitHub API to determine if the fork is currently accessible.
     * It does not guarantee that the fork is immediately ready for all operations; use
     * {@link #waitForFork(long, long)} to wait for full initialization.</p>
     *
     * @return {@code true} if the fork exists (HTTP 200), {@code false} if not found (HTTP 404)
     * @throws IOException if the GitHub API returns an unexpected status code
     */
    public boolean forkExists() throws IOException 
    {
        String endpoint = API_BASE + "/repos/" + authenticatedUsername + "/" + upstreamRepo;
        HttpURLConnection connection = HttpUtil.openAuthenticatedConnection(endpoint, "GET", accessToken);
        int status = connection.getResponseCode();

        if (status == 404) 
        {
            connection.disconnect();
            return false;
        }

        if (status != 200) 
        {
            connection.disconnect();
            throw new IOException("Unexpected status while checking fork: " + status);
        }

        String body = HttpUtil.readResponseBody(connection);
        connection.disconnect();

        String actualParent = JsonParser.extractNestedValue(body, "parent", "full_name");
        String expectedParent = upstreamOwner + "/" + upstreamRepo;

        if (actualParent == null || !actualParent.equals(expectedParent)) 
        {
            throw new ForkParentMismatchException(
                "A repo named \"" + upstreamRepo + "\" already exists under your account, "
                + "but it is not a fork of " + expectedParent
                + (actualParent == null ? " (it has no fork parent at all)." : " (its actual parent is " + actualParent + ").")
                + " Rename or delete this repo, or point the plugin at a different upstream.");
        }

        return true;
    }

    /**
     * Creates a new fork of the upstream repository in the authenticated user's account.
     *
     * <p>This operation is <em>asynchronous</em> on GitHub's side. A successful response
     * (HTTP 200 or 202) indicates that the fork request was accepted, but the fork may not
     * be immediately queryable via the API. Call {@link #waitForFork(long, long)} to wait
     * for the fork to become ready.</p>
     *
     * <p><strong>Precondition:</strong> The fork must not already exist. Call
     * {@link #forkExists()} first to check, or use {@link #ensureForkExists()} to handle
     * both cases automatically.</p>
     *
     * @throws IOException if the GitHub API returns an error status or the request cannot be sent
     */
    public void createFork() throws IOException
    {
        String endpoint = API_BASE + "/repos/" + upstreamOwner + "/" + upstreamRepo + "/forks";
        HttpURLConnection connection = HttpUtil.openAuthenticatedConnection(endpoint, "POST", accessToken);
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json");
        connection.getOutputStream().write("{}".getBytes("UTF-8"));
        int status = connection.getResponseCode();
        connection.disconnect();
        if (status != 202 && status != 200) 
        {
            throw new IOException("Fork creation failed. GitHub returned: " + status);
        }
    }

    /**
     * Polls {@link #forkExists()} repeatedly until the fork becomes available or a timeout occurs.
     *
     * <p><strong>Threading:</strong> This method <em>blocks</em> the calling thread by sleeping
     * between polling attempts. It must <em>never</em> be called from the Event Dispatch Thread (EDT).
     * Call this method from a background thread, such as a {@link javax.swing.SwingWorker}, to avoid
     * freezing the user interface.</p>
     * 
     * <p><strong>Typical Usage:</strong></p>
     * <pre>
     * // Wait up to 60 seconds, checking every 3 seconds
     * if (forkService.waitForFork(60_000, 3_000)) {
     *     System.out.println("Fork is ready!");
     * } else {
     *     System.out.println("Fork did not become ready in time.");
     * }
     * </pre>
     *
     * @param timeoutMillis   total time to wait before giving up (e.g., 60_000 for 60 seconds)
     * @param intervalMillis  time to sleep between each check (e.g., 3_000 for 3 seconds)
     * @return {@code true} if the fork became available before timeout, {@code false} if the
     *         deadline was reached without the fork becoming available
     * @throws IOException            if the GitHub API returns an unexpected status code during polling
     * @throws InterruptedException   if the calling thread is interrupted while sleeping
     */
    public boolean waitForFork(long timeoutMillis, long intervalMillis) throws IOException, InterruptedException
    {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline)
        {
            if (forkExists()) return true;
            Thread.sleep(intervalMillis);
        }
        return false;
    }

    /**
     * Ensures that a fork of the upstream repository exists, creating one if necessary and
     * waiting for it to become ready.
     *
     * <p>This is a convenience method that combines the three-step fork workflow:</p>
     * <ol>
     * <li>Check if the fork already exists using {@link #forkExists()}</li>
     * <li>If missing, create it using {@link #createFork()}</li>
     * <li>Poll until ready using {@link #waitForFork(long, long)} (60-second timeout, 3-second intervals)</li>
     * </ol>
     *
     * <p><strong>Threading:</strong> This method blocks the calling thread. It <em>must</em> be called
     * from a background thread (e.g., {@link javax.swing.SwingWorker}), never from the Event Dispatch
     * Thread (EDT).</p>
     *
     * @return {@code true} if the fork is confirmed ready to use; {@code false} if creation
     *         was necessary but the fork did not become available within the timeout period
     * @throws IOException            if the GitHub API returns an unexpected status code
     * @throws InterruptedException   if the calling thread is interrupted while waiting
     */
    public boolean ensureForkExists() throws IOException, InterruptedException
    {
        if (forkExists()) return true;
        createFork();
        return waitForFork(60_000, 3_000);
    }
}
