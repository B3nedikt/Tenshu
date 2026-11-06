#!/usr/bin/env bash
#
# Run the web gate so that a green result MEANS something — the `scripts/run-gate.sh` idea,
# applied to the lanes that gate `web/` and `:tenshu-sdk-js`.
#
# ⚠⚠ Why this exists, and it is the same trap as the JVM gate. `./gradlew :tenshu-sdk-js:jsTest`
# run a second time reports BUILD SUCCESSFUL having executed **zero** test tasks — measured
# 2026-08-28, a re-run printed BUILD SUCCESSFUL in 12s with every task UP-TO-DATE. So this
# deletes the results dir first and REFUSES to report success unless test cases actually ran.
#
# ⚠⚠ The lanes are not interchangeable, and none of them is "the" gate:
#   sdk       — :tenshu-sdk-js:jsTest. Real Kotlin runtime in headless Chrome. The ONLY lane
#               that exercises the in-process runtime; the only place a boundary-contract test
#               can live (docs/testing.md §9.3).
#   contracts — the same lane, but named separately because it is the one that was missing:
#               nothing asserted a listing row's shape from Phase C to 2026-08-28 and the whole
#               0 B family shipped behind green suites.
#   web       — vitest/jsdom component tests for apex + every app package (`web_apps` below).
#   build     — tsc + vite. Catches contract drift between web/sdk types and their consumers,
#               which no test does.
#
# ⚠ What NO lane here covers: browser policy (background-tab throttling, storage eviction),
# cross-device sync, and anything needing a relay. Those need a device pass — see
# docs/testing.md §9.5.
#
# Usage:
#   scripts/run-web-gate.sh          # all lanes
#   scripts/run-web-gate.sh sdk      # one lane
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT" || exit 1
LANE="${1:-all}"
FAILED=0
RESULTS="tenshu-sdk-js/build/test-results/jsBrowserTest"

run_sdk() {
  echo "── sdk: :tenshu-sdk-js:jsTest ─────────────────────────────────────────"
  rm -rf "$RESULTS"
  ./gradlew :tenshu-sdk-js:jsTest --rerun-tasks || { echo "FAIL: jsTest"; FAILED=1; return; }
  local cases
  cases=$(grep -ch "<testcase" "$RESULTS"/*.xml 2>/dev/null | awk '{s+=$1} END {print s+0}')
  if [ "$cases" -lt 30 ]; then
    echo "FAIL: only $cases test cases recorded — a green here would not mean anything"
    FAILED=1
  else
    echo "ok: $cases test cases actually executed"
  fi
}

# The app packages: apex FIRST (the host every app dials, and the longest suite — a red apex is
# the most useful thing to see early), then every other `web/<app>` with a Vite config, sorted.
# ⚠ Iterated rather than named, so a new app (web-app-suite.md F4) joins the gate by existing:
# a hard-coded list is how `web/drive` went unlinted and untested in CI until its third phase.
# A package without `vite.config.ts` (sdk, shared, store-frame, app-kit) is a library, not an app.
# The discovery rule is deploy/lib/web-apps.sh's — the one the image build and CI use too.
# shellcheck source=deploy/lib/web-apps.sh
source "$REPO_ROOT/deploy/lib/web-apps.sh"
web_apps() {
  echo apex
  web_app_ids
}

# The shared app-kit (web-app-suite.md F3) is not an app, but every app runs on it, so its own
# suite — including the guard that it reaches no `@tenshu/sdk` value but `/bridge` — is in the gate.
APP_KIT="@tenshu/app-kit"
# The shared map renderer (web-app-suite.md W-MAP) is a library too (no vite.config.ts at its root, so
# the app discovery above skips it), and its suite holds the lazy-MapLibre split and the no-foreign-URL
# style guard — both of which every map app relies on.
MAP_KIT="@tenshu/map-kit"

run_web() {
  echo "── web: component tests — $(web_apps | tr '\n' ' ')app-kit map-kit ─────"
  local app
  for app in $(web_apps); do
    (cd web && pnpm -F "$app" test) || { echo "FAIL: $app tests"; FAILED=1; }
  done
  (cd web && pnpm -F "$APP_KIT" test) || { echo "FAIL: app-kit tests"; FAILED=1; }
  (cd web && pnpm -F "$MAP_KIT" test) || { echo "FAIL: map-kit tests"; FAILED=1; }
}

run_build() {
  echo "── build: tsc + vite (the only lane that sees TS contract drift) ──────"
  # ⚠⚠ Builds into a scratch outDir, NEVER `web/*/dist`. The first version of this lane ran
  # `pnpm -F apex build`, which overwrites the dist a local `vite preview` is serving — and
  # rebuilds it WITHOUT whatever `VITE_*` overrides that preview needs. On 2026-08-28 that
  # silently repointed a running local apex at the PRODUCTION relay three separate times, which
  # CORS-refuses a localhost origin: pairing failed with `tenshu/core/error/internal`, sync went
  # dead, and each time the code was perfectly correct. A gate must not be able to break the
  # environment it is run in.
  local out app
  out="$(mktemp -d)/gate-dist"
  for app in $(web_apps); do
    (cd "web/$app" && npx tsc -b && npx vite build --outDir "$out/$app" >/dev/null) \
      || { echo "FAIL: $app build"; FAILED=1; }
  done
  (cd web/app-kit && npx tsc -b) || { echo "FAIL: app-kit typecheck"; FAILED=1; }
  (cd web/map-kit && npx tsc -b) || { echo "FAIL: map-kit typecheck"; FAILED=1; }
  rm -rf "$out"
}

case "$LANE" in
  sdk) run_sdk ;;
  web) run_web ;;
  build) run_build ;;
  all) run_sdk; run_web; run_build ;;
  *) echo "unknown lane: $LANE (sdk|web|build|all)"; exit 2 ;;
esac

if [ "$FAILED" -ne 0 ]; then
  echo; echo "WEB GATE RED"; exit 1
fi
echo; echo "WEB GATE GREEN — ⚠ covers no browser policy, no cross-device sync, no relay."
