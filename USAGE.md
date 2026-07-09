# PathVisio GitHub CLI — Usage Guide

Two standalone command-line tools that authenticate with GitHub via OAuth 2.0
Device Authorization Grant, then operate against a fork of the WikiPathways
sandbox repository:

- **`PathVisioGitHubCli`** (v1) — auth → fork → branch. Stops there. Kept
  as-is as an already-demo-tested baseline; not modified further.
- **`PathVisioGitHubCli2`** (v2) — the full flow: auth → fork → branch →
  local `.gpml` file → encode + commit → optionally open a pull request.
  This is the tool that reflects the complete backend, and the one meant
  for end-to-end testing.

Both wrap the same service layer (`GitHubAuthService`, `GitHubForkService`,
`GitHubBranchService`, and — v2 only — `GitHubCommitService`,
`GitHubPullService`) that also backs the eventual PathVisio GUI plugin. The
backend logic is identical either way — only the trigger differs (terminal
command here, Swing UI in the full plugin).

---

## Prerequisites

- **Java 11** or higher (`java -version` to check)
- **Maven 3.6+** (`mvn -version` to check)
- A **GitHub account**

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

> The OAuth App's public `client_id` is already hardcoded in
> `GitHubAuthService.java` — nothing to configure here. (It's a public
> client ID, not a secret, so committing it is fine.)

---

## Getting the code

The project currently lives at:

```
https://github.com/PathVisio/gsoc2026-pathvisio-wp-github
```

**This repository is private** and you'll need to be added as a collaborator (or have org
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
> work as-is for anyone — no collaborator access required.

After cloning:

```bash
cd gsoc2026-pathvisio-wp-github
```

All commands in the rest of this guide are run from that project root.

---

## Building

There are **three** distinct build outcomes:

| Command | Produces | Manifest `Main-Class` | Status |
|---|---|---|---|
| `mvn package` (no profile) | `target/githubplugin.jar` | `org.pathvisio.githubplugin.GitHubPlugin` | **This class does not exist yet** (the GUI plugin is still at blueprint stage). The jar builds fine, but `java -jar target/githubplugin.jar` will fail with `NoClassDefFoundError`/`ClassNotFoundException` until that class is implemented. |
| `mvn package -P cli` | `target/pathvisio-github-cli.jar` | `org.pathvisio.githubplugin.PathVisioGitHubCli` (v1 only) | Works today. Self-contained fat jar via `maven-shade-plugin` — bundles all runtime dependencies. |
| `mvn package -P cli2` | `target/pathvisio-github-cli2.jar` | `org.pathvisio.githubplugin.PathVisioGitHubCli2` (v2 only) | **Works today.** Mirrors the `cli` profile exactly — same shade config, self-contained fat jar including `libgpml` and all other runtime dependencies. |

**`mvn clean compile` still compiles both CLI classes** regardless of profile
(Maven compiles every `.java` file whether or not a profile is active). The `cli2` profile now produces a proper runnable jar the same way `cli` does for v1.

---

## Running the CLIs

### `PathVisioGitHubCli` (v1 — auth, fork, branch only)

```bash
mvn package -P cli
java -jar target/pathvisio-github-cli.jar [branch-name]
```

- `[branch-name]` is `args[0]`, **optional**. If omitted, a name like
  `contribution-<timestamp>` is auto-generated.

```
[1/4] Checking GitHub authentication...
[2/4] Looking up authenticated username...
[3/4] Ensuring if fork of wikipathways/sandbox-wp-db exists...
[4/4] Checking branch '<branch-name>'...
```

### `PathVisioGitHubCli2` (v2 — full commit + PR flow)

```bash
mvn package -P cli2
java -jar target/pathvisio-github-cli2.jar [path-to-gpml-file] [branch-name]
```

- `[path-to-gpml-file]` is `args[0]`, **optional** — if omitted, you're
  prompted interactively for the file path.
- `[branch-name]` is `args[1]`, **optional** — if omitted, a name like
  `contribution-<timestamp>` is auto-generated, same as v1.

```
[1/6] Checking GitHub authentication...
[2/6] Looking up authenticated username...
[3/6] Ensuring fork of wikipathways/sandbox-wp-db exists...
[4/6] Checking branch '<branch-name>'...
[5/6] Encoding and committing GPML file...
[6/6] Commit complete. (optional PR creation follows)
```

> **⚠️ Argument order is NOT the same between the two tools.** v1 takes the
> branch name as its *first* (and only) argument. v2 takes a *file path*
> first and the branch name *second*. If you're used to v1 and run
> `PathVisioGitHubCli2 my-branch-name` expecting it to set the branch, it
> will instead try to treat `"my-branch-name"` as a file path, fail the
> file-existence check, and silently fall back to the interactive file
> prompt instead of a clear error. Double-check which tool you're invoking
> before typing an argument.

### What happens, step by step (both tools)

**Step 1 — Authentication.** If no valid token is cached, the tool requests
a device code from GitHub, prints a one-time code, and attempts to open your
default browser to `https://github.com/login/device`. If the browser cannot
be opened automatically (for example, in a WSL environment with no display),
the code and URL are printed instead so you can navigate there manually. The
tool then polls GitHub every few seconds until you approve the request in
the browser.

**Step 2 — Username lookup.** Once authenticated, the tool calls GitHub's
`/user` endpoint to determine the logged-in username.

**Step 3 — Fork.** The tool checks whether you already have a fork of
`sandbox-wp-db`. If not, it creates one and polls until GitHub finishes the
fork (forking is asynchronous on GitHub's side, so this step may pause for a
few seconds). If a fork already exists, this step is skipped entirely.

**Step 4 — Branch.** The tool checks whether the requested branch already
exists on your fork. If not, it reads the default branch's latest commit SHA
and creates the new branch from it. **If the branch already exists, it is
reused as-is** — see the idempotency note below for why this matters more
in v2 than v1.

**Step 5 (v2 only) — Encode and commit.** Reads the local `.gpml` file into
a `PathwayModel`, encodes it to Base64, checks whether a file already exists
at that path in the repo (to decide create vs. update), and commits after a
`y/n` confirmation.

**Step 6 (v2 only) — Optional pull request.** After a separate `y/n`
confirmation, prompts for a PR title/description and opens the PR against
`wikipathways/sandbox-wp-db`.

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
project. Both CLIs share the same cached token — authenticating once via
either tool means the other won't re-prompt.

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
fork, branch, and (for v2) commit/PR exist directly via GitHub's API or
website:

```bash
# Confirm fork exists
curl -s https://api.github.com/repos/<your-username>/sandbox-wp-db \
  | grep '"full_name"'

# Confirm branch exists
curl -s https://api.github.com/repos/<your-username>/sandbox-wp-db/branches \
  | grep '"name"'

# (v2) Confirm a PR was opened
curl -s https://api.github.com/repos/wikipathways/sandbox-wp-db/pulls \
  | grep '"html_url"'
```

Or visit `github.com/<your-username>/sandbox-wp-db` directly in a browser.

---

## Idempotency

Running either tool multiple times is safe in the sense that it won't create
duplicate forks or re-trigger the device flow. A second run with the same
authenticated account will:

- Skip the device flow entirely (cached token is reused)
- Skip fork creation entirely (fork already exists)
- Only create a new branch if the given branch name doesn't already exist

**Caveat for v2 specifically:** if you deliberately pass the *same* branch
name across two separate runs (e.g. testing two different `.gpml` files),
each run's commit lands on that same branch — so a PR opened afterward will
show every commit made across all those runs, not just the most recent one.
This isn't a bug, but it can produce PRs with more files changed than
intended if you're not tracking which branch names you've already used in a
session. Leaving the branch-name argument blank (the default) avoids this
entirely, since each run then gets its own unique timestamp-based branch.

---

## Scope: v1 vs v2

- **v1 (`PathVisioGitHubCli`)** stops after ensuring a fork and branch
  exist — it does not commit or open a PR. Kept deliberately unmodified as
  an already-demo-tested baseline.
- **v2 (`PathVisioGitHubCli2`)** is the full flow, including commit and PR
  creation via `GitHubCommitService` and `GitHubPullService`.
- The target repository (`wikipathways/sandbox-wp-db`) is fixed in source
  for both tools — not configurable via arguments, since they're
  purpose-built for WikiPathways sandbox contribution rather than generic
  forking. Switching to the production `wikipathways/wikipathways-database`
  repo for real users would be a constant change in each CLI file.

---

## Project structure reference

```
src/main/java/org/pathvisio/githubplugin/
    GitHubAuthService.java       — OAuth 2.0 Device Authorization Grant
    GitHubForkService.java       — fork check / create / wait-for-ready
    GitHubBranchService.java     — branch check / create
    GitHubCommitService.java     — commit GPML content to a branch (v2)
    GitHubPullService.java       — open a pull request (v2)
    PullRequestResult.java       — DTO for PR number / URL / state (v2)
    PathVisioGitHubCli.java      — CLI entry point (v1: auth/fork/branch)
    PathVisioGitHubCli2.java     — CLI entry point (v2: full commit+PR flow)
    util/
        TokenManager.java        — Preferences-backed token cache
        HttpUtil.java            — shared authenticated connection helper
        JsonParser.java          — lightweight JSON field extraction
        GpmlEncoder.java         — GPML → Base64 encoding + SHA lookup for commits
```