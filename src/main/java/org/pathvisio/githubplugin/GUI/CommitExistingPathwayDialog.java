package org.pathvisio.githubplugin.GUI;


import org.pathvisio.githubplugin.controller.PluginController;
import org.pathvisio.githubplugin.worker.ForkCheckWorker;
import org.pathvisio.githubplugin.worker.ForkCheckWorker.ForkCheckCallback;
import org.pathvisio.githubplugin.worker.BranchReuseWorker;
import org.pathvisio.githubplugin.worker.BranchReuseWorker.BranchReuseCallback;
import org.pathvisio.githubplugin.worker.ShaLookupWorker;
import org.pathvisio.githubplugin.worker.ShaLookupWorker.ShaLookupCallback;
import org.pathvisio.libgpml.model.PathwayModel;
import org.pathvisio.githubplugin.worker.PullRequestWorker;
import org.pathvisio.githubplugin.service.GitHubForkService;
import org.pathvisio.githubplugin.service.PullRequestResult;


import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.net.URI;

import org.pathvisio.githubplugin.worker.CommitWorker;
import org.pathvisio.githubplugin.worker.CommitWorker.CommitCallback;

import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;



/**
 * Dialog for committing changes to an existing pathway already present in
 * the WikiPathways repository.
 *
 * <p>Sequence: on open, {@link ForkCheckWorker} confirms the fork exists
 * and is synced, then on success a {@link ShaLookupWorker} resolves the
 * existing file's SHA at the derived repo path. Save Changes stays
 * disabled until both complete. On Save click, {@link BranchReuseWorker}
 * resolves which branch to commit to — reusing an abandoned branch,
 * deleting and recreating one whose prior PR was merged, or blocking if
 * an earlier PR is still open — before committing via {@code CommitWorker}
 * with the resolved SHA. This is the update flow, unlike create flow.</p>
 */
public class CommitExistingPathwayDialog extends JDialog
{
    private final String UPSTREAM_REPO;
    private final String UPSTREAM_OWNER;
    private static final String BASE_BRANCH = "main";
    private final PluginController controller;
    private static final String DASHBOARD_URL = "https://upload.wikipathways.org/dashboard?mine=1";   // Theme A, Step 4

    private ForkCheckWorker forkCheckWorker;
    private BranchReuseWorker branchReuseWorker;
    private ShaLookupWorker shaLookupWorker;
    private CommitWorker commitWorker;

    private String confirmedBranch;
    private String resolvedSha;
    private String repoPath;
    private boolean prInProgress = false;

    private JLabel activePathwayLabel;
    private JLabel targetRepoLabel;

    private JTextField wpidField;
    private JTextArea descriptionArea;
    private JTextArea statusArea;
    private JButton saveButton;
    private JButton cancelButton;

    private JCheckBox dataNodesCheckBox;
    private JCheckBox identifiersCheckBox;
    private JCheckBox interactionsCheckBox;
    private JCheckBox titleDescriptionCheckBox;
    private JCheckBox referencesCheckBox;
    private JCheckBox ontologyTagsCheckBox;
    private JCheckBox layoutOnlyCheckBox;
    private JCheckBox otherCheckBox;

    private JLabel statusLabel;
    private JPanel statusTextPanel;
    private JCheckBox showDetailsCheckBox;
    private JPanel logPanel;
    

    public CommitExistingPathwayDialog(Frame owner, PluginController controller) 
    {
        super(owner, "Commit to Existing Pathway", true);
        this.controller = controller;
        this.UPSTREAM_REPO = controller.getUpstreamRepo();
        this.UPSTREAM_OWNER = controller.getUpstreamOwner();
        buildUI();
        populateFromController();
        startForkCheck();
    }

    private void buildUI()
    {
        setLayout(new BorderLayout(10, 10));

        JPanel contextPanel = new JPanel();
        contextPanel.setLayout(new BoxLayout(contextPanel, BoxLayout.Y_AXIS));
        contextPanel.setBorder(BorderFactory.createTitledBorder("Context & Status"));

        activePathwayLabel = new JLabel("Active Pathway: (loading...)");
        targetRepoLabel = new JLabel("Target Repo: " + UPSTREAM_REPO);
        wpidField = new JTextField();
        wpidField.setEditable(false);
        wpidField.setFocusable(false);
        wpidField.setFont(wpidField.getFont().deriveFont(Font.BOLD, wpidField.getFont().getSize() + 2f));

        contextPanel.add(activePathwayLabel);
        contextPanel.add(targetRepoLabel);
        contextPanel.add(new JLabel("WPID:"));
        contextPanel.add(wpidField);

        JPanel formPanel = new JPanel();
        formPanel.setLayout(new BoxLayout(formPanel, BoxLayout.Y_AXIS));

        descriptionArea = new JTextArea(4, 30);
        statusArea = new JTextArea(4, 30);
        statusArea.setEditable(false);
        statusArea.setText("Ready.");

        dataNodesCheckBox = new JCheckBox("Data nodes");
        identifiersCheckBox = new JCheckBox("Identifiers");
        interactionsCheckBox = new JCheckBox("Interactions");
        titleDescriptionCheckBox = new JCheckBox("Title / description");
        referencesCheckBox = new JCheckBox("References");
        ontologyTagsCheckBox = new JCheckBox("Ontology tags");
        layoutOnlyCheckBox = new JCheckBox("Layout only");
        otherCheckBox = new JCheckBox("Other");
        JPanel whatChangedPanel = new JPanel(new GridLayout(3, 3));
        whatChangedPanel.setBorder(BorderFactory.createTitledBorder("What changed? (optional, helps the reviewer)"));
        whatChangedPanel.add(dataNodesCheckBox);
        whatChangedPanel.add(identifiersCheckBox);
        whatChangedPanel.add(interactionsCheckBox);
        whatChangedPanel.add(titleDescriptionCheckBox);
        whatChangedPanel.add(referencesCheckBox);
        whatChangedPanel.add(ontologyTagsCheckBox);
        whatChangedPanel.add(layoutOnlyCheckBox);
        whatChangedPanel.add(otherCheckBox);
    
        JLabel commentLabel = new JLabel("Additional Comment:");
        formPanel.add(whatChangedPanel);
        formPanel.add(commentLabel);
        formPanel.add(new JScrollPane(descriptionArea));

        JLabel warningBanner = new JLabel("This commit will be submitted as a pull request for review.");
        warningBanner.setOpaque(true);
        warningBanner.setBackground(Color.YELLOW);
        
        statusLabel = new JLabel("Ready.");
        statusTextPanel = new JPanel();
        statusTextPanel.setLayout(new BoxLayout(statusTextPanel, BoxLayout.Y_AXIS));
        statusTextPanel.setBackground(Color.WHITE);
        statusTextPanel.add(statusLabel);

        showDetailsCheckBox = new JCheckBox("Show details");

        logPanel = new JPanel(new BorderLayout());
        logPanel.setBorder(BorderFactory.createTitledBorder("Log"));
        logPanel.add(new JScrollPane(statusArea), BorderLayout.CENTER);
        logPanel.setVisible(false);   // hidden by default

        JPanel statusPanel = new JPanel(new BorderLayout());
        statusPanel.setBorder(BorderFactory.createTitledBorder("Status"));
        statusPanel.setBackground(Color.WHITE);
        statusPanel.add(statusTextPanel, BorderLayout.NORTH);
        statusPanel.add(showDetailsCheckBox, BorderLayout.CENTER);
        statusPanel.add(logPanel, BorderLayout.SOUTH);

        showDetailsCheckBox.addItemListener(e -> {
            logPanel.setVisible(showDetailsCheckBox.isSelected());
            pack();
        });

        saveButton = new JButton("Submit Pathway");
        cancelButton = new JButton("Cancel");
        saveButton.setEnabled(false);

        JPanel buttonPanel = new JPanel();
        buttonPanel.add(cancelButton);
        buttonPanel.add(saveButton);

    
        JPanel southPanel = new JPanel();
        southPanel.setLayout(new BoxLayout(southPanel, BoxLayout.Y_AXIS));
        southPanel.add(warningBanner);
        southPanel.add(statusPanel);
        southPanel.add(buttonPanel);

        cancelButton.addActionListener(e -> {
        if (prInProgress)
        {
            return;
        }
        cancelRunningWorkers();
        dispose();
        });

        setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() 
        {
        @Override
            public void windowClosing(WindowEvent e) 
            {
                if (prInProgress)
                {
                    return;
                }
                cancelRunningWorkers();
                dispose();
            }
        });

    saveButton.addActionListener(e -> startBranchReuseCheck());

        JPanel topPanel = new JPanel();
        topPanel.setLayout(new BoxLayout(topPanel, BoxLayout.Y_AXIS));
        topPanel.add(contextPanel);
        topPanel.add(formPanel);

        add(topPanel, BorderLayout.NORTH);
        add(southPanel, BorderLayout.SOUTH);

        pack();
        setLocationRelativeTo(getOwner());
    }

    private void showDashboardLink() 
    {
        JLabel dashboardLink = new JLabel("<html><a href=''>see it on the dashboard</a></html>");
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


    private void cancelRunningWorkers()
    {
        if (forkCheckWorker != null)
        {
            forkCheckWorker.cancel(true);
        }
        if (branchReuseWorker != null)
        {
            branchReuseWorker.cancel(true);
        }
        if (shaLookupWorker != null)
        {
            shaLookupWorker.cancel(true);
        }
        if (commitWorker != null)
        {
            commitWorker.cancel(true);
        }
    }

    
    private void populateFromController()
    {
        PathwayModel model = controller.getActivePathwayModel();
        if (model == null)
        {
            activePathwayLabel.setText("Active Pathway: (none loaded)");
        }

        else
        {
            activePathwayLabel.setText("Active Pathway: " + model.getPathway().getTitle());
            String version = model.getPathway().getVersion();
            if (version != null && version.matches("^WP\\d+.*"))
            {
                int underscoreIndex = version.indexOf('_');
                wpidField.setText(underscoreIndex > 0 ? version.substring(0, underscoreIndex) : version);
            }

            else
            {
                wpidField.setText("(not yet assigned)");
            }
        }

        File gpmlFile = controller.getActiveGpmlFile();
        if (gpmlFile != null)
        {
            repoPath = buildRepoPath(gpmlFile);
        }
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
                UPSTREAM_OWNER,
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
                    statusArea.append("SHA Status: Blocked (fork conflict)\n");
                    statusLabel.setText("There was a problem preparing your submission. See details below.");
                    }

                    @Override
                    public void onSuccess()
                    {
                        controller.setForkReady(true);
                        statusArea.append("Fork ready.\n");
                        statusLabel.setText("Looking up the existing file...");
                        startShaLookup();
                    }

                    @Override
                    public void onFailure(String errorMessage)
                    {
                        statusArea.append("Error: " + errorMessage + "\n");
                        statusArea.append("SHA Status: Unavailable (fork error)\n");
                        statusLabel.setText("There was a problem preparing your submission. See details below.");
                    }
                });
        forkCheckWorker.execute();
    }

    private void startCommit()
    {
        saveButton.setEnabled(false);
        statusLabel.setText("Saving your changes...");
        String commitTitle = "Update " + wpidField.getText();

        commitWorker = new CommitWorker(controller.getAccessToken(),
            controller.getAuthenticatedUsername(),
            UPSTREAM_REPO,
            confirmedBranch,
            repoPath,
            controller.getActivePathwayModel(),
            resolvedSha,
            commitTitle,
            new CommitCallback()
            {
                @Override
                public void onStatusUpdate(String message)
                {
                    statusArea.append(message + "\n");
                }

                @Override
                public void onConflict() 
                {
                    statusArea.append(
                        "Error: this file has changed on GitHub since it was "
                        + "loaded. Please close this dialog and try again to "
                        + "pick up the latest version.\n");
                    saveButton.setEnabled(true);
                    statusLabel.setText("Someone else has changed this pathway. Please close this window and try again.");
                }

                @Override
                public void onSuccess(String newSha) 
                {
                    resolvedSha = newSha;
                    statusArea.append("Commit successful. New SHA: " + newSha + "\n");
                    saveButton.setText("Committed");
                    statusLabel.setText("Your changes were saved. Submitting for review...");
                    String prTitle = "Contribution: " + commitTitle;
                    String prBody = buildCommitDescription();

                    PullRequestWorker pullRequestWorker = new PullRequestWorker(
                        controller.getAccessToken(),
                        UPSTREAM_OWNER,
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
                                prInProgress = false;
                                cancelButton.setEnabled(true);
                                cancelButton.setText("Close");
                                showDashboardLink();

                            }
                            
                            @Override
                            public void onValidationFailure(String message)
                            {
                                statusArea.append("Committed (SHA: " + newSha + "), but PR creation failed: " + message + "\n");
                                prInProgress = false;
                                cancelButton.setEnabled(true);
                                cancelButton.setText("Close");
                                statusLabel.setText("Your changes were saved, but the submission for review failed. See details below.");
                            }

                            @Override
                            public void onFailure(String errorMessage)
                            {
                                statusArea.append("Committed (SHA: " + newSha + "), but PR creation failed: " + errorMessage + "\n");
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
                    statusArea.append("Error: " + errorMessage + "\n");
                    saveButton.setEnabled(true);
                    statusLabel.setText("There was a problem preparing your submission. See details below.");
                }
            });
        commitWorker.execute();
    }

    private void startBranchReuseCheck()
    {
        String wpid = wpidField.getText();
        if (wpid == null || !wpid.matches("^WP\\d+$"))
        {
            statusArea.append("Error: this pathway has no valid WPID (field shows: \"" + wpid + "\"). "
                + "Branch operations require a real WPID and cannot proceed.\n");
            statusLabel.setText("This pathway doesn't have a WPID yet — use \"Submit New Pathway\" instead.");
            saveButton.setEnabled(false);
            return;
        }
        saveButton.setEnabled(false);
        statusLabel.setText("Checking branch status...");

        branchReuseWorker = new BranchReuseWorker(
                controller.getAccessToken(),
                controller.getAuthenticatedUsername(),
                UPSTREAM_OWNER,
                UPSTREAM_REPO,
                wpidField.getText(),
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

    private String buildCommitDescription()
    {
        StringBuilder changes = new StringBuilder();
    
        if (dataNodesCheckBox.isSelected())
        {
            appendChange(changes, "Data nodes");
        }

        if (identifiersCheckBox.isSelected())
        {
            appendChange(changes, "Identifiers");
        }

        if (interactionsCheckBox.isSelected())
        {
            appendChange(changes, "Interactions");
        }

        if (titleDescriptionCheckBox.isSelected())
        {
            appendChange(changes, "Title/description");
        }

        if (referencesCheckBox.isSelected())
        {
            appendChange(changes, "References");
        }

        if (ontologyTagsCheckBox.isSelected())
        {
            appendChange(changes, "Ontology tags");
        }

        if (layoutOnlyCheckBox.isSelected())
        {
            appendChange(changes, "Layout only");
        }

        if (otherCheckBox.isSelected())
        {
            appendChange(changes, "Other");
        }

        String changesLine;
        if (changes.length() == 0)
        {
            changesLine = "No changes specified.";
        }

        else
        {
            changesLine = "Changes: " + changes.toString();
        }

        String note = descriptionArea.getText();
        if (note == null || note.trim().isEmpty()) 
        {
            return changesLine;
        }
        else
        {
            return changesLine + "\n\n" + note.trim();
        }
    }


    private void appendChange(StringBuilder builder, String label) 
    {
        if (builder.length() > 0) 
        {
            builder.append(", ");
        }
        builder.append(label);
    }
    
    private void startShaLookup()
    {
        if (repoPath == null)
        {
            statusArea.append("Error: no active GPML file to resolve a path from.\n");
            statusArea.append("SHA Status: Unavailable (no active file)\n");
            statusLabel.setText("There was a problem preparing your submission. See details below.");
            return;
        }

        shaLookupWorker = new ShaLookupWorker(
                controller.getAccessToken(),
                controller.getAuthenticatedUsername(),
                UPSTREAM_REPO,
                repoPath,
                new ShaLookupCallback() {
                    @Override
                    public void onStatusUpdate(String message) 
                    {
                        statusArea.append(message + "\n");
                    }

                    @Override
                    public void onShaResolved(String sha) 
                    {
                        resolvedSha = sha;
                        if (sha == null)
                        {
                            statusArea.append("SHA Status: No existing file at this path (will create)\n");
                        }
                        else
                        {
                            statusArea.append("SHA Status: Existing file found (will update)\n");
                        }
                        saveButton.setEnabled(true);
                        statusLabel.setText("Ready. Fill in what changed and click Save.");
                    }

                    @Override
                    public void onFailure(String errorMessage)
                    {
                        statusArea.append("Error: " + errorMessage + "\n");
                        statusArea.append("SHA Status: Lookup failed\n");
                        statusLabel.setText("There was a problem preparing your submission. See details below.");
                    }
                });
        shaLookupWorker.execute();
    }
}