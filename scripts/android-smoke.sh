#!/usr/bin/env bash
#
# Android PR smoke gate.
#
# Boots the emulator if no device is attached, builds + installs Control (the
# proxy) and its clients (Drive, Maps, Home, Calendar, Timeline), launches them,
# and FAILS on any crash / ANR in our apps.
#
# It also launches and checks — but does NOT build — every *other* first-party
# Tenshu app already installed on the device (Fit, Contacts today — Mia left the
# canary set when MQ-1 gave it a client stack). That
# asymmetry is the point, not an oversight: an app this gate reinstalls is
# current by construction and can never be caught out of step, so the apps it
# leaves alone are the only ones whose lockstep/announce assertions carry
# information. See step 3.5.
#
# ⚠⚠ The two halves therefore pull in opposite directions, and both are real:
# building an app is the only way to gate a *change to it*, and NOT building one is
# the only way to detect *skew*. The rule that keeps both is that at least one
# first-party app always stays un-built — so add an app to the built set when its
# own changes need gating, never until.
# Run this before opening or merging any PR that touches androidMain, :control,
# or :drive-android — it catches DI-wiring / manifest / startup regressions that
# a Kotlin unit test or a bare `compileDebugKotlin` cannot (see AGENTS.md
# "Android smoke gate").
#
# It is a *smoke* gate, not a functional one: it proves the apps build, install,
# launch, talk over Binder without crashing, are all in schema lockstep (Control +
# clients report the same TENSHU_STORAGE_FORMAT_VERSION), are all *admitted* by
# the schema-announce gate (per-service versions, which move independently of the
# format version — see step 4.6), and that no call an app actually made came back
# as a structural failure (step 4.7 — the only check that catches a purely
# *additive* skew, which moves neither version). Genuinely device/network-
# dependent flows — real uploads, pairing, metered-network (WifiOnly) deferral,
# a background drain actually firing — still need a maintainer's own device.
#
# Usage:   scripts/android-smoke.sh
# Env:     TENSHU_SMOKE_AVD   AVD name to boot if none attached (default Medium_Phone)
#          ANDROID_HOME       SDK root (default ~/Library/Android/sdk)
#          ANDROID_SERIAL     pin a specific device; otherwise a running emulator
#                             is preferred, then a single attached device
#          TENSHU_SMOKE_KEEP_EMULATOR=1  leave an emulator this run booted running
# Exit:    0 = passed, non-zero = a check failed (see output).
#
# Reuses an already-attached device/emulator if there is one (physical or a
# window you have open); only boots a headless AVD when none is present, and
# kills that one again on exit. TENSHU_SMOKE_KEEP_EMULATOR=1 leaves it running
# for fast reruns (stop it later with `adb emu kill`).
#
# ⚠⚠ Why it does not stay up by default: a headless AVD on swiftshader renders
# on the CPU into host RAM — one left resident at 7.3 GB has kernel-panicked the
# box on memory (docs/tech-debt.md "Idle build processes hold the machine's RAM"). An emulator you started yourself
# is attached before the script runs and is never touched.

set -euo pipefail

# Queue behind any other gate or smoke run on this machine — see the file for why.
# shellcheck source=scripts/lib/gate-lock.sh
. "$(dirname "${BASH_SOURCE[0]}")/lib/gate-lock.sh"
tenshu_gate_lock "$0" "$@"

SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
ADB="$SDK/platform-tools/adb"
EMU="$SDK/emulator/emulator"
AVD="${TENSHU_SMOKE_AVD:-Medium_Phone}"
CONTROL_PKG="app.tenshu.control.android"
DRIVE_PKG="app.tenshu.drive.android"
# Maps is the offline, keyless basemap app. Its manifest strips INTERNET +
# ACCESS_FINE/COARSE_LOCATION (Ph7b hardening) — see maps-android/src/main/
# AndroidManifest.xml. A clean launch here IS the standing House-Rule-#1
# regression: the app cannot even build a network stack, so no-crash on launch
# proves "nothing leaves the device" holds at the OS boundary. No extra
# assertion beyond the shared no-crash/ANR checks below is needed.
MAPS_PKG="app.tenshu.maps.android"
# Home is a Tenshu client since HD-3 (home-device-service.md §3): it reads
# hub-authored HubStatus / HomeDevice / DeviceState rows over Binder and issues
# device commands, so it is in schema lockstep and belongs in this gate. Its
# manifest strips INTERNET too (all egress is Control's), so as with Maps a clean
# launch is itself the House-Rule-#1 regression check.
HOME_PKG="app.tenshu.home.android"
# ⚠⚠ Calendar is in this gate because its ABSENCE from it is what let CA-1…CA-4
# ship with Calendar never wired into Control at all — no schema modules, no
# implementation factory, no `initCalendarService`. Four phases of unit-green code
# that could not have worked on a device, found only when CA-5c went looking.
# A launch here is the check: Calendar binds Control on start, so a missing
# registration surfaces as a crash or a refused announce rather than as silence.
# ⚠ Since CA-7c it also ships a Glance widget. This gate does NOT place it — a
# launcher-placed widget is not something a smoke run can arrange — so a broken
# `CalendarWidgetReceiver` is still only caught by a maintainer putting it on a
# home screen. What the gate does cover is the manifest merging and the app
# starting with the receiver declared.
CALENDAR_PKG="app.tenshu.calendar.android"
# ⚠⚠ Timeline is BUILT here, not only launched, because the gate would otherwise
# gate a stale APK on every PR that changes it: a timeline change would be
# verified by launching whatever build happened to be on the device, which is no
# verification at all, and nothing in the output would say so.
# ⚠ This deliberately trades away Timeline's own lockstep signal — see the header:
# an app this gate reinstalls is current by construction, so it can never be caught
# out of step. That is acceptable only because Fit and Contacts remain
# un-built canaries. **At least one first-party app must always stay un-built**, or
# steps 4.5/4.6 stop carrying information for anyone. ⚠ And the real canary set is
# whichever of those are *installed on the device in front of you* — a clean
# emulator often leaves only Fit, so the margin is thinner than the module list
# suggests; step 3.5 prints what it actually found for exactly this reason.
TIMELINE_PKG="app.tenshu.timeline.android"
# ⚠ Mia is BUILT here since MQ-1 (mia-query-layer.md MQ-D2), which gave it its first
# client stack — a Binder binding, a manifest `<queries>` entry and a
# `ClientCallbackService`, none of which any unit test can exercise and all of which fail
# SILENTLY (the app simply reports that Control cannot be reached). That is the HD-3
# precedent: an app joins this gate on the day it gains a client, not later.
# ⚠⚠ It costs Mia's canary role. The un-built set is now **Fit and Contacts** — still
# non-empty, which is the rule, but thinner than it was, and on a clean emulator it is
# often just Fit. Step 3.5 prints what it actually found.
MIA_PKG="app.tenshu.mia.android"

# ⚠ Photos joins the built set on the day it exists (`photos-app.md` PH-1), which is the HD-3 /
# Mia precedent: it ships with a Binder client, a manifest `<queries>` entry and a
# `ClientCallbackService`, none of which any unit test exercises and all of which fail SILENTLY —
# a Photos with a broken client shows an empty grid, not a crash.
# ⚠⚠ It also ships in the same PR as a `FileService` version bump, so it is the app most likely to
# be caught out of lockstep. The un-built canary set stays **Fit and Contacts** — non-empty, which
# is the rule.
PHOTOS_PKG="app.tenshu.photos.android"

# Let Gradle find the SDK without a local.properties (worktree-safe).
export ANDROID_HOME="$SDK"
export ANDROID_SDK_ROOT="$SDK"

[ -x "$ADB" ] || { echo "adb not found at $ADB (is the Android SDK installed?)"; exit 2; }

# 0.5 Multi-device guard. Bare `adb shell` (and Gradle's installDebug) fail with
# "more than one device/emulator" when a phone is plugged in next to the
# emulator — this gate then dies with a misleading "emulator did not finish
# booting". Resolve ONE target up front and pin it via
# ANDROID_SERIAL, which both adb and Gradle honour: an explicit ANDROID_SERIAL
# wins; else prefer a running emulator (the gate's native habitat); else a
# single attached device; else ask the caller to pin one.
# ⚠⚠ Never the dev-lab emulator (AVD `tenshu_dev_lab`, pinned to port 5580): it holds a client
# onboarded against tenshu.xyz, and this gate would reinstall production-pointed builds over it.
# An explicit ANDROID_SERIAL still wins. docs/how-to/deploy-dev-lab-scaleway.md §10.
DEV_LAB_SERIAL="emulator-5580"
attached_serials() {
  "$ADB" devices | awk -v skip="$DEV_LAB_SERIAL" 'NR>1 && $2=="device" && $1!=skip{print $1}'
}
if [ -z "${ANDROID_SERIAL:-}" ]; then
  serials="$(attached_serials)"
  count=$(printf '%s' "$serials" | grep -c . || true)
  if [ "$count" -gt 0 ]; then
    emu_serial=$(printf '%s\n' "$serials" | grep '^emulator-' | head -1 || true)
    if [ -n "$emu_serial" ]; then
      export ANDROID_SERIAL="$emu_serial"
    elif [ "$count" -eq 1 ]; then
      export ANDROID_SERIAL="$serials"
    else
      echo "Multiple non-emulator devices attached:"; printf '  %s\n' $serials
      echo "Pin one with: ANDROID_SERIAL=<serial> $0"; exit 2
    fi
    echo "==> Target device: $ANDROID_SERIAL"
  fi
fi

# 0.7 No client may reach the network: only Control holds INTERNET (House Rule #1 at the OS
# boundary). Read from the RELEASE merged manifests of every app in settings.gradle.kts — what Play
# ships, including the ones this gate does not install — because a dependency merges INTERNET in
# without any line of ours changing: Drive held it via ML Kit's telemetry while its own manifest
# said "no INTERNET". A launch cannot catch that; the permission is simply granted. Runs before the
# emulator boots: it needs no device, and a failure here should not cost a boot.
echo "==> Checking that no client app holds INTERNET (release merged manifests)…"
manifest_status=0
# shellcheck disable=SC2046 # one task per word, by design
./gradlew -q $(python3 -I scripts/lib/check_client_no_internet.py --list-tasks --variant release) &&
  python3 -I scripts/lib/check_client_no_internet.py --variant release || manifest_status=$?
if [ "$manifest_status" -ne 0 ]; then
  echo
  echo "SMOKE GATE: FAILED — a client app's release manifest holds INTERNET, or could not be built"
  echo "  (no device was used, nothing else was asserted). See the ✗ lines above."
  exit "$manifest_status"
fi

# 1. Ensure a device/emulator is attached; boot the AVD headless if not.
if [ -z "${ANDROID_SERIAL:-}" ]; then
  echo "==> No device attached; booting emulator '$AVD' (headless)…"
  [ -x "$EMU" ] || { echo "emulator not found at $EMU"; exit 2; }
  "$EMU" -avd "$AVD" -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect -no-snapshot >/dev/null 2>&1 &
  BOOTED_EMU_PID=$!
  # Poll for the emulator's serial instead of `adb wait-for-device` (which also
  # breaks if a second transport appears mid-boot), then pin it.
  emu_serial=""
  for _ in $(seq 1 60); do
    emu_serial=$("$ADB" devices | awk -v skip="$DEV_LAB_SERIAL" 'NR>1 && $1 ~ /^emulator-/ && $1!=skip {print $1; exit}')
    [ -n "$emu_serial" ] && break
    sleep 2
  done
  [ -n "$emu_serial" ] || { echo "emulator did not appear in adb"; kill "$BOOTED_EMU_PID" 2>/dev/null; exit 3; }
  export ANDROID_SERIAL="$emu_serial"
  if [ -z "${TENSHU_SMOKE_KEEP_EMULATOR:-}" ]; then
    # Only the emulator THIS run booted — by serial, with the pid as the fallback for a console
    # that no longer answers.
    trap '"$ADB" -s "$emu_serial" emu kill >/dev/null 2>&1 || kill "$BOOTED_EMU_PID" 2>/dev/null; echo "==> Stopped the emulator this run booted ($emu_serial; TENSHU_SMOKE_KEEP_EMULATOR=1 keeps it)"' EXIT
  fi
  echo "==> Target device: $ANDROID_SERIAL"
  echo "==> Waiting for boot_completed…"
  for _ in $(seq 1 90); do
    [ "$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ] && break
    sleep 5
  done
fi
[ "$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ] || {
  echo "device ${ANDROID_SERIAL:-<none>} did not finish booting"; exit 3;
}

# 2. Build + install the apps (Control is the proxy Drive binds to; Maps is the
# standalone offline basemap app; Home is the smart-home manager; Calendar is the
# newest client and the one whose wiring gap this gate exists to catch; Timeline is
# the capture app, whose changes this gate used to miss entirely; Mia is the newest
# client, whose Binder wiring cannot be exercised any other way).
#
# ⚠⚠ The build is teed to a log and runs with --stacktrace because THIS step is the one that
# fails, ~1 run in 3, at `*:packageDebug` in a different app module each time (tech-debt.md
# "The Android smoke gate's packaging step fails ~1 run in 3"). Every occurrence so far has been
# diagnosed from console scrollback, which shows only
#     > A failure occurred while executing …PackageAndroidArtifact$IncrementalSplitterRunnable
# and NOT the `Caused by:` underneath it — the one line that would say whether this is an OOM, a
# zip-entry collision or a stale-file delete. `check` in CI skips assembly, so packageDebug runs
# nowhere but here: if this script does not keep the log, nobody does, and the next occurrence is
# as undiagnosable as the last three. Costs nothing on a green run.
BUILD_LOG="${TENSHU_SMOKE_BUILD_LOG:-build/smoke/android-smoke-build.log}"
mkdir -p "$(dirname "$BUILD_LOG")"

# 0.9 The OTHER billing rail compiles. Everything below builds the default (`play`) rail, and the
# Stripe rail — the APK distributed outside Play — compiles a different source dir
# (`control-shared/src/billingStripe`) without the Play Billing library (control-shared/build.gradle.kts,
# commerce-store-link-out.md §0.8). Nothing else builds it, so a Play-only reference from shared
# code would break the non-Play APK silently. Compile only (`:control-shared`, where the rails
# differ), not a second assemble: cheap, and it is the compile that can break.
echo "==> Compiling Control's Stripe billing rail (-Ptenshu.android.billingRail=stripe)…"
if ! ./gradlew -q :control-shared:compileAndroidMain -Ptenshu.android.billingRail=stripe >"$BUILD_LOG.stripe-rail" 2>&1; then
  echo
  echo "SMOKE GATE: FAILED — Control's Stripe billing rail does not compile (log: $BUILD_LOG.stripe-rail)."
  grep -E '^e: ' "$BUILD_LOG.stripe-rail" | head -10 || true
  exit 1
fi

echo "==> Building + installing Control + Drive + Maps + Home + Calendar + Timeline + Mia + Photos…"
echo "    (build log: $BUILD_LOG)"
# `set -o pipefail` is already in force, so the pipeline's status is gradlew's — but `set -e` must
# be suspended around it or the script dies before it can point at the log it just captured.
build_status=0
set +e
./gradlew --stacktrace \
  :control:installDebug :drive-android:installDebug :maps-android:installDebug \
  :home-android:installDebug :calendar-android:installDebug :timeline-android:installDebug \
  :mia-android:installDebug :photos-android:installDebug 2>&1 | tee "$BUILD_LOG"
build_status=$?
set -e
if [ "$build_status" -ne 0 ]; then
  echo
  echo "SMOKE GATE: FAILED during the BUILD (no app was launched, nothing was asserted)."
  failed_task="$(grep -oE "Execution failed for task '[^']+'" "$BUILD_LOG" | head -1 || true)"
  [ -n "$failed_task" ] && echo "  $failed_task"
  if grep -qE 'IncrementalSplitterRunnable|PackageAndroidArtifact' "$BUILD_LOG"; then
    echo "  This looks like the known ~1-in-3 packaging flake — tech-debt.md, \"The Android smoke"
    echo "  gate's packaging step fails ~1 run in 3\". Retry ONCE. A second failure on the SAME"
    echo "  task is NOT this flake and wants reading, not re-running."
    echo "  ⚠ The full log is at $BUILD_LOG and a retry OVERWRITES it — copy it aside first if"
    echo "    you want the root cause on record. The entry is still waiting on one captured"
    echo "    'Caused by:' to move from hypothesis to finding; please attach it there."
  fi
  exit "$build_status"
fi

# 3. Launch (Control first) and let each app come up before starting the next.
#
# Poll for the process instead of sleeping a fixed number of seconds. A fresh `installDebug`
# means a cold first start (dexopt, JIT), which on a loaded emulator can take well over the
# 6-8s this used to allow — the gate then reported "crashed on launch?" for apps that were
# merely still starting, and would have been alive a second later. A false FAIL is worse than
# a slow gate: it trains you to ignore the gate. A genuinely crashing app still fails, because
# it never appears within the window (and step 4 re-checks liveness at the end anyway).
# ⚠⚠ Dismiss any runtime-permission dialog left over from an EARLIER run before launching
# anything. A modal owned by the permission controller keeps the foreground across gate runs, and
# `monkey`'s LAUNCHER intent then does not bring the target app up AT ALL — its process is never
# created, so the poll below runs its full 60s and the app is reported as "crashed on launch?".
#
# Measured: this is the Fit flake that alternates pass/fail. Each run launches Fit, Fit asks for
# Health Connect permissions, the dialog stays up, and the NEXT run's Fit launch is blocked by it
# — so the gate fails on every other run with no crash anywhere (every `ApplicationExitInfo` says
# reason=10 FORCE STOP, this function's own call). ⚠ It reads exactly
# like a broken app and is not one, which is the failure mode this whole file is written against:
# a false FAIL trains you to ignore the gate.
#
# ⚠ Cleared per launch, not once up front: an app that raises its own dialog is fine (its process
# exists and the poll finds it) — what must never carry over is the *previous* app's modal.
dismiss_stale_permission_dialogs() {
  "$ADB" shell am force-stop com.google.android.permissioncontroller >/dev/null 2>&1 || true
  "$ADB" shell am force-stop com.google.android.healthconnect.controller >/dev/null 2>&1 || true
}

launch_and_wait() {
  local pkg="$1" deadline=$((SECONDS + 60))
  dismiss_stale_permission_dialogs
  # Cold start: a process surviving from a previous gate run would just be
  # foregrounded by monkey — onCreate never re-runs, so it neither re-logs its
  # schema-lockstep beacon (cleared above) nor re-exercises startup at all.
  "$ADB" shell am force-stop "$pkg" >/dev/null 2>&1 || true
  "$ADB" shell monkey -p "$pkg" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
  while [ "$SECONDS" -lt "$deadline" ]; do
    [ -n "$("$ADB" shell pidof "$pkg" 2>/dev/null | tr -d '\r')" ] && { sleep 2; return 0; }
    sleep 2
  done
  return 0 # let step 4 report it — this only bounds the wait
}

# 3.5 Discover the first-party apps this gate does NOT build. Every one of the
# four above was just reinstalled from the working tree, so their versions agree
# with the source *by construction* — steps 4.5/4.6 can only ever pass for them.
# The apps that can actually be out of step are the ones already on the device
# from an earlier commit: rebuild Fit alone in Android Studio after a `git pull`
# that added a service, and its announce is refused by a Control that predates it
# (logged as `tenshu/realmData/service announced=1 … current=0`, which
# the app surfaces only as "Can't reach Control"). Those are free to check — they
# are installed already, so they cost one launch each and no build.
#
# Discovered, not listed, so a new first-party app is covered the day it exists.
BUILT_PKGS="$CONTROL_PKG $DRIVE_PKG $MAPS_PKG $HOME_PKG $CALENDAR_PKG $TIMELINE_PKG $MIA_PKG $PHOTOS_PKG"
# `|| true` throughout: an empty result is the normal case on a clean emulator,
# and a non-zero grep must not kill the run under `set -euo pipefail`.
EXTRA_PKGS="$("$ADB" shell pm list packages 2>/dev/null | tr -d '\r' \
  | sed -n 's/^package://p' | grep '^app\.tenshu\.' \
  | grep -vxF -e "$CONTROL_PKG" -e "$DRIVE_PKG" -e "$MAPS_PKG" -e "$HOME_PKG" \
             -e "$CALENDAR_PKG" -e "$TIMELINE_PKG" -e "$MIA_PKG" -e "$PHOTOS_PKG" \
  | sort || true)"
ALL_PKGS="$BUILT_PKGS $EXTRA_PKGS"
if [ -n "$EXTRA_PKGS" ]; then
  echo "==> Also checking (installed, not built by this gate):"
  printf '    %s\n' $EXTRA_PKGS
fi

# The appId an app announces under is its package minus the `.android` suffix
# (`wireSchemaAnnounce(appId = …)` in each app's client module, mirrored by
# BundledAppManifestSource.*_APP_ID). Control is the exception: it announces
# in-process under `platformPackageName`, i.e. the package itself (§6.6).
app_id_for() {
  case "$1" in
    "$CONTROL_PKG") printf '%s' "$1" ;;
    *.android) printf '%s' "${1%.android}" ;;
    *) printf '%s' "$1" ;;
  esac
}

"$ADB" logcat -c
# Grow the log buffer for the run: several cold app starts log enough that the
# default ring can rotate the FIRST app's schema-lockstep beacon out before
# step 4.5 reads it (observed: Control's beacon lost, gate died). Best-effort.
"$ADB" logcat -G 16M >/dev/null 2>&1 || true
# Control first — it is the proxy every other app announces to.
launch_and_wait "$CONTROL_PKG"
# ⚠⚠ Derived from $ALL_PKGS, never re-listed by hand. Calendar was installed by step 2 and
# liveness-checked by step 4 but MISSING from this loop, so the gate reported "not running
# (crashed on launch?)" for an app it had never started — and reported it identically before
# and after a real crash was fixed, which is the worst thing a gate can do. Same omission, in
# the gate itself, that this gate exists to catch (AGENTS.md: Calendar's absence from the app
# list is why CA-1…CA-4 shipped never wired into Control).
for pkg in $ALL_PKGS; do
  [ "$pkg" = "$CONTROL_PKG" ] && continue   # already launched, first, on purpose
  launch_and_wait "$pkg"
done
sleep 4 # a moment for a late crash to surface after the process appears

fail=0

# 4. All processes must be alive.
for pkg in $ALL_PKGS; do
  if [ -n "$("$ADB" shell pidof "$pkg" 2>/dev/null | tr -d '\r')" ]; then
    echo "  ok: $pkg running"
  else
    echo "  FAIL: $pkg is not running (crashed on launch?)"; fail=1
  fi
done

# 4.5 Schema lockstep. Every installed Tenshu app must report the SAME storage-format
# version as the current source (HR #9). This catches the exact bug where Control is left
# on a stale schema while a client is rebuilt: the peer-broken read surfaces only as a
# cryptic `IPC Client -> Failure … error/internal`, not a crash, so the checks above miss
# it. Here it is a loud, named failure. Each app logs `schemaLockstepLine()` at startup.
expected_version="$(grep -oE 'TENSHU_STORAGE_FORMAT_VERSION: Int = [0-9]+' \
  core-common/src/commonMain/kotlin/org/tenshu/core/common/StorageFormat.kt | grep -oE '[0-9]+$')"
if [ -z "$expected_version" ]; then
  echo "  FAIL: could not read TENSHU_STORAGE_FORMAT_VERSION from source"; fail=1
else
  # LC_ALL=C is load-bearing: logcat carries non-UTF-8 bytes, and macOS `tr` aborts
  # on them ("Illegal byte sequence") under a UTF-8 locale. The pipeline then yields
  # NOTHING and every beacon reads as missing — a false FAIL that looks like a real
  # lockstep break. Same fix as the EXTRA_PKGS pipeline below.
  lockstep_lines="$("$ADB" logcat -d 2>/dev/null | LC_ALL=C tr -d '\r' | grep 'TenshuSchemaLockstep' || true)"
  for pkg in $ALL_PKGS; do
    # `|| true`: under set -euo pipefail a missing beacon would otherwise kill
    # the whole script silently instead of reaching the WARN/FAIL below.
    reported="$(printf '%s\n' "$lockstep_lines" | grep -oE "$pkg storageFormatVersion=[0-9]+" | grep -oE '[0-9]+$' | tail -1 || true)"
    if [ -z "$reported" ]; then
      echo "  WARN: $pkg reported no schema-lockstep beacon (older build without it?)"
    elif [ "$reported" != "$expected_version" ]; then
      echo "  FAIL: $pkg storage-format v$reported != source v$expected_version — schema out of lockstep (stale install; reinstall it)"; fail=1
    else
      echo "  ok: $pkg schema v$reported == source"
    fi
  done
fi

# 4.6 Schema announce (core-schema-evolution.md §6.5). Every client session must
# announce its version vector and be admitted — the SchemaAnnouncer prints a
# `TenshuSchemaAnnounce: <appId> admitted=<bool>` beacon (via println → the
# `System.out` logcat tag) on every announce. A refused admission is a hard FAIL
# (the reject-if-silent gate would refuse every subsequent op). A *missing*
# beacon is a WARN for apps that may not have touched the proxy yet during the
# smoke window: lazy clients announce on their first call. ⚠ **Mia is expected to
# WARN here, structurally.** Since MQ-1 it does wire an announcer, but the announce
# is a *session preflight* that fires ahead of the first op — and Mia makes no op
# until the user types something, which a smoke run does not do. Its schema-version
# beacon (step 4.5) still carries the lockstep signal. Control announces eagerly at
# startup (in-process, §6.6) so its beacon missing is a FAIL.
#
# ⚠ This does NOT duplicate step 4.5. Per-service versions (`service(id, version=…)`)
# move independently of TENSHU_STORAGE_FORMAT_VERSION: adding a service to :core
# raises every app's announced vector while the format version sits still: a rebuilt
# Fit is refused by a slightly older Control with both sides reporting the same
# format version — 4.5 passes, and only this check names it.
announce_lines="$("$ADB" logcat -d 2>/dev/null | LC_ALL=C tr -d '\r' | grep 'TenshuSchemaAnnounce:' || true)"
check_announce() { # $1 = appId, $2 = required (1 = FAIL when missing), $3 = remediation hint
  line="$(printf '%s\n' "$announce_lines" | grep -F "TenshuSchemaAnnounce: $1 " | tail -1 || true)"
  if [ -z "$line" ]; then
    if [ "$2" = "1" ]; then
      echo "  FAIL: $1 never announced its schema session (§6.5 gate would refuse it)"; fail=1
    else
      echo "  WARN: $1 announced no schema session yet (no proxy call in the smoke window?)"
    fi
  elif printf '%s' "$line" | grep -q 'admitted=true'; then
    echo "  ok: $1 schema session admitted"
  else
    echo "  FAIL: $1 schema session REFUSED: $line"
    echo "        ($3)"
    fail=1
  fi
}
for pkg in $ALL_PKGS; do
  if [ "$pkg" = "$CONTROL_PKG" ]; then
    required=1
    # Control IS the proxy; a refusal here means the source tree disagrees with
    # itself, not that an install is stale.
    hint="the bracket names the service — Control's own vector disagrees with the source"
  else
    required=0
    # `current=0`/`current<announced` means Control is the stale side; the reverse
    # means this app is. Either way the fix is `installDebug`, never a rebuild of
    # the refused app alone.
    hint="stale install: reinstall BOTH via ANDROID_SERIAL=\$ANDROID_SERIAL ./gradlew :control:installDebug"
  fi
  check_announce "$(app_id_for "$pkg")" "$required" "$hint"
done

# 4.7 No structural IPC failure. Every `TResult.Failure` an app receives over IPC is
# printed by RemoteBinaryTaskExecutor as
#   `IPC Client -> Failure: <callstack ids> -> <error id>`
# where the callstack's second segment names the relation/op the proxy would not serve.
#
# A typed Failure is a legitimate channel (ops return `TResult.Failure` by design), so
# this cannot fail on every line. It fails on the STRUCTURAL error ids only:
#   tenshu/core/error/internal    InternalError — the last-resort wrapper for an
#                                 exception that escaped an op impl body. A bug by
#                                 definition, never a domain outcome.
#   tenshu/core/error/dispatch/*  missing / wrong / unknown parameter — the callstack
#                                 did not match the proxy's schema at all.
# Everything else is reported as a WARN so a real domain failure stays visible without
# turning the gate red.
#
# ⚠ A call refused `unannouncedSession` because it beat the re-announce after a Control
# restart is resent once, and logs `IPC Client -> unannounced session, re-announcing: …`
# instead of a Failure line. It is deliberately not counted. Only a refusal that survives
# the resend shows up here (as a WARN).
#
# ⚠ This is the ONLY step that catches a purely *additive* skew, and it exists because
# steps 4.5 and 4.6 structurally cannot. Adding a relation to a ServiceSchema
# (`serviceDataRelation(...)`) is correctly neither a stored-row change (no
# TENSHU_STORAGE_FORMAT_VERSION bump, so 4.5 passes) nor a per-service version change
# (so the announce is admitted and 4.6 passes) — yet it does change the set of calls a
# client may make against the proxy. Example: Timeline's Activities tab is silently
# empty against a Control missing `workoutActivityRollups`, with the same format version
# and `admitted=true` on BOTH sides; the read returns error/internal and the
# repository's `.catch { }` folds it into an empty list. No crash, no banner, no beacon
# mismatch — this line is the only signal on the device.
#
# The logcat prefix (timestamp + pid) is stripped BEFORE `sort -u` so the dedupe keys on
# the callstack — the diagnosis — rather than the timestamp. Without that, one broken
# reactive read (which re-fires on every resubscribe) fills the whole report with copies
# of itself and `head` hides the other failures behind it.
#
# LC_ALL=C: logcat carries non-UTF-8 bytes, and `tr`/`grep`/`sed` in a UTF-8 locale abort
# on them ("Illegal byte sequence"), truncating the read mid-stream.
ipc_failures="$("$ADB" logcat -d 2>/dev/null \
  | LC_ALL=C grep -a 'IPC Client -> Failure' \
  | LC_ALL=C tr -d '\r' \
  | LC_ALL=C sed 's/^.*IPC Client -> Failure: //' \
  | sort -u || true)"
if [ -z "$ipc_failures" ]; then
  echo "  ok: no IPC failures during the smoke window"
else
  structural_re='tenshu/core/error/(internal|dispatch/)'
  ipc_structural="$(printf '%s\n' "$ipc_failures" | grep -aE "$structural_re" || true)"
  ipc_other="$(printf '%s\n' "$ipc_failures" | grep -avE "$structural_re" || true)"
  if [ -n "$ipc_structural" ]; then
    echo "  FAIL: structural IPC failure — the proxy could not serve a call a client made:"
    printf '%s\n' "$ipc_structural" | head -10 | sed 's/^/    /'
    echo "        (the 2nd callstack segment names the relation/op Control did not know;"
    echo "         fix is ANDROID_SERIAL=\$ANDROID_SERIAL ./gradlew :control:installDebug)"
    fail=1
  fi
  if [ -n "$ipc_other" ]; then
    echo "  WARN: typed IPC failure(s) — a legitimate channel, but confirm they are expected:"
    printf '%s\n' "$ipc_other" | head -5 | sed 's/^/    /'
  fi
fi

# 5. No fatal crash / ANR for our apps.
# Scoped to `app.tenshu.` rather than the four built packages: every package under
# that prefix is ours, and the discovered apps (step 3.5) were just launched too,
# so a crash in one is signal the gate should not swallow.
# ⚠⚠ LC_ALL=C, same reason as the beacon pipelines above — and it matters MORE here. When `tr`
# aborts on a non-UTF-8 byte this variable comes back EMPTY, the grep below matches nothing, and the
# gate reports "no crashes" however many there were. A false alarm wastes an afternoon; a false pass
# on the crash check is the gate silently not doing its job.
crash_dump="$("$ADB" logcat -d -b crash 2>/dev/null | LC_ALL=C tr -d '\r' || true)"
if printf '%s' "$crash_dump" | grep -qiE 'app\.tenshu\.'; then
  echo "  FAIL: crash-buffer entry for a Tenshu app:"
  printf '%s\n' "$crash_dump" | grep -iE 'app\.tenshu|FATAL|Caused by' | head -20 | sed 's/^/    /'
  fail=1
fi
# ⚠ Same false-pass hazard as the crash buffer above.
if "$ADB" logcat -d 2>/dev/null | LC_ALL=C tr -d '\r' | grep -qiE 'ANR in app\.tenshu'; then
  echo "  FAIL: ANR in a Tenshu app"; fail=1
fi

# 6. Informational: the bgproc background jobs Control scheduled. Scoped to our
# package (the generic "SyncWorker" name collides with Google system apps). May be
# empty until the proxy fully inits (Drive binds + a realm is set up).
echo "==> Control's scheduled WorkManager jobs (informational; may be empty pre-pairing):"
"$ADB" shell dumpsys jobscheduler 2>/dev/null | LC_ALL=C tr -d '\r' \
  | grep -iE '^ *JOB .*app\.tenshu\.control\.android' | sed 's/^ */    /' | head || true

if [ "$fail" -ne 0 ]; then
  echo "SMOKE GATE: FAILED"
  exit 1
fi
echo "SMOKE GATE: PASSED"
exit 0
