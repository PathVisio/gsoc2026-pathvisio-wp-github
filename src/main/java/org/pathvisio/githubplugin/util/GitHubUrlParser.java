package org.pathvisio.githubplugin.util;

/**
 * Parses a GitHub repository HTTPS URL into its owner and repo components.
 *
 * <p>Accepts URLs of the form {@code https://github.com/owner/repo},
 * with or without a trailing {@code .git} or trailing slash. Does not
 * support SSH form or bare {@code owner/repo} shorthand.</p>
 */
public class GitHubUrlParser
{
    /**
     * Holds a parsed owner/repo pair.
     */
    public static class OwnerRepo
    {
        public final String owner;
        public final String repo;

        public OwnerRepo(String owner, String repo)
        {
            this.owner = owner;
            this.repo = repo;
        }
    }

    /**
     * Parses a GitHub repository URL into owner and repo.
     *
     * @param url the repository URL, e.g. "https://github.com/wikipathways/sandbox-wp-db"
     * @return the parsed owner/repo pair
     * @throws IllegalArgumentException if the URL is null, blank, or not a
     *         well-formed "https://github.com/owner/repo" URL
     */
    public static OwnerRepo parse(String url)
    {
        if (url == null || url.trim().isEmpty())
        {
            throw new IllegalArgumentException("Repository URL is empty.");
        }

        String trimmed = url.trim();

        if (!trimmed.startsWith("https://github.com/"))
        {
            throw new IllegalArgumentException(
                "Repository URL must start with https://github.com/ — got: " + trimmed);
        }

        String path = trimmed.substring("https://github.com/".length());

        if (path.endsWith("/"))
        {
            path = path.substring(0, path.length() - 1);
        }
        if (path.endsWith(".git"))
        {
            path = path.substring(0, path.length() - ".git".length());
        }

        String[] parts = path.split("/");
        if (parts.length != 2 || parts[0].isEmpty() || parts[1].isEmpty())
        {
            throw new IllegalArgumentException(
                "Repository URL must be in the form https://github.com/owner/repo — got: " + trimmed);
        }

        return new OwnerRepo(parts[0], parts[1]);
    }
}