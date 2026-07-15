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

import org.pathvisio.githubplugin.util.GpmlEncoder;
import org.pathvisio.githubplugin.util.HttpUtil;
import org.pathvisio.githubplugin.util.JsonParser;
import org.pathvisio.libgpml.model.PathwayModel;

import java.io.File;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.util.Scanner;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Standalone CLI that extends the auth/fork/branch flow of
 * {@link PathVisioGitHubCli} with GPML encoding, committing, and pull request
 * creation. Kept as a separate entry point rather than modifying
 * {@code PathVisioGitHubCli.java}, which is already demo-tested and left
 * untouched.
 *
 * <p>
 * Flow: authenticate → fetch username → ensure fork → ensure branch →
 * prompt for local .gpml file → encode + commit → prompt to open a pull
 * request → (optionally) open it.
 * </p>
 *
 * <p>
 * Usage: {@code java -cp pathvisio-github-cli.jar
 * org.pathvisio.githubplugin.PathVisioGitHubCli2 [path-to-gpml-file]
 * [branch-name]}
 * </p>
 *
 * <p>
 * Both arguments are optional. If the file path is omitted, the user is
 * prompted interactively. This is deliberate: the plugin's real users are
 * biologists who just finished editing a pathway in the PathVisio desktop
 * app and have a local {@code .gpml} file saved somewhere — they are not
 * expected to know the file's path ahead of time as a command-line argument.
 * </p>
 *
 * @author Snehashree Prusty
 * @version 1.1
 */
public class PathVisioGitHubCli2
{
    private static final String UPSTREAM_OWNER = "wikipathways";
    private static final String UPSTREAM_REPO = "sandbox-wp-db";

    // Confirmed via `git remote show origin` — HEAD branch is "main".
    private static final String BASE_BRANCH = "main";

    public static void main(String[] args) throws Exception
    {
        Scanner scanner = new Scanner(System.in);

        String branchName = args.length > 1 ? args[1] : "contribution-" + System.currentTimeMillis();

        System.out.println("[1/6] Checking GitHub authentication...");
        String token = authenticate();
        System.out.println("[1/6] Authenticated.");

        try
        {
            System.out.println("[2/6] Looking up authenticated username...");
            String username = fetchAuthenticatedUsername(token);
            System.out.println("    -> Logged in as: " + username);

            System.out.println("[3/6] Ensuring fork of " + UPSTREAM_OWNER + "/" + UPSTREAM_REPO + " exists...");
            GitHubForkService forkService = new GitHubForkService(token, username, UPSTREAM_REPO);

            if (forkService.forkExists())
            {
                System.out.println("    -> Fork already exists, skipping creation.");
            }
            else
            {
                System.out.println("    -> No fork found, creating...");
                forkService.createFork();
                System.out.println("    -> Waiting for GitHub to complete fork (async)...");
                boolean ready = forkService.waitForFork(60_000, 3_000);
                if (!ready)
                {
                    System.err.println("Fork did not become ready in time. Try again shortly.");
                    System.exit(1);
                    return;
                }
                System.out.println("    -> Fork created successfully.");
            }
            System.out.println("    -> Fork ready: " + username + "/" + UPSTREAM_REPO);

            System.out.println("[4/6] Checking branch '" + branchName + "'...");
            GitHubBranchService branchService = new GitHubBranchService(token, username, UPSTREAM_REPO);
            String confirmedBranch = branchService.ensureBranchExists(UPSTREAM_OWNER, branchName);
            System.out.println("    -> Branch ready: " + confirmedBranch);

            // -----------------------------------------------------------
            // Prompt for the local .gpml file rather than requiring it as
            // a command-line argument. Real users have just saved a file
            // from the PathVisio desktop app and know its location, not
            // an argument they'd type on a command line.
            // -----------------------------------------------------------
            File gpmlFile = resolveGpmlFile(args, scanner);

            String fileName = gpmlFile.getName();
            String baseName = fileName.endsWith(".gpml")
                    ? fileName.substring(0, fileName.length() - ".gpml".length())
                    : fileName;
            String repoPath = "pathways/" + baseName + "/" + fileName;

            System.out.println("[5/6] Encoding and committing GPML file...");
            System.out.println("    -> Reading: " + gpmlFile.getAbsolutePath());

            PathwayModel pathwayModel = new PathwayModel();
            pathwayModel.readFromXml(gpmlFile, true);
            System.out.println("    -> Parsed pathway model, encoding to Base64...");

            String base64Content = GpmlEncoder.encodeToBase64(pathwayModel, username);

            String contentsApiUrl = String.format(
                    "https://api.github.com/repos/%s/%s/contents/%s",
                    username, UPSTREAM_REPO, repoPath);

            System.out.println("    -> Checking for existing file at: " + repoPath);
            String existingSha = GpmlEncoder.getExistingGpmlSHA(contentsApiUrl, token);

            if (existingSha == null)
            {
                System.out.println("    -> No existing file found — this will create a new file.");
            }
            else
            {
                System.out.println("    -> Existing file found (SHA: " + existingSha + ") — this will update it.");
            }

            System.out.print("    -> Proceed with commit? (y/n): ");
            String confirmCommit = scanner.nextLine().trim().toLowerCase();
            if (!confirmCommit.startsWith("y"))
            {
                System.out.println("Commit cancelled by user. Exiting.");
                System.exit(0);
                return;
            }

            GitHubCommitService commitService = new GitHubCommitService(username, UPSTREAM_REPO, token);
            String commitMessage = "Add/update " + gpmlFile.getName()
                    + " via PathVisio GitHub plugin";
            String newSha = commitService.commitFile(
                    repoPath, confirmedBranch, base64Content, existingSha, commitMessage);
            System.out.println("    -> Commit successful. New content SHA: " + newSha);

            // -----------------------------------------------------------
            // Prompt before opening a pull request — this is a distinct,
            // separate decision from committing. A user may want to push
            // several commits to the same branch before opening one PR.
            // -----------------------------------------------------------
            System.out.println("[6/6] Commit complete.");
            System.out.print("    -> Open a pull request against "
                    + UPSTREAM_OWNER + "/" + UPSTREAM_REPO + " now? (y/n): ");
            String confirmPr = scanner.nextLine().trim().toLowerCase();

            if (!confirmPr.startsWith("y"))
            {
                System.out.println("Skipping pull request. Your commit is on branch '"
                        + confirmedBranch + "' in " + username + "/" + UPSTREAM_REPO + ".");
                System.out.println("\nDone.");
                System.exit(0);
                return;
            }

            System.out.print("    -> Pull request title (leave blank for default): ");
            String titleInput = scanner.nextLine().trim();
            String prTitle = titleInput.isEmpty()
                    ? "Contribution: " + gpmlFile.getName()
                    : titleInput;

            System.out.print("    -> Pull request description (leave blank for default): ");
            String bodyInput = scanner.nextLine().trim();
            String prBody = bodyInput.isEmpty()
                    ? "This pull request was created via the PathVisio GitHub plugin."
                    : bodyInput;

            GitHubPullService pullService = new GitHubPullService(
                    UPSTREAM_OWNER, UPSTREAM_REPO, username, token);

            PullRequestResult result = pullService.createPullRequest(
                    prTitle, confirmedBranch, BASE_BRANCH, prBody);

            System.out.println("    -> Pull request #" + result.getNumber()
                    + " opened: " + result.getHtmlUrl());
            System.out.println("    -> State: " + result.getState());

            System.out.println("\nDone. Commit + pull request flow completed successfully.");
        }
        catch (IOException e)
        {
            System.err.println("GitHub API error: " + e.getMessage());
            System.exit(1);
            return;
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            System.err.println("Interrupted while waiting on GitHub.");
            System.exit(1);
            return;
        }
        catch (Exception e)
        {
            // Catches GpmlEncoder's checked Exception (e.g. ConverterException
            // from readFromXml or encodeToBase64) that doesn't fit the two
            // more specific catches above.
            System.err.println("Unexpected error: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
            return;
        }
        System.exit(0);
    }

    /**
     * Resolves the local .gpml file to commit, either from the first
     * command-line argument (for scripted/repeat use) or by prompting the
     * user interactively. Keeps prompting on invalid input rather than
     * failing immediately, since a mistyped path is easy to correct.
     *
     * @param args    the CLI arguments passed to main()
     * @param scanner shared Scanner over System.in
     * @return a validated, existing .gpml file
     */
    private static File resolveGpmlFile(String[] args, Scanner scanner)
    {
        File gpmlFile;

        if (args.length > 0)
        {
            gpmlFile = new File(args[0]);
            if (gpmlFile.exists() && gpmlFile.isFile())
            {
                return gpmlFile;
            }
            System.out.println("    -> File not found at provided path: "
                    + gpmlFile.getAbsolutePath());
            System.out.println("    -> Falling back to interactive prompt.");
        }

        while (true)
        {
            System.out.print("Enter the full path to your .gpml file: ");
            String inputPath = scanner.nextLine().trim();
            // Strip surrounding quotes in case the path was drag-and-dropped
            // into the terminal, which some shells wrap in quotes.
            if (inputPath.length() >= 2 && inputPath.startsWith("\"") && inputPath.endsWith("\""))
            {
                inputPath = inputPath.substring(1, inputPath.length() - 1);
            }

            gpmlFile = new File(inputPath);
            if (gpmlFile.exists() && gpmlFile.isFile())
            {
                return gpmlFile;
            }
            System.out.println("    -> No file found at: " + gpmlFile.getAbsolutePath()
                    + ". Please try again.");
        }
    }

    // =========================================================================
    // Duplicated from PathVisioGitHubCli.java, unchanged.
    // PathVisioGitHubCli.java is intentionally left untouched since it's
    // already demo-tested; these two helpers are copied rather than shared.
    // =========================================================================

    private static String authenticate()
    {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> tokenRef = new AtomicReference<>();
        AtomicReference<String> errorRef = new AtomicReference<>();
        GitHubAuthService authService = new GitHubAuthService();
        authService.startAuthentication(new GitHubAuthService.AuthCallback()
        {
            @Override
            public void onUserCodeReceived(String userCode, int expiresIn)
            {
                System.out.println("    -> Visit: https://github.com/login/device");
                System.out.println("    -> Enter code: " + userCode + "  (expires in " + expiresIn + "s)");
                System.out.println("    -> Opening your browser...");
            }
            @Override
            public void onStatusUpdate(String message)
            {
                System.out.println("    -> " + message);
            }
            @Override
            public void onSuccess(String accessToken)
            {
                tokenRef.set(accessToken);
                latch.countDown();
            }
            @Override
            public void onFailure(String errorMessage)
            {
                errorRef.set(errorMessage);
                latch.countDown();
            }
        });

        try
        {
            latch.await();
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            System.err.println("Interrupted while waiting for authentication.");
            System.exit(1);
        }
        if (tokenRef.get() == null)
        {
            System.err.println("Authentication failed: " + errorRef.get());
            System.exit(1);
        }
        return tokenRef.get();
    }

    private static String fetchAuthenticatedUsername(String token) throws IOException
    {
        String endpoint = "https://api.github.com/user";
        HttpURLConnection connection = HttpUtil.openAuthenticatedConnection(endpoint, "GET", token);
        int status = connection.getResponseCode();
        if (status != 200)
        {
            connection.disconnect();
            throw new IOException("Failed to fetch authenticated user. Status: " + status);
        }
        String body = HttpUtil.readResponseBody(connection);
        connection.disconnect();
        return JsonParser.extractValue(body, "login");
    }
}