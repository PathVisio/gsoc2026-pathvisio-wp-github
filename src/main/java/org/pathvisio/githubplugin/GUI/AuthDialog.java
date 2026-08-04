package org.pathvisio.githubplugin.GUI;

import org.pathvisio.desktop.PvDesktop;
import org.pathvisio.githubplugin.service.GitHubAuthService;
import org.pathvisio.githubplugin.controller.PluginController;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

public class AuthDialog extends JDialog implements GitHubAuthService.AuthCallback 
{
    private final PluginController controller;
    private final GitHubAuthService authService;

    private JLabel userCodeLabel;
    private JLabel statusLabel;
    private JButton cancelButton;
    private final PvDesktop desktop;
    public AuthDialog(PvDesktop desktop, PluginController controller, GitHubAuthService authService) 
    {
        super(desktop.getFrame(), "GitHub Authentication", true);
        this.desktop = desktop;
        this.controller = controller;
        this.authService = authService;

        buildUI();

        authService.startAuthentication(this);
    }
     private void buildUI() 
     {
        userCodeLabel = new JLabel("Requesting device code from GitHub...");
        userCodeLabel.setHorizontalAlignment(SwingConstants.CENTER);

        statusLabel = new JLabel(" ");
        statusLabel.setHorizontalAlignment(SwingConstants.CENTER);

        cancelButton = new JButton("Cancel");
        cancelButton.addActionListener(e -> authService.cancelAuthentication());

        JPanel centerPanel = new JPanel(new BorderLayout(10, 10));
        centerPanel.add(userCodeLabel, BorderLayout.NORTH);
        centerPanel.add(statusLabel, BorderLayout.CENTER);

        JPanel buttonPanel = new JPanel(new FlowLayout());
        buttonPanel.add(cancelButton);

        this.setLayout(new BorderLayout(10, 10));
        this.add(centerPanel, BorderLayout.CENTER);
        this.add(buttonPanel, BorderLayout.SOUTH);
        this.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);
        this.addWindowListener(new WindowAdapter() 
        {
        @Override
        public void windowClosing(WindowEvent e) {
            authService.cancelAuthentication();
        }
        });
        this.setSize(400, 200);
        this.setLocationRelativeTo(getOwner());
     }
    @Override
    public void onUserCodeReceived(String userCode, int expiresIn) {
        userCodeLabel.setText(
            "<html><center>Enter this code at github.com/login/device:<br>"
            + "<b>" + userCode + "</b><br>"
            + "(expires in " + expiresIn + " seconds)</center></html>"
        );
    }

    @Override
    public void onStatusUpdate(String message) 
    {
        statusLabel.setText(message);
    }

    @Override
    public void onSuccess(String accessToken, String username) 
    {
        controller.setAccessToken(accessToken);
        controller.setAuthenticatedUsername(username);
        this.dispose();
        
        ContributionDashboardFrame dashboard = new ContributionDashboardFrame(desktop, controller, authService);
        dashboard.setVisible(true);
    }

    @Override
    public void onFailure(String errorMessage) 
    {
        statusLabel.setText(errorMessage);
        cancelButton.setEnabled(false);
    }
    
}