#!/usr/bin/env bash
#
# Claude Code on the web — environment pre-warm.
#
# Paste this into the **Setup script** field of a Claude-on-web environment
# (see docs/how-to/claude-web-environment.md). It runs ONCE as root on Ubuntu
# 24.04 when a session first starts in that environment; Anthropic then snapshots
# the filesystem, so later sessions start with everything below already on disk.
#
# It makes this KMP repo buildable in the cloud sandbox, which the stock image
# is NOT out of the box:
#   1. A JDK 17 for the ~100 shared modules' `jvmToolchain(17)` AND a JDK 25 for
#      the three that pin `FFM_JDK` (`:macos-helpers`, `:drive-desktop`,
#      `:control-desktop`). The image ships only JDK 21, and the foojay resolver
#      those build files count on can't reach api.foojay.io through the egress proxy.
#   2. The Android SDK — EVERY module applies the Android plugin, so without a valid
#      SDK the build fails to *configure* ("SDK location not found"), even for a
#      JS- or desktop-only task.
#   3. A warm Gradle wrapper distribution so the first in-session build skips the
#      ~130 MB cold download.
#   4. A Gradle init script that sends every Maven Central request to Google's
#      mirror of it. ⚠⚠ From the shared cloud egress IP, repo.maven.apache.org
#      answered 429 Too Many Requests on every attempt (2026-10-13/14, five separate
#      runs) and no Gradle task configured at all. storage.googleapis.com is reachable.
#   5. Google Chrome, so `:tenshu-sdk-js:jsTest` (Karma + ChromeHeadless) can run —
#      the web gate's `sdk` lane (scripts/run-web-gate.sh). As root, Karma adds
#      --no-sandbox itself: tenshu-sdk-js/karma.config.d/chrome-no-sandbox-as-root.js.
#   6. A warm dependency cache for the JS SDK (time-boxed to the setup budget), so
#      the snapshot carries it and a session's first build does not resolve cold.
#
# ⚠⚠ **JDK 25 is not optional and its absence is not scoped to the desktop apps.**
# `control-desktop/build.gradle.kts` and `drive-desktop/build.gradle.kts` resolve that
# toolchain EAGERLY (`javaToolchains.launcherFor { 25 }.get()`, for jpackage's
# `javaHome`), and Gradle configures every project in the build — so a missing JDK 25
# fails `:core-common:desktopTest` at CONFIGURATION, naming `control-desktop` in the
# error. Verified 2026-08-31: with only JDK 21 present, no Gradle task in this repo
# runs at all.
#
# PREREQUISITE — Network access must be **Custom** (with the default package-manager
# list included) PLUS these two hosts, or the downloads below 403:
#     dl.google.com
#     maven.google.com
# Also add these to the environment's **Environment variables** field (a root setup
# script's exports do not reach the session shell):
#     ANDROID_HOME=/opt/android-sdk
#     ANDROID_SDK_ROOT=/opt/android-sdk
#     CHROME_BIN=/usr/bin/google-chrome-stable
#
# Keep total runtime under ~5 min so the environment cache can build. If it ever
# times out, drop step 3 first (the first real build will pay that download once).
set -u

# 1) JDKs 17 + 25 (toolchains) + unzip (needed by step 2). Ubuntu archives are
#    allowlisted under the Trusted defaults, so no extra host is required here.
#    ⚠ Both are needed — see the JDK-25 note in the header. `-headless` is enough:
#    nothing here runs a desktop UI, and it saves ~100 MB of the setup budget.
apt-get update -qq && apt-get install -y -qq \
  openjdk-17-jdk-headless openjdk-25-jdk-headless unzip || true

# 2) Android SDK — the minimum for compileSdk 37.
export ANDROID_HOME=/opt/android-sdk
export ANDROID_SDK_ROOT="$ANDROID_HOME"
CLT="$ANDROID_HOME/cmdline-tools"
if [ ! -x "$CLT/latest/bin/sdkmanager" ]; then
  mkdir -p "$CLT"
  # Pinned bootstrap build of the command-line tools; sdkmanager self-updates the
  # actual packages afterwards. Bump the build number if Google rotates it
  # (browse https://dl.google.com/android/repository/ for the current one).
  curl -sSL -o /tmp/clt.zip \
    "https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
  unzip -q /tmp/clt.zip -d /tmp/clt && rm -f /tmp/clt.zip
  # The archive unpacks to `cmdline-tools/`; sdkmanager expects it under `.../latest/`.
  rm -rf "$CLT/latest" && mv /tmp/clt/cmdline-tools "$CLT/latest"
fi
SDKM="$CLT/latest/bin/sdkmanager"
yes | "$SDKM" --sdk_root="$ANDROID_HOME" --licenses >/dev/null 2>&1 || true
# ⚠⚠ The platform package for compileSdk 37 is `platforms;android-37.0` — WITH the
# minor suffix. Google publishes minor platform revisions now (36.1, 37.0, 37.1, …)
# and there is no bare `platforms;android-37`; asking for one gets a
# `Warning: Failed to find package` that sdkmanager reports on stdout while still
# exiting 0. That is exactly how this script sat installing android-36 against a
# compileSdk-37 repo — so the verification block below exists, and is the point.
"$SDKM" --sdk_root="$ANDROID_HOME" \
  "platform-tools" "platforms;android-37.0" "build-tools;37.0.0" >/dev/null 2>&1 || true

# 3) Warm the Gradle wrapper distribution (the version is pinned in
#    gradle/wrapper/gradle-wrapper.properties). `--version` fetches + unpacks the
#    distribution but does NOT evaluate build scripts, so it needs neither the SDK
#    nor AGP — a clean, cheap warm. Best-effort. NOTE: services.gradle.org 302s to a
#    GitHub release asset; if that host isn't allowlisted this warm 403s harmlessly
#    (the first real build then 403s too) — add github.com + objects.githubusercontent.com
#    or use Full access. See docs/how-to/claude-web-environment.md.
[ -f ./gradlew ] && ./gradlew --version >/dev/null 2>&1 || true

# 3b) Maven Central -> Google's mirror, for every build this user runs. An init
#     script rather than a repo change: the repo's own builds (maintainer, CI) keep
#     resolving from Central, and only this environment is redirected. Rewrites every
#     Central URL wherever it is declared (settings, root build, the app modules), and
#     puts the mirror ahead of gradlePluginPortal(), which otherwise redirects
#     Central-hosted plugin jars straight back to repo.maven.apache.org.
#     Written for root AND any other home, because the session user is not
#     guaranteed to be the setup user.
MIRROR_INIT='// Written by scripts/cloud-setup.sh: Maven Central via Google'"'"'s mirror, because
// repo.maven.apache.org answers 429 from the shared cloud egress IP.
def MIRROR = "https://storage.googleapis.com/maven-central/maven2/"
def CENTRAL = ["https://repo.maven.apache.org/maven2", "https://repo1.maven.org/maven2"]
def redirect = { RepositoryHandler repos ->
    repos.withType(MavenArtifactRepository).configureEach { r ->
        def u = r.url.toString()
        if (CENTRAL.any { u.startsWith(it) }) r.url = MIRROR
    }
}
beforeSettings { settings ->
    settings.pluginManagement.repositories.maven { name = "mavenCentralMirror"; url = MIRROR }
    redirect(settings.pluginManagement.repositories)
    redirect(settings.dependencyResolutionManagement.repositories)
    redirect(settings.buildscript.repositories)
}
allprojects {
    redirect(repositories)
    redirect(buildscript.repositories)
}'
for home in /root /home/*; do
  [ -d "$home" ] || continue
  mkdir -p "$home/.gradle/init.d"
  printf '%s\n' "$MIRROR_INIT" > "$home/.gradle/init.d/tenshu-maven-mirror.gradle"
  [ "$home" = /root ] || chown -R "$(stat -c %U "$home")" "$home/.gradle" 2>/dev/null || true
done

# 3c) Google Chrome for the Karma lane. From dl.google.com, which step 2 already
#     needs allowlisted; Ubuntu's own `chromium` is a snap and does not run here.
if ! command -v google-chrome-stable >/dev/null 2>&1; then
  curl -sSL -o /tmp/chrome.deb \
    "https://dl.google.com/linux/direct/google-chrome-stable_current_amd64.deb" \
    && apt-get install -y -qq /tmp/chrome.deb >/dev/null 2>&1 || true
  rm -f /tmp/chrome.deb
fi

# 3d) Warm the JS SDK's dependency cache with whatever is left of the ~5-minute
#     budget (bash's SECONDS counts from script start). A killed run still leaves
#     every finished download in the cache, so a partial warm is worth keeping.
WARM_BUDGET=$(( 280 - SECONDS ))
if [ -f ./gradlew ] && [ "$WARM_BUDGET" -gt 30 ]; then
  ANDROID_HOME=/opt/android-sdk ANDROID_SDK_ROOT=/opt/android-sdk \
    timeout "$WARM_BUDGET" ./gradlew :tenshu-sdk-js:compileTestKotlinJs --no-daemon >/dev/null 2>&1 || true
fi

# 4) Verify, loudly. Every install above is best-effort (`|| true`) so a partial
#    failure cannot abort environment creation — which also means a partial failure
#    is INVISIBLE, and a snapshot is then taken of the broken result. These lines
#    are the only thing standing between that and a session that cannot configure
#    Gradle at all. They warn rather than exit non-zero, on purpose: a hard failure
#    here fails the whole environment, and a warned-about environment is still worth
#    having for the parts that do work.
missing=""
[ -d /usr/lib/jvm/java-17-openjdk-amd64 ] || missing="$missing JDK-17"
[ -d /usr/lib/jvm/java-25-openjdk-amd64 ] || missing="$missing JDK-25"
[ -d "$ANDROID_HOME/platforms/android-37.0" ] || missing="$missing platforms/android-37.0"
[ -d "$ANDROID_HOME/build-tools/37.0.0" ] || missing="$missing build-tools/37.0.0"
[ -f /root/.gradle/init.d/tenshu-maven-mirror.gradle ] || missing="$missing maven-mirror-init"
command -v google-chrome-stable >/dev/null 2>&1 || missing="$missing google-chrome"
if [ -n "$missing" ]; then
  echo "!! cloud-setup.sh: MISSING ->$missing"
  echo "!! Gradle will fail to CONFIGURE (not just to build) with any of these absent."
  echo "!! Check the environment's allowed hosts (dl.google.com, maven.google.com) and"
  echo "!! re-run the setup script. See docs/how-to/claude-web-environment.md."
else
  echo "cloud-setup.sh: OK — JDK 17 + 25, Android platform 37.0, build-tools 37.0.0, Maven mirror, Chrome."
fi

exit 0
