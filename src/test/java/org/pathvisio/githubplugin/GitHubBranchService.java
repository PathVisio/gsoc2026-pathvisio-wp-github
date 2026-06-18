package org.pathvisio.githubplugin;

import org.pathvisio.githubplugin.util.HttpUtil;
import org.pathvisio.githubplugin.util.JsonParser;
import java.io.IOException;
import java.net.HttpURLConnection;

public class GitHubBranchService 
{

    private static final String API_BASE = "https://api.github.com";

    private final String accessToken;
    private final String forkOwner;  
    private final String repoName;  

    public GitHubBranchService(String accessToken, String forkOwner, String repoName) 
    {
        this.accessToken = accessToken;
        this.forkOwner   = forkOwner;
        this.repoName    = repoName;
    }

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
     * The single method your SwingWorker calls after the fork is confirmed.
     * Checks → creates if missing → returns the branch name ready to use.
     *
     * @param upstreamOwner  "wikipathways"
     * @param branchName     e.g. "pathway-update-WP5046"
     * @return the branchName, confirmed to exist
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