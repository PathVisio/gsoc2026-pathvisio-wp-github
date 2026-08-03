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
package org.pathvisio.githubplugin.worker;

import org.pathvisio.githubplugin.service.GitHubCommitService;
import org.pathvisio.githubplugin.service.GitHubCommitService.StaleShaConflictException;
import org.pathvisio.githubplugin.util.GpmlEncoder;
import org.pathvisio.libgpml.model.PathwayModel;

import javax.swing.SwingWorker;
import java.util.List;
import java.util.concurrent.ExecutionException;

/**
 * Encodes a PathwayModel to GPML and commits it to a branch of the user's
 * fork, off the EDT.
 *
 * <p>Sequence: encode pathway to Base64 GPML, then commit via
 * {@link GitHubCommitService}. The SHA (null for new files, non-null for
 * updates) is supplied by the caller — this worker does not look it up
 * itself, since only the calling dialog knows whether it already resolved
 * one (e.g. for a SHA-match status display).</p>
 */
public class CommitWorker extends SwingWorker<String, String> 
{

    private final String accessToken;
    private final String forkOwner;
    private final String repoName;
    private final String branch;
    private final String path;
    private final PathwayModel pathwayModel;
    private final String sha;
    private final String commitMessage;
    private final CommitCallback callback;

    public CommitWorker (String accessToken, String forkOwner, String repoName, String branch, String path, PathwayModel pathwayModel, String sha, String commitMessage, CommitCallback callback) 
    {
        this.accessToken = accessToken;
        this.forkOwner = forkOwner;
        this.repoName = repoName;
        this.branch = branch;
        this.path = path;
        this.pathwayModel = pathwayModel;
        this.sha = sha;
        this.commitMessage = commitMessage;
        this.callback = callback;
    }

    @Override
    protected String doInBackground() throws Exception 
    {
        GitHubCommitService commitService = new GitHubCommitService(forkOwner, repoName, accessToken);

        publish("Encoding pathway...");
        String base64Content = GpmlEncoder.encodeToBase64(pathwayModel);

        publish("Committing to branch...");
        String newSha = commitService.commitFile(path, branch, base64Content, sha, commitMessage);

        publish("Commit complete");
        return newSha;
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
            String newSha = get();
            callback.onSuccess(newSha);
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
            if (cause instanceof StaleShaConflictException) 
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
     * Callback contract for CommitWorker. All methods fire on the EDT,
     * same contract as ForkAndBranchCallback / AuthCallback.
     */
    public interface CommitCallback {
        void onStatusUpdate(String message);
        void onConflict();
        void onSuccess(String newSha);
        void onFailure(String errorMessage);
    }
}