package org.pathvisio.githubplugin.GUI;

import org.pathvisio.githubplugin.controller.PluginController;
import org.pathvisio.githubplugin.service.GitHubForkService;
import org.pathvisio.githubplugin.worker.PullRequestWorker;
import org.pathvisio.githubplugin.worker.PullRequestWorker.PullRequestCallback;
import org.pathvisio.githubplugin.service.PullRequestResult;
import org.pathvisio.libgpml.model.PathwayModel;

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

/**
 * Dialog for opening a pull request to submit a pathway for review. 
 * This dialog does NOT auto-start any background work, since the user may want to edit the title/body first.
 * The caller should create a {@link PullRequestWorker} and call {@code execute()} on
 */
public class SubmitForReviewDialog extends JDialog 
{
    private static final String UPSTREAM_REPO = "sandbox-wp-db";
    private static final String BASE_BRANCH = "main";

    private final PluginController controller;
    private PullRequestWorker pullRequestWorker;

    private JTextField reviewTitleField;
    private JTextArea descriptionArea;
    private JProgressBar progressBar;
    private JTextArea statusArea;
    private JButton createPrButton;
    private JButton cancelButton;

    public SubmitForReviewDialog(Frame owner, PluginController controller) 
    {
        super(owner, "Submit Pathway for Review", true);
        this.controller = controller;

        buildUI();
        populateFromController();
    }
   

    private void buildUI() 
    {
        setLayout(new BorderLayout(10, 10));

        JPanel formPanel = new JPanel();
        formPanel.setLayout(new BoxLayout(formPanel, BoxLayout.Y_AXIS));

        reviewTitleField = new JTextField();
        descriptionArea = new JTextArea(3, 30);

        JLabel infoBanner = new JLabel("<html>This will open a pull request from your branch into " + "the upstream repository's main branch for review.</html>");

        formPanel.add(new JLabel("Review Title:"));
        formPanel.add(reviewTitleField);
        formPanel.add(new JLabel("Description:"));
        formPanel.add(new JScrollPane(descriptionArea));
        formPanel.add(infoBanner);

        progressBar = new JProgressBar();
        progressBar.setIndeterminate(false);
        progressBar.setVisible(false);

        statusArea = new JTextArea(4, 30);
        statusArea.setEditable(false);
        statusArea.setText("Ready.");

        JPanel statusPanel = new JPanel(new BorderLayout(5, 5));
        statusPanel.setBorder(BorderFactory.createTitledBorder("Status"));
        statusPanel.add(progressBar, BorderLayout.NORTH);
        statusPanel.add(new JScrollPane(statusArea), BorderLayout.CENTER);

        createPrButton = new JButton("Create Pull Request");
        cancelButton = new JButton("Cancel");

        JPanel buttonPanel = new JPanel();
        buttonPanel.add(cancelButton);
        buttonPanel.add(createPrButton);

        cancelButton.addActionListener(e -> {
            if (pullRequestWorker != null) 
            {
                pullRequestWorker.cancel(true);
            }
            dispose();
        });

        setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() 
        {
            @Override
            public void windowClosing(WindowEvent e) 
            {
                if (pullRequestWorker != null) 
                {
                    pullRequestWorker.cancel(true);
                }
                dispose();
            }
        });

        createPrButton.addActionListener(e -> startPullRequest());

        add(formPanel, BorderLayout.NORTH);
        add(statusPanel, BorderLayout.CENTER);
        add(buttonPanel, BorderLayout.SOUTH);

        pack();
        setLocationRelativeTo(getOwner());
    }

    private void populateFromController() 
    {
        PathwayModel model = controller.getActivePathwayModel();
        if (model != null) {
            reviewTitleField.setText(model.getPathway().getTitle());
        }

        if (controller.getConfirmedBranch() == null) 
        {
            createPrButton.setEnabled(false);
            statusArea.setText("No branch has been confirmed yet. Please submit or commit "+ "a pathway first before requesting review.");
        } 
        else 
        {
            createPrButton.setEnabled(true);
        }
    }
    private void startPullRequest() 
    {
        createPrButton.setEnabled(false);
        progressBar.setVisible(true);
        progressBar.setIndeterminate(true);

        pullRequestWorker = new PullRequestWorker(
                controller.getAccessToken(),
                GitHubForkService.getUpstreamOwner(),
                UPSTREAM_REPO,
                controller.getAuthenticatedUsername(),
                controller.getConfirmedBranch(),
                BASE_BRANCH,
                reviewTitleField.getText(),
                descriptionArea.getText(),
                new PullRequestCallback() 
                {

                    @Override
                    public void onStatusUpdate(String message) 
                    {
                        statusArea.append(message + "\n");
                    }

                    @Override
                    public void onValidationFailure(String message) 
                    {
                        statusArea.append("Could not create pull request: " + message + "\n");
                        progressBar.setIndeterminate(false);
                        progressBar.setVisible(false);
                        createPrButton.setEnabled(true);
                    }

                    @Override
                    public void onSuccess(PullRequestResult result) 
                    {
                        statusArea.append("Pull request #" + result.getNumber() + " created.\n");
                        statusArea.append(result.getHtmlUrl() + "\n");
                        progressBar.setIndeterminate(false);
                        progressBar.setVisible(false);
                        createPrButton.setText("Done");
                        createPrButton.setEnabled(false);
                        cancelButton.setText("Close");
                    }

                    @Override
                    public void onFailure(String errorMessage) 
                    {
                        statusArea.append("Error: " + errorMessage + "\n");
                        progressBar.setIndeterminate(false);
                        progressBar.setVisible(false);
                        createPrButton.setEnabled(true);
                    }
                });
        pullRequestWorker.execute();
    }
}