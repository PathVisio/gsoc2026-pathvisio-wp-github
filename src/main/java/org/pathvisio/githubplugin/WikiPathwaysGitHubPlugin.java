package org.pathvisio.githubplugin;

import org.pathvisio.desktop.PvDesktop;
import org.pathvisio.desktop.plugin.Plugin;

import javax.swing.AbstractAction;
import javax.swing.Action;
import java.awt.event.ActionEvent;

public class WikiPathwaysGitHubPlugin implements Plugin {

    private PvDesktop desktop;
    private PluginController controller;
    private GitHubAuthService authService;
    private Action menuAction;

    private static final String MENU_KEY = "Plugins";

    @Override
    public void init(PvDesktop desktop) {
        this.desktop = desktop;
        this.controller = new PluginController();
        this.authService = new GitHubAuthService();

        menuAction = new AbstractAction("WikiPathways GitHub Plugin") {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (authService.isAuthenticated()) {
                    // TODO: open ContributionDashboardFrame once built (Module 4)
                } else {
                    // TODO: open AuthDialog once built (Module 3)
                }
            }
        };

        desktop.registerMenuAction(MENU_KEY, menuAction);
    }

    @Override
    public void done() 
    {
        desktop.unregisterMenuAction(MENU_KEY, menuAction);
    }
}