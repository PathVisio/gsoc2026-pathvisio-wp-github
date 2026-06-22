package org.pathvisio.githubplugin;

import org.pathvisio.githubplugin.util.HttpUtil;
import java.io.IOException;
import java.net.HttpURLConnection;

public class GitHubForkService
{
    private static final String API_BASE       = "https://api.github.com";
    private static final String UPSTREAM_OWNER = "wikipathways";
   
    private final String accessToken;
    private final String authenticatedUsername;
    private final String upstreamRepo;

    public GitHubForkService(String accessToken, String authenticatedUsername, String upstreamRepo) 
    {
        this.accessToken = accessToken;
        this.authenticatedUsername = authenticatedUsername;
        this.upstreamRepo = upstreamRepo;
    }
    public boolean forkExists() throws IOException 
    {
        String endpoint = API_BASE + "/repos/" + authenticatedUsername + "/" + upstreamRepo;
        HttpURLConnection connection = HttpUtil.openAuthenticatedConnection(endpoint, "GET", accessToken);
        int status = connection.getResponseCode();
        connection.disconnect();
        if (status == 200) return true;
        if (status == 404) return false;
        throw new IOException("Unexpected status while checking fork: " + status);
    }
    public void createFork() throws IOException
    {
        String endpoint = API_BASE + "/repos/" + UPSTREAM_OWNER + "/" + UPSTREAM_REPO + "/forks";
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
     * Polls forkExists() repeatedly until the fork appears or we time out.
     * This method BLOCKS the thread it runs on — must be called from SwingWorker,
     * never from the Event Dispatch Thread (EDT).
     *
     * @param timeoutMillis   total time to wait before giving up (e.g. 60_000 = 60s)
     * @param intervalMillis  time to sleep between each check (e.g. 3_000 = 3s)
     * @return true if fork became available, false if timed out
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
     * The single method your SwingWorker calls.
     * Combines all of the above: check → create if missing → wait → confirm.
     *
     * @return true if the fork is confirmed ready to use
     */
     public boolean ensureForkExists() throws IOException, InterruptedException
     {
        if (forkExists()) return true;
        createFork();
        return waitForFork(60_000, 3_000);
     }
}