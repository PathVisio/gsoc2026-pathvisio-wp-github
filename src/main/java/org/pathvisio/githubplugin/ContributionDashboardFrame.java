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

package org.pathvisio.githubplugin;

import org.pathvisio.desktop.PvDesktop;
import javax.swing.JFrame;
import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.BoxLayout;
import javax.swing.BorderFactory;

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
        submitNewButton.addActionListener(e -> {
            // TODO: open SubmitNewPathwayDialog once built (Module 6)
        });

        commitExistingButton = new JButton("Commit to existing pathway");
        commitExistingButton.addActionListener(e -> {
            // TODO: open CommitExistingPathwayDialog once built (Module 7)
        });

        submitForReviewButton = new JButton("Submit for review");
        submitForReviewButton.addActionListener(e -> {
            // TODO: open SubmitForReviewDialog once built (Module 9)
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