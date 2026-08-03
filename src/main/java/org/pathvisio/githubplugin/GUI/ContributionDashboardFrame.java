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
import javax.swing.JOptionPane;
import javax.swing.BoxLayout;
import javax.swing.BorderFactory;
import org.pathvisio.githubplugin.service.GitHubAuthService;
import org.pathvisio.githubplugin.controller.PluginController;

import org.pathvisio.githubplugin.GUI.SubmitNewPathwayDialog;
import org.pathvisio.githubplugin.GUI.CommitExistingPathwayDialog;
import org.pathvisio.githubplugin.GUI.SubmitForReviewDialog;


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
 * Before opening the first two dialogs, this frame reads the currently active
 * {@link PathwayModel} from PathVisio's engine and the active GPML file from
 * the {@link PluginController}, then guards against the case where no pathway
 * is open. {@link SubmitForReviewDialog} does not require an active pathway —
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

    /**
     * Reads the active pathway model from PathVisio's engine and the active
     * GPML file from the controller, then stores them back onto the controller
     * so that the commit dialogs can read them via {@code populateFromController()}.
     *
     * <p>
     * Both the model and the file must be non-null for a commit dialog to work.
     * If either is missing, this method shows a {@link JOptionPane} error to the
     * user and returns {@code false} — the caller must not open the dialog.
     * </p>
     *
     * <p>
     * The model is sourced live from
     * {@code desktop.getSwingEngine().getEngine().getActivePathwayModel()} so it
     * always reflects what is currently open in the PathVisio canvas. The file is
     * read from {@code controller.getActiveGpmlFile()}, which is populated by the
     * {@code ApplicationEvent.PATHWAY_OPENED} listener registered in
     * {@link WikiPathwaysGitHubPlugin#init(PvDesktop)}.
     * </p>
     *
     * @return {@code true} if both model and file are available and have been
     *         loaded into the controller; {@code false} if the user should be
     *         asked to open a GPML file first
     */
    private boolean loadActivePathwayIntoController()
    {
        // Read the live model from PathVisio's engine — this is always current.
        PathwayModel model =
            desktop.getSwingEngine().getEngine().getActivePathwayModel();

        if (model == null)
        {
            JOptionPane.showMessageDialog(
                this,
                "No pathway is currently open in PathVisio.\n"
                    + "Please open a GPML file before submitting.",
                "No Pathway Open",
                JOptionPane.WARNING_MESSAGE
            );
            return false;
        }

        // The active file is tracked by the plugin controller via the
        // ApplicationEvent.PATHWAY_OPENED listener in WikiPathwaysGitHubPlugin.
        // If it is null here, the listener was not fired (e.g. the file was
        // already open before the plugin was initialized this session).
        if (controller.getActiveGpmlFile() == null)
        {
            JOptionPane.showMessageDialog(
                this,
                "Could not determine the GPML file path for the current pathway.\n"
                    + "Please close and reopen the file, then try again.",
                "File Not Resolved",
                JOptionPane.WARNING_MESSAGE
            );
            return false;
        }

        // Push the live model into the controller so the dialog's
        // populateFromController() reads an up-to-date copy.
        controller.setActivePathwayModel(model);
        return true;
    }
}
