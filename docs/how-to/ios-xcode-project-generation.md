# Regenerating the iOS Xcode projects

`control-ios/ControlIos.xcodeproj` and `drive-ios/DriveIos.xcodeproj` are **generated** from
each app's tracked `project.yml` with [XcodeGen](https://github.com/yonatankoren/xcodegen) and
are **gitignored** — they are never committed. Only `project.yml` and the `Sources/` tree are
source of truth.

## The stale-project trap

XcodeGen resolves `sources: - path: Sources` **at generate time** and writes every Swift file
out individually in the `.pbxproj`. So when you switch branches, merge, or rebase and the set of
Swift files changes (a file added, removed, or renamed), the on-disk `.xcodeproj` goes stale: it
either misses a new file or still lists a deleted one. The classic symptom is a build error like

```
'SyncDashboardView' not found in scope
```

in a file that is itself compiled — the *referenced* type's file just isn't in the project.

The fix is always: regenerate.

## Manual regeneration

```bash
brew install xcodegen          # once

./gradlew generateXcodeProjects        # both apps
./gradlew generateControlIosProject    # just Control
./gradlew generateDriveIosProject      # just Drive
```

(or `cd control-ios && xcodegen generate` directly).

## Automatic regeneration (git hooks)

The repo ships hooks under `.githooks/` that regenerate the projects after
`checkout` / `merge`. They're **opt-in** — git never runs hooks from a fresh clone
for security reasons. Install once:

```bash
./gradlew installGitHooks      # sets core.hooksPath -> .githooks
```

To undo: `git config --unset core.hooksPath`.

The hook (`.githooks/regenerate-xcode-projects.sh`) is safe on machines without XcodeGen — if
`xcodegen` isn't on `PATH` it exits 0 and does nothing, so non-iOS devs and CI are unaffected.
On `post-checkout` it only regenerates when something under an iOS app dir actually changed
between the two commits, to avoid churning the project (and prompting Xcode to reload) on every
branch switch.
