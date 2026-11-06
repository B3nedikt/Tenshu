#!/usr/bin/env bash
#
# Run the JVM/desktop/Android gate so that a green result MEANS something.
#
# ⚠⚠ Why this exists. `./gradlew desktopTest jvmTest test` run a second time reports
# `BUILD SUCCESSFUL` having executed **zero** test tasks — every one is UP-TO-DATE from the run
# before. Measured on clean master: `1907 actionable tasks: 180 executed, 1727
# up-to-date`, of which **0** were test tasks and 92 were skipped. The line still says
# "1907 actionable tasks", so nothing about the output looks unusual.
#
# That turns the most natural verification habit — "it failed, let me re-run it" — into a
# guaranteed green, because a task you made pass in a targeted module run is UP-TO-DATE too
# (docs/tech-debt.md, "The full gate is flaky under parallel load").
#
# So: delete every test-results dir first, then REFUSE to report success unless enough test tasks
# actually ran.
#
# It also runs the Kotlin/JS TEST line (which the test line above cannot reach) and compiles the two
# lanes it cannot — Kotlin/JS and Kotlin/Native — because
# a gate that silently covers three of five targets is the same class of lie as an UP-TO-DATE test.
#
# Usage:
#   scripts/run-gate.sh            # one gate run + both compile lanes
#   scripts/run-gate.sh 3          # three test runs (the lanes still run once — they are compiles)
#
# Env:
#   TENSHU_GATE_SKIP_LANES=1       # tests only. Prints what it did NOT verify; never silent.
#   TENSHU_GATE_ACCEPT_MODULE_LOSS=1
#   TENSHU_GATE_NO_LOCK=1          # do not queue behind other gates (scripts/lib/gate-lock.sh)
#
# A test task that hangs is stopped by the build, not by this script: every `Test` task has a
# 20-minute limit (root build.gradle.kts, `-Ptenshu.testTaskTimeoutMinutes=N`), and a task that
# fails that way — or because its worker was killed or crashed — is named below even though it
# leaves no failing test in its results.
#
# ⚠ Still NOT covered: **Swift**. Nothing here compiles a line of it — `scripts/ios-smoke.sh
# --build-only <app>` is the only thing that does. Nor the iOS *test suite* (`iosTestCheck`), which
# carries a known red.

set -uo pipefail

# Queue behind any other gate or smoke run on this machine — see the file for why.
# shellcheck source=scripts/lib/gate-lock.sh
. "$(dirname "${BASH_SOURCE[0]}")/lib/gate-lock.sh"
tenshu_gate_lock "$0" "$@"

RUNS="${1:-1}"
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT" || exit 1

# A full gate runs ~97 test tasks. Anything far below that means Gradle skipped the suite.
MIN_TEST_TASKS=50

OVERALL=0

# ── The JS TEST line — not a lane ────────────────────────────────────────────────────────────────
#
# ⚠⚠ `jsTest` is a TEST task, so the UP-TO-DATE asymmetry that lets the compile lanes skip an
# executed-task floor does NOT apply to it: an up-to-date test task verified nothing this run. It
# therefore gets its own floor and refuses to report green on zero executions, exactly like the main
# line above.
#
# ⚠⚠ It runs INSIDE the `RUNS` loop, once per iteration, and that is the whole reason the N-run
# mode is worth anything for JS: `run-gate.sh 3` exists to hunt *intermittent tests*, and a test
# line executed once regardless of N is precisely the one place an intermittent can hide from it.
# The compile lanes stay outside the loop for the opposite and equally deliberate reason (see their
# banner) — a compile is deterministic, a test is not.
#
# ⚠ Ordering within an iteration is load-bearing: it runs AFTER the module-loss ledger has been
# parsed and written, so its JUnit XML never lands in the `*/build/test-results/*` glob that ledger
# is built from. The next iteration's `find -prune -exec rm -rf` then clears it before the following
# parse, so no iteration ever sees the previous one's JS results.
#
# ⚠⚠ Three modules are EXCLUDED because they were red when the lane was first run. They are named
# here rather than hidden, and tracked in docs/tech-debt.md ("Three modules are excluded from the
# JS test line"): excluding a module is a latch, and a latch nobody can see becomes permanent. Take one off this list as it goes green.
JS_TEST_EXCLUSIONS="basemap-core inference-service-proxy routine-service-proxy"

run_js_tests() {
  local log status executed args
  log="$(mktemp -t tenshu-jstest.XXXXXX)"
  args=""
  for m in $JS_TEST_EXCLUSIONS; do args="$args -x :$m:jsTest"; done
  # shellcheck disable=SC2086
  ./gradlew jsTest --continue $args > "$log" 2>&1
  status=$?
  # ⚠⚠ A misspelt exclusion is a HARD Gradle failure ("Cannot locate excluded tasks that match
  # ':x:jsTest' as project 'x' not found"), not a silent no-op — and it looks identical to a red
  # suite in the summary above. The first version of this list used a package name
  # (`routine-proxy`) where the Gradle path is `routine-service-proxy`, and the whole line reported
  # RED having run nothing. Say which it was.
  if grep -q "Cannot locate excluded tasks" "$log"; then
    echo "  RED  JS tests   an entry in JS_TEST_EXCLUSIONS names no such project — fix the list, not the suite:"
    grep -E "Cannot locate excluded tasks" "$log" | head -3 | sed 's/^/         /'
    OVERALL=1
    return
  fi
  # ⚠ `$` anchored: Gradle prints "> Task :x:jsTest" when it RAN and "> Task :x:jsTest UP-TO-DATE"
  # when it did not, so the anchor is what separates verification from a stale pass.
  executed=$(grep -cE "^> Task :.*:jsTest$" "$log")
  if [ "$status" -ne 0 ]; then
    echo "  RED  JS tests   (log: $log)"
    grep -E "FAILED$" "$log" | sort -u | head -20 | sed 's/^/         /'
    OVERALL=1
  elif [ "$executed" -lt 1 ]; then
    echo "  RED  JS tests   executed ZERO jsTest tasks — refusing to report green (log: $log)"
    OVERALL=1
  else
    echo "  JS tests: green ($executed module(s) ran)"
  fi
  echo "     ⚠ NOT covered, known red since before this lane was enforced: $JS_TEST_EXCLUSIONS"
}


for i in $(seq 1 "$RUNS"); do
  echo "=== gate run $i/$RUNS ==="

  # Stale XMLs read as results. A deliberately-failed run from an hour ago leaves its report on
  # disk, and an UP-TO-DATE task writes no new one — so without this, a class can be reported red
  # having never run, or green having been skipped.
  find . -type d -path "*/build/test-results" -prune -exec rm -rf {} + 2>/dev/null

  # ⚠ The template needs its X's: BSD mktemp treats `-t PREFIX` as a prefix and fills in the
  # random part itself, GNU mktemp treats the argument as a template and refuses one with fewer
  # than three X's ("too few X's in template"). Without them the script runs the whole gate with LOG
  # unset, counts zero test tasks and prints "all green" — a false pass, which is the one thing this
  # script exists to prevent.
  LOG="$(mktemp -t tenshu-gate.XXXXXX)"
  # ⚠ `--continue` is not optional here. Without it the FIRST failing module aborts the
  # rest, so a run that should have executed ~97 test tasks stops at 20 — and since this
  # repo's gate has several independent intermittent failures, you would see one of them
  # and learn nothing about the other 77 tasks. It also makes "which tests are red" a
  # complete answer rather than "the first one Gradle happened to reach".
  # ⚠⚠ `testAndroidHostTest` is NOT reachable from `test`. Without it, a widened
  # `IClientInterface.aidl` that breaks the anonymous stubs in `AndroidClientConnectionTest` (an
  # `androidHostTest` source set) leaves every gate green, because nothing in this line compiles them.
  # 12 modules have such a source set. Same family as `test` missing every KMP module, and as the
  # JS/iOS lanes this line cannot reach — which is what the compile lanes below close.
  ./gradlew desktopTest jvmTest test testAndroidHostTest --continue > "$LOG" 2>&1
  STATUS=$?

  EXECUTED=$(grep -cE "^> Task :[a-zA-Z0-9_-]+:(desktopTest|jvmTest|testDebugUnitTest|testAndroidHostTest|test)$" "$LOG")
  SKIPPED=$(grep -cE "^> Task :[a-zA-Z0-9_-]+:(desktopTest|jvmTest|testDebugUnitTest|testAndroidHostTest|test) UP-TO-DATE$" "$LOG")
  echo "  test tasks: executed=$EXECUTED up-to-date=$SKIPPED   (log: $LOG)"

  # ⚠⚠ An `else`, NOT a `continue`. The JS line below has to be reachable on EVERY iteration:
  # `--continue` exists so one red does not hide the others, and skipping past the JS suite because
  # the JVM pass fell short does exactly that — it hides independent JS failures behind a native one
  # and makes `run-gate.sh 3` perform fewer than three JS passes, or none. Both halves of the gate
  # report; neither gates the other.
  if [ "$EXECUTED" -lt "$MIN_TEST_TASKS" ]; then
    echo "  ⚠⚠ ONLY $EXECUTED test tasks executed (expected >= $MIN_TEST_TASKS)."
    echo "     This run certifies NOTHING, whatever Gradle's exit code says."
    OVERALL=1
  else

  python3 - "$LOG" <<'PY'
import glob, sys, xml.etree.ElementTree as ET

rows, classes = [], 0
for path in glob.glob("*/build/test-results/*/TEST-*.xml"):
    classes += 1
    try:
        root = ET.parse(path).getroot()
    except Exception:
        continue
    if int(root.get("failures") or 0) + int(root.get("errors") or 0) == 0:
        continue
    module = path.split("/")[0]
    for case in root.findall("testcase"):
        failure = case.find("failure")
        if failure is None:
            failure = case.find("error")
        if failure is not None:
            message = (failure.get("message") or "").split("\n")[0][:140]
            rows.append(f"  RED  {module} :: {case.get('name')} — {message}")

# ── A test TASK that failed with no failing test to show for it ──────────────────────────────
#
# ⚠⚠ Everything above is read off JUnit XML, and a test process that dies — killed, crashed, out
# of memory, or stopped by the task timeout after a test hung — writes no XML for the class it was
# in. Gradle fails the task, the classes that finished still report green, and nothing above names
# it. A deadlocked test whose worker is killed leaves a run that lists the unrelated reds and not a
# word about the module that never finished; the only trace is a lower class count.
import re

failed_tasks = re.findall(
    r"^> Task :([a-zA-Z0-9_-]+):(desktopTest|jvmTest|testDebugUnitTest|testAndroidHostTest|test) FAILED$",
    open(sys.argv[1], errors="replace").read(),
    re.M,
)
log_text = open(sys.argv[1], errors="replace").read()
for module, task in sorted(set(failed_tasks)):
    has_failing_case = False
    for path in glob.glob(f"{module}/build/test-results/{task}/TEST-*.xml"):
        try:
            root = ET.parse(path).getroot()
        except Exception:
            continue
        if int(root.get("failures") or 0) + int(root.get("errors") or 0) > 0:
            has_failing_case = True
            break
    if has_failing_case:
        continue
    why = re.search(
        rf"Execution failed for task ':{re.escape(module)}:{task}'[^\n]*\n(?:>?[^\n]*\n)?\s*> ([^\n]+)", log_text
    )
    reason = why.group(1).strip()[:150] if why else "no reason found in the log"
    rows.append(
        f"  RED  {module} :: {task} FAILED with NO failing test in its results — "
        f"a test process died, was killed or timed out: {reason}"
    )

print(f"  test classes with fresh results: {classes}")
if rows:
    print("\n".join(sorted(set(rows))))

# ── A module that had tests last run and has none now ────────────────────────────────────────
#
# ⚠⚠  THE failure this catches: widen a signature, an implementor or call site stops compiling,
# and that module's test task never runs — so it writes NO JUnit XML and the gate reports "all
# green" over a smaller suite. Green is true and meaningless; without this, the class count is
# the only signal.
#
# ⚠ Deliberately NOT a count threshold. Counts drift legitimately between branches, and a check
# that cries wolf gets ignored — which is worse than no check. "A module that produced results
# before produces none now" is exact: it has one cause, and that cause is always a defect.
#
# Escape hatch for a legitimate removal: TENSHU_GATE_ACCEPT_MODULE_LOSS=1
import os, pathlib

seen = sorted({q.split("/")[0] for q in glob.glob("*/build/test-results/*/TEST-*.xml")})
ledger = pathlib.Path("build/gate-modules.txt")
previous = sorted(ledger.read_text().split()) if ledger.exists() else []

lost = [m for m in previous if m not in seen]
if lost and not os.environ.get("TENSHU_GATE_ACCEPT_MODULE_LOSS"):
    print("  !! %d module(s) produced test results last run and NONE now:" % len(lost))
    for m in lost:
        print("       " + m)
    print("     A non-compiling test source set writes no JUnit XML, so the run above")
    print("     certifies LESS than the last one, however green it looks. Check it compiles:")
    print("       ./gradlew :<module>:compileTestKotlinJvm   (or :compileTestKotlinDesktop)")
    print("     Removed on purpose? TENSHU_GATE_ACCEPT_MODULE_LOSS=1 scripts/run-gate.sh")
    sys.exit(3)

ledger.parent.mkdir(parents=True, exist_ok=True)
ledger.write_text("\n".join(seen) + "\n")

if not rows:
    print("  all green")
PY
  GATE_STATUS=$?
  [ "$GATE_STATUS" -eq 3 ] && OVERALL=1

  [ "$STATUS" -ne 0 ] && OVERALL=1
  fi

  # Reached on every iteration, including one whose JVM pass fell short — see the floor above.
  # Gated by the same switch as the compile lanes, so TENSHU_GATE_SKIP_LANES keeps meaning exactly
  # what its message below claims it means.
  if [ -z "${TENSHU_GATE_SKIP_LANES:-}" ]; then
    echo "  === JS test line (run $i/$RUNS) ==="
    run_js_tests
  fi
done

# ── The compile lanes: Kotlin/JS and Kotlin/Native ───────────────────────────────────────────────
#
# ⚠⚠ Neither is reachable from the test line above, which builds JVM / desktop / Android only, so a
# break in either passes that line. The commonest is a JVM-only stdlib extension (`putIfAbsent`,
# `toSortedMap`) called from `commonMain`, which compiles for JVM/Android and for nothing else.
#
# ⚠ `iosKotlinCheck` is NOT what runs here, deliberately. It compiles the `:tenshu-sdk-ios-*` app
# framework modules + `:ui-core`, so it reaches only what those depend on, and its own KDoc calls itself "a heuristic, not a proof".
# `:photos-shared-logic` has iOS targets that no iOS binary links, so it never compiles it for
# K/N. The repo-wide task below has no such blind spot.
#
# ⚠ Run ONCE, outside the loop: these are deterministic compiles, and the N-run mode exists for
# hunting intermittent *tests*. Tripling a two-minute K/N compile would buy nothing.
#
# ⚠⚠ UP-TO-DATE is not a lie here, unlike everywhere above. A test task that is up-to-date verified
# nothing this run; a COMPILE task that is up-to-date has a successful output for exactly these
# inputs, which IS the verification. That asymmetry is why the lanes need no executed-task floor —
# do not "fix" this by adding one.
run_lane() {
  local name="$1" task="$2"
  local log
  log="$(mktemp -t "tenshu-lane.XXXXXX")"
  # ⚠ `--continue` or Gradle stops at the first failing module — that is how five separate breaks
  # read as one, and fixing the reported one left master red looking like the fix had failed.
  ./gradlew "$task" --continue > "$log" 2>&1
  if [ $? -ne 0 ]; then
    echo "  RED  $name lane   (log: $log)"
    grep -E "^e: " "$log" | sort -u | head -20 | sed 's/^/         /'
    OVERALL=1
  else
    echo "  $name lane: green"
  fi
}

if [ -n "${TENSHU_GATE_SKIP_LANES:-}" ]; then
  echo "=== compile lanes: SKIPPED (TENSHU_GATE_SKIP_LANES) ==="
  echo "  ⚠⚠ This run verifies NO Kotlin/JS and NO Kotlin/Native, and runs NO JS tests. Do not cite"
  echo "     it for a change to any commonMain that a JS or iOS target compiles."
else
  echo "=== compile lanes ==="
  run_lane "JS " compileTestKotlinJs

  # ⚠⚠ macOS ONLY, and it must SAY so rather than pass. K/N will not cross-compile an Apple target,
  # so off macOS every task is SKIPPED and Gradle reports BUILD SUCCESSFUL having compiled nothing —
  # the false green `iosKotlinCheck` refuses. This does not fail the gate (that would
  # make it unrunnable on the Linux agents), so the honest half is that the line is unmissable.
  if [ "$(uname -s)" = "Darwin" ]; then
    run_lane "iOS" compileTestKotlinIosSimulatorArm64
  else
    echo "  ⚠⚠ iOS lane NOT VERIFIED on $(uname -s) — K/N cannot cross-compile an Apple target, so"
    echo "     every task would be SKIPPED and report success. An iosMain/commonMain change verified"
    echo "     here is UNVERIFIED for iOS; say so rather than citing this gate."
  fi

fi

exit "$OVERALL"
