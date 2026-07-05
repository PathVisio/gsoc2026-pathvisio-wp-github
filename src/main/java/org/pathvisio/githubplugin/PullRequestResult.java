/*******************************************************************************
 * PathVisio, a tool for data visualization and analysis using biological pathways
 * Copyright 2006-2026 PathVisio
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy
 * of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 ******************************************************************************/
package org.pathvisio.githubplugin;

/**
 * Immutable result of a successful {@link GitHubPullService#createPullRequest}
 * call.
 *
 * <p>
 * Wraps the three fields of GitHub's pull request response that the caller
 * (Swing UI or CLI) actually needs: the PR number for logging/reference,
 * the HTML URL to show the user a clickable link, and the state to confirm
 * the PR was actually opened as expected.
 * </p>
 *
 * @author Snehashree Prusty
 * @version 1.0
 * @see GitHubPullService
 */
public class PullRequestResult {
    /**
     * The pull request number (e.g. 264), as assigned by GitHub.
     */
    private final int number;

    /**
     * The full browser URL to view the pull request
     * (e.g. {@code "https://github.com/wikipathways/sandbox-wp-db/pull/264"}).
     */
    private final String htmlUrl;

    /**
     * The pull request's state as reported by GitHub — expected to be
     * {@code "open"} immediately after creation.
     */
    private final String state;

    /**
     * Creates a new {@code PullRequestResult}.
     *
     * @param number  the pull request number
     * @param htmlUrl the full browser URL for the pull request
     * @param state   the pull request state (e.g. {@code "open"})
     */
    public PullRequestResult(int number, String htmlUrl, String state) {
        this.number = number;
        this.htmlUrl = htmlUrl;
        this.state = state;
    }

    /**
     * Returns the pull request number.
     *
     * @return the PR number as assigned by GitHub
     */
    public int getNumber() {
        return number;
    }

    /**
     * Returns the full browser URL for the pull request.
     *
     * @return the HTML URL to view the pull request
     */
    public String getHtmlUrl() {
        return htmlUrl;
    }

    /**
     * Returns the pull request's current state.
     *
     * @return the state string (e.g. {@code "open"})
     */
    public String getState()
    {
        return state;
    }
}
