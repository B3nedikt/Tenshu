#!/usr/bin/env bash
# Bring the whole Tenshu backend plane up on this machine: the sync relay + the commerce
# issuer, wired to each other, with entitlement enforcement ON from the first second.
#
# This is CE-1 of `docs/proposals/commerce-end-to-end-dry-run.md` — the thing that makes the
# end-to-end flow demonstrable without a droplet. The existing
# `docs/how-to/reset-and-commerce-test.md` runbook stays for *deployed* instances.
#
#   ./scripts/local-commerce-stack.sh            # up (Ctrl-C to stop both)
#   ./scripts/local-commerce-stack.sh --reset    # wipe local state first (fresh realm + accounts)
#   ./scripts/local-commerce-stack.sh --no-enforce   # relay accepts unentitled writes
#   STRIPE_MODE=real ./scripts/local-commerce-stack.sh   # opt out of the dev payment rail
#   WEB_APP_ORIGIN=http://localhost:4173 ./scripts/local-commerce-stack.sh  # a different apex origin
#
# Then, in another terminal:
#   ./scripts/run-control-desktop-local-commerce.sh
#
# ---------------------------------------------------------------------------------------
# Three implementation choices worth knowing, because each of them is load-bearing:
#
# 1. **Fat jars + `java -jar`, NOT `./gradlew :x:run`.** Not an env-passing concern — Gradle
#    does sync the invoking shell's environment into the daemon, so `FOO=bar ./gradlew run`
#    reaches the forked JVM (verified; that is why the sibling Control script can rely on it).
#    The reason is lifecycle: these are two *long-lived* servers that must start, be killed and
#    be restarted independently. Under Gradle each would hold a build alive, contend for the
#    same daemon/build lock, and swallow Ctrl-C. A plain background PID is a thing this script
#    can actually manage.
#
# 2. **The issuer public key is scraped from the commerce boot log, never hardcoded here.**
#    That is exactly the hand-off an operator performs in production (commerce prints its
#    public half; you paste it into the relay's ENTITLEMENT_ISSUER_PUBKEY), so the local run
#    rehearses the real step instead of routing around it — and it cannot drift from the key
#    the service actually signs with. In DEV_MODE the key is the pinned
#    `EntitlementSignature.COMMERCE_PUBLIC_KEY`, so nothing has to be copied by hand.
#
# 3. **`ENTITLEMENT_ENFORCE=true` by default.** The interesting failure modes only exist under
#    enforcement; a dry run with it off would prove nothing about the posture we ship.
#
# 4. **`STRIPE_MODE=fake` by default** (CE-3): buying Basic/Premium works with no Stripe account.
#    Only the *processor* is faked — confirming the hosted page posts a correctly-signed event into
#    the REAL webhook, so signature verification, idempotency, the entitlement ledger and the
#    statutory invoice all genuinely run. `CommerceConfig.validate()` refuses this outside dev mode.
#
# ⚠ Everything here is DEV MODE: the relay's `X-Dev-Owner` auth bypass is live and commerce
#   signs with the committed dev seed. Never point a real device fleet at this.
# ⚠ Real OTP email is CE-2. Until it lands the code is printed to the commerce log — this
#   script tails it for you and tells you where to look.

set -euo pipefail

cd "$(dirname "$0")/.."
ROOT="$(pwd)"
STATE="${TENSHU_LOCAL_STATE:-$ROOT/.tenshu-local}"

RESET=0
ENFORCE=true
for arg in "$@"; do
  case "$arg" in
    --reset) RESET=1 ;;
    --no-enforce) ENFORCE=false ;;
    -h|--help) sed -n '2,40p' "$0"; exit 0 ;;
    *) echo "unknown option: $arg (try --help)" >&2; exit 2 ;;
  esac
done

# Bind on all interfaces so an Android emulator (10.0.2.2) or a second device on the LAN can
# reach both planes — the second-device leg of the dry run needs it. Override for a stricter run.
RELAY_BIND="${TENSHU_LOCAL_BIND:-0.0.0.0}"
COMMERCE_BIND="${TENSHU_LOCAL_BIND:-0.0.0.0}"
RELAY_PORT="${TENSHU_LOCAL_RELAY_PORT:-8080}"
COMMERCE_PORT="${TENSHU_LOCAL_COMMERCE_PORT:-8081}"
# The admin console stays on loopback even locally — it is the one surface that shows A28 PII
# and the A30 ownerId↔email join in the clear, and that posture is worth keeping muscle memory for.
ADMIN_PORT="${TENSHU_LOCAL_ADMIN_PORT:-8082}"
ADMIN_TOKEN="${TENSHU_LOCAL_ADMIN_TOKEN:-local-dev-admin}"
# Founder-style redemption seed so the identity-light grant path is exercisable too (§2.3).
REDEMPTION_CODES="${TENSHU_LOCAL_REDEMPTION_CODES:-LOCAL-PREMIUM:tenshu.drive.premium:100}"

if [ "$RESET" = "1" ]; then
  echo "==> Resetting local state at $STATE"
  rm -rf "$STATE"
fi
mkdir -p "$STATE"
RELAY_LOG="$STATE/relay.log"
COMMERCE_LOG="$STATE/commerce.log"

# --- 1. Build both fat jars --------------------------------------------------------------
# --configure-on-demand: only the two server modules and their deps get configured. Besides
# being faster, it keeps this script independent of app-module toolchains — configuring the
# whole repo drags in :control-desktop's JDK 25 requirement, which has nothing to do with
# standing up a backend.
echo "==> Building fat jars (incremental after the first run)"
./gradlew --quiet --configure-on-demand :commerce-service:buildFatJar :sync-relay-server:buildFatJar

COMMERCE_JAR="$ROOT/commerce-service/build/libs/commerce-service-all.jar"
RELAY_JAR="$ROOT/sync-relay-server/build/libs/sync-relay-server-all.jar"
for jar in "$COMMERCE_JAR" "$RELAY_JAR"; do
  [ -f "$jar" ] || { echo "missing $jar — did buildFatJar succeed?" >&2; exit 1; }
done

PIDS=()
# ⚠ This only runs on a normal exit / Ctrl-C. If you background this script and SIGKILL it, the
# trap never fires and the two servers outlive it — kill them by pid in that case. Escalate to
# -9 after a grace period so a wedged JVM doesn't leave the ports bound for the next run.
cleanup() {
  echo
  echo "==> Stopping the stack"
  for pid in "${PIDS[@]:-}"; do
    [ -n "${pid:-}" ] && kill "$pid" 2>/dev/null || true
  done
  sleep 2
  for pid in "${PIDS[@]:-}"; do
    [ -n "${pid:-}" ] && kill -9 "$pid" 2>/dev/null || true
  done
  wait 2>/dev/null || true
}
trap cleanup EXIT INT TERM

# --- 2. Commerce first (the relay needs its public key) ----------------------------------
# The durable account store is Postgres when you point DATABASE_URL at one; otherwise the
# in-memory twin, which is fine for a single pass but loses every account on restart. Set
# TENSHU_LOCAL_COMMERCE_DB to keep accounts across restarts, e.g.
#   TENSHU_LOCAL_COMMERCE_DB='jdbc:postgresql://localhost:5432/commerce?user=commerce&password=dev'
COMMERCE_DB="${TENSHU_LOCAL_COMMERCE_DB:-}"
if [ -z "$COMMERCE_DB" ]; then
  echo "==> Commerce store: IN-MEMORY (accounts are lost on restart)"
  echo "    Set TENSHU_LOCAL_COMMERCE_DB=<jdbc postgres url> to keep them."
else
  echo "==> Commerce store: $COMMERCE_DB"
fi

# Real OTP email (CE-2) is opt-in locally: export SMTP_HOST + SMTP_FROM (and usually
# SMTP_USERNAME/SMTP_PASSWORD) before running this and the code is actually mailed instead of
# logged. Without them commerce falls back to the dev log sender, which is fine for a wiring check
# but is NOT the §5 demo — step 3 of the dry run is "a mail lands in an actual inbox".
# ⚠ `env -i` below scrubs the environment on purpose (so a stray TENSHU_*/DATABASE_URL in your
# shell cannot silently reconfigure the service), which is why these have to be forwarded by name.
if [ -n "${SMTP_HOST:-}" ] && [ -n "${SMTP_FROM:-}" ]; then
  echo "==> Commerce email: SMTP $SMTP_HOST (real mail)"
else
  echo "==> Commerce email: dev log sender (export SMTP_HOST + SMTP_FROM for real mail)"
fi

# The apex talks to commerce from a browser, and the service has no CORS unless told which origin
# to admit — so locally we default it to the Vite dev server. Override for a different port.
# ⚠ It is also where Stripe returns a web customer to, so pointing it at an origin that is not
# actually serving the apex breaks the post-payment landing as well as every API call.
echo "==> Commerce web origin: ${WEB_APP_ORIGIN:-http://localhost:5173} (browser access + checkout return)"

echo "==> Starting commerce issuer on $COMMERCE_BIND:$COMMERCE_PORT (admin on 127.0.0.1:$ADMIN_PORT)"
: > "$COMMERCE_LOG"
env -i \
  PATH="$PATH" HOME="$HOME" \
  SMTP_HOST="${SMTP_HOST:-}" \
  SMTP_FROM="${SMTP_FROM:-}" \
  SMTP_PORT="${SMTP_PORT:-}" \
  SMTP_USERNAME="${SMTP_USERNAME:-}" \
  SMTP_PASSWORD="${SMTP_PASSWORD:-}" \
  SMTP_FROM_NAME="${SMTP_FROM_NAME:-Tenshu}" \
  SMTP_STARTTLS="${SMTP_STARTTLS:-}" \
  OTP_SEND_PER_IP_PER_HOUR="${OTP_SEND_PER_IP_PER_HOUR:-}" \
  OTP_SEND_PER_EMAIL_PER_HOUR="${OTP_SEND_PER_EMAIL_PER_HOUR:-}" \
  OTP_VERIFY_PER_EMAIL_PER_HOUR="${OTP_VERIFY_PER_EMAIL_PER_HOUR:-}" \
  STRIPE_MODE="${STRIPE_MODE:-fake}" \
  WEB_APP_ORIGIN="${WEB_APP_ORIGIN:-http://localhost:5173}" \
  PUBLIC_BASE_URL="http://127.0.0.1:$COMMERCE_PORT" \
  DEV_MODE=true \
  BIND_HOST="$COMMERCE_BIND" \
  PORT="$COMMERCE_PORT" \
  ADMIN_BIND_HOST=127.0.0.1 \
  ADMIN_PORT="$ADMIN_PORT" \
  ADMIN_TOKEN="$ADMIN_TOKEN" \
  REDEMPTION_CODES="$REDEMPTION_CODES" \
  DATABASE_URL="$COMMERCE_DB" \
  java -jar "$COMMERCE_JAR" >>"$COMMERCE_LOG" 2>&1 &
PIDS+=($!)

# --- 3. Scrape the issuer public key off the boot log (the production hand-off, rehearsed) --
echo "==> Waiting for the commerce issuer public key"
ISSUER_PUBKEY=""
for _ in $(seq 1 60); do
  # Printed by Application.kt as: "Commerce issuer public key (set relay ...): <base64> [kid=…]"
  line="$(grep -m1 'Commerce issuer public key' "$COMMERCE_LOG" 2>/dev/null || true)"
  if [ -n "$line" ]; then
    ISSUER_PUBKEY="$(printf '%s' "$line" | sed -E 's/.*: ([A-Za-z0-9+/=]+).*/\1/')"
    break
  fi
  # Fail fast rather than spinning out the timeout if the process already died.
  kill -0 "${PIDS[0]}" 2>/dev/null || { echo "commerce exited during boot; see $COMMERCE_LOG" >&2; tail -20 "$COMMERCE_LOG" >&2; exit 1; }
  sleep 1
done
[ -n "$ISSUER_PUBKEY" ] || { echo "never saw the issuer public key; see $COMMERCE_LOG" >&2; exit 1; }
echo "    issuer pubkey: $ISSUER_PUBKEY"

# --- 4. Relay, verifying against that key ------------------------------------------------
echo "==> Starting sync relay on $RELAY_BIND:$RELAY_PORT (enforce=$ENFORCE)"
: > "$RELAY_LOG"
env -i \
  PATH="$PATH" HOME="$HOME" \
  DEV_MODE=true \
  BIND_HOST="$RELAY_BIND" \
  PORT="$RELAY_PORT" \
  DATABASE_URL="jdbc:sqlite:$STATE/relay.db" \
  BLOB_DIR="$STATE/relay-blobs" \
  ENTITLEMENT_ISSUER_PUBKEY="$ISSUER_PUBKEY" \
  ENTITLEMENT_ENFORCE="$ENFORCE" \
  java -jar "$RELAY_JAR" >>"$RELAY_LOG" 2>&1 &
PIDS+=($!)

# --- 5. Wait for both to answer ----------------------------------------------------------
wait_for_http() {
  local name="$1" url="$2" pid="$3"
  for _ in $(seq 1 60); do
    if curl -fsS --max-time 2 "$url" >/dev/null 2>&1; then return 0; fi
    kill -0 "$pid" 2>/dev/null || { echo "$name exited during boot" >&2; return 1; }
    sleep 1
  done
  echo "$name never answered at $url" >&2
  return 1
}
wait_for_http "commerce" "http://127.0.0.1:$COMMERCE_PORT/" "${PIDS[0]}" || { tail -20 "$COMMERCE_LOG" >&2; exit 1; }
wait_for_http "relay" "http://127.0.0.1:$RELAY_PORT/" "${PIDS[1]}" || { tail -20 "$RELAY_LOG" >&2; exit 1; }

cat <<BANNER

────────────────────────────────────────────────────────────────────────────
  Tenshu local plane is up.

  relay      http://127.0.0.1:$RELAY_PORT      (enforce=$ENFORCE, sqlite at $STATE/relay.db)
  commerce   http://127.0.0.1:$COMMERCE_PORT      (dev issuer, redemption code: ${REDEMPTION_CODES%%:*})
  payments   ${STRIPE_MODE:-fake} rail — 'Get more storage' opens a local confirm page, no Stripe account needed
  admin      http://127.0.0.1:$ADMIN_PORT/admin/users
             curl -H "Authorization: Bearer $ADMIN_TOKEN" http://127.0.0.1:$ADMIN_PORT/admin/users

  Run Control against it:
      ./scripts/run-control-desktop-local-commerce.sh

  The OTP is not emailed yet (CE-2) — read it from the commerce log:
      grep -i 'OTP for' $COMMERCE_LOG

  Logs: $RELAY_LOG
        $COMMERCE_LOG

  Ctrl-C stops both.
────────────────────────────────────────────────────────────────────────────

BANNER

# Tail both logs so the OTP and the relay's accept/refuse lines are visible in one place —
# the two things the dry run is actually watching.
tail -f "$COMMERCE_LOG" "$RELAY_LOG" &
PIDS+=($!)
wait
