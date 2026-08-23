# Direct Integration of PathVisio with WikiPathways GitHub

**Google Summer of Code 2026 — Final Work Submission**

A PathVisio plugin that lets biologists perform Git operations like fork, branch, commit, and open pull requests for GPML pathway files directly from PathVisio's desktop interface without needing to leave the tool.

- **Organization:** NRNB (National Resource for Network Biology)
- **Contributor:** Snehashree Prusty
- **Mentors:** Dr. Martina Summer-Kutmon, Hasan Balci
- **GSoC Project Page:** https://summerofcode.withgoogle.com/myprojects/details/KRgVknHK

## Goals

WikiPathways hosts thousands of curated biological pathway diagrams (GPML files), maintained and improved by a community of biologists using PathVisio as their primary editing tool. Historically, contributing changes back to WikiPathways required stepping outside PathVisio entirely which involved manually cloning repositories, managing branches, and using git or GitHub's web interface which has a workflow that assumes technical familiarity which would be out of scope for biologists and researchers working with PathVisio.

This project's goal was to close that gap: bring the full contribution workflow of forking, branching, committing and opening a pull request for a GPML pathway file directly into PathVisio's existing Swing UI, so a biologist can edit pathways and submit it for reviews in PathVisio's applicatiob itself through the plugin.

Specific goals for the GSoC period:
- Implement GitHub OAuth authentication inside PathVisio (no manual token handling).
- Build the underlying fork/branch/commit/PR service layer against the GitHub API.
- Design and implement a UI (within PathVisio) for submitting new pathways and updating existing ones.
- Handle the real-world edge cases of collaborative editing such as stale forks, branch reuse, SHA conflicts etc so the workflow is safe for concurrent contributors.

## What I Did

### Authentication & Encoding (May–June)
- Implemented GitHub OAuth 2.0 Device Authorization Grant (RFC 8628) for an in-app authentication, avoiding manual token handling by the user.
- Built the GPML encoding layer needed to prepare pathway files for commit.
- Core classes: `GitHubAuthService`, `TokenManager`, `JsonParser`, `GpmlEncoder`.

### Backend Service Layer (July)
- Built the fork/branch/PR service layer against the GitHub API: `GitHubForkSyncService`, `ForkAndBranchWorker`, `BranchReuseWorker`, `ForkCheckWorker`.
- Extended `GitHubPullService` with `findPullRequestForBranch` and `isMerged()` to distinguish a merged PR from a rejected/closed one.
- Extended `GitHubBranchService` with `deleteBranch` and `findBranchByPrefix` to support branch reuse logic.
- Verified the full fork → branch → commit → PR flow end-to-end via CLI2 against the live GitHub API.

### UI Consolidation (August)
- Redesigned the plugin's UI around two flows: submitting a new pathway and submitting an existing one, replacing an earlier design that had separate branch-name fields per dialog.
- Merged duplicate submit paths into single buttons per pathway type, per mentor guidance to simplify the UX for non-technical users.
- Migrated both dialogs (`CommitExistingPathwayDialog`, `SubmitNewPathwayDialog`) to the fork/branch reuse logic (`ForkCheckWorker` + `BranchReuseWorker`), replacing the older `ForkAndBranchWorker` flow in the UI layer.
- Made the target sandbox and production repository configurable via preferences, so redirecting the plugin from the test sandbox to the real WikiPathways repository is a settings change, not a code change.

### Notable Bugs Found & Fixed
- **Null-commit bug:** `PluginController`'s `activePathwayModel`/`activeGpmlFile` setters were never being called anywhere in the codebase, causing silent "Commit failed: null" errors. Fixed by wiring `Engine.addApplicationEventListener` in the plugin's `init()`.
- **Auth/username bug:** `GitHubAuthService` never actually fetched the authenticated GitHub username, so fork operations were silently querying `.../repos/null/sandbox-wp-db`. Fixed across five files with a new `AuthResult` holder and a `fetchAuthenticatedUsername` helper.
- **GPML2013a WPID bug:** discovered during live testing that `wpidField` always showed "(not yet assigned)" for existing pathways. Root cause traced to `GPML2013aReader` never populating `Pathway.getXref()`. Fixed by parsing the WPID out of `Pathway.getVersion()` instead, with a regex guard for malformed values.
- **CI dependency fix:** the plugin's `pom.xml` depended on a PathVisio module jar that only existed in a local `.m2` cache. Fixed the CI workflow to build and install the required module jars from source on each run.

## Current State

The plugin loads correctly as an OSGi bundle inside PathVisio, confirmed via the plugin's own initialization log output after installation through PathVisio's Plugins menu.

The core contribution workflow involving forking, branching, committing a GPML pathway file, and opening a pull request has been verified end-to-end against a sandbox WikiPathways database repository, with a real pull request successfully created through the full flow.

Both submission flows are implemented and tested: submitting a brand-new pathway as well as committing changes to an existing one. They share the same underlying fork-check and branch-handling logic, which covers:

- Detecting and reusing an abandoned branch (one with no associated pull request) rather than creating a duplicate.
- Detecting a branch tied to a merged pull request and starting fresh with a new branch instead of reusing a stale one.
- Blocking a new submission when an existing branch still has an open pull request pending review, so a user cannot submit conflicting changes for the same pathway at the same time.
- Detecting and surfacing stale-SHA conflicts rather than silently overwriting a file that has changed upstream since it was last read.

On submission, both flows automatically open a pull request against the configured target repository, the user is not required to leave PathVisio or interact with GitHub directly at any point in the process.

The target repository read from a PathVisio preference at the time each operation runs such that pointing the plugin at a different repository is a configuration change rather than a code change. This has been validated against the sandbox repository, and a code-level review confirms every service in the fork/branch/commit/PR chain consistently uses this configured value rather than any hardcoded repository name. End-to-end validation against a second, non-sandbox repository under the WikiPathways organization is still pending.

## Sandbox vs. Production

All development and end-to-end testing targeted a sandbox WikiPathways database repository rather than the live production WikiPathways repository. This was a deliberate choice to avoid polluting production data with pathway files, branches, and pull requests. Meanwhile, the plugin's fork, branch-reuse, and commit logic were still being built and iterated on using the sandbox repository. 

This is not a limitation of the finished plugin. As described earlier, the target repository is read from a PathVisio preference at runtime rather than hardcoded, so directing the plugin at the production WikiPathways repository instead of the sandbox is a configuration change, not a code change.

## What's Left to Do

**Pull requests not appearing on pathway portal's dashboard.** Investigation traced this to an architectural boundary rather than a bug, the portal's dashboard reads exclusively from its own internal database, which is only populated when a submission goes through the portal's own web-based submission flow. A pull request opened directly via the GitHub API, which is how this plugin submits, thus it has no path into that database regardless of which repository or account it targets. This is now understood and root-caused, not an open question.

**`ForkAndBranchWorker.java`.** Both submission dialogs have been migrated to the newer fork-check and branch-reuse workers, and this class is now unreferenced anywhere in the plugin or its CLI tools. It has not yet been deleted, since the question of whether it's safe to remove was already raised with the mentors; deletion is planned as a final cleanup step once that's confirmed.

## Challenges and What I Learned

**Split development environment.** Compiling in WSL bash while running PathVisio's Swing GUI in PowerShell (due to WSL's display rendering limitations) meant every test cycle crossed two environments. This made straightforward things like clearing cached state or verifying a fresh build was actually the one running less trivial than in a single-environment setup and required being deliberate about which side of the WSL/Windows boundary a given command or fix actually needed to run on.

**Windows Preferences storage.** Java's `Preferences` API stores its data in the Windows Registry, not the filesystem, when running on Windows. This caused real confusion early on when trying to clear cached GitHub auth tokens — deleting files or running `rm -rf` from the WSL side had no effect, since there was nothing on disk to remove. Clearing state correctly required going through the Registry-backed API directly (via `jshell` from PowerShell) rather than filesystem commands.

**Reverse-engineering GPML parsing behavior.** A bug where the WPID field always showed as unassigned for existing pathways couldn't be explained from documentation or assumptions about how GPML files are structured. Tracing it required reading `GPML2013aReader`'s source directly, which showed it never populates `Pathway.getXref()` for GPML2013a files at all — the WPID has to be recovered from `Pathway.getVersion()` instead. This was a useful reminder that assumptions about a library's behavior, even one this project depends on directly, need to be checked against its actual source rather than inferred from how it's used elsewhere.

## Links

- **Plugin repository:** https://github.com/PathVisio/gsoc2026-pathvisio-wp-github
- **Sandbox test repository:** https://github.com/wikipathways/sandbox-wp-db
- **Related repositories:**
  - Pathway portal web application: https://github.com/marvinm2/pathway-portal
  - PathVisio core (Ant-based, used for CI dependency build): https://github.com/PathVisio/pathvisio4-ant
- **GSoC project page:** https://summerofcode.withgoogle.com/myprojects/details/KRgVknHK

# How to Build and Extend

## Prerequisites

* JDK 11
* Apache Maven
* Apache Ant (only needed for the one-time PathVisio core dependency build described below)

## Dependency setup: PathVisio core jars

This plugin depends on three PathVisio core modules — `org.pathvisio.core`, `org.pathvisio.gui`, and `org.pathvisio.desktop` — at version `4.0.0-local`. These are not published to Maven Central; they must be built from source and installed into your local Maven repository before the plugin will build.

1. Clone the Ant-based PathVisio core repository:

   ```bash
   git clone https://github.com/PathVisio/pathvisio4-ant.git
   ```

2. From inside that checkout, build the module jars:

   ```bash
   ant desktop.jar
   ```

3. Install each built jar into your local Maven repository under the coordinates this plugin's `pom.xml` expects:

   ```bash
   mvn install:install-file -Dfile=pathvisio4-ant/modules/org.pathvisio.core.jar \
       -DgroupId=org.pathvisio -DartifactId=org.pathvisio.core \
       -Dversion=4.0.0-local -Dpackaging=jar

   mvn install:install-file -Dfile=pathvisio4-ant/modules/org.pathvisio.gui.jar \
       -DgroupId=org.pathvisio -DartifactId=org.pathvisio.gui \
       -Dversion=4.0.0-local -Dpackaging=jar

   mvn install:install-file -Dfile=pathvisio4-ant/modules/org.pathvisio.desktop.jar \
       -DgroupId=org.pathvisio -DartifactId=org.pathvisio.desktop \
       -Dversion=4.0.0-local -Dpackaging=jar
   ```

The project's CI workflow performs these same three steps automatically on every push and pull request, so a fresh CI runner (which starts with an empty local Maven repository) can still build the plugin without anyone's pre-populated cache.

## Building the plugin

From the plugin's own repository root:

```bash
mvn -B package --file pom.xml
```

To run the test suite:

```bash
mvn -B test --file pom.xml
```

The build produces an OSGi bundle jar (`githubplugin.jar`) via the `maven-bundle-plugin`, with the manifest headers required for PathVisio to load it as a plugin.

## CLI variants

Two standalone command-line entry points are also available, built via Maven profiles rather than the default build:

```bash
mvn package -Pcli    # builds pathvisio-github-cli.jar
mvn package -Pcli2   # builds pathvisio-github-cli2.jar
```

Each produces a shaded (fat) jar with its own `main` class, useful for testing the fork/branch/commit/PR flow independently of PathVisio's UI.

## Installing the built plugin into PathVisio

Once built, the plugin jar can be installed through PathVisio's own Plugins menu inside the application, rather than by manually placing it in a directory.

## Extending the plugin

The fork, branch, commit, and pull-request logic lives in `org.pathvisio.githubplugin.service`; the two submission dialogs live under the UI layer and drive that service layer through `ForkCheckWorker` and `BranchReuseWorker` (both `SwingWorker` subclasses, keeping GitHub API calls off the Swing Event Dispatch Thread). The upstream repository of both owner and repo name is read at runtime from a single configurable URL (e.g. `https://github.com/owner/repo`) via `PluginController.getUpstreamOwner()` / `getUpstreamRepo()`, backed by a PathVisio preference (Preferences → WikiPathways GitHub Plugin → "Online repository URL"). This lets the plugin target any upstream repository without code changes.

## Known Limitations
 
**Fork name collisions across different upstream owners.** The plugin identifies a user's fork purely by repository name  `github.com/{yourUsername}/{upstreamRepo}` and does not currently support forking two different upstream repositories that happen to share the exact same repo name under different owners (e.g. `ownerA/sandbox-wp-db` and `ownerB/sandbox-wp-db`). If you already have a fork of one such repository and then point the plugin's preference at a different upstream repo with the same name, the plugin will detect the mismatch at the fork-check step and report a clear error rather than silently using the wrong fork but it cannot resolve the conflict automatically.
 
To switch between upstream repositories with colliding names, resolve the conflict locally first:
1. Go to `github.com/{yourUsername}/{upstreamRepo}` and confirm it's the fork you no longer need (check the "forked from" text under the repo name).
2. Either delete that repository (Settings → Danger Zone → Delete this repository), or rename it (Settings → Repository name) to free up the name.
3. Point the plugin's Preferences URL at the new upstream and reopen the submission dialog — it will create a fresh fork under the now-available name.
This does not affect the common case of switching between upstream repositories with distinct names, which works without any manual cleanup.
 