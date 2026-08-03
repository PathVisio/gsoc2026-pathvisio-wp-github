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
 * Presents three actions to an authenticated user:
 * <ol>
 *   <li><strong>Submit new pathway</strong> — opens {@link SubmitNewPathwayDialog}
 *       to commit a brand-new GPML file to WikiPathways (Module 6).</li>
 *   <li><strong>Commit to existing pathway</strong> — opens
 *       {@link CommitExistingPathwayDialog} to update a file already in the
 *       repository (Module 7).</li>
 *   <li><strong>Submit for review</strong> — opens {@link SubmitForReviewDialog}
 *       to open a GitHub pull request from the user's branch (Module 9).</li>
 * </ol>
 * </p>
 *
 * <p>
 * This frame assumes that an active {@link PathwayModel} and GPML file are 
 * already loaded (typically enforced by the UI before this dashboard can be 
 * opened). {@link SubmitForReviewDialog} does not require an active pathway —
 * it guards internally against a missing branch.
 * </p>
 *
 * @see SubmitNewPathwayDialog
 * @see CommitExistingPathwayDialog
 * @see SubmitForReviewDialog
 */
public class ContributionDashboardFrame extends JFrame
{
    private final PvDesktop desktop;
    private final PluginController controller;
    private final GitHubAuthService authService;

    private JButton submitNewButton;
    private JButton commitExistingButton;
    private JButton submitForReviewButton;

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
        submitNewButton = new JButton("Submit new pathway");
        commitExistingButton = new JButton("Commit to existing pathway");
        submitForReviewButton = new JButton("Submit for review");

        submitNewButton.addActionListener(e -> {
        SubmitNewPathwayDialog dialog = new SubmitNewPathwayDialog(desktop.getFrame(), controller);
        dialog.setVisible(true);
        });

        commitExistingButton.addActionListener(e -> {
        CommitExistingPathwayDialog dialog = new CommitExistingPathwayDialog(desktop.getFrame(), controller);
        dialog.setVisible(true);
        });

        
        submitForReviewButton.addActionListener(e -> {
        SubmitForReviewDialog dialog = new SubmitForReviewDialog(desktop.getFrame(), controller);
        dialog.setVisible(true);
        });
       

        JPanel buttonPanel = new JPanel();
        buttonPanel.setLayout(new BoxLayout(buttonPanel, BoxLayout.Y_AXIS));
        buttonPanel.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));
        buttonPanel.add(submitNewButton);
        buttonPanel.add(commitExistingButton);
        buttonPanel.add(submitForReviewButton);

        this.add(buttonPanel);
        this.setSize(320, 220);
        this.setLocationRelativeTo(desktop.getFrame());
    }
}
