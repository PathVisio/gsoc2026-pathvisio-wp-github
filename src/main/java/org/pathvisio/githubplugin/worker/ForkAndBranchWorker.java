package org.pathvisio.githubplugin.worker;

import org.pathvisio.githubplugin.service.GitHubForkService;
import org.pathvisio.githubplugin.service.GitHubForkSyncService;
import org.pathvisio.githubplugin.service.GitHubForkSyncService.SyncResult;
import org.pathvisio.githubplugin.service.GitHubBranchService;

import javax.swing.SwingWorker;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutionException;

/**
 * Ensures a working branch exists in the user's fork, off the EDT.
 *
 * <p>Sequence: confirm fork exists, sync fork's main with upstream, then
 * ensure the target branch exists (creating it from the synced main if
 * needed). If sync reports a conflict, branch creation is skipped and the
 * conflict is reported back via the callback instead.</p>
 */
public class ForkAndBranchWorker extends SwingWorker<String, String> 
{

    private final String accessToken;
    private final String authenticatedUsername;
    private final String upstreamRepo;
    private final String desiredBranchName;
    private final ForkAndBranchCallback callback;
    
    /**
     * Signals a sync conflict internally; caught in done() and translated
     * into callback.onConflict() rather than callback.onFailure().
     */
    private static class ForkConflictException extends IOException 
    {
        ForkConflictException(String message) 
        {
            super(message);
        }
    }
    public ForkAndBranchWorker(String accessToken, String authenticatedUsername,String upstreamRepo, String desiredBranchName,ForkAndBranchCallback callback) 
    {
        this.accessToken = accessToken;
        this.authenticatedUsername = authenticatedUsername;
        this.upstreamRepo = upstreamRepo;
        this.desiredBranchName = desiredBranchName;
        this.callback = callback;
    }
    @Override
    protected String doInBackground() throws Exception 
    {
        GitHubForkService forkService = new GitHubForkService(accessToken, authenticatedUsername, upstreamRepo);
        GitHubForkSyncService syncService = new GitHubForkSyncService(accessToken, authenticatedUsername, upstreamRepo);
        GitHubBranchService branchService = new GitHubBranchService(accessToken, authenticatedUsername, upstreamRepo);

        String resultBranchName;

        publish("Checking fork...");
        boolean forkReady = forkService.ensureForkExists();
        if (forkReady) 
        {
            publish("Fork ready");
        } 
        else 
        {
            throw new IOException("Fork creation timed out before becoming ready.");
        }
        publish("Syncing with upstream...");
        SyncResult syncResult = syncService.syncWithUpstreamMain();
        if (syncResult == SyncResult.CONFLICT) 
        {
            throw new ForkConflictException("Fork's main has diverged and could not be auto-synced.");
        } 
        else 
        {
            if (syncResult == SyncResult.UPDATED)
            {
                publish("Fork synced");
            }
            else if (syncResult == SyncResult.ALREADY_CURRENT) 
            {
                publish("Fork already up to date");
            } 
            else 
            {
                throw new IOException("Unexpected sync result: " + syncResult);
        }
            String branchName;
            if (desiredBranchName == null || desiredBranchName.trim().isEmpty()) 
            {
                branchName = "contribution-" + System.currentTimeMillis();
            } 
            else 
            {
                branchName = desiredBranchName.trim();
            }
            publish("Checking branch...");
            String upstreamOwner = GitHubForkService.getUpstreamOwner();
            branchService.ensureBranchExists(upstreamOwner, branchName);
            publish("Branch ready");

            resultBranchName = branchName;
        }
        return resultBranchName;
    }
    @Override
    protected void process(List<String> chunks) 
    {
        for (String statusMessage : chunks) 
        {
            callback.onStatusUpdate(statusMessage);
        }
    }
    @Override
    protected void done() 
    {
        try 
        {
            String branchName = get();
            callback.onSuccess(branchName);
        } 
        catch (InterruptedException e) 
        {
            Thread.currentThread().interrupt();
            callback.onFailure(e.getMessage());
        } 
        catch (java.util.concurrent.CancellationException e) 
        {
            callback.onFailure("Operation was cancelled.");
        } 
        catch (ExecutionException e) 
        {
            Throwable cause = e.getCause();
            if (cause instanceof ForkConflictException) 
            {
                callback.onConflict();
            } 
            else if (cause != null) 
            {
                callback.onFailure(cause.getMessage());
            } 
            else 
            {
                callback.onFailure(e.getMessage());
            }
        }
    }

     /**
     * Callback contract for ForkAndBranchWorker. All methods fire on the EDT,
     * same contract as GitHubAuthService.AuthCallback.
     */
    public interface ForkAndBranchCallback {
        void onStatusUpdate(String message);
        void onConflict();
        void onSuccess(String branchName);
        void onFailure(String errorMessage);
    }
}