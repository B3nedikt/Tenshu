# shellcheck shell=bash
#
# One heavy build script at a time on this machine. Source it, then call
# `tenshu_gate_lock "$0" "$@"` before the script does any work.
#
# WHY THIS EXISTS. The working style here is one session per worktree, and each session runs its
# own gate. A gate is a ~5 GiB Gradle daemon, a ~4 GiB Kotlin daemon and up to six test JVMs, so
# three of them at once is more RAM than the box has: it swaps, the count/timing assertions start
# failing for reasons no diff explains (docs/tech-debt.md, "The full gate is flaky under parallel
# load"), and the harness kills whichever run it reaches first. Overlapping gates are therefore
# slower than queued ones AND less true.
#
# ⚠⚠ The lock file lives OUTSIDE every checkout, beside `tenshu-native-slots`, for the reason given
# there: a lock under a project root gives each worktree its own lock and looks fixed while
# behaving identically.
#
# ⚠⚠ It is taken by RE-EXECUTING the script under `lockf`/`flock`, not by holding a descriptor in
# this shell. `./gradlew` leaves a daemon behind that inherits every open descriptor and idles for
# 30 minutes, so a lock held on an fd of the script would outlive the script by half an hour. Both
# tools are told to keep the lock out of the command they run (`lockf` closes it itself, `flock`
# needs `-o`).
#
# ⚠ The kernel owns the lock, so a killed gate releases it — there is no stale-lock state and
# nothing to clean up by hand. The `.owner` file beside it is only what a waiting run prints; it
# is never read to decide anything.
#
# ⚠ Only as good as the least-rebased worktree: a checkout that predates this file does not queue.
# And it covers the scripts that source it, not a bare `./gradlew :module:jvmTest`.
#
# Env:
#   TENSHU_GATE_NO_LOCK=1    # run now, alongside whatever holds the lock. Says so; never silent.

# True when a process above us already holds [lock] — a caller that wrapped this script in
# `lockf -k <lock> script` (or `flock <lock> script`) by hand instead of letting it lock itself.
#
# ⚠⚠ Without this, the script re-executes under a SECOND `lockf` on the same file, which waits on
# its own parent forever. Observed 2026-10-23: `lockf -k ~/.gradle/tenshu-gate.lock
# scripts/ios-smoke.sh …` from a session's shell sat 1 h 38 min, with ten gates queued behind it.
# The kernel cannot see the cycle: the lock belongs to the parent's descriptor, so the child is just
# another waiter.
_tenshu_gate_ancestor_holds() {
  local lock="$1" pid="$PPID" cmd tool w i shared
  local -a words
  while [ -n "$pid" ] && [ "$pid" -ge 1 ] 2>/dev/null; do
    cmd=$(ps -o command= -p "$pid" 2>/dev/null) || return 1
    read -r -a words <<< "$cmd"
    tool="${words[0]##*/}"   # `/usr/bin/lockf` is lockf too
    if [ "$tool" = lockf ] || [ "$tool" = flock ]; then
      # The lock file is the first word after the tool that is not an option (or an option's value).
      shared=0
      i=1
      while [ "$i" -lt "${#words[@]}" ]; do
        w="${words[$i]}"
        case "$tool:$w" in
          flock:-s | flock:--shared) shared=1 ;;                  # a SHARED hold is not ours to borrow
          lockf:-t | flock:-w | flock:--timeout | flock:-E | flock:--conflict-exit-code) i=$((i + 1)) ;;
          *:-*) ;;
          *)
            # `-ef`: the same file, however it is spelled (symlinked dirs, `//`). ⚠ A RELATIVE path is
            # resolved against OUR cwd, not the ancestor's; the scripts' own callers pass absolute ones.
            if [ "$shared" = 0 ] && [ "$w" -ef "$lock" ]; then return 0; fi
            break
            ;;
        esac
        i=$((i + 1))
      done
    fi
    [ "$pid" -eq 1 ] && break
    pid=$(ps -o ppid= -p "$pid" 2>/dev/null | tr -d ' ')
  done
  return 1
}

tenshu_gate_lock() {
  local script="$1"
  shift

  local lock="${TENSHU_GATE_LOCK_FILE:-$HOME/.gradle/tenshu-gate.lock}"

  if [ -n "${TENSHU_GATE_LOCK_HELD:-}" ]; then
    # The re-executed copy, or a script started by one: the lock is already ours.
    echo "pid $$ · $(basename "$script") · $PWD · since $(date '+%H:%M:%S')" > "$lock.owner" 2>/dev/null
    return 0
  fi

  if [ -n "${TENSHU_GATE_NO_LOCK:-}" ]; then
    echo "⚠⚠ TENSHU_GATE_NO_LOCK: not queueing behind other gates on this machine. Under load the"
    echo "   count/timing assertions fail on their own — do not read a red from this run as yours."
    return 0
  fi

  if _tenshu_gate_ancestor_holds "$lock"; then
    # ⚠ Borrowed, so it lasts only as long as that caller: a gate it backgrounds and abandons runs on
    # unlocked once the caller exits — the same hole an inherited TENSHU_GATE_LOCK_HELD has.
    export TENSHU_GATE_LOCK_HELD=1
    echo "=== gate lock already held by a caller above this script ($lock) — not taking it twice ==="
    echo "pid $$ · $(basename "$script") · $PWD · since $(date '+%H:%M:%S') (via caller)" > "$lock.owner" 2>/dev/null
    return 0
  fi

  mkdir -p "$(dirname "$lock")"

  local -a hold probe
  if command -v lockf >/dev/null 2>&1; then
    hold=(lockf -k "$lock")
    probe=(lockf -k -s -t 0 "$lock" true)
  elif command -v flock >/dev/null 2>&1; then
    hold=(flock -o "$lock")
    probe=(flock -n "$lock" true)
  else
    echo "⚠⚠ neither lockf nor flock on PATH — running WITHOUT the machine-wide gate lock."
    return 0
  fi

  # Only so a queued run says why it is silent; the real acquisition is the exec below, and losing
  # the race between the two costs nothing but this message.
  if ! "${probe[@]}" 2>/dev/null; then
    echo "=== another gate holds $lock — queued behind it ==="
    echo "    holder: $(cat "$lock.owner" 2>/dev/null || echo unknown)"
    echo "    (TENSHU_GATE_NO_LOCK=1 runs alongside it instead)"
  fi

  exec "${hold[@]}" env TENSHU_GATE_LOCK_HELD=1 "$script" "$@"
}
