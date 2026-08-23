# PathVisio GitHub Plugin — Developer Usage Guide

## Folder layout (confirmed, no changes needed)

Two **separate, sibling folders** under `Desktop` — never nested inside each other:

```
Desktop/
├── pathvisio4-ant/              <- PathVisio itself (Ant-built)
└── PATHVISIO GITHUB PLUGIN/     <- Your plugin (Maven-built)
```

The plugin project links to PathVisio only through Maven coordinates
(`org.pathvisio.desktop:4.0.0-local`, `org.pathvisio.core:4.0.0-local`) already
installed in your local `.m2` cache. There is no need to clone, copy, or nest
either project inside the other.

---

## Step 1 — Build PathVisio itself (only when PathVisio source changes)

**Where:** WSL bash terminal, inside `pathvisio4-ant`

```bash
cd /mnt/c/Users/sneha/OneDrive/Desktop/pathvisio4-ant
ant
```

This is a **full build**, not a launch. It compiles every module
(`core`, `launcher`, `gui`, `desktop`, `pluginmanager`, etc.) and packages a
single self-contained `pathvisio.jar` with all bundles embedded inside it.
Takes a few minutes, ends with `BUILD SUCCESSFUL`.

**You only need to re-run this if you change PathVisio's own source** — not
for changes to your plugin. Skip this step entirely on a normal plugin-dev
loop.

---

## Step 2 — Launch PathVisio's Swing UI

**Where:** PowerShell, same folder (via the Windows path)

```powershell
cd "C:\Users\sneha\OneDrive\Desktop\pathvisio4-ant"
java -jar pathvisio.jar
```

Console output will show PathVisio's embedded OSGi bootstrapper
(`PathVisioMain`, backed by Felix) unpacking and starting each bundle one by
one, ending with:

```
Saved org.pathvisio.desktop for last
____________________________
Welcome to Apache Felix Gogo

g! Bundle org.pathvisio.desktop started
```

The Swing window then opens, with **Plugins** visible in the menu bar. This
console stays open and attached — **keep this PowerShell window visible**,
since it's where any `System.out.println` log lines from your plugin will
print once it loads.

> Note: a `derbytools` bundle currently fails to start with a
> `NullPointerException` on a null `symbolicName`. This is a pre-existing
> issue in PathVisio's own bundled jar, unrelated to the GitHub plugin, and
> doesn't stop the rest of the app (including `org.pathvisio.desktop`) from
> starting normally. Safe to ignore.

---

## Step 3 — Build your plugin

**Where:** a *different* terminal (WSL bash is fine, since this is just Maven),
inside the plugin project

```bash
cd "your-file-location"
mvn clean package
```

Produces `target/githubplugin.jar` with the OSGi manifest (`Bundle-Activator`,
`Bundle-SymbolicName`, etc.) baked in via `maven-bundle-plugin`.

**Run this every time you change plugin code.** No need to touch
`pathvisio4-ant` or re-run `ant` for plugin-only changes — this is the normal
edit/rebuild loop.

---

## Step 4 — Load your plugin into the running PathVisio instance

With PathVisio's Swing UI still open from Step 2:

1. Go to **Plugins** in the menu bar.
2. Find the **Install local plugins** action (exact submenu wording/path not
   yet confirmed on your machine — first time trying this, so note down
   exactly what you see under **Plugins** if it isn't a direct menu item).
3. Point the file browser at your file's location. Make sure it is pointing to where the target is getting built. 
4. Watch the **PowerShell console from Step 2** for your temporary log line:
   ```
   WikiPathwaysGitHubPlugin.init() called - plugin loaded successfully
   ```

### What each outcome means

| What you see | What it tells us |
|---|---|
| Log line prints | Full chain worked: manifest → `PluginManager` → `Activator.start()` → service registration → `initPlugins()` discovery → `init()` called. This is the real milestone. |
| No log line, no error | `initPlugins()` likely ran but didn't find the service — points back to something in `Activator.start()` or the manifest not being picked up as expected. Paste the console output so we can check. |
| A stack trace / exception | Paste it in full (don't summarize) — OSGi errors like `ServiceException` or `BundleException` usually pinpoint the exact failure line. |

---

## Everyday loop (once Step 4 is confirmed working)

You will **not** need to repeat Steps 1–2 every time:

1. Edit plugin code.
2. `mvn clean package` (Step 3).
3. If PathVisio is still running: **Plugins → Install local plugins** again,
   pointing at the freshly rebuilt jar.
   (If OSGi doesn't cleanly reload a changed bundle version, closing and
   relaunching PathVisio via Step 2 is the fallback — this is something to
   confirm once we're past the first successful load.)

Only re-run `ant` (Step 1) if PathVisio's own source changes, which should be
rare for this project.
