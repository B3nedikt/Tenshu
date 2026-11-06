# Building Tenshu in a Claude Code on the web environment

[Claude Code on the web](https://code.claude.com/docs/en/claude-code-on-the-web)
runs sessions in an Anthropic-managed Ubuntu 24.04 sandbox. The stock image ships
common runtimes (including OpenJDK 21 + Gradle) but **cannot build this repo out of
the box** — it has no Android SDK, neither of the two JDKs the toolchains ask for, and
its outbound network is restricted to an allowlist that omits Google's Maven host. This
how-to gets a cloud environment to the point where `./gradlew` works.

It is the cloud counterpart to `scripts/android-smoke.sh` (which assumes a locally
installed SDK + emulator). Nothing here changes any module or build script — it is
pure environment configuration, applied once per environment in the web UI.

## Why the defaults aren't enough

Three gaps, each of which fails the build on its own:

1. **No Android SDK.** Every module applies an Android Gradle plugin
   (`com.android.application` / `com.android.kotlin.multiplatform.library`). AGP needs
   a valid SDK location just to *configure* the project — so without one, even a
   JS- or desktop-only task dies at configuration with `SDK location not found`.
2. **No JDK 17 and no JDK 25.** Most modules declare `jvmToolchain(17)`; the three
   that bundle an FFM runtime — `:macos-helpers`, `:drive-desktop`, `:control-desktop`
   — declare `jvmToolchain(25)`. The image only has JDK 21. Gradle's fallback is to
   auto-provision a JDK via Foojay, but `api.foojay.io` is not on the egress
   allowlist, so provisioning fails (`403` through the proxy). Installing both
   locally lets the toolchains resolve with no network.

   ⚠⚠ **The JDK-25 half is not confined to the desktop apps.** `control-desktop` and
   `drive-desktop` resolve that toolchain **eagerly at configuration time**
   (`javaToolchains.launcherFor { … }.get()`, to give jpackage an explicit `javaHome`),
   and Gradle configures every project in the build. So with JDK 25 absent,
   `:core-common:desktopTest` — a module with nothing to do with desktop packaging —
   dies before compiling anything, with an error naming `control-desktop`. Verified
   2026-08-31: on JDK 21 alone, **no** Gradle task in this repo runs.
3. **`dl.google.com` is not allowlisted.** The default **Trusted** network policy
   already allows `services.gradle.org`, `repo1.maven.org`, and `kotlinlang.org` — but
   **not** `dl.google.com`, which is where Gradle's `google()` repository (the Android
   Gradle Plugin, AndroidX, Compose) actually resolves. It must be added explicitly.
   ⚠ Note the Gradle *distribution* download itself is a redirect: `services.gradle.org`
   302s to a GitHub release asset (`github.com/gradle/gradle-distributions/releases/…`).
   On some policies that redirect host is **not** allowlisted, so the wrapper download
   403s before any build configures. If you hit that, add `github.com` +
   `objects.githubusercontent.com` + `release-assets.githubusercontent.com` to the
   allowlist too (§1 lists the full set), or just use **Full** access (below) — for a
   throwaway dev environment that's the least fiddly choice.

## Setup

All three settings live in the environment editor: open the environment selector
(the cloud icon), hover the environment, and click the settings icon.

### 1. Network access

Set **Network access** to **Custom**, tick *"Also include default list of common
package managers"*, and add to **Allowed domains**:

```text
dl.google.com
maven.google.com
github.com
objects.githubusercontent.com
release-assets.githubusercontent.com
```

Why each is needed:

- **`dl.google.com` + `maven.google.com`** — Gradle's `google()` repository (the Android
  Gradle Plugin, AndroidX, Compose). The default **Trusted** policy already allows
  `services.gradle.org`, `repo1.maven.org`, and `kotlinlang.org` but **not** these, so
  the Android plugins fail to resolve without them. (`maven.google.com` 302-redirects to
  `dl.google.com`; both are listed so nothing snags on the redirect.)
- **`github.com` + `objects.githubusercontent.com` + `release-assets.githubusercontent.com`**
  — the Gradle *wrapper distribution* download. `services.gradle.org` 302s to a GitHub
  release asset (`github.com/gradle/gradle-distributions/releases/…`), which redirects
  again to a `*.githubusercontent.com` CDN host (`release-assets.githubusercontent.com`
  on current GitHub, `objects.githubusercontent.com` historically). Without these the
  wrapper download 403s before any build configures — most visibly when the session has
  **single-repo-scoped** GitHub access, which blocks release-asset hosts even though they
  sit on the default Trusted list.

Leaving it at plain **Trusted** is not enough — the Android plugins will fail to resolve.
**Full** access sidesteps the whole allowlist and works too if you prefer not to maintain
one for a throwaway dev environment.

### 2. Environment variables

Add (`.env` format, no quotes — a root setup script's `export` does not reach the
session shell, so the SDK path must be set here):

```text
ANDROID_HOME=/opt/android-sdk
ANDROID_SDK_ROOT=/opt/android-sdk
CHROME_BIN=/usr/bin/google-chrome-stable
```

`CHROME_BIN` is where Karma looks for the browser behind `:tenshu-sdk-js:jsTest`, the
`sdk` lane of `scripts/run-web-gate.sh`.

### 3. Setup script

Paste [`scripts/cloud-setup.sh`](../../scripts/cloud-setup.sh) into the **Setup
script** field. It installs JDK 17 **and** JDK 25, installs the Android SDK for
`compileSdk 37` (`platform-tools`, `platforms;android-37.0`, `build-tools;37.0.0`),
warms the Gradle wrapper distribution, and then **verifies all four and says so**.

⚠⚠ **The platform package carries a minor suffix: `platforms;android-37.0`, not
`platforms;android-37`.** Google publishes minor platform revisions now (36.1, 37.0,
37.1, …) and there is no bare `android-37`. Asking for one prints
`Warning: Failed to find package` on stdout and **still exits 0** — which, combined
with the script's deliberate `|| true`, is how it silently kept installing android-36
after the repo moved to `compileSdk 37`. That is what step 4's verification block is
for; read its output rather than assuming the script worked.

The script runs once; Anthropic then snapshots the filesystem so later sessions
start with the SDK, JDK, and Gradle distribution already present. It re-runs only
when you change the setup script or the allowed hosts, or after the cache's ~7-day
expiry. Keep it under the ~5-minute setup budget — if it ever times out, remove the
Gradle-wrapper warm (step 3 in the script) first; the first real build will just pay
that download once.

### 4. Maven Central goes through Google's mirror (2026-10-23)

⚠⚠ **From October 2026 the cloud could not run Gradle at all.** Every task died while
configuring the root project with `repo.maven.apache.org … 429 Too Many Requests`, and the
plugin portal failed too, because it redirects Central-hosted plugin jars to the same
host. That happened in five separate runs on 2026-10-13/14, including a run launched on its
own that retried for 45 minutes. Maven Central rate-limits by IP, and cloud sessions share
an egress IP.

The setup script therefore writes `~/.gradle/init.d/tenshu-maven-mirror.gradle`, which
rewrites every Maven Central repository (settings, root build, app modules) to
`https://storage.googleapis.com/maven-central/maven2/`, Google's public mirror of Central.
It also puts that mirror ahead of `gradlePluginPortal()`. The redirect lives in the
environment, not the repo: maintainer and CI builds still resolve from Central. It then
warms the JS SDK's dependency cache inside the setup budget, so the snapshot already holds
the dependencies.

`storage.googleapis.com` has been reachable from the cloud environment (checked
2026-10-14). If a session still reports `repo.maven.apache.org` in a resolution error,
the init script did not reach that user's Gradle home; check `ls ~/.gradle/init.d`.

## Verify

In a **fresh** session in that environment (existing sessions keep their old policy):

```bash
./gradlew :fit-shared-data:compileKotlinJs        # JS target
./gradlew :core-common:desktopTest                # a KMP unit-test module — this is
                                                  # the one that dies at CONFIGURATION
                                                  # when JDK 25 is missing
./gradlew :file-service-proxy:testAndroidHostTest # exercises the Android SDK, which
                                                  # `desktopTest` alone never touches
scripts/run-web-gate.sh                           # the web gate: jsTest in Chrome,
                                                  # vitest, tsc + vite
```

⚠ Pick the Android one deliberately: a wrong/absent `platforms;android-NN` fails at
`checkDebugAarMetadata` naming a *dependency*, not the missing platform, so a JVM-only
check will not find it. Both of the above passed on 2026-08-31 in a cloud session
(821 and 274 tests respectively, 0 failures) once the four prerequisites were present.

### 5. Attach `Kotlin/karma` to every session (2026-10-23)

⚠⚠ **The domain allowlist does not govern GitHub.** A session may fetch from GitHub (`github.com`,
`codeload.github.com`, `api.github.com`) only for repositories **attached to that session**.
Kotlin/JS's test toolchain pins JetBrains' Karma fork as a GitHub tarball
(`kotlin-js-store/yarn.lock`: `karma@github:Kotlin/karma#…`, resolved from `codeload.github.com`).
So with only this repo attached, `:kotlinNpmInstall` fails, and with it `jsBrowserDistribution`,
`jsTest`, and every web lane that needs the SDK bundle:

```text
error Error: https://codeload.github.com/Kotlin/karma/tar.gz/239a8fc…: Request failed "403 Forbidden"
```

How to tell it apart from an allowlist denial (measured with a probe session):
- **This block:** the tunnel opens, then GitHub-shaped JSON comes back: *"GitHub access to this
  repository is not enabled for this session"*. This repo's own tarball from the same host returns 200.
- **An allowlist denial:** refused at `CONNECT` with plain text, *"request blocked: no rule or
  allowlist entry allows host …"*. Adding `codeload.github.com` to the allowed domains changed
  nothing.

**Fix:** list `https://github.com/Kotlin/karma` as a second source repository in every session.
For a RemoteTrigger run, put both repos in `session_context.sources`. With it, the whole web gate
ran green in the cloud (measured with a probe session):
- `sdk`: 95 cases in headless Chrome;
- apex 896 tests, Drive 243 tests;
- the build lane;
- `compileTestKotlinJs` for every module.

If a Kotlin bump moves the pin to another repo, the `resolved` line in `kotlin-js-store/yarn.lock`
names the repo to attach.

⚠ Also: `corepack pnpm` is broken in the image (it cannot fetch the pinned pnpm). Use plain `pnpm`.

## Scope / limitations

- **No emulator.** The image has no Android emulator (and DO/cloud nested-virt is
  unreliable), so the House Rule #8 smoke gate (`scripts/android-smoke.sh`) does not
  run here. This environment is for Kotlin/JVM/JS **compile + unit tests**; the
  emulator smoke gate stays on a maintainer machine or a dedicated CI runner.
- **Kotlin/Native (iOS) targets** pull a large Konan toolchain on first compile; the
  setup script deliberately does not pre-warm them (it would blow the 5-minute
  budget). They compile in-session on demand, just slowly the first time.
- The setup script pins a specific `cmdline-tools` bootstrap build; bump the build
  number in the script if Google rotates it (see the comment there). ⚠ A stale pin is
  **not** why a new platform fails to install — the package list is fetched from
  Google's repository at run time, so the pinned 11076708 bootstrap resolves
  `platforms;android-37.0` fine (checked 2026-08-31). Suspect the package *name* first.
