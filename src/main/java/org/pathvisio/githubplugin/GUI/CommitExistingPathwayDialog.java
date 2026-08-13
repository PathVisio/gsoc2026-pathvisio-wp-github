package org.pathvisio.githubplugin.GUI;


import org.pathvisio.githubplugin.controller.PluginController;
import org.pathvisio.githubplugin.worker.ForkAndBranchWorker;
import org.pathvisio.githubplugin.worker.ForkAndBranchWorker.ForkAndBranchCallback;
import org.pathvisio.githubplugin.worker.ShaLookupWorker;
import org.pathvisio.githubplugin.worker.ShaLookupWorker.ShaLookupCallback;
import org.pathvisio.libgpml.model.PathwayModel;

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
 * resolved SHA — this is the update flow, unlike Module 6's create flow.</p>
 */
public class CommitExistingPathwayDialog extends JDialog 
{
    private static final String UPSTREAM_REPO = "sandbox-wp-db";

    private final PluginController controller;

    private ForkAndBranchWorker forkAndBranchWorker;
    private ShaLookupWorker shaLookupWorker;
    private CommitWorker commitWorker;

    private String confirmedBranch;
    private String resolvedSha;
    private String repoPath;

    private JLabel activePathwayLabel;
    private JLabel targetRepoLabel;
    private JLabel shaStatusLabel;
   
    private JTextField commitTitleField;
    private JTextArea descriptionArea;
    private JTextArea statusArea;
    private JButton saveButton;
    private JButton cancelButton;
    

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

        contextPanel.add(activePathwayLabel);
        contextPanel.add(targetRepoLabel);
        contextPanel.add(shaStatusLabel);

        JPanel formPanel = new JPanel();
        formPanel.setLayout(new BoxLayout(formPanel, BoxLayout.Y_AXIS));

       
        commitTitleField = new JTextField();
        descriptionArea = new JTextArea(3, 30);
        statusArea = new JTextArea(4, 30);
        statusArea.setEditable(false);
        statusArea.setText("Ready.");

        
        formPanel.add(new JLabel("Commit Title:"));
        formPanel.add(commitTitleField);
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
        cancelRunningWorkers();
        dispose();
        });

        setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() 
        {
         @Override
            public void windowClosing(WindowEvent e) 
            {
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

        commitWorker = new CommitWorker(controller.getAccessToken(),
            controller.getAuthenticatedUsername(),
            UPSTREAM_REPO,
            confirmedBranch,
            repoPath,
            controller.getActivePathwayModel(),
            resolvedSha,
            commitTitleField.getText(),
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
                    cancelButton.setText("Close");
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