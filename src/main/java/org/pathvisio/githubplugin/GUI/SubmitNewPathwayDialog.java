package org.pathvisio.githubplugin.GUI;

import org.pathvisio.githubplugin.controller.PluginController;
import org.pathvisio.githubplugin.worker.CommitWorker;
import org.pathvisio.githubplugin.worker.ForkAndBranchWorker;
import org.pathvisio.githubplugin.worker.ForkAndBranchWorker.ForkAndBranchCallback;
import org.pathvisio.libgpml.model.PathwayModel;

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;

/**
 * Dialog for submitting a brand-new pathway (no existing WikiPathways entry)
 * to the WikiPathways GitHub repository. Corresponds to Fig 4.5.3.
 *
 * <p>On open, automatically runs {@link ForkAndBranchWorker} in the background
 * to confirm the fork and branch are ready. The "Save Changes" button stays
 * disabled until that succeeds, then commits via {@link CommitWorker} with
 * {@code sha = null} (create flow, since this is always a new file).</p>
 */ class SubmitNewPathwayDialog extends JDialog 
{
    private final PluginController controller;
    private static final String UPSTREAM_REPO = "sandbox-wp-db";
    private ForkAndBranchWorker forkAndBranchWorker;

    private JTextField titleField;
    private JTextField commitMessageField;
    private JTextArea descriptionArea;
    private JTextArea statusArea;
    private JButton saveButton;
    private JButton cancelButton;
    private JTextField branchNameField;

    private CommitWorker commitWorker;
    private String confirmedBranch;
    private String repoPath;    

    public SubmitNewPathwayDialog(Frame owner, PluginController controller) 
    {
        super(owner, "Submit Pathway", true);
        this.controller = controller;

        buildUI();
        populateFromController();
        startForkAndBranch();
    }
    private void buildUI() 
    {
        setLayout(new BorderLayout(10, 10));

        JPanel formPanel = new JPanel();
        formPanel.setLayout(new BoxLayout(formPanel, BoxLayout.Y_AXIS));

        titleField = new JTextField();
        commitMessageField = new JTextField();
        branchNameField = new JTextField();
        descriptionArea = new JTextArea(3, 30);
        statusArea = new JTextArea(4, 30);
        statusArea.setEditable(false);
        statusArea.setText("Ready.");

        formPanel.add(new JLabel("Pathway Title:"));
        formPanel.add(titleField);
        formPanel.add(new JLabel("Commit Message:"));
        formPanel.add(commitMessageField);

        formPanel.add(new JLabel("Branch Name (optional):"));
        formPanel.add(branchNameField);

        formPanel.add(new JLabel("Description:"));
        formPanel.add(new JScrollPane(descriptionArea));

        JPanel statusPanel = new JPanel(new BorderLayout());
        statusPanel.setBorder(BorderFactory.createTitledBorder("Status"));
        statusPanel.add(new JScrollPane(statusArea), BorderLayout.CENTER);

        saveButton = new JButton("Save Changes");
        cancelButton = new JButton("Cancel");
        saveButton.setEnabled(false);

        JPanel buttonPanel = new JPanel();
        buttonPanel.add(cancelButton);
        buttonPanel.add(saveButton);

        cancelButton.addActionListener(e -> {
        if (forkAndBranchWorker != null) 
        {
            forkAndBranchWorker.cancel(true);
        }
        if (commitWorker != null) 
        commitWorker.cancel(true);
        dispose();
        });
        
        setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) 
            {
                if (forkAndBranchWorker != null) {
                    forkAndBranchWorker.cancel(true);
                }
                if (commitWorker != null) 
                {
                    commitWorker.cancel(true);
                }
                dispose();
            }
        });

        saveButton.addActionListener(e -> {
        commitWorker = new CommitWorker(
            controller.getAccessToken(),
            controller.getAuthenticatedUsername(),
            UPSTREAM_REPO,
            confirmedBranch,
            repoPath,
            controller.getActivePathwayModel(),
            null, // sha — always null, this is the create flow
            commitMessageField.getText(),
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
                }
                @Override
                public void onSuccess(String newSha) 
                {
                    statusArea.append("Commit successful. New SHA: " + newSha + "\n");
                    saveButton.setEnabled(false);
                }
                @Override
                public void onFailure(String errorMessage) 
                {
                    statusArea.append("Commit failed: " + errorMessage + "\n");
                }
            });
    commitWorker.execute();
    });
        add(formPanel, BorderLayout.NORTH);
        add(statusPanel, BorderLayout.CENTER);
        add(buttonPanel, BorderLayout.SOUTH);
        pack();
        setLocationRelativeTo(getOwner());
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

    private void startForkAndBranch() 
    {
        forkAndBranchWorker = new ForkAndBranchWorker
        (
                controller.getAccessToken(),
                controller.getAuthenticatedUsername(),
                UPSTREAM_REPO, 
                branchNameField.getText(),
                new ForkAndBranchCallback() 
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
                    }
                    @Override
                    public void onSuccess(String branchName) 
                    {
                        confirmedBranch = branchName;
                        controller.setConfirmedBranch(branchName);
                        controller.setForkReady(true);
                        statusArea.append("Branch ready: " + branchName + "\n");
                        saveButton.setEnabled(true);
                    }

                    @Override
                    public void onFailure(String errorMessage) 
                    {
                        statusArea.append("Error: " + errorMessage + "\n");
                    }
                });
        forkAndBranchWorker.execute(); 
    }
}
