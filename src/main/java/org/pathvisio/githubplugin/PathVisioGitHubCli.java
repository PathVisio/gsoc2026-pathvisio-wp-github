package org.pathvisio.githubplugin;

import org.pathvisio.githubplugin.util.HttpUtil;
import org.pathvisio.githubplugin.util.JsonParser;


import java.io.IOException;
import java.net.HttpURLConnection;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
public class PathVisioGitHubCli 
{
    private static final String UPSTREAM_OWNER = "wikipathways";
    private static final String UPSTREAM_REPO = "sandbox-wp-db";

    public static void main(String[] args) throws Exception
    {
        String branchName = args.length > 0 ? args[0] : "contribution-" + System.currentTimeMillis();
        System.out.println("[1/4] Checking GitHub authentication...");
        String token = authenticate();
        System.out.println("[1/4] Authenticated.");

        try 
        {
        System.out.println("[2/4] Looking up authenticated username...");
        String username = fetchAuthenticatedUsername(token);
        System.out.println("    -> Logged in as: " + username);

        System.out.println("[3/4] Ensuring if fork of " + UPSTREAM_OWNER + "/" + UPSTREAM_REPO + " exists...");
        GitHubForkService forkService = new GitHubForkService(token, username, UPSTREAM_REPO);
        
          if (forkService.forkExists())
            {
                System.out.println("    -> Fork already exists, skipping creation.");
            } else 
            {
                System.out.println("    -> No fork found, creating...");
                forkService.createFork();
                System.out.println("    -> Waiting for GitHub to complete fork (async)...");
                boolean ready = forkService.waitForFork(60_000, 3_000);
                if (!ready) 
                {
                    System.err.println("Fork did not become ready in time. Try again shortly.");
                    System.exit(1);
                }
                System.out.println("    -> Fork created successfully.");
            }
            System.out.println("    -> Fork ready: " + username + "/" + UPSTREAM_REPO);

            System.out.println("[4/4] Checking branch '" + branchName + "'...");
            GitHubBranchService branchService = new GitHubBranchService(token, username, UPSTREAM_REPO);
            String confirmedBranch = branchService.ensureBranchExists(UPSTREAM_OWNER, branchName);
            System.out.println("    -> Branch ready: " + confirmedBranch);

            System.out.println("\nDone. " + username + "/" + UPSTREAM_REPO + " @ " + confirmedBranch + " is ready for commits.");
         }
        
         catch (IOException e) 
        {
            System.err.println("GitHub API error: " + e.getMessage());
            System.exit(1);
        } 
        
         catch (InterruptedException e) 
        {
            Thread.currentThread().interrupt();
            System.err.println("Interrupted while waiting on GitHub.");
            System.exit(1);
        }
         System.exit(0);
}

private static String authenticate() 
{
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> tokenRef = new AtomicReference<>();
        AtomicReference<String> errorRef = new AtomicReference<>();
        GitHubAuthService authService = new GitHubAuthService();
        authService.startAuthentication(new GitHubAuthService.AuthCallback() 
        {
            @Override
            public void onUserCodeReceived(String userCode, int expiresIn) 
            {
                System.out.println("    -> Visit: https://github.com/login/device");
                System.out.println("    -> Enter code: " + userCode + "  (expires in " + expiresIn + "s)");
                System.out.println("    -> Opening your browser...");
            }
            @Override
            public void onStatusUpdate(String message) 
            {
                System.out.println("    -> " + message);
            }
            @Override
            public void onSuccess(String accessToken) 
            {
                tokenRef.set(accessToken);
                latch.countDown();
            }
            @Override
            public void onFailure(String errorMessage) 
            {
                errorRef.set(errorMessage);
                latch.countDown();
            }
        });

        try 
        {
            latch.await(); 
        } 
        catch (InterruptedException e) 
        {
            Thread.currentThread().interrupt();
            System.err.println("Interrupted while waiting for authentication.");
            System.exit(1);
        }
         if (tokenRef.get() == null) 
        {
            System.err.println("Authentication failed: " + errorRef.get());
            System.exit(1);
        }
        return tokenRef.get();
}
private static String fetchAuthenticatedUsername(String token) throws IOException 
{
        String endpoint = "https://api.github.com/user";
        HttpURLConnection connection = HttpUtil.openAuthenticatedConnection(endpoint, "GET", token);
        int status = connection.getResponseCode();
        if (status != 200) 
        {
            connection.disconnect();
            throw new IOException("Failed to fetch authenticated user. Status: " + status);
        }
        String body = HttpUtil.readResponseBody(connection);
        connection.disconnect();
        return JsonParser.extractValue(body, "login");
}
}


