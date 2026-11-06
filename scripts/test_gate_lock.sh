#!/usr/bin/env bash
# Tests for scripts/lib/gate-lock.sh. Every case runs under a timeout, so a regression to the
# self-deadlock fails here instead of hanging. Run: bash scripts/test_gate_lock.sh
# Uses a temp lock file only — never ~/.gradle/tenshu-gate.lock, which other sessions queue on.
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WORK="$(mktemp -d)"
LOCK="$WORK/gate.lock"
OTHER="$WORK/other.lock"
: > "$LOCK"; : > "$OTHER"
export TENSHU_GATE_LOCK_FILE="$LOCK"
unset TENSHU_GATE_LOCK_HELD TENSHU_GATE_NO_LOCK

if command -v lockf >/dev/null 2>&1; then
  TOOL=lockf; HOLD=(lockf -k); HOLD_BARE=(lockf); PROBE=(lockf -k -s -t 0)
else
  TOOL=flock; HOLD=(flock -o); HOLD_BARE=(flock); PROBE=(flock -n)
fi
TOOL_PATH="$(command -v "$TOOL")"

holders=()
cleanup() { for p in "${holders[@]:-}"; do [ -n "$p" ] && kill "$p" 2>/dev/null; done; rm -rf "$WORK"; }
trap cleanup EXIT

# A script that takes the gate lock the way the real ones do, then reports.
cat > "$WORK/gate.sh" <<EOS
#!/usr/bin/env bash
. "$HERE/lib/gate-lock.sh"
tenshu_gate_lock "\$0" "\$@"
echo "ran \$1 held=\${TENSHU_GATE_LOCK_HELD:-}"
EOS
chmod +x "$WORK/gate.sh"

fails=0
# within <seconds> cmd…: run it with a timeout; output in $WORK/out; returns 124 on timeout.
within() {
  local t="$1"; shift
  "$@" > "$WORK/out" 2>&1 & local pid=$!
  local i=0
  while kill -0 "$pid" 2>/dev/null; do
    sleep 0.2; i=$((i + 1))
    if [ "$i" -ge $((t * 5)) ]; then pkill -P "$pid" 2>/dev/null; kill "$pid" 2>/dev/null; wait "$pid" 2>/dev/null; return 124; fi
  done
  wait "$pid"
}
check() { # name, expected substring in $WORK/out
  if grep -q "$2" "$WORK/out"; then echo "ok   $1"; else echo "FAIL $1"; sed 's/^/     /' "$WORK/out"; fails=$((fails + 1)); fi
}
# Hold [lock] from an unrelated process until killed; returns once it provably holds it.
hold_unrelated() {
  "${HOLD[@]}" "$1" sleep 60 & holders+=($!)
  local i=0
  while "${PROBE[@]}" "$1" true 2>/dev/null; do sleep 0.1; i=$((i + 1)); [ "$i" -lt 100 ] || break; done
}
release_unrelated() { for p in "${holders[@]:-}"; do [ -n "$p" ] && kill "$p" 2>/dev/null; done; holders=(); sleep 0.2; }

within 10 "$WORK/gate.sh" plain; echo "rc=$?" >> "$WORK/out"
check "takes the lock itself and runs" "ran plain held=1"

within 10 "${HOLD[@]}" "$LOCK" "$WORK/gate.sh" wrapped; echo "rc=$?" >> "$WORK/out"
check "wrapped by a caller ($TOOL with flag): runs, held=1" "ran wrapped held=1"

within 10 "${HOLD_BARE[@]}" "$LOCK" "$WORK/gate.sh" bare; echo "rc=$?" >> "$WORK/out"
check "wrapped with no flags ($TOOL <lock> cmd)" "ran bare"

within 10 "$TOOL_PATH" "${HOLD[@]:1}" "$LOCK" "$WORK/gate.sh" abspath; echo "rc=$?" >> "$WORK/out"
check "wrapped by the tool's absolute path" "ran abspath"

ln -s "$WORK" "$WORK/link"
within 10 "${HOLD[@]}" "$WORK/link/gate.lock" "$WORK/gate.sh" symlink; echo "rc=$?" >> "$WORK/out"
check "wrapped through a symlinked directory" "ran symlink"

# An unrelated holder must still make it queue.
hold_unrelated "$LOCK"
within 2 "$WORK/gate.sh" queued; echo "rc=$?" >> "$WORK/out"
check "an unrelated holder still makes it wait" "rc=124"

# An ancestor lockf on ANOTHER file that merely mentions our lock path is not a holder.
within 2 "${HOLD[@]}" "$OTHER" "$WORK/gate.sh" "$LOCK"; echo "rc=$?" >> "$WORK/out"
check "an ancestor on another lock that only mentions ours still waits" "rc=124"
release_unrelated

if [ "$TOOL" = flock ]; then
  # A SHARED hold by an ancestor is not ours to borrow (flock -s); another shared holder exists.
  flock -s "$LOCK" sleep 60 & holders+=($!)
  sleep 0.3
  within 2 flock -s "$LOCK" "$WORK/gate.sh" shared; echo "rc=$?" >> "$WORK/out"
  check "a shared ancestor hold is not borrowed" "rc=124"
  release_unrelated
fi

[ "$fails" -eq 0 ] && echo "all gate-lock tests passed" || { echo "$fails failed"; exit 1; }
