package org.pathvisio.githubplugin.preferences;

import org.pathvisio.core.preferences.Preference;

/**
 * Preference key(s) for the WikiPathways GitHub plugin's own settings,
 * registered in PathVisio's global Preferences dialog.
 *
 * <p>Currently holds a single preference: the upstream WikiPathways repo
 * the plugin forks, branches, commits, and opens pull requests against.
 * Defaults to "sandbox-wp-db" so existing behavior is unchanged for anyone
 * who never opens Preferences.</p>
 */
public enum GitHubRepoPreference implements Preference
{
    UPSTREAM_REPO("sandbox-wp-db");

    private final String defaultValue;

    GitHubRepoPreference(String defaultValue)
    {
        this.defaultValue = defaultValue;
    }

    @Override
    public String getDefault()
    {
        return defaultValue;
    }
}