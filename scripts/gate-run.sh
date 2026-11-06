#!/usr/bin/env bash
#
# Run the gate and KEEP the result.
#
# `docs/tech-debt.md` § "The full gate is flaky under parallel load" records ~20
# rotating victims, and almost every one of them cost a full gate run to observe
# a second time — because the evidence is destroyed by construction:
#
#   1. Gradle overwrites `build/test-results/**` on the next run, so the message
#      is gone the moment you re-run to check. Two victims are recorded in that
#      entry with "message not captured" for exactly this reason.
#   2. A task that is UP-TO-DATE writes no XML at all, so a scan of the results
#      tree reports a class GREEN that never ran — and a targeted module re-run
#      makes the next full gate skip the very test you were verifying ("BUILD
#      SUCCESSFUL in 5s" taken twice in one day as a re-verification).
#   3. A stale XML from an earlier (possibly deliberately-failed) run is read as
#      a result of *this* one.
#   4. `./gradlew … > log 2>&1; echo "EXIT=$?"` reports the **echo's** status, so
#      a wrapper sees 0 while Gradle underneath is red.
#
# This script closes all four, and appends one JSON record per run to an
# append-only history so "which test, how often" becomes a query instead of an
# investigation. That matters more than any single fix: the entry's own repeated
# lesson is that its conclusions kept being drawn from 3, 6 or 16 runs, and were
# twice wrong at that sample size (Fisher p ≈ 0.24–0.26 for a "clean" arm). A
# denominator that accumulates over weeks is the only way out of that.
#
# Usage:
#   scripts/gate-run.sh                        # one full gate, harvested
#   scripts/gate-run.sh --repeat 10            # ten, for a rate
#   scripts/gate-run.sh --label pre-fix        # tag the runs for a before/after
#   scripts/gate-run.sh -- :control-shared:desktopTest --rerun   # a module only
#   scripts/gate-report.py                     # read what accumulated
#
# Env:
#   TENSHU_GATE_HISTORY  history file (default .tenshu-gate/history.jsonl)
#   TENSHU_GATE_GRADLE   gradle command (default ./gradlew)
#
# Exit: 0 only if EVERY iteration built successfully. A red iteration does not
#       stop the loop — the point is to collect a rate, so `--repeat 10` runs ten
#       times whatever happens and the summary tells you how many were red.

set -uo pipefail

# Queue behind any other gate or smoke run on this machine — see the file for why.
# shellcheck source=scripts/lib/gate-lock.sh
. "$(dirname "${BASH_SOURCE[0]}")/lib/gate-lock.sh"
tenshu_gate_lock "$0" "$@"

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

HISTORY="${TENSHU_GATE_HISTORY:-$REPO_ROOT/.tenshu-gate/history.jsonl}"
GRADLE="${TENSHU_GATE_GRADLE:-$REPO_ROOT/gradlew}"
LOG_DIR="$REPO_ROOT/.tenshu-gate/logs"

# The documented gate. `test` alone matches ZERO KMP test tasks (AGENTS.md
# § Build & test), so naming desktopTest and jvmTest is not optional.
# `--rerun` forces the named tasks to execute even when UP-TO-DATE — trap 2.
# `--continue` is what makes a run report ALL its failures instead of stopping
# at the first; the entry's "one test per run" title turned out to be an
# artifact of under-sampling, and two clean runs later reported 2 and 3.
DEFAULT_ARGS=(desktopTest jvmTest test --rerun --continue)

repeat=1
label=""
gradle_args=()

while [[ $# -gt 0 ]]; do
  case "$1" in
    --repeat) repeat="$2"; shift 2 ;;
    --label)  label="$2";  shift 2 ;;
    --history) HISTORY="$2"; shift 2 ;;
    --) shift; gradle_args=("$@"); break ;;
    -h|--help) sed -n '2,50p' "$0"; exit 0 ;;
    *) echo "gate-run.sh: unknown option $1 (did you mean '-- $1'?)" >&2; exit 2 ;;
  esac
done

if [[ ${#gradle_args[@]} -eq 0 ]]; then
  gradle_args=("${DEFAULT_ARGS[@]}")
fi

# `:macos-helpers:test` fails deterministically off macOS by design
# (docs/tech-debt.md § "MacDatalessProbeTest"), so on any other host it is a
# guaranteed red that would drown the signal we are here to measure.
if [[ "$(uname -s)" != "Darwin" ]]; then
  gradle_args+=(-x :macos-helpers:test)
fi

mkdir -p "$LOG_DIR" "$(dirname "$HISTORY")"

commit="$(git rev-parse HEAD 2>/dev/null || echo unknown)"
dirty=false
[[ -n "$(git status --porcelain 2>/dev/null)" ]] && dirty=true

overall=0
red_runs=0

for ((i = 1; i <= repeat; i++)); do
  run_id="$(date -u +%Y%m%dT%H%M%SZ)-$$-$i"
  log="$LOG_DIR/$run_id.log"

  # Trap 1+3: every stale XML goes before the run, so what is on disk afterwards
  # is this run's output and nothing else. A task that does not execute now
  # leaves a visible hole rather than someone else's green.
  stale="$(find . -path '*/build/test-results/*' -name '*.xml' -print -delete 2>/dev/null | wc -l | tr -d ' ')"

  echo "── gate-run $i/$repeat  ($run_id)  cleared $stale stale XML"
  echo "   $GRADLE ${gradle_args[*]}"

  # Trap 5, learned by walking into it: editing a source file WHILE a measurement
  # loop runs makes your own change look like a flake sighting. `docs/tech-debt.md`
  # records it once ("a run 12 RED in those logs is my own mutation, not a flake
  # sighting") and it happened again on 2026-08-15, costing six phantom victims in
  # one run. A fingerprint of the tracked tree before and after is enough to catch
  # it, and it costs one `git status` per run.
  tree_before="$(git status --porcelain 2>/dev/null | sha256sum | cut -d' ' -f1)"

  started="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  start_s=$SECONDS
  "$GRADLE" "${gradle_args[@]}" 2>&1 | tee "$log"
  # Trap 4: the pipe's exit status is `tee`'s. Read Gradle's own.
  status=${PIPESTATUS[0]}
  elapsed=$((SECONDS - start_s))
  finished="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

  # ...and corroborate it against what Gradle SAID, because the two disagreeing
  # is itself worth recording (a harness that mangles the status is a bug we
  # would otherwise attribute to a test).
  build_result=unknown
  grep -q '^BUILD SUCCESSFUL' "$log" && build_result=successful
  grep -q '^BUILD FAILED' "$log" && build_result=failed

  tree_after="$(git status --porcelain 2>/dev/null | sha256sum | cut -d' ' -f1)"
  tree_changed=false
  if [[ "$tree_before" != "$tree_after" ]]; then
    tree_changed=true
    echo "   ⚠⚠ the working tree CHANGED during this run — its failures may be yours" >&2
  fi

  python3 "$REPO_ROOT/scripts/gate-harvest.py" \
    --history "$HISTORY" \
    --tree-changed "$tree_changed" \
    --run-id "$run_id" \
    --label "$label" \
    --commit "$commit" \
    --dirty "$dirty" \
    --started "$started" \
    --finished "$finished" \
    --duration "$elapsed" \
    --exit-code "$status" \
    --build-result "$build_result" \
    --log "$log" \
    --gradle-args "${gradle_args[*]}"
  harvest=$?

  if [[ $status -ne 0 || $build_result == failed ]]; then
    red_runs=$((red_runs + 1))
    overall=1
  fi
  if [[ $harvest -ne 0 ]]; then
    echo "   ⚠ harvest failed — this run is NOT in the history" >&2
    overall=1
  fi
done

echo
echo "── $repeat run(s), $red_runs red. History: $HISTORY"
echo "   scripts/gate-report.py    # rates, victims, co-occurrence"
exit $overall
