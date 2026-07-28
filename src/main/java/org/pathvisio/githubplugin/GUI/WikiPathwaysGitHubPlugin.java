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
package org.pathvisio.githubplugin;

import org.pathvisio.desktop.PvDesktop;
import org.pathvisio.desktop.plugin.Plugin;
import org.pathvisio.githubplugin.service.GitHubAuthService;
import javax.swing.AbstractAction;
import javax.swing.Action;
import java.awt.event.ActionEvent;
import org.pathvisio.githubplugin.controller.PluginController;

/**
 * Main plugin class for integrating GitHub and WikiPathways functionality into PathVisio.
 *
 * <p>
 * This plugin enables PathVisio users to contribute pathway modifications directly to the
 * WikiPathways repository via GitHub. It provides a unified interface for authentication,
 * forking, branch management, file commits, and pull request creation.
 * </p>
 *
 * <p>
 * <strong>Plugin Lifecycle:</strong>
 * <ol>
 *   <li>PathVisio invokes {@link #init(PvDesktop)} during startup, passing a reference
 *       to the desktop environment.</li>
 *   <li>The plugin initializes core services ({@code PluginController}, {@code GitHubAuthService})
 *       and registers a menu action in the Plugins menu.</li>
 *   <li>When the user clicks the menu item, an action listener checks authentication status
 *       and delegates to the appropriate dialog (login or dashboard).</li>
 *   <li>When PathVisio shuts down, {@link #done()} is invoked to unregister the menu action.</li>
 * </ol>
 * </p>
 *
 * <p>
 * <strong>Menu Integration:</strong>
 * The plugin registers a "WikiPathways GitHub Plugin" menu item under the
 * {@code Plugins} menu. The menu action's behavior depends on authentication status:
 * <ul>
 *   <li><strong>Authenticated:</strong> Opens the {@code ContributionDashboardFrame}
 *       (Module 4), allowing users to manage pathways and submit contributions.</li>
 *   <li><strong>Not Authenticated:</strong> Opens the {@code AuthDialog} (Module 3),
 *       guiding the user through GitHub OAuth authentication.</li>
 * </ul>
 * </p>
 *
 * <p>
 * <strong>Core Components:</strong>
 * <ul>
 *   <li>{@link PluginController} — Maintains plugin state (access token, username,
 *       fork status, active file/pathway).</li>
 *   <li>{@link GitHubAuthService} — Handles GitHub OAuth Device Flow authentication,
 *       including device code requests, polling, and token storage.</li>
 * </ul>
 * </p>
 *
 * <p>
 * <strong>Dependencies:</strong>
 * This plugin depends on the PathVisio desktop framework ({@code org.pathvisio.desktop})
 * and integrates with various GitHub services defined in the
 * {@code org.pathvisio.githubplugin} package for handling forks, branches, commits, and
 * pull requests.
 * </p>
 *
 * @author Snehashree Prusty
 * @version 1.0
 * @see Plugin
 * @see PluginController
 * @see GitHubAuthService
 */
public class WikiPathwaysGitHubPlugin implements Plugin {

    /**
     * Reference to the PathVisio desktop environment.
     * Provides access to the desktop's menu bar and other UI components.
     */
    private PvDesktop desktop;

    /**
     * Controller for managing plugin state and application-level coordination.
     * Maintains user authentication details, fork status, and the active pathway model.
     */
    private PluginController controller;

    /**
     * Service for handling GitHub OAuth 2.0 authentication using the Device Flow.
     * Manages user login, token validation, and token persistence.
     */
    private GitHubAuthService authService;

    /**
     * The menu action registered in the Plugins menu.
     * Displays authentication or dashboard UI based on login status.
     */
    private Action menuAction;

    /**
     * The menu key under which the plugin action is registered.
     * Corresponds to the "Plugins" menu in PathVisio's menu bar.
     */
    private static final String MENU_KEY = "Plugins";

    /**
     * Initializes the plugin when PathVisio starts up.
     *
     * <p>
     * This method is invoked by the PathVisio framework during application startup.
     * It performs the following initialization steps:
     * <ol>
     *   <li>Stores the desktop reference for menu registration.</li>
     *   <li>Creates a new {@link PluginController} instance to manage plugin state.</li>
     *   <li>Creates a new {@link GitHubAuthService} instance for GitHub authentication.</li>
     *   <li>Defines and registers a menu action under the Plugins menu that:
     *       <ul>
     *         <li>Opens the contribution dashboard if the user is authenticated.</li>
     *         <li>Opens the login dialog if the user is not authenticated.</li>
     *       </ul>
     *   </li>
     * </ol>
     * </p>
     *
     * <p>
     * <strong>Note:</strong> The TODO comments indicate that {@code ContributionDashboardFrame}
     * and {@code AuthDialog} are planned for future modules (Module 4 and Module 3, respectively)
     * and are not yet implemented.
     * </p>
     *
     * @param desktop the PathVisio desktop environment, used to register menu actions
     */
    @Override
    public void init(PvDesktop desktop) 
    {
        this.desktop = desktop;
        this.controller = new PluginController();
        this.authService = new GitHubAuthService();

        menuAction = new AbstractAction("WikiPathways GitHub Plugin") {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (authService.isAuthenticated()) {
                    // TODO: open ContributionDashboardFrame once built (Module 4)
                } else {
                    // TODO: open AuthDialog once built (Module 3)
                }
            }
        };

        desktop.registerMenuAction(MENU_KEY, menuAction);
    }

    /**
     * Cleans up the plugin when PathVisio shuts down.
     *
     * <p>
     * This method is invoked by the PathVisio framework during application shutdown.
     * It unregisters the plugin's menu action from the desktop to ensure no lingering
     * UI references remain after the plugin is disabled.
     * </p>
     *
     * <p>
     * Called after {@link #init(PvDesktop)}.
     * </p>
     */
    @Override
    public void done() 
    {
        desktop.unregisterMenuAction(MENU_KEY, menuAction);
    }
}