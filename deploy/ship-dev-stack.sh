#!/usr/bin/env bash
#
# Build all three dev-lab images, load them onto the Scaleway dev box, and cut over.
#
#   ./deploy/ship-dev-stack.sh root@<dev-lab-ip>              # all three
#   ./deploy/ship-dev-stack.sh root@<ip> --only relay         # just one
#   ./deploy/ship-dev-stack.sh root@<ip> --tag mytag          # pin the tag
#
# ## Why a registry is not involved
#
# The same reasoning as deploy/build-relay-image.sh: GHCR credentials have been unreliable, CI does
# not run (docs/tech-debt.md "CI blocked by GitHub Actions billing"), and a
# `docker save | ssh docker load` depends on neither. It also leaves the previous image on the box,
# so a rollback is a tag flip in /opt/tenshu/dev.env plus a restart, with nothing to re-download.
#
# ## Why this is not the only way in
#
# The image workflows publish to GHCR, so a dev box given `ghcr_user`/`ghcr_token` pulls
# its own images and needs none of this. `--ship` stays the fast path for iterating: no registry
# round-trip, no waiting on CI, and the previous image is left on the box so a rollback is a tag
# flip in /opt/tenshu/dev.env plus a restart.
#
# ⚠ The dev box is x86, matching production — so the default linux/amd64 build is what it wants and
# no PLATFORM override appears below. That is deliberate: a dev plane on a different architecture
# from production cannot catch an architecture-specific problem in either direction.
#
# ⚠⚠ The apex SPA DOES have a relay URL, inlined by Vite at BUILD time. This script reads the dev
# domain off the box and builds the apex against `https://relay.<that domain>` — build it any other
# way and you get an apex on the dev box that talks to PRODUCTION, which is the exact failure the
# whole isolated plane exists to prevent (docs/proposals/ops-dev-lab.md §4).

set -euo pipefail
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

TARGET="${1:-}"
[[ -n "$TARGET" ]] || { sed -n '2,26p' "$0" | sed 's/^# \{0,1\}//'; exit 1; }
shift
[[ "$TARGET" == *@* ]] || TARGET="root@$TARGET"   # Scaleway's Ubuntu images log in as root

TAG=""
ONLY=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --tag)  TAG="${2:?--tag needs a value}"; shift 2 ;;
    --only) ONLY="${2:?--only needs relay|commerce|apex}"; shift 2
            # An unknown name matches no component: nothing is built, and the cutover below would
            # still restart the stack and print "Shipped".
            case "$ONLY" in relay|commerce|apex) ;; *) echo "--only: unknown component '$ONLY' (relay|commerce|apex)" >&2; exit 1 ;; esac ;;
    -h|--help) sed -n '2,26p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "unknown arg: $1" >&2; exit 1 ;;
  esac
done

if [[ -z "$TAG" ]]; then
  TAG="$(git rev-parse --short=8 HEAD)"
  # --porcelain, not `git diff`: an untracked source file is in the build context too.
  if [[ -n "$(git status --porcelain)" ]]; then
    echo "⚠  working tree is dirty — tagging as '$TAG' would misname these images." >&2
    echo "   Commit, stash, or pass --tag <something-else>." >&2
    exit 1
  fi
fi

# The box is the source of truth for its own domain — one less thing to keep in sync by hand.
DEV_DOMAIN="$(ssh "$TARGET" "grep '^DEV_DOMAIN=' /opt/tenshu/dev.env | cut -d= -f2-")"
[[ -n "$DEV_DOMAIN" ]] || { echo "could not read DEV_DOMAIN from $TARGET:/opt/tenshu/dev.env" >&2; exit 1; }
echo "==> dev plane: $DEV_DOMAIN  (tag $TAG, linux/amd64)"

# ⚠⚠ This plane's commerce signs with its OWN seed under its OWN issuer id, and a client trusts an
# issuer only if it was compiled to (`TENSHU_EXTRA_ISSUER`, entitlement-trust-anchor-distribution.md
# D3a). Read both halves off the box, so the web bundle built below is told about the issuer it is
# about to be served next to. Empty on a plane's very first ship — commerce has not logged its key
# yet — in which case ship again once §9 of the runbook has filled it in.
DEV_ISSUER_ID="$(ssh "$TARGET" "grep '^ISSUER_ID=' /opt/tenshu/dev.env | cut -d= -f2-")"
DEV_ISSUER_PUBKEY="$(ssh "$TARGET" "grep '^ENTITLEMENT_ISSUER_PUBKEY=' /opt/tenshu/dev.env | cut -d= -f2-")"
EXTRA_ISSUER=""
if [[ -n "$DEV_ISSUER_ID" && -n "$DEV_ISSUER_PUBKEY" ]]; then
  EXTRA_ISSUER="$DEV_ISSUER_ID:$DEV_ISSUER_PUBKEY"
  echo "    issuer: $EXTRA_ISSUER"
else
  echo "⚠  no issuer key on the box yet — the web bundle will NOT trust this plane's commerce." >&2
fi

# shellcheck source=deploy/lib/web-apps.sh
source "$REPO_ROOT/deploy/lib/web-apps.sh"

dev_app_origins() {
  local id
  for id in $(web_app_ids); do
    printf '%s_ORIGIN=https://%s.%s\n' "$(web_app_env_stem "$id")" "$id" "$DEV_DOMAIN"
  done
}

build() { # build(component)
  case "$1" in
    relay)    ./deploy/build-relay-image.sh    --tag "$TAG" --ship "$TARGET" ;;
    commerce) ./deploy/build-commerce-image.sh --tag "$TAG" --ship "$TARGET" ;;
    # Every hosted app `<id>` at `https://<id>.$DEV_DOMAIN`, as `<ID>_ORIGIN=…` words
    # (deploy/lib/web-apps.sh discovers the apps, so a new one needs no edit here).
    # shellcheck disable=SC2046 # one word per app, by design
    apex)     env $(dev_app_origins) \
                RELAY_URL="https://relay.$DEV_DOMAIN" COMMERCE_URL="https://commerce.$DEV_DOMAIN" \
                APEX_ORIGIN="https://$DEV_DOMAIN" \
                TENSHU_EXTRA_ISSUER="$EXTRA_ISSUER" \
                ./deploy/build-apex-image.sh --tag "$TAG" --ship "$TARGET" ;;
  esac
}

SHIPPED=()
for c in relay commerce apex; do
  [[ -n "$ONLY" && "$ONLY" != "$c" ]] && continue
  echo "==> $c"
  build "$c"
  SHIPPED+=("$c")
done

# ⚠⚠ Only the components this run actually SHIPPED get their tag moved. Rewriting all three
# under `--only` points the two skipped ones at a tag that was never built and is on neither the
# box nor GHCR, so the next `up -d` — or the next reboot — cannot start them. That failure is
# swallowed by up.sh's "images are not on this box yet" branch, and this script would still print
# "Shipped": a partial deploy that reads as a whole one, and only shows up as two dead services.
echo "==> cutting over on $TARGET (${SHIPPED[*]})"
# ⚠ QUOTED heredoc + positional args: the body is sent verbatim and the values travel as
# arguments, so nothing here is expanded by the LOCAL shell.
ssh "$TARGET" bash -s -- "$TAG" "${SHIPPED[@]}" <<'REMOTE'
set -euo pipefail
tag="$1"; shift
for c in "$@"; do
  var="$(printf '%s' "$c" | tr '[:lower:]' '[:upper:]')_IMAGE_TAG"
  sed -i "s|^$var=.*|$var=$tag|" /opt/tenshu/dev.env
done
bash /opt/tenshu/up.sh
REMOTE

cat <<EOM

Shipped $TAG to $DEV_DOMAIN.

  relay     https://relay.$DEV_DOMAIN/health
  commerce  https://commerce.$DEV_DOMAIN
  apex      https://$DEV_DOMAIN
$(for id in $(web_app_ids); do printf '  %-9s https://%s.%s\n' "$id" "$id" "$DEV_DOMAIN"; done)

⚠ If this was the FIRST ship, commerce has now logged its issuer public key. Enforcement is off
until you copy it in — see docs/how-to/deploy-dev-lab-scaleway.md §7.
EOM
