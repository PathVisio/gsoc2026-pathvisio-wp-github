package org.pathvisio.githubplugin.worker;

import org.pathvisio.githubplugin.service.GitHubBranchService;
import org.pathvisio.githubplugin.service.GitHubPullService;
import org.pathvisio.githubplugin.service.PullRequestResult;
import org.pathvisio.githubplugin.service.GitHubForkService;

import javax.swing.SwingWorker;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutionException;

public class BranchReuseWorker extends SwingWorker<String, String>
{
    private final String accessToken;
    private final String authenticatedUsername;
    private final String upstreamOwner;
    private final String upstreamRepo;
    private final String wpid;
    private final BranchReuseCallback callback;

    private static class BlockedException extends IOException
    {
        BlockedException(String message) { super(message); }
    }

    public BranchReuseWorker(String accessToken, String authenticatedUsername,String upstreamOwner, String upstreamRepo, String wpid, BranchReuseCallback callback)
    {
        this.accessToken = accessToken;
        this.authenticatedUsername = authenticatedUsername;
        this.upstreamOwner = upstreamOwner;
        this.upstreamRepo = upstreamRepo;
        this.wpid = wpid;
        this.callback = callback;
    }

    @Override
    protected String doInBackground() throws Exception
    {
        GitHubBranchService branchService = new GitHubBranchService(accessToken, authenticatedUsername, upstreamRepo);
        GitHubPullService pullService = new GitHubPullService(upstreamOwner, upstreamRepo, authenticatedUsername, accessToken);

        String prefix = wpid + "_" + authenticatedUsername + "_";

        publish("Checking for an existing branch...");
        String existingBranch = branchService.findBranchByPrefix(prefix);

        if (existingBranch != null)
        {
            publish("Found an existing branch, checking its pull request...");
            PullRequestResult existingPr = pullService.findPullRequestForBranch(existingBranch);

            if (existingPr != null && !existingPr.isMerged())
            {
                throw new BlockedException("You already have an open pull request.");
            }

            if (existingPr != null && existingPr.isMerged())
            {
                publish("Old branch has been merged, cleaning up...");
                branchService.deleteBranch(existingBranch);
                // falls through to fresh-branch creation below
            }
            else
            {
                publish("Reusing your existing branch...");
                return existingBranch;
            }
        }

        publish("Creating a new branch...");
        String defaultBranch = branchService.getDefaultBranch(upstreamOwner);
        String headSha = branchService.getHeadSHA(defaultBranch);
        String newBranchName = wpid + "_" + authenticatedUsername + "_" + generateTimestamp();
        branchService.createBranch(newBranchName, headSha);
        publish("Branch ready");

        return newBranchName;
    }

    private String generateTimestamp()
    {
        return new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date());
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
            if (cause instanceof BlockedException)
            {
                callback.onBlocked(cause.getMessage());
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

    public interface BranchReuseCallback
    {
        void onStatusUpdate(String message);
        void onBlocked(String reason);
        void onSuccess(String branchName);
        void onFailure(String errorMessage);
    }
}