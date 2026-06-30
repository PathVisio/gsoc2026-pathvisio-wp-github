# PathVisio GitHub CLI — Usage Guide

A standalone command-line tool that authenticates with GitHub via OAuth 2.0
Device Authorization Grant, then ensures a fork and a working branch exist on
the WikiPathways sandbox repository — ready for GPML pathway commits.

This CLI wraps the same service layer (`GitHubAuthService`, `GitHubForkService`,
`GitHubBranchService`) that will eventually run inside the PathVisio plugin's
`GitHubContributeWorker`. The backend logic is identical either way — only the
trigger differs (terminal command here, Swing UI button in the full plugin).

---

## Prerequisites

- **Java 11** or higher (`java -version` to check)
- **Maven 3.6+** (`mvn -version` to check)
- A **GitHub account**
- The project's **OAuth App client_id** must already be set in
  `GitHubAuthService.java`:

  ```java
  private static final String CLIENT_ID = "your-client-id-here";
  ```

  This is a public client ID (not a secret) — safe to commit, per the
  proposal's section 4.1 device-flow design.

- **Device Authorization Flow must be enabled** on the GitHub OAuth App.
  This is a checkbox that is **off by default**:

  ```
  github.com/settings/developers
  → OAuth Apps
  → [the app]
  → "Enable Device Flow" checkbox
  → Save changes
  ```

  Without this enabled, the tool fails immediately with `HTTP 400` on the
  very first request.

---

## Getting the code

The project currently lives at:

```
https://github.com/PathVisio/gsoc2026-pathvisio-wp-github
```

**This repository is private.** If you're reading this and aren't one
of the two of us, you'll need to be added as a collaborator (or have org
access under `PathVisio`) before cloning will work. Once access is
confirmed:

```bash
# HTTPS (will prompt for a GitHub login or personal access token)
git clone https://github.com/PathVisio/gsoc2026-pathvisio-wp-github.git

# SSH (requires your SSH key added to your GitHub account)
git clone git@github.com:PathVisio/gsoc2026-pathvisio-wp-github.git
```

> **Note for later:** once this repo's visibility changes to public (e.g.
> after the GSoC work merges upstream), the same two clone commands above
> work as-is for anyone — no collaborator access required. Nothing in this
> guide will need to change when that happens.

After cloning:

```bash
cd gsoc2026-pathvisio-wp-github
```

All commands in the rest of this guide are run from that project root.

---

## Building the CLI jar

The project produces two different jars depending on the Maven profile used:

| Command | Produces | Main class | Purpose |
|---|---|---|---|
| `mvn package` | `target/githubplugin.jar` | `GitHubPlugin` | Future PathVisio plugin jar |
| `mvn package -P cli` | `target/pathvisio-github-cli.jar` | `PathVisioGitHubCli` | Standalone CLI tool |

To build the CLI specifically:

```bash
mvn package -P cli -DskipTests
```

`-DskipTests` is used here because the CLI build doesn't depend on the test
suite passing — the tool is verified by running it live against the real
GitHub API instead. Feel free to drop this flag and run `mvn package -P cli`
once the full test suite is green.

The CLI profile activates the `maven-shade-plugin`, which bundles every
dependency (`HttpUtil`, `JsonParser`, `TokenManager`, and all three service
classes) into one self-contained "fat" jar, and rewrites the manifest so
`java -jar` knows to run `PathVisioGitHubCli` instead of the plugin's own
entry point.

After building, confirm the jar exists and has the right entry point:

```bash
ls -lh target/pathvisio-github-cli.jar

unzip -p target/pathvisio-github-cli.jar META-INF/MANIFEST.MF
# Should show: Main-Class: org.pathvisio.githubplugin.PathVisioGitHubCli
```

---

## Running the CLI

```bash
java -jar target/pathvisio-github-cli.jar <branch-name>
```

- `<branch-name>` is optional. If omitted, a name like
  `contribution-<timestamp>` is auto-generated.
- The target repository (`wikipathways/sandbox-wp-db`) is fixed inside the
  CLI — not configurable via arguments, since the tool is purpose-built for
  WikiPathways contribution rather than generic forking.

Example:

```bash
java -jar target/pathvisio-github-cli.jar patch-WP5046
```

### What happens, step by step

```
[1/4] Checking GitHub authentication...
[2/4] Looking up authenticated username...
[3/4] Checking fork of wikipathways/sandbox-wp-db...
[4/4] Checking branch '<branch-name>'...
```

**Step 1 — Authentication.** If no valid token is cached, the tool requests
a device code from GitHub, prints a one-time code, and attempts to open your
default browser to `https://github.com/login/device`. If the browser cannot
be opened automatically (for example, in a WSL environment with no display),
the code and URL are printed instead so you can navigate there manually. The
tool then polls GitHub every few seconds until you approve the request in
the browser.

**Step 2 — Username lookup.** Once authenticated, the tool calls GitHub's
`/user` endpoint to determine the logged-in username, which the fork and
branch services need.

**Step 3 — Fork.** The tool checks whether you already have a fork of
`sandbox-wp-db`. If not, it creates one and polls until GitHub finishes the
fork (forking is asynchronous on GitHub's side, so this step may pause for a
few seconds). If a fork already exists, this step is skipped entirely.

**Step 4 — Branch.** The tool checks whether the requested branch already
exists on your fork. If not, it reads the default branch's latest commit SHA
and creates the new branch from it.

### Token caching

On successful authentication, your access token is cached using Java's
`java.util.prefs.Preferences` API — not written to any file inside the
project directory, and never visible to `git status`. Storage location is
OS-dependent:

- **Windows:** `HKEY_CURRENT_USER\Software\JavaSoft\Prefs`
- **macOS:** `~/Library/Preferences/`
- **Linux/WSL:** `~/.java/.userPrefs/`


This design is specified in proposal section 6.3, which calls for
`Preferences`-based caching rather than a plaintext file inside the
project.

On subsequent runs, the cached token is reused automatically — you will not
be asked to authenticate again unless the token is revoked or expires.

### Forcing re-authentication

To manually clear the cached token and force the full device flow again:

```bash
# Linux / WSL
rm -rf ~/.java/.userPrefs/org/pathvisio/githubplugin
```

Alternatively, revoking the app's access from
`github.com/settings/applications` will cause the next run to detect a
`401 Unauthorized` from `/user`, automatically clear the stale token, and
re-prompt — this is the same recovery path the full plugin will use.

---

## Verifying the result independently

The CLI's own console output is not the only proof — you can confirm the
fork and branch exist directly via GitHub's API or website:

```bash
# Confirm fork exists
curl -s https://api.github.com/repos/<your-username>/sandbox-wp-db \
  | grep '"full_name"'

# Confirm branch exists
curl -s https://api.github.com/repos/<your-username>/sandbox-wp-db/branches \
  | grep '"name"'
```

Or visit `github.com/<your-username>/sandbox-wp-db` directly in a browser.

---

## Idempotency

Running the tool multiple times is safe. A second run with the same
authenticated account will:

- Skip the device flow entirely (cached token is reused)
- Skip fork creation entirely (fork already exists)
- Only create a new branch if the given branch name doesn't already exist

This means the tool can be re-run freely without creating duplicate forks or
side effects — important for a tool intended to be handed to end users.

---

## Known limitations (current state)

- Only the branch name is configurable; the target repository is fixed in
  source. Switching from the sandbox repo to the production
  `wikipathways/wikipathways-database` repo for real users is a single
  constant change in `PathVisioGitHubCli.java`.
- Commit and pull-request functionality (`GitHubCommitService`,
  `GitHubPullRequestService`) are not yet implemented — this CLI currently
  prepares a fork and branch only, stopping short of staging and submitting
  a GPML file.

---

## Project structure reference

```
src/main/java/org/pathvisio/githubplugin/
    GitHubAuthService.java      — OAuth 2.0 Device Authorization Grant
    GitHubForkService.java      — fork check / create / wait-for-ready
    GitHubBranchService.java    — branch check / create
    PathVisioGitHubCli.java     — CLI entry point (this tool)
    util/
        TokenManager.java       — Preferences-backed token cache
        HttpUtil.java           — shared authenticated connection helper
        JsonParser.java         — lightweight JSON field extraction
        GpmlEncoder.java        — GPML → Base64 encoding for commits
```