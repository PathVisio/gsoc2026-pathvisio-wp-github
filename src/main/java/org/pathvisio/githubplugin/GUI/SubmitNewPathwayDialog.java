package org.pathvisio.githubplugin.GUI;

import org.pathvisio.githubplugin.controller.PluginController;
import org.pathvisio.githubplugin.worker.CommitWorker;
import org.pathvisio.githubplugin.worker.ForkCheckWorker;
import org.pathvisio.githubplugin.worker.ForkCheckWorker.ForkCheckCallback;
import org.pathvisio.githubplugin.worker.BranchReuseWorker;
import org.pathvisio.githubplugin.worker.BranchReuseWorker.BranchReuseCallback;
import org.pathvisio.githubplugin.worker.PullRequestWorker;
import org.pathvisio.libgpml.model.PathwayModel;
import org.pathvisio.githubplugin.service.GitHubForkService;
import org.pathvisio.githubplugin.service.PullRequestResult;

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.net.URI;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;


/**
 * Dialog for submitting a brand-new pathway (no existing WikiPathways entry)
 * to the WikiPathways GitHub repository. Corresponds to Fig 4.5.3.
 *
 * <p>On open, automatically runs {@link ForkCheckWorker} in the background to
 * confirm the fork exists and is synced. The "Save Changes" button stays
 * disabled until that succeeds. On Save click, {@link BranchReuseWorker}
 * resolves which branch to commit to — reusing an abandoned branch, deleting
 * and recreating one whose prior PR was merged, or blocking if an earlier PR
 * is still open — using the fixed placeholder WPID ({@code "WP0001"}, matching
 * the WikiPathways pathway-portal's own pipeline-mode convention) since no
 * real WPID exists yet for a new pathway. Commits via {@link CommitWorker}
 * with {@code sha = null} (create flow, since this is always a new file).</p>
 */
class SubmitNewPathwayDialog extends JDialog 
{
    private final PluginController controller;
    private final String UPSTREAM_REPO;
    private static final String BASE_BRANCH = "main";
    private static final String DASHBOARD_URL = "https://upload.wikipathways.org/dashboard?mine=1";
    private ForkCheckWorker forkCheckWorker;
    private BranchReuseWorker branchReuseWorker;
    private JTextField titleField;
    private JTextArea descriptionArea;
    private JTextArea statusArea;
    private JButton saveButton;
    private JButton cancelButton;
    private JTextField wpidField;

    private JLabel statusLabel;
    private JPanel statusTextPanel;
    private JCheckBox showDetailsCheckBox;
    private JPanel logPanel;

    private CommitWorker commitWorker;
    private String confirmedBranch;
    private String repoPath;
    private boolean prInProgress = false;
    private static final String NEW_PATHWAY_WPID = "WP0001";

    public SubmitNewPathwayDialog(Frame owner, PluginController controller)
    {
        super(owner, "Submit Pathway", true);
        this.controller = controller;
        this.UPSTREAM_REPO = controller.getUpstreamRepo();
        buildUI();
        populateFromController();
        startForkCheck();
    }

    private void buildUI() 
    {
        setLayout(new BorderLayout(10, 10));

        JPanel formPanel = new JPanel();
        formPanel.setLayout(new BoxLayout(formPanel, BoxLayout.Y_AXIS));

        titleField = new JTextField();
        descriptionArea = new JTextArea(3, 30);
        wpidField = new JTextField("(assigned after curator approval)");
        wpidField.setEditable(false);
        wpidField.setForeground(Color.GRAY);
        statusArea = new JTextArea(4, 30);
        statusArea.setEditable(false);
        statusArea.setText("Ready.");

        formPanel.add(new JLabel("Pathway Title:"));
        formPanel.add(titleField);
    
        formPanel.add(new JLabel("Description:"));
        formPanel.add(new JScrollPane(descriptionArea));
        formPanel.add(new JLabel("WPID:"));
        formPanel.add(wpidField);

        statusLabel = new JLabel("Ready.");
        statusTextPanel = new JPanel();
        statusTextPanel.setLayout(new BoxLayout(statusTextPanel, BoxLayout.Y_AXIS));
        statusTextPanel.add(statusLabel);

        showDetailsCheckBox = new JCheckBox("Show details");

        logPanel = new JPanel(new BorderLayout());
        logPanel.setBorder(BorderFactory.createTitledBorder("Log"));
        logPanel.add(new JScrollPane(statusArea), BorderLayout.CENTER);
        logPanel.setVisible(false);   // hidden by default

        JPanel statusPanel = new JPanel(new BorderLayout());
        statusPanel.setBorder(BorderFactory.createTitledBorder("Status"));
        statusPanel.add(statusTextPanel, BorderLayout.NORTH);
        statusPanel.add(showDetailsCheckBox, BorderLayout.CENTER);
        statusPanel.add(logPanel, BorderLayout.SOUTH);

        showDetailsCheckBox.addItemListener(e -> {
            logPanel.setVisible(showDetailsCheckBox.isSelected());
            pack();
        });

        saveButton = new JButton("Save Changes");
        cancelButton = new JButton("Cancel");
        saveButton.setEnabled(false);

        JPanel buttonPanel = new JPanel();
        buttonPanel.add(cancelButton);
        buttonPanel.add(saveButton);

        cancelButton.addActionListener(e -> {
            if (prInProgress)
            {
                return;
            }
            if (forkCheckWorker != null)
            {
                forkCheckWorker.cancel(true);
            }
            if (branchReuseWorker != null)
            {
                branchReuseWorker.cancel(true);
            }
            if (commitWorker != null)
            {
                commitWorker.cancel(true);
            }
            dispose();
        });
        
        setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) 
            {
                if (prInProgress)
                {
                    return;
                }
            if (forkCheckWorker != null)
            {
                forkCheckWorker.cancel(true);
            }
            if (branchReuseWorker != null)
            {
                branchReuseWorker.cancel(true);
            }
            if (commitWorker != null)
            {
                commitWorker.cancel(true);
            }
                dispose();
            }
        });
        saveButton.addActionListener(e -> startBranchReuseCheck());
        add(formPanel, BorderLayout.NORTH);
        add(statusPanel, BorderLayout.CENTER);
        add(buttonPanel, BorderLayout.SOUTH);
        pack();
        setLocationRelativeTo(getOwner());
    }
    private void showDashboardLink() 
    {
        JLabel dashboardLink = new JLabel(
            "<html><a href=''>Your pathway was submitted — see it on the dashboard</a></html>");
        dashboardLink.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        dashboardLink.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) 
            {
                if (Desktop.isDesktopSupported()) 
                {
                    try 
                    {
                        Desktop.getDesktop().browse(new URI(DASHBOARD_URL));
                    } 
                    catch (Exception ex) 
                    {
                        statusArea.append("Could not open link: " + ex.getMessage() + "\n");
                    }
                } 
                else 
                {
                    statusArea.append("Open this link in your browser: " + DASHBOARD_URL + "\n");
                }
            }
        });

        statusLabel.setText("Your pathway was submitted for review.");
        statusLabel.setIcon(null);
        statusTextPanel.add(dashboardLink);
        statusTextPanel.revalidate();
        statusTextPanel.repaint();
    }

    private void populateFromController() 
    {
        File gpmlFile = controller.getActiveGpmlFile();
        if (gpmlFile != null) 
        {
            repoPath = buildRepoPath(gpmlFile);
        }

        PathwayModel model = controller.getActivePathwayModel();
        if (model == null) 
        {
            titleField.setText("");
        } 
        else 
        {
            titleField.setText(model.getPathway().getTitle());
            String description = model.getPathway().getDescription();
            if (description == null || description.trim().isEmpty())
            {
                descriptionArea.setText("");
            } 
            else 
            {
                descriptionArea.setText(description);
            }
        }
    }

    /**
     * Sanitizes a free-text pathway title into a safe repo path segment.
     * Falls back to a timestamp-based placeholder if the title is empty
     * or contains no usable characters after sanitization.
     */
    private String sanitizeTitle(String title)
    {
        String sanitized = title == null ? "" : title.trim().replaceAll("\\s+", "_");
        sanitized = sanitized.replaceAll("[^A-Za-z0-9_-]", "");
        if (sanitized.isEmpty())
        {
            sanitized = "untitled-pathway-" + System.currentTimeMillis();
        }
        return sanitized;
    }

    /**
     * Builds the repo-relative path for the active GPML file, matching
     * PathVisioGitHubCli2's convention: "pathways/<baseName>/<fileName>".
     */
    private String buildRepoPath(File gpmlFile) 
    {
        String fileName = gpmlFile.getName();
        String baseName = fileName.endsWith(".gpml")
                ? fileName.substring(0, fileName.length() - ".gpml".length())
                : fileName;
        return "pathways/" + baseName + "/" + fileName;
    }

    private void startForkCheck()
    {
        statusLabel.setText("Checking if your submission is ready...");

        forkCheckWorker = new ForkCheckWorker(
                controller.getAccessToken(),
                controller.getAuthenticatedUsername(),
                UPSTREAM_REPO,
                new ForkCheckCallback()
                {
                    @Override
                    public void onStatusUpdate(String message)
                    {
                        statusArea.append(message + "\n");
                    }

                    @Override
                    public void onConflict()
                    {
                        statusArea.append("Fork has diverged and could not be synced.\n");
                        statusLabel.setText("There was a problem preparing your submission. See details below.");
                    }

                    @Override
                    public void onSuccess()
                    {
                        controller.setForkReady(true);
                        statusArea.append("Fork ready.\n");
                        saveButton.setEnabled(true);
                        statusLabel.setText("Ready. Fill in the details and click Save.");
                    }

                    @Override
                    public void onFailure(String errorMessage)
                    {
                        statusArea.append("Error: " + errorMessage + "\n");
                        statusLabel.setText("There was a problem preparing your submission. See details below.");
                    }
                });
        forkCheckWorker.execute();
    }

    private void startCommit()
    {
        saveButton.setEnabled(false);

        if (repoPath == null)
        {
            String sanitizedTitle = sanitizeTitle(titleField.getText());
            repoPath = "pathways/" + sanitizedTitle + "/" + sanitizedTitle + ".gpml";
        }

        statusLabel.setText("Saving your changes...");
        String commitTitle = "New pathway: " + titleField.getText();

        commitWorker = new CommitWorker(
            controller.getAccessToken(),
            controller.getAuthenticatedUsername(),
            UPSTREAM_REPO,
            confirmedBranch,
            repoPath,
            controller.getActivePathwayModel(),
            null, // sha — always null, this is the create flow
            commitTitle,
            new CommitWorker.CommitCallback() {
                @Override
                public void onStatusUpdate(String message)
                {
                    statusArea.append(message + "\n");
                }

                @Override
                public void onConflict()
                {
                    statusArea.append("Conflict: file was modified concurrently.\n");
                    statusLabel.setText("Someone else has changed this pathway. Please close this window and try again.");
                }

                @Override
                public void onSuccess(String newSha)
                {
                    statusArea.append("Commit successful. New SHA: " + newSha + "\n");
                    statusLabel.setText("Your changes were saved. Submitting for review...");

                    String prTitle = "Contribution: " + titleField.getText();
                    String prBody = descriptionArea.getText();

                    PullRequestWorker pullRequestWorker = new PullRequestWorker(
                        controller.getAccessToken(),
                        GitHubForkService.getUpstreamOwner(),
                        UPSTREAM_REPO,
                        controller.getAuthenticatedUsername(),
                        confirmedBranch,
                        BASE_BRANCH,
                        prTitle,
                        prBody,
                        new PullRequestWorker.PullRequestCallback()
                        {
                            @Override
                            public void onStatusUpdate(String message)
                            {
                                statusArea.append(message + "\n");
                            }

                            @Override
                            public void onSuccess(PullRequestResult result)
                            {
                                statusArea.append("Pull request #" + result.getNumber() + " created.\n");
                                statusArea.append(result.getHtmlUrl() + "\n");
                                saveButton.setEnabled(false);
                                prInProgress = false;
                                cancelButton.setEnabled(true);
                                cancelButton.setText("Close");
                                showDashboardLink();
                            }

                            @Override
                            public void onValidationFailure(String message)
                            {
                                statusArea.append("Committed (SHA: " + newSha + "), but PR creation failed: " + message + "\n");
                                saveButton.setEnabled(false);
                                prInProgress = false;
                                cancelButton.setEnabled(true);
                                cancelButton.setText("Close");
                                statusLabel.setText("Your changes were saved, but the submission for review failed. See details below.");
                            }

                            @Override
                            public void onFailure(String errorMessage)
                            {
                                statusArea.append("Committed (SHA: " + newSha + "), but PR creation failed: " + errorMessage + "\n");
                                saveButton.setEnabled(false);
                                prInProgress = false;
                                cancelButton.setEnabled(true);
                                cancelButton.setText("Close");
                                statusLabel.setText("Your changes were saved, but the submission for review failed. See details below.");
                            }
                        }
                    );

                    prInProgress = true;
                    cancelButton.setEnabled(false);
                    pullRequestWorker.execute();
                }

                @Override
                public void onFailure(String errorMessage)
                {
                    statusArea.append("Commit failed: " + errorMessage + "\n");
                    statusLabel.setText("There was a problem preparing your submission. See details below.");
                }
            }
        );

        commitWorker.execute();
    }

    private void startBranchReuseCheck()
    {
        saveButton.setEnabled(false);
        statusLabel.setText("Checking branch status...");

        branchReuseWorker = new BranchReuseWorker(
                controller.getAccessToken(),
                controller.getAuthenticatedUsername(),
                UPSTREAM_REPO,
                NEW_PATHWAY_WPID,
                new BranchReuseCallback()
                {
                    @Override
                    public void onStatusUpdate(String message)
                    {
                        statusArea.append(message + "\n");
                    }

                    @Override
                    public void onBlocked(String reason)
                    {
                        statusArea.append("Blocked: " + reason + "\n");
                        statusLabel.setText(reason);
                    }

                    @Override
                    public void onSuccess(String branchName)
                    {
                        confirmedBranch = branchName;
                        controller.setConfirmedBranch(branchName);
                        statusArea.append("Branch ready: " + branchName + "\n");
                        startCommit();
                    }

                    @Override
                    public void onFailure(String errorMessage)
                    {
                        statusArea.append("Error: " + errorMessage + "\n");
                        saveButton.setEnabled(true);
                        statusLabel.setText("There was a problem preparing your submission. See details below.");
                    }
                });
        branchReuseWorker.execute();
    }
}