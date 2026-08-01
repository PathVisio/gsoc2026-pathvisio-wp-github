package org.pathvisio.githubplugin.worker;

import org.pathvisio.githubplugin.service.GitHubPullService;
import org.pathvisio.githubplugin.service.GitHubPullService.PullRequestValidationException;
import org.pathvisio.githubplugin.service.PullRequestResult;

import javax.swing.SwingWorker;
import java.util.List;
import java.util.concurrent.ExecutionException;

public class PullRequestWorker extends SwingWorker<PullRequestResult, String> 
{
    private final String accessToken;
    private final String upstreamOwner;
    private final String repoName;
    private final String forkOwner;
    private final String headBranch;
    private final String baseBranch;
    private final String title;
    private final String body;
    private final PullRequestCallback callback;

    public PullRequestWorker(String accessToken, String upstreamOwner, String repoName, String forkOwner, String headBranch, String baseBranch, String title, String body, PullRequestCallback callback) 
    {
        this.accessToken = accessToken;
        this.upstreamOwner = upstreamOwner;
        this.repoName = repoName;
        this.forkOwner = forkOwner;
        this.headBranch = headBranch;
        this.baseBranch = baseBranch;
        this.title = title;
        this.body = body;
        this.callback = callback;
    }
    @Override
    protected PullRequestResult doInBackground() throws Exception 
    {
        GitHubPullService pullService = new GitHubPullService(upstreamOwner, repoName, forkOwner, accessToken);

        publish("Creating pull request...");
        PullRequestResult result = pullService.createPullRequest(title, headBranch, baseBranch, body);

        publish("Pull request created");
        return result;
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
            PullRequestResult result = get();
            callback.onSuccess(result);
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
            if (cause instanceof PullRequestValidationException) 
            {
                callback.onValidationFailure(cause.getMessage());
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
    public interface PullRequestCallback 
    {
        void onStatusUpdate(String message);
        void onValidationFailure(String message); // HTTP 422 — no commits yet, or duplicate PR
        void onSuccess(PullRequestResult result);
        void onFailure(String errorMessage);
    }
}