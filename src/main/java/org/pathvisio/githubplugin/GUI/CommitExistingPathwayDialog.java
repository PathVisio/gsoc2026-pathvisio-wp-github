package org.pathvisio.githubplugin.GUI;


import org.pathvisio.githubplugin.controller.PluginController;
import org.pathvisio.githubplugin.worker.ForkAndBranchWorker;
import org.pathvisio.githubplugin.worker.ForkAndBranchWorker.ForkAndBranchCallback;
import org.pathvisio.githubplugin.worker.ShaLookupWorker;
import org.pathvisio.githubplugin.worker.ShaLookupWorker.ShaLookupCallback;
import org.pathvisio.libgpml.model.PathwayModel;
import org.pathvisio.githubplugin.worker.PullRequestWorker;
import org.pathvisio.githubplugin.service.GitHubForkService;
import org.pathvisio.githubplugin.service.PullRequestResult;
import org.bridgedb.Xref;

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;

import org.pathvisio.githubplugin.worker.CommitWorker;
import org.pathvisio.githubplugin.worker.CommitWorker.CommitCallback;


/**
 * Dialog for committing changes to an existing pathway already present in
 * the WikiPathways repository.
 *
 * <p>Sequence: {@link ForkAndBranchWorker} confirms fork/branch readiness,
 * then on success a {@link ShaLookupWorker} resolves the existing file's
 * SHA at the derived repo path. Save Changes stays disabled until both
 * complete. Commits via {@code CommitWorker} (Module 8, complete) with the
 * resolved SHA — this is the update flow, unlike create flow.</p>
 */
public class CommitExistingPathwayDialog extends JDialog
{
    private static final String UPSTREAM_REPO = "sandbox-wp-db";
    private static final String BASE_BRANCH = "main";
    private final PluginController controller;

    private ForkAndBranchWorker forkAndBranchWorker;
    private ShaLookupWorker shaLookupWorker;
    private CommitWorker commitWorker;

    private String confirmedBranch;
    private String resolvedSha;
    private String repoPath;
    private boolean prInProgress = false;

    private JLabel activePathwayLabel;
    private JLabel targetRepoLabel;
    private JLabel shaStatusLabel;

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
    

    public CommitExistingPathwayDialog(Frame owner, PluginController controller) 
    {
        super(owner, "Commit to Existing Pathway", true);
        this.controller = controller;

        buildUI();
        populateFromController();
        startForkAndBranch();
    }

    private void buildUI() 
    {
        setLayout(new BorderLayout(10, 10));

        JPanel contextPanel = new JPanel();
        contextPanel.setLayout(new BoxLayout(contextPanel, BoxLayout.Y_AXIS));
        contextPanel.setBorder(BorderFactory.createTitledBorder("Context & Status"));

        activePathwayLabel = new JLabel("Active Pathway: (loading...)");
        targetRepoLabel = new JLabel("Target Repo: " + UPSTREAM_REPO);
        shaStatusLabel = new JLabel("SHA Status: Checking fork and branch...");
        wpidField = new JTextField();
        wpidField.setEditable(false);

        contextPanel.add(activePathwayLabel);
        contextPanel.add(targetRepoLabel);
        contextPanel.add(shaStatusLabel);
        contextPanel.add(new JLabel("WPID:"));
        contextPanel.add(wpidField);

        JPanel formPanel = new JPanel();
        formPanel.setLayout(new BoxLayout(formPanel, BoxLayout.Y_AXIS));

        descriptionArea = new JTextArea(3, 30);
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
    
        formPanel.add(whatChangedPanel);
        formPanel.add(new JLabel("Description:"));
        formPanel.add(new JScrollPane(descriptionArea));

        JLabel warningBanner = new JLabel("This commit will be made to the branch specified above.");
        warningBanner.setOpaque(true);
        warningBanner.setBackground(Color.YELLOW);

        JPanel statusPanel = new JPanel(new BorderLayout());
        statusPanel.setBorder(BorderFactory.createTitledBorder("Status"));
        statusPanel.add(new JScrollPane(statusArea), BorderLayout.CENTER);

        saveButton = new JButton("Save Changes");
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

        saveButton.addActionListener(e -> startCommit());

        add(contextPanel, BorderLayout.NORTH);
        add(formPanel, BorderLayout.CENTER);
        add(southPanel, BorderLayout.SOUTH);

        pack();
        setLocationRelativeTo(getOwner());
    }

    private void cancelRunningWorkers() 
    {
        if (forkAndBranchWorker != null) 
        {
            forkAndBranchWorker.cancel(true);
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
            Xref xref = model.getPathway().getXref();
            if (xref != null)
            {
                wpidField.setText(xref.getId());
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

    private void startForkAndBranch() {
        forkAndBranchWorker = new ForkAndBranchWorker(
                controller.getAccessToken(),
                controller.getAuthenticatedUsername(),
                UPSTREAM_REPO,
                null,
                new ForkAndBranchCallback() {
                    @Override
                    public void onStatusUpdate(String message) {
                        statusArea.append(message + "\n");
                    }

                    @Override
                    public void onConflict() {
                        statusArea.append("Fork has diverged and could not be synced.\n");
                        shaStatusLabel.setText("SHA Status: Blocked (fork conflict)");
                    }

                    @Override
                    public void onSuccess(String branchName) {
                        confirmedBranch = branchName;
                        controller.setConfirmedBranch(branchName);
                        controller.setForkReady(true);
                        statusArea.append("Branch ready: " + branchName + "\n");
                        startShaLookup();
                    }

                    @Override
                    public void onFailure(String errorMessage) {
                        statusArea.append("Error: " + errorMessage + "\n");
                        shaStatusLabel.setText("SHA Status: Unavailable (fork/branch error)");
                    }
                });
        forkAndBranchWorker.execute();
    }

    // start commit

    private void startCommit()
    {
        saveButton.setEnabled(false);

        
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
                }

                @Override
                public void onSuccess(String newSha) 
                {
                    resolvedSha = newSha;
                    statusArea.append("Commit successful. New SHA: " + newSha + "\n");
                    saveButton.setText("Committed");
                    
                    String prTitle = "Contribution: " + commitTitle;
                    String prBody = buildCommitDescription();

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
                                prInProgress = false;
                                cancelButton.setEnabled(true);
                                cancelButton.setText("Close");
                            }
                            
                            @Override
                            public void onValidationFailure(String message) 
                            {
                                statusArea.append("Committed (SHA: " + newSha + "), but PR creation failed: " + message + "\n");
                                prInProgress = false;
                                cancelButton.setEnabled(true);
                                cancelButton.setText("Close");
                            }

                            @Override
                            public void onFailure(String errorMessage)
                            {
                                statusArea.append("Committed (SHA: " + newSha + "), but PR creation failed: " + errorMessage + "\n");
                                prInProgress = false;
                                cancelButton.setEnabled(true);
                                cancelButton.setText("Close");
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
                }
            });
        commitWorker.execute();
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
            shaStatusLabel.setText("SHA Status: Unavailable (no active file)");
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
                        if (sha == null) {
                            shaStatusLabel.setText("SHA Status: No existing file at this path (will create)");
                        } else {
                            shaStatusLabel.setText("SHA Status: Existing file found (will update)");
                        }
                        saveButton.setEnabled(true);
                    }

                    @Override
                    public void onFailure(String errorMessage) 
                    {
                        statusArea.append("Error: " + errorMessage + "\n");
                        shaStatusLabel.setText("SHA Status: Lookup failed");
                    }
                });
        shaLookupWorker.execute();
    }
}