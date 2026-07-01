/*******************************************************************************
 * PathVisio, a tool for data visualization and analysis using biological pathways
 * Copyright 2006-2026 PathVisio
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy
 * of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 ******************************************************************************/
package org.pathvisio.githubplugin;

import org.pathvisio.githubplugin.util.HttpUtil;
import org.pathvisio.githubplugin.util.JsonParser;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;

/**
 * Service class that encapsulates the GitHub "Create or update file contents"
 * REST API operation used by the PathVisio-GitHub integration plugin.
 *
 * <p>
 * This class handles committing a Base64-encoded GPML file to a branch of the
 * user's fork of the WikiPathways repository. It is responsible for:
 * <ul>
 *   <li>Building the JSON request body for the PUT contents endpoint.</li>
 *   <li>Distinguishing between creating a new file (no SHA) and updating an
 *       existing file (SHA required), as described in section 4.3 of the
 *       GSoC proposal.</li>
 *   <li>Returning the new content SHA so callers can cache it without an
 *       extra GET call, mitigating rate-limit pressure (proposal §6.1).</li>
 * </ul>
 * </p>
 *
 * <p>
 * The caller ({@code GpmlEncoder}) is expected to have already:
 * <ol>
 *   <li>Serialised the pathway model to a GPML XML string.</li>
 *   <li>Converted it to a UTF-8 byte array.</li>
 *   <li>Base64-encoded the byte array via {@code java.util.Base64}.</li>
 *   <li>Determined the current SHA of the file (or {@code null} for new
 *       files) via {@code GpmlEncoder.getExistingGpmlSHA()}.</li>
 * </ol>
 * </p>
 *
 * <p>
 * All network errors, including stale-SHA conflicts (HTTP 409) that arise from
 * concurrent edits by other biologists, are surfaced as {@link IOException}
 * with a descriptive message. The UI layer is responsible for displaying these
 * as merge-conflict error logs, as described in proposal §6.2.
 * </p>
 *
 * <p>
 * GitHub REST API reference:
 * <a href="https://docs.github.com/en/rest/repos/contents#create-or-update-file-contents">
 * PUT /repos/{owner}/{repo}/contents/{path}</a>
 * </p>
 *
 * @author Snehashree Prusty
 * @version 1.0
 * @see GitHubBranchService
 */
public class GitHubCommitService
{
    /**
     * GitHub contents API URL template.
     * Formatted with: owner, repo, path-within-repo.
     */
    private static final String CONTENTS_API =
            "https://api.github.com/repos/%s/%s/contents/%s";

    /**
     * GitHub username of the fork owner — typically the authenticated user.
     * Commits are always made to the user's fork, not the upstream directly.
     */
    private final String forkOwner;

    /**
     * Repository name (e.g. {@code "wikipathways-database"}).
     */
    private final String repoName;

    /**
     * GitHub OAuth access token injected into all REST API request headers
     * as {@code Authorization: Bearer <token>}.
     */
    private final String accessToken;

    // =========================================================================
    // Constructor
    // =========================================================================

    /**
     * Creates a new {@code GitHubCommitService}.
     *
     * @param forkOwner   the GitHub username of the fork owner (the authenticated user)
     * @param repoName    the repository name (e.g. {@code "wikipathways-database"})
     * @param accessToken a valid GitHub OAuth access token with {@code repo} scope
     */
    public GitHubCommitService(String forkOwner, String repoName, String accessToken)
    {
        this.forkOwner   = forkOwner;
        this.repoName    = repoName;
        this.accessToken = accessToken;
    }

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Commits a new or updated GPML file to a branch of the user's fork.
     *
     * <p>
     * This method maps directly to proposal §4.3: it sends a PUT request to
     * the GitHub contents endpoint with the Base64-encoded GPML payload. When
     * {@code sha} is {@code null}, GitHub interprets the request as creating a
     * new file; when {@code sha} is non-null, GitHub treats it as an update
     * and validates that the SHA matches the current head to prevent accidental
     * overwrites by concurrent editors.
     * </p>
     *
     * <p>
     * A HTTP 409 (Conflict) response means the SHA is stale — another
     * contributor has committed to the same file. The caller should inform the
     * user to download the latest version of the GPML file and redo their
     * changes (proposal §6.2).
     * </p>
     *
     * @param path           path to the file within the repo
     *                       (e.g. {@code "pathways/WP5046/WP5046.gpml"})
     * @param branch         branch to commit to — the branch created by
     *                       {@link GitHubBranchService}
     * @param base64Content  Base64-encoded GPML content produced by
     *                       {@code GpmlEncoder.encodeToBase64()}
     * @param sha            current SHA of the file if updating an existing pathway;
     *                       {@code null} or empty string when creating a new file
     * @param commitMessage  commit message — free text; will be JSON-escaped
     * @return the SHA of the newly committed file object ({@code content.sha}
     *         from the response), suitable for caching to avoid a subsequent
     *         GET call in the same session
     * @throws IllegalArgumentException if any required parameter is {@code null}
     * @throws IOException if the HTTP call itself fails (network, timeout), or
     *                     if GitHub returns a non-success status — e.g.:
     *                     <ul>
     *                       <li>409 Conflict — stale SHA, concurrent edit detected</li>
     *                       <li>422 Unprocessable Entity — validation error</li>
     *                       <li>401 Unauthorized — token is revoked or invalid</li>
     *                     </ul>
     */
    public String commitFile(
            String path,
            String branch,
            String base64Content,
            String sha,
            String commitMessage) throws IOException
    {
        if (path == null || branch == null)
        {
            throw new IllegalArgumentException("path and branch must not be null");
        }
        if (base64Content == null || commitMessage == null)
        {
            throw new IllegalArgumentException(
                    "base64Content and commitMessage must not be null");
        }

        String url = String.format(CONTENTS_API, forkOwner, repoName, stripLeadingSlash(path));
        String jsonBody = buildCommitBody(commitMessage, base64Content, branch, sha);

        HttpURLConnection connection =
                HttpUtil.openAuthenticatedConnection(url, "PUT", accessToken);
        connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        connection.setDoOutput(true);

        try
        {
            writeBody(connection, jsonBody);

            int status = connection.getResponseCode();
            String responseBody = HttpUtil.readResponseBody(connection);

            // GitHub returns 201 Created for new files and 200 OK for updates.
            if (status != HttpURLConnection.HTTP_OK && status != HttpURLConnection.HTTP_CREATED)
            {
                // Surface stale-SHA conflicts with a clear message so the UI
                // layer can show a merge-conflict log (proposal §6.2).
                if (status == HttpURLConnection.HTTP_CONFLICT)
                {
                    throw new IOException(
                            "Commit failed: SHA mismatch (HTTP 409). Another contributor may "
                            + "have edited this pathway. Please download the latest version and "
                            + "redo your changes. GitHub response: " + responseBody);
                }
                throw new IOException(
                        "GitHub commit failed. HTTP " + status + " — " + responseBody);
            }

            return parseContentSha(responseBody);
        }
        finally
        {
            // Always release the underlying TCP connection, consistent with
            // GitHubBranchService and GpmlEncoder patterns in this project.
            connection.disconnect();
        }
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    /**
     * Writes the JSON payload to the connection's output stream.
     *
     * <p>
     * {@link HttpUtil#openAuthenticatedConnection} intentionally does not call
     * {@code setDoOutput} or write a body, because GET requests used elsewhere
     * never need one. This method handles the PUT-specific body writing.
     * </p>
     *
     * @param connection the open connection — must already have
     *                   {@code setDoOutput(true)} called
     * @param jsonBody   the JSON string to write
     * @throws IOException if writing to the output stream fails
     */
    private void writeBody(HttpURLConnection connection, String jsonBody)
            throws IOException
    {
        byte[] bodyBytes = jsonBody.getBytes(StandardCharsets.UTF_8);
        try (OutputStream os = connection.getOutputStream())
        {
            os.write(bodyBytes);
        }
    }

    /**
     * Builds the JSON request body for the contents PUT endpoint.
     *
     * <p>
     * The {@code sha} field is conditionally included: its presence tells
     * GitHub to treat the request as an <em>update</em> to an existing file,
     * while its absence means <em>create a new file</em> (proposal §4.3).
     * </p>
     *
     * @param message        commit message — will be escaped
     * @param base64Content  Base64 payload — safe characters, no escaping needed
     * @param branch         branch name — safe characters, no escaping needed
     * @param sha            existing file SHA, or {@code null} / empty for new files
     * @return the JSON string ready to be written to the connection body
     */
    private String buildCommitBody(
            String message,
            String base64Content,
            String branch,
            String sha)
    {
        StringBuilder json = new StringBuilder();
        json.append("{");
        json.append("\"message\":\"").append(escapeJson(message)).append("\",");
        json.append("\"content\":\"").append(base64Content).append("\",");
        json.append("\"branch\":\"").append(escapeJson(branch)).append("\"");
        if (sha != null && !sha.isEmpty())
        {
            json.append(",\"sha\":\"").append(sha).append("\"");
        }
        json.append("}");
        return json.toString();
    }

    /**
     * Extracts {@code content.sha} from the GitHub PUT contents response.
     *
     * <p>
     * The returned SHA can be cached by the caller to avoid an extra GET
     * request if the same file needs to be committed again in the same session,
     * helping to conserve the 5 000 req/hr rate limit (proposal §6.1).
     * </p>
     *
     * <p>
     * Uses {@link JsonParser#extractNestedValue(String, String, String)} which
     * is already used by {@link GitHubBranchService#getHeadSHA(String)} to
     * extract {@code object.sha} from the refs endpoint — same nesting pattern.
     * </p>
     *
     * @param responseJson the raw JSON body of the 200/201 response
     * @return the {@code content.sha} string from GitHub's response
     * @throws IOException if the response is empty or the SHA field is missing
     */
    private String parseContentSha(String responseJson) throws IOException
    {
        if (responseJson == null || responseJson.isEmpty())
        {
            throw new IOException(
                    "Empty response body received from GitHub contents API.");
        }

        // Delegates to the same helper used by GitHubBranchService.getHeadSHA()
        // to extract nested SHA values — consistent with project conventions.
        String contentSha = JsonParser.extractNestedValue(responseJson, "content", "sha");

        if (contentSha == null || contentSha.isEmpty())
        {
            throw new IOException(
                    "Could not parse 'content.sha' from GitHub response. "
                    + "Response was: " + responseJson);
        }
        return contentSha;
    }

    /**
     * Escapes special characters in free-text JSON string values.
     *
     * <p>
     * Applied only to user-supplied strings that can contain arbitrary
     * characters (e.g. commit messages). Base64 content, branch names, and
     * SHA hashes only use characters that are safe in JSON strings and do
     * not require escaping.
     * </p>
     *
     * @param input the raw string to escape
     * @return the JSON-safe escaped string
     */
    private String escapeJson(String input)
    {
        return input
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    /**
     * Removes a leading slash from a repository path if present.
     *
     * <p>
     * The GitHub contents API path segment must not begin with {@code /},
     * but callers may supply paths in either format for convenience.
     * </p>
     *
     * @param path the raw file path
     * @return the path without a leading slash
     */
    private String stripLeadingSlash(String path)
    {
        return path.startsWith("/") ? path.substring(1) : path;
    }
}
