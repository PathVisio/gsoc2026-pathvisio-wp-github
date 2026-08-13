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

package org.pathvisio.githubplugin.GUI;

import org.pathvisio.desktop.PvDesktop;
import org.pathvisio.libgpml.model.PathwayModel;
import javax.swing.JFrame;
import javax.swing.JButton;
import javax.swing.JPanel;

import javax.swing.BoxLayout;
import javax.swing.BorderFactory;
import org.pathvisio.githubplugin.service.GitHubAuthService;
import org.pathvisio.githubplugin.controller.PluginController;

/**
 * The central hub of the plugin's contribution workflow.
 *
 * <p>
 * Presents two actions to an authenticated user, each of which now performs
 * a commit followed immediately by pull request creation as one operation:
 * <ol>
 *   <li><strong>Submit new pathway (Commit + PR)</strong> — opens
 *       {@link SubmitNewPathwayDialog} to commit a brand-new GPML file to
 *       WikiPathways and open a pull request for it.</li>
 *   <li><strong>Submit existing pathway</strong> — opens
 *       {@link CommitExistingPathwayDialog} to update a file already in the
 *       repository and open a pull request for the change.</li>
 * </ol>
 * </p>
 *
 * <p>
 * This frame assumes that an active {@link PathwayModel} and GPML file are 
 * already loaded (typically enforced by the UI before this dashboard can be 
 * opened).
 * </p>
 *
 * @see SubmitNewPathwayDialog
 * @see CommitExistingPathwayDialog
 */
public class ContributionDashboardFrame extends JFrame
{
    private final PvDesktop desktop;
    private final PluginController controller;
    private final GitHubAuthService authService;

    private JButton submitNewButton;
    private JButton commitExistingButton;
   

    public ContributionDashboardFrame(PvDesktop desktop, PluginController controller, GitHubAuthService authService)
    {
        super("WikiPathways contributions");
        this.desktop = desktop;
        this.controller = controller;
        this.authService = authService;

        buildUI();
    }

    // Builds and lays out all Swing components for this frame.
    private void buildUI()
    {
        submitNewButton = new JButton("Submit changes to new pathway");
        commitExistingButton = new JButton("Submit changes to existing pathway");

        submitNewButton.addActionListener(e -> {
        SubmitNewPathwayDialog dialog = new SubmitNewPathwayDialog(desktop.getFrame(), controller);
        dialog.setVisible(true);
        });

        commitExistingButton.addActionListener(e -> {
        CommitExistingPathwayDialog dialog = new CommitExistingPathwayDialog(desktop.getFrame(), controller);
        dialog.setVisible(true);
        });
       

        JPanel buttonPanel = new JPanel();
        buttonPanel.setLayout(new BoxLayout(buttonPanel, BoxLayout.Y_AXIS));
        buttonPanel.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));
        buttonPanel.add(submitNewButton);
        buttonPanel.add(commitExistingButton);


        this.add(buttonPanel);
        this.setSize(360, 160);
        this.setLocationRelativeTo(desktop.getFrame());
    }
}
