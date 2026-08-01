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
package org.pathvisio.githubplugin.service;

import org.pathvisio.githubplugin.util.HttpUtil;
import org.pathvisio.githubplugin.util.JsonParser;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;

/**
 * Service class that encapsulates the GitHub "Create a pull request" REST API
 * operation used by the PathVisio-GitHub integration plugin.
 *
 * <p>
 * This class handles opening a pull request from a branch on the user's fork
 * against a branch on the upstream WikiPathways repository. It is responsible
 * for:
 * <ul>
 *   <li>Building the JSON request body for the pulls endpoint.</li>
 *   <li>Formatting the {@code head} field as {@code "forkOwner:branchName"},
 *       which is required whenever the head branch lives on a fork rather
 *       than the upstream repository itself.</li>
 *   <li>Parsing the created pull request's number, HTML URL, and state from
 *       GitHub's response into a {@link PullRequestResult}.</li>
 * </ul>
 * </p>
 *
 * <p>
 * Unlike {@link GitHubCommitService}, the request is sent to the
 * <strong>upstream</strong> repository's API path, not the fork's — GitHub's
 * pulls endpoint always lives under the repository that is receiving the
 * pull request.
 * </p>
 *
 * <p>
 * GitHub REST API reference:
 * <a href="https://docs.github.com/en/rest/pulls/pulls#create-a-pull-request">
 * POST /repos/{owner}/{repo}/pulls</a>
 * </p>
 *
 * @author Snehashree Prusty
 * @version 1.0
 * @see GitHubCommitService
 * @see PullRequestResult
 */
public class GitHubPullService
{
    /**
     * GitHub pulls API URL template.
     * Formatted with: upstream owner, upstream repo name.
     */
    private static final String PULLS_API =
            "https://api.github.com/repos/%s/%s/pulls";

    /**
     * GitHub username or organisation that owns the upstream repository
     * receiving the pull request (e.g. {@code "wikipathways"}).
     */
    private final String upstreamOwner;

    /**
     * Repository name, shared by both the upstream repo and the fork
     * (e.g. {@code "sandbox-wp-db"}).
     */
    private final String repoName;

    /**
     * GitHub username of the fork owner — the authenticated user. Required
     * to correctly format the {@code head} field, since the branch being
     * pulled from lives on the fork, not upstream.
     */
    private final String forkOwner;

    /**
     * GitHub OAuth access token injected into all REST API request headers
     * as {@code Authorization: Bearer <token>}.
     */
    private final String accessToken;

    // =========================================================================
    // Constructor
    // =========================================================================

    /**
     * Creates a new {@code GitHubPullService}.
     *
     * @param upstreamOwner the GitHub username/organisation of the upstream
     *                      repository (e.g. {@code "wikipathways"})
     * @param repoName      the repository name, shared by upstream and fork
     *                      (e.g. {@code "sandbox-wp-db"})
     * @param forkOwner     the GitHub username of the fork owner (the
     *                      authenticated user)
     * @param accessToken   a valid GitHub OAuth access token with {@code repo}
     *                      scope
     */
    public GitHubPullService(
            String upstreamOwner,
            String repoName,
            String forkOwner,
            String accessToken)
    {
        this.upstreamOwner = upstreamOwner;
        this.repoName      = repoName;
        this.forkOwner     = forkOwner;
        this.accessToken   = accessToken;
    }

    public static class PullRequestValidationException extends IOException 
    {
        public PullRequestValidationException(String message) 
        {
            super(message);
        }
    }

    /**
     * Opens a pull request from a branch on the user's fork against a branch
     * on the upstream repository.
     *
     * <p>
     * This method sends a POST request to the upstream repository's pulls
     * endpoint. The {@code head} field is formatted as
     * {@code "forkOwner:headBranch"} — omitting the fork owner prefix would
     * cause GitHub to look for {@code headBranch} inside the upstream
     * repository itself, find nothing, and reject the request with a 422.
     * </p>
     *
     * <p>
     * A 422 response commonly means one of: there are no commits between
     * {@code headBranch} and {@code baseBranch} (the commit didn't actually
     * land, or landed on the wrong branch), or a pull request already exists
     * for this exact head/base pair.
     * </p>
     *
     * @param title       the pull request title — free text; will be JSON-escaped
     * @param headBranch  the branch on the fork containing the new commits
     *                    (created by {@link GitHubBranchService})
     * @param baseBranch  the upstream branch the pull request targets
     *                    (e.g. {@code "main"})
     * @param body        the pull request description — free text; will be
     *                    JSON-escaped
     * @return a {@link PullRequestResult} containing the new PR's number,
     *         HTML URL, and state
     * @throws IllegalArgumentException if any required parameter is {@code null}
     * @throws IOException if the HTTP call itself fails (network, timeout), or
     *                     if GitHub returns a non-success status — e.g.:
     *                     <ul>
     *                       <li>422 Unprocessable Entity — no diff between
     *                           branches, or a pull request already exists</li>
     *                       <li>401 Unauthorized — token is revoked or invalid</li>
     *                       <li>404 Not Found — upstream repo or branch doesn't
     *                           exist</li>
     *                     </ul>
     */
    public PullRequestResult createPullRequest(
            String title,
            String headBranch,
            String baseBranch,
            String body) throws IOException
    {
        if (title == null || headBranch == null || baseBranch == null)
        {
            throw new IllegalArgumentException(
                    "title, headBranch, and baseBranch must not be null");
        }
        if (body == null)
        {
            throw new IllegalArgumentException("body must not be null");
        }

        String url = String.format(PULLS_API, upstreamOwner, repoName);
        String head = forkOwner + ":" + headBranch;
        String jsonBody = buildPullRequestBody(title, head, baseBranch, body);

        HttpURLConnection connection =
                HttpUtil.openAuthenticatedConnection(url, "POST", accessToken);
        connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        connection.setDoOutput(true);

        try
        {
            writeBody(connection, jsonBody);

            int status = connection.getResponseCode();
            String responseBody = HttpUtil.readResponseBody(connection);

            // GitHub returns 201 Created for a successfully opened pull request.
            // There is no "update" status here, unlike the contents endpoint —
            // a pull request is either created or it isn't.
            if (status != HttpURLConnection.HTTP_CREATED)
            {
                if (status == 422)
                {
                    throw new PullRequestValidationException(
                            "Pull request creation failed (HTTP 422). This usually means "
                            + "there are no commits between '" + head + "' and '" + baseBranch
                            + "', or a pull request already exists for this branch pair. "
                            + "GitHub response: " + responseBody);
                }
                throw new IOException(
                        "GitHub pull request creation failed. HTTP " + status
                        + " — " + responseBody);
            }

            return parsePullResponse(responseBody);
        }
        finally
        {
            // Always release the underlying TCP connection, consistent with
            // GitHubCommitService and GitHubBranchService patterns.
            connection.disconnect();
        }
    }
   

    /**
     * Writes the JSON payload to the connection's output stream.
     *
     * <p>
     * Identical in structure to {@link GitHubCommitService}'s private
     * {@code writeBody} — {@link HttpUtil#openAuthenticatedConnection} never
     * calls {@code setDoOutput} or writes a body itself, since GET requests
     * used elsewhere in the plugin don't need one.
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
     * Builds the JSON request body for the pulls endpoint.
     *
     * <p>
     * Unlike {@link GitHubCommitService#buildCommitBody}, there is no
     * conditional field here — all four fields are always present.
     * </p>
     *
     * @param title fields — {@code title} and {@code body} will be escaped;
     *              {@code head} and {@code base} are branch identifiers and
     *              do not require escaping
     * @param head  the formatted {@code "forkOwner:branchName"} string
     * @param base  the upstream base branch name
     * @param body  the pull request description
     * @return the JSON string ready to be written to the connection body
     */
    private String buildPullRequestBody(
            String title,
            String head,
            String base,
            String body)
    {
        StringBuilder json = new StringBuilder();
        json.append("{");
        json.append("\"title\":\"").append(escapeJson(title)).append("\",");
        json.append("\"head\":\"").append(head).append("\",");
        json.append("\"base\":\"").append(base).append("\",");
        json.append("\"body\":\"").append(escapeJson(body)).append("\"");
        json.append("}");
        return json.toString();
    }

    /**
     * Parses GitHub's pull request creation response into a
     * {@link PullRequestResult}.
     *
     * <p>
     * Uses {@link JsonParser#extractValue(String, String)} for the three
     * top-level fields needed — {@code number}, {@code html_url}, and
     * {@code state} — the same flat-field extraction pattern already used by
     * {@code PathVisioGitHubCli.fetchAuthenticatedUsername()} for the
     * {@code login} field.
     * </p>
     *
     * @param responseJson the raw JSON body of the 201 response
     * @return a populated {@link PullRequestResult}
     * @throws IOException if the response is empty, or the {@code number}
     *                     field cannot be parsed as an integer
     */
    private PullRequestResult parsePullResponse(String responseJson) throws IOException
    {
        if (responseJson == null || responseJson.isEmpty())
        {
            throw new IOException(
                    "Empty response body received from GitHub pulls API.");
        }

        String numberField = JsonParser.extractValue(responseJson, "number");
        String htmlUrl     = JsonParser.extractValue(responseJson, "html_url");
        String state       = JsonParser.extractValue(responseJson, "state");

        int number;
        try
        {
            number = Integer.parseInt(numberField);
        }
        catch (NumberFormatException e)
        {
            throw new IOException(
                    "Could not parse pull request 'number' from GitHub response. "
                    + "Value was: " + numberField + ". Full response: " + responseJson, e);
        }

        return new PullRequestResult(number, htmlUrl, state);
    }

    /**
     * Escapes special characters in free-text JSON string values.
     *
     * <p>
     * Applied only to user-supplied strings that can contain arbitrary
     * characters (PR title and body). Branch names only use characters that
     * are safe in JSON strings and do not require escaping.
     * </p>
     *
     * <p>
     * Duplicated from {@link GitHubCommitService#escapeJson}, matching the
     * project's existing pattern of each service owning its own copy rather
     * than depending on a shared text-utility class.
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
}