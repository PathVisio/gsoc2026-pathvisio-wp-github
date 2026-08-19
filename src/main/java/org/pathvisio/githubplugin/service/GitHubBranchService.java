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
import java.io.IOException;
import java.net.HttpURLConnection;
import org.pathvisio.githubplugin.util.HttpUtil;
import org.pathvisio.githubplugin.util.JsonParser;

/**
 * Service class that encapsulates GitHub branch-related operations used by the
 * PathVisio GitHub integration plugin.
 *
 * <p>
 * Responsibilities:
 * <ul>
 *   <li>Read repository metadata to discover the default branch for an upstream repo.</li>
 *   <li>Query branch refs to obtain head SHAs and existence checks.</li>
 *   <li>Create new branch refs in the user's fork.</li>
 * </ul>
 * </p>
 *
 * <p>
 * This class performs simple REST calls to the GitHub REST API using
 * HttpURLConnection wrapped by HttpUtil and parses JSON results via JsonParser.
 * All network errors are surfaced as IOExceptions. Methods assume the provided
 * access token is valid and has repository permissions in the fork (create refs).
 * </p>
 *
 * <p>
 * Notes aligned with the GSoC proposal:
 * <ul>
 *   <li>The plugin uses these methods after forking to create a branch for pathway edits.</li>
 *   <li>Branch creation is performed by creating a new ref referencing an existing commit SHA.</li>
 *   <li>Callers should ensure the fork exists and is up-to-date before creating branches; forking is asynchronous on GitHub.</li>
 * </ul>
 * </p>
 */
public class GitHubBranchService 
{

    private static final String API_BASE = "https://api.github.com";

    private final String accessToken;
    private final String forkOwner;  
    private final String repoName;  

    /**
     * Create a new GitHubBranchService.
     *
     * @param accessToken A valid GitHub OAuth access token with repo permissions for the fork.
     * @param forkOwner   The owner (username or org) of the fork where branches will be created.
     * @param repoName    The repository name (e.g. "wikipathways-database").
     */
    public GitHubBranchService(String accessToken, String forkOwner, String repoName) 
    {
        this.accessToken = accessToken;
        this.forkOwner   = forkOwner;
        this.repoName    = repoName;
    }

    /**
     * Fetches the default branch name for an upstream repository.
     *
     * <p>
     * This method calls GET /repos/{upstreamOwner}/{repoName} and parses the
     * "default_branch" field from the returned JSON. Example return values are
     * commonly "main" or "master".
     * </p>
     *
     * @param upstreamOwner The repository owner of the upstream (e.g. "wikipathways").
     * @return The default branch name of the upstream repository.
     * @throws IOException If the metadata cannot be retrieved or an unexpected HTTP status is returned.
     * @see <a href="https://docs.github.com/en/rest/repos/repos#get-a-repository">GET /repos/{owner}/{repo}</a>
     */
    public String getDefaultBranch(String upstreamOwner) throws IOException
    {
        String endpoint = API_BASE + "/repos/" + upstreamOwner + "/" + repoName;
        HttpURLConnection connection = HttpUtil.openAuthenticatedConnection(endpoint, "GET", accessToken);
        int status = connection.getResponseCode();
        if (status != 200) 
        {
            connection.disconnect();
            throw new IOException("Could not read upstream repo metadata. Status: " + status);
        }
        String body = HttpUtil.readResponseBody(connection);
        connection.disconnect();
        return JsonParser.extractValue(body, "default_branch");
    }

    /**
     * Returns the commit SHA (object.sha) pointed to by the named branch in the configured fork.
     *
     * <p>
     * Calls GET /repos/{forkOwner}/{repoName}/git/ref/heads/{branchName} and extracts
     * the nested "object.sha" value. The returned SHA is suitable to use as the
     * base for creating a new branch ref.
     * </p>
     *
     * @param branchName The branch name (without refs/heads/), e.g. "main".
     * @return The SHA string of the branch head.
     * @throws IOException If the branch cannot be read or an unexpected HTTP status is returned.
     * @see <a href="https://docs.github.com/en/rest/git/refs#get-a-reference">GET /repos/{owner}/{repo}/git/ref/{ref}</a>
     */
    public String getHeadSHA(String branchName) throws IOException
    {
        String endpoint = API_BASE + "/repos/" + forkOwner + "/" + repoName + "/git/ref/heads/" + branchName;
        HttpURLConnection connection = HttpUtil.openAuthenticatedConnection(endpoint, "GET", accessToken);
        int status = connection.getResponseCode();
        if (status != 200) 
        {
            connection.disconnect();
            throw new IOException("Could not get SHA for branch '" + branchName + "'. Status: " + status);
        }
        String body = HttpUtil.readResponseBody(connection);
        connection.disconnect();
        return JsonParser.extractNestedValue(body, "object", "sha");
    }

    /**
     * Checks whether a branch with the provided name exists in the configured fork.
     *
     * <p>
     * This performs a GET to the branch ref endpoint and interprets HTTP status:
     * 200 -> exists, 404 -> does not exist. Any other status results in an IOException.
     * </p>
     *
     * @param branchName The branch name to check (without refs/heads/).
     * @return true if the branch exists in the fork, false if it does not (404).
     * @throws IOException If an unexpected HTTP status is returned.
     */
    public boolean branchExists(String branchName) throws IOException
    {
        String endpoint = API_BASE + "/repos/" + forkOwner + "/" + repoName + "/git/ref/heads/" + branchName;
        HttpURLConnection connection = HttpUtil.openAuthenticatedConnection(endpoint, "GET", accessToken);
        int status = connection.getResponseCode();
        connection.disconnect();

        if (status == 200) return true;
        if (status == 404) return false;
        throw new IOException("Unexpected status checking branch '" + branchName + "': " + status);
    }

    /**
     * Creates a new branch in the configured fork pointing to the specified base SHA.
     *
     * <p>
     * This constructs a JSON payload of the form:
     * {"ref":"refs/heads/{newBranchName}","sha":"{baseSha}"} and POSTs it to
     * POST /repos/{forkOwner}/{repoName}/git/refs. A successful creation returns HTTP 201.
     * </p>
     *
     * @param newBranchName The name of the branch to create (without refs/heads/).
     * @param baseSha       The commit SHA the new branch should point to (typically head SHA of a base branch).
     * @throws IOException If the creation fails or GitHub returns a status other than 201 Created.
     * @see <a href="https://docs.github.com/en/rest/git/refs#create-a-reference">POST /repos/{owner}/{repo}/git/refs</a>
     */
    public void createBranch(String newBranchName, String baseSha) throws IOException 
    {
        String endpoint = API_BASE + "/repos/" + forkOwner + "/" + repoName + "/git/refs";
        HttpURLConnection connection = HttpUtil.openAuthenticatedConnection(endpoint, "POST", accessToken);
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json");
        String payload = "{\"ref\":\"refs/heads/" + newBranchName + "\"," + "\"sha\":\"" + baseSha + "\"}";
        connection.getOutputStream().write(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        int status = connection.getResponseCode();
        connection.disconnect();
        if (status != 201) 
        {
            throw new IOException("Branch creation failed for '" + newBranchName + "'. GitHub returned: " + status);
        }
    }

    /**
     * Deletes a branch from the configured fork.
     *
     * <p>
     * Sends DELETE to /repos/{forkOwner}/{repoName}/git/refs/heads/{branchName}.
     * A successful deletion returns HTTP 204 No Content, per GitHub's REST API
     * convention for ref deletion. Used by Theme E's branch-reuse logic to
     * clean up a stale branch once its associated pull request has been merged,
     * before creating a fresh branch for a new contribution.
     * </p>
     *
     * @param branchName The branch name to delete (without refs/heads/).
     * @throws IOException If the deletion fails or GitHub returns a status
     *                      other than 204 No Content.
     * @see <a href="https://docs.github.com/en/rest/git/refs#delete-a-reference">
     *      DELETE /repos/{owner}/{repo}/git/refs/{ref}</a>
     */
    public void deleteBranch(String branchName) throws IOException 
    {
        String endpoint = API_BASE + "/repos/" + forkOwner + "/" + repoName + "/git/refs/heads/" + branchName;
        HttpURLConnection connection = HttpUtil.openAuthenticatedConnection(endpoint, "DELETE", accessToken);
        int status = connection.getResponseCode();
        connection.disconnect();

        if (status != 204) 
        {
            throw new IOException("Branch deletion failed for '" + branchName + "'. GitHub returned: " + status);
        }
    }

    /**
     * Ensures the requested branch exists in the user's fork: returns the branch name if it
     * already exists; otherwise creates it from the default branch and returns the name.
     *
     * <p>
     * Behavior:
     * <ol>
     *   <li>If branchExists(branchName) is true, simply returns branchName.</li>
     *   <li>Otherwise, fetches the upstream default branch name via getDefaultBranch(upstreamOwner).</li>
     *   <li>Reads the head SHA (via getHeadSHA) and creates the new branch in the fork.</li>
     * </ol>
     * </p>
     *
     * <p>
     * Important note: getHeadSHA uses the configured forkOwner to fetch the SHA for the
     * named branch. If the fork is not yet synchronized with upstream (for example, immediately
     * after a fork request which is asynchronous on GitHub), callers should ensure the fork has
     * been created and is up-to-date before invoking this method.
     * </p>
     *
     * @param upstreamOwner The upstream repository owner (used to determine upstream default branch).
     * @param branchName    The desired branch name to ensure exists in the fork.
     * @return The branchName, guaranteed to exist (or an IOException is thrown).
     * @throws IOException If any network/API call fails or returns an unexpected status.
     */
    public String ensureBranchExists(String upstreamOwner, String branchName) throws IOException 
    {
        if (branchExists(branchName)) return branchName;
        String defaultBranch = getDefaultBranch(upstreamOwner);
        String headSha = getHeadSHA(defaultBranch);
        createBranch(branchName, headSha);
        return branchName;
    }
}
