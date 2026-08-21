/*******************************************************************************
 * PathVisio, a tool for data visualization and analysis using biological pathways
 * Copyright 2006-2026 PathVisio
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy
 * of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 ******************************************************************************/
package org.pathvisio.githubplugin.controller;
import java.io.File;

import org.pathvisio.githubplugin.service.GitHubAuthService;
import org.pathvisio.githubplugin.service.GitHubBranchService;
import org.pathvisio.githubplugin.service.GitHubForkService;
import org.pathvisio.githubplugin.util.GitHubUrlParser;
import org.pathvisio.libgpml.model.PathwayModel;

import org.pathvisio.githubplugin.util.GitHubUrlParser;
import org.pathvisio.core.preferences.PreferenceManager;
import org.pathvisio.githubplugin.preferences.GitHubRepoPreference;

/**
 * Central state container for the PathVisio GitHub integration plugin.
 *
 * <p>
 * {@code PluginController} holds shared state across the plugin's UI and service
 * layers, including authentication credentials, fork readiness flags, GitHub branch
 * information, and references to the currently active GPML file and pathway model.
 * </p>
 *
 * <p>
 * Responsibilities:
 * <ul>
 *   <li>Store GitHub OAuth access token and authenticated username.</li>
 *   <li>Track fork readiness and the confirmed branch name for contributions.</li>
 *   <li>Maintain references to the active GPML file and its corresponding {@code PathwayModel}.</li>
 *   <li>Provide getter/setter pairs for each state field.</li>
 * </ul>
 * </p>
 *
 * <p>
 * Usage in Plugin Flow:
 * <ul>
 *   <li>After authentication (Module 3), the {@code accessToken} and {@code authenticatedUsername}
 *       are populated by {@link GitHubAuthService}.</li>
 *   <li>After fork operations (Module 2), {@code forkReady} is set to {@code true}.</li>
 *   <li>After branch creation (Module 2), {@code confirmedBranch} is populated with the
 *       branch name.</li>
 *   <li>When a user opens a GPML file in PathVisio, {@code activeGpmlFile} and
 *       {@code activePathwayModel} are populated by the UI layer.</li>
 *   <li>These values are then used by service classes to commit changes and create
 *       pull requests.</li>
 * </ul>
 * </p>
 *
 * <p>
 * <strong>Thread Safety:</strong> This class is not synchronized. If used in a
 * multi-threaded environment, external synchronization is required when
 * accessing or modifying state.
 * </p>
 *
 * @author Snehashree Prusty
 * @version 1.0
 * @see GitHubAuthService
 * @see GitHubForkService
 * @see GitHubBranchService
 */
public class PluginController 
{
    /**
     * GitHub OAuth access token obtained during authentication.
     * Used for authenticating all subsequent REST API calls.
     * {@code null} until the user authenticates.
     */
    private String accessToken;

    /**
     * The GitHub username of the authenticated user.
     * Retrieved from GitHub's /user endpoint after successful authentication.
     * {@code null} until the user authenticates.
     */
    private String authenticatedUsername;

    /**
     * Flag indicating whether a fork of the upstream repository exists
     * and is ready for branch creation and commits.
     * {@code false} by default; set to {@code true} after fork is confirmed
     * or created.
     */
    private boolean forkReady;

    /**
     * The confirmed branch name in the user's fork where contributions
     * will be made. For example: {@code "contribution-1234567890"}.
     * {@code null} until a branch is created or confirmed.
     */
    private String confirmedBranch;

    /**
     * Reference to the currently active GPML file selected or opened in
     * the PathVisio desktop application. {@code null} if no file is active.
     */
    private File activeGpmlFile;

    /**
     * The pathway model object corresponding to {@code activeGpmlFile}.
     * Loaded from the GPML file and used for serialization when committing
     * changes to GitHub. {@code null} if no file is active.
     */
    private PathwayModel activePathwayModel;
    
    /**
     * Creates a new {@code PluginController} with all state initialized to
     * default values (empty or false).
     */
    public PluginController() 
    {
        this.accessToken = null;
        this.authenticatedUsername = null;
        this.forkReady = false;
        this.confirmedBranch = null;
        this.activeGpmlFile = null;
        this.activePathwayModel = null;
    }
    /**
     * Returns the repo name portion of the upstream repository URL configured
     * in PathVisio's Preferences dialog (Theme F / Theme G).
     *
     * @return the parsed repo name (e.g. "sandbox-wp-db")
     * @throws IllegalArgumentException if the stored preference is not a
     *         well-formed https://github.com/owner/repo URL
     */
    public String getUpstreamRepo()
    {
        String url = PreferenceManager.getCurrent().get(GitHubRepoPreference.UPSTREAM_REPO);
        GitHubUrlParser.OwnerRepo parsed = GitHubUrlParser.parse(url);
        return parsed.repo;
    }

    /**
     * Returns the owner portion of the upstream repository URL configured
     * in PathVisio's Preferences dialog (Theme F / Theme G).
     *
     * @return the parsed owner (e.g. "wikipathways")
     * @throws IllegalArgumentException if the stored preference is not a
     *         well-formed https://github.com/owner/repo URL
     */
    public String getUpstreamOwner()
    {
        String url = PreferenceManager.getCurrent().get(GitHubRepoPreference.UPSTREAM_REPO);
        GitHubUrlParser.OwnerRepo parsed = GitHubUrlParser.parse(url);
        return parsed.owner;
    }

    /**
     * Returns the GitHub OAuth access token.
     *
     * @return the access token, or {@code null} if not yet authenticated
     */
    public String getAccessToken() 
    {
        return accessToken;
    }

    /**
     * Sets the GitHub OAuth access token.
     *
     * @param accessToken the access token obtained from GitHub authentication
     */
    public void setAccessToken(String accessToken) 
    {
        this.accessToken = accessToken;
    }

    /**
     * Returns the username of the authenticated GitHub user.
     *
     * @return the authenticated username, or {@code null} if not yet authenticated
     */
    public String getAuthenticatedUsername() 
    {
        return authenticatedUsername;
    }

    /**
     * Sets the username of the authenticated GitHub user.
     *
     * @param authenticatedUsername the GitHub username
     */
    public void setAuthenticatedUsername(String authenticatedUsername) 
    {
        this.authenticatedUsername = authenticatedUsername;
    }

    /**
     * Returns whether a fork is ready for branch creation and commits.
     *
     * @return {@code true} if the fork exists and is ready; {@code false} otherwise
     */
    public boolean isForkReady() 
    {
        return forkReady;
    }

    /**
     * Sets the fork readiness flag.
     *
     * @param forkReady {@code true} if the fork is ready; {@code false} otherwise
     */
    public void setForkReady(boolean forkReady) 
    {
        this.forkReady = forkReady;
    }

    /**
     * Returns the confirmed branch name in the user's fork.
     *
     * @return the branch name, or {@code null} if not yet confirmed
     */
    public String getConfirmedBranch() 
    {
        return confirmedBranch;
    }

    /**
     * Sets the confirmed branch name in the user's fork.
     *
     * @param confirmedBranch the branch name (e.g. {@code "contribution-123"})
     */
    public void setConfirmedBranch(String confirmedBranch) 
    {
        this.confirmedBranch = confirmedBranch;
    }

    /**
     * Returns the currently active GPML file.
     *
     * @return the active GPML file, or {@code null} if no file is active
     */
    public File getActiveGpmlFile() 
    {
        return activeGpmlFile;
    }

    /**
     * Sets the currently active GPML file.
     *
     * @param activeGpmlFile the GPML file to set as active
     */
    public void setActiveGpmlFile(File activeGpmlFile) 
    {
        this.activeGpmlFile = activeGpmlFile;
    }

    /**
     * Returns the pathway model of the currently active GPML file.
     *
     * @return the active pathway model, or {@code null} if no model is loaded
     */
    public PathwayModel getActivePathwayModel() 
    {
        return activePathwayModel;
    }

    /**
     * Sets the pathway model of the currently active GPML file.
     *
     * @param activePathwayModel the pathway model to set as active
     */
    public void setActivePathwayModel(PathwayModel activePathwayModel) 
    {
        this.activePathwayModel = activePathwayModel;
    }
    
}
