# WikiPathways GitHub Plugin — CLI & CLI2

Two standalone command-line tools for testing the GitHub integration backend
without needing PathVisio's desktop GUI. Both are built as self-contained
("shaded") jars — anyone on the team can run them with just a JRE, without
cloning the repository or setting up Maven.

---

## What's the difference between CLI and CLI2?

| | **CLI** (`PathVisioGitHubCli`) | **CLI2** (`PathVisioGitHubCli2`) |
|---|---|---|
| Auth → fork → branch flow | ✅ | ✅ |
| Encode + commit a local `.gpml` file | ❌ | ✅ |
| Open a pull request | ❌ | ✅ |

**CLI** is the original, minimal tool — it authenticates, ensures your fork
exists, and ensures a branch exists, then stops. Good for testing just the
auth/fork/branch backend in isolation.

**CLI2** does everything CLI does, then continues: prompts for a local
`.gpml` file, encodes and commits it, and optionally opens a pull request.
This is the one that exercises the full commit pipeline — the same backend
logic the GUI's Submit New Pathway / Commit to Existing Pathway dialogs use.

For most testing purposes, **CLI2 is the one you want.**

---

## Option A — Just run the jar (no repo needed)

If someone has sent you a pre-built `pathvisio-github-cli2.jar` (or `pathvisio-github-cli.jar`), all you need is a JRE (Java 11+) installed. No Maven, no IDE, no cloned repository.

```bash
java -jar pathvisio-github-cli2.jar [path-to-gpml-file] [branch-name]
```

Both arguments are optional:
- **No file path?** CLI2 will prompt you interactively for one — it accepts
  a full path, and strips surrounding quotes automatically if you drag-and-drop
  a file into a terminal that wraps the path in quotes.
- **No branch name?** A branch name is auto-generated as
  `contribution-<timestamp>`.

Example:
```bash
java -jar pathvisio-github-cli2.jar "/path/to/MyPathway.gpml" my-test-branch
```

### What to expect, step by step

1. **Authentication** — opens your browser to `github.com/login/device` and
   prints a code to enter. If your browser doesn't open automatically, the
   URL and code are printed so you can visit manually.
2. **Fork check** — confirms (or creates) your fork of `wikipathways/sandbox-wp-db`.
3. **Branch check** — confirms (or creates) the branch you specified.
4. **File resolution + encoding** — reads and parses your local `.gpml` file,
   encodes it to Base64.
5. **SHA check** — checks whether a file already exists at the corresponding
   repo path; tells you whether this will be a create or an update.
6. **Commit confirmation** — asks `(y/n)` before actually committing.
7. **Optional pull request** — asks whether to open a PR against
   `wikipathways/sandbox-wp-db`, with optional custom title/description.

CLI (without the "2") stops after step 3.

---

## Option B — Building the jar yourself

If you have the repository cloned and want to (re)build the jars:

```bash
# Build CLI2 (full flow: auth, fork, branch, commit, PR)
mvn package -P cli2

# Build CLI (auth, fork, branch only)
mvn package -P cli
```

Output lands in `target/`:
- `target/pathvisio-github-cli2.jar`
- `target/pathvisio-github-cli.jar`

These are shaded jars — Maven bundles in every dependency (PathVisio desktop,
core, libGPML, BridgeDB, etc.) so the resulting jar is fully self-contained.
This is what makes Option A possible: build once, then hand the single jar
file to anyone on the team.

**Note:** building requires the project's local Maven dependencies
(`org.pathvisio.desktop`, `org.pathvisio.core` at `4.0.0-local`) to already
be installed in your `.m2` repository. If you're building for the first
time, see the main project setup docs for how those are installed from the
`pathvisio4-ant` build output.

---

## Known limitations

- **Token storage is OS-specific.** Both CLIs store your GitHub token via
  Java's `Preferences` API, which uses different backends per OS (Windows
  Registry, macOS Keychain-adjacent plist, Linux `~/.java/.userPrefs`).
  A token saved by one environment (e.g. WSL) is *not* visible to another
  (e.g. native Windows) — each environment will need its own device-code
  login the first time.
- **No logout/token-clear command yet.** If you need to force a fresh login
  (e.g. to test the device-code flow again), the token must currently be
  cleared manually via Java's `Preferences` API rather than through a CLI
  flag.
- Both CLIs target the `wikipathways/sandbox-wp-db` repository specifically
  — this is hardcoded, not configurable via arguments.