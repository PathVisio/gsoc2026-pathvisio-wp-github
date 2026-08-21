package org.pathvisio.githubplugin.worker;

import org.pathvisio.githubplugin.service.GitHubForkService;
import org.pathvisio.githubplugin.service.GitHubForkSyncService;
import org.pathvisio.githubplugin.service.GitHubForkSyncService.SyncResult;

import javax.swing.SwingWorker;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutionException;

/**
 * Confirms the user's fork exists and is synced with upstream, off the EDT.
 * Does NOT create or check any branch — see {@link BranchReuseWorker} for
 * that, which now runs separately on Save click rather than on dialog open.
 */
public class ForkCheckWorker extends SwingWorker<Void, String>
{
    private final String accessToken;
    private final String authenticatedUsername;
    private final String upstreamOwner;
    private final String upstreamRepo;
    private final ForkCheckCallback callback;

    private static class ForkConflictException extends IOException
    {
        ForkConflictException(String message) { super(message); }
    }

    public ForkCheckWorker(String accessToken, String authenticatedUsername, String upstreamOwner, String upstreamRepo, ForkCheckCallback callback)
    {
        this.accessToken = accessToken;
        this.authenticatedUsername = authenticatedUsername;
        this.upstreamOwner = upstreamOwner;
        this.upstreamRepo = upstreamRepo;
        this.callback = callback;
    }

    @Override
    protected Void doInBackground() throws Exception
    {
        GitHubForkService forkService = new GitHubForkService(accessToken, authenticatedUsername, upstreamOwner, upstreamRepo);
        GitHubForkSyncService syncService = new GitHubForkSyncService(accessToken, authenticatedUsername, upstreamRepo);

        publish("Checking fork...");
        boolean forkReady = forkService.ensureForkExists();
        if (!forkReady)
        {
            throw new IOException("Fork creation timed out before becoming ready.");
        }
        publish("Fork ready");

        publish("Syncing with upstream...");
        SyncResult syncResult = syncService.syncWithUpstreamMain();
        if (syncResult == SyncResult.CONFLICT)
        {
            throw new ForkConflictException("Fork's main has diverged and could not be auto-synced.");
        }
        publish(syncResult == SyncResult.UPDATED ? "Fork synced" : "Fork already up to date");

        return null;
    }

    @Override
    protected void process(List<String> chunks)
    {
        for (String message : chunks)
        {
            callback.onStatusUpdate(message);
        }
    }

    @Override
    protected void done()
    {
        try
        {
            get();
            callback.onSuccess();
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

    public interface ForkCheckCallback
    {
        void onStatusUpdate(String message);
        void onConflict();
        void onSuccess();
        void onFailure(String errorMessage);
    }
}