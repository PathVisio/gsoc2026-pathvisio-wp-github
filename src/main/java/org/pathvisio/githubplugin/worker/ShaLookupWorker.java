package org.pathvisio.githubplugin.worker;

import org.pathvisio.githubplugin.util.GpmlEncoder;

import javax.swing.SwingWorker;
import java.util.List;
import java.util.concurrent.ExecutionException;

/**
 * Resolves the existing SHA (if any) of a GPML file at a given path in the
 * user's fork, off the EDT.
 *
 * <p>A {@code null} result is not a failure — it means no file currently
 * exists at that path (new-file case). Only genuine network/API errors
 * route to {@link ShaLookupCallback#onFailure(String)}.</p>
 */
public class ShaLookupWorker extends SwingWorker<String, String> 
{

    private final String accessToken;
    private final String forkOwner;
    private final String repoName;
    private final String path;
    private final ShaLookupCallback callback;

    public ShaLookupWorker(String accessToken, String forkOwner, String repoName,String path, ShaLookupCallback callback) 
    {
        this.accessToken = accessToken;
        this.forkOwner = forkOwner;
        this.repoName = repoName;
        this.path = path;
        this.callback = callback;
    }

    @Override
    protected String doInBackground() throws Exception 
    {
        publish("Checking for existing file...");

        String contentsApiUrl = "https://api.github.com/repos/"+ forkOwner + "/" + repoName + "/contents/" + path;

        String sha = GpmlEncoder.getExistingGpmlSHA(contentsApiUrl, accessToken);
        return sha; // null is valid — means no existing file at this path
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
            String sha = get();
            callback.onShaResolved(sha);
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
            if (cause != null) 
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
     * Callback contract for ShaLookupWorker. All methods fire on the EDT,
     * same contract as ForkAndBranchCallback / CommitCallback.
     */
    public interface ShaLookupCallback 
    {
        void onStatusUpdate(String message);
        void onShaResolved(String sha); // null means no existing file at this path
        void onFailure(String errorMessage);
    }
}