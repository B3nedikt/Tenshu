#!/usr/bin/env bash
#
# Scaffold a new Tenshu web app on the app-kit (web-app-suite.md F3/F4; docs/how-to/new-web-app.md).
#
#   scripts/new-web-app.sh <id> <port> ["Display Name"]
#   scripts/new-web-app.sh calendar 5176
#   scripts/new-web-app.sh home-hub 5180 "Home hub"
#
# Creates, from web/app-kit/template/:
#   web/<id>/                                   a working app: package, configs, a minimal screen,
#                                               `lib/proxy.ts` with only the session methods,
#                                               i18n en/de/fr, tests, the manifest (via the preset)
#   web/apex/src/apps/registry/apps/<id>.ts     its apex registry entry — adding this file IS the
#                                               registration (launcher, Apps list, route, gateway
#                                               origin + allowlist); the allowlist starts at the
#                                               session methods alone
# then runs `pnpm install` (the lockfile gains the app) and prints what is left to do by hand.
#
# ⚠ It never edits a shared file. Everything a new app still needs in one (deploy config, DNS, a
# gateway module for a new namespace) is on the printed checklist, because those are decisions —
# what an origin may call, what production serves — not boilerplate.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"
TEMPLATE="web/app-kit/template"

usage() { sed -n '4,7p' "$0" | sed 's/^# \{0,1\}//' >&2; exit 2; }
[[ $# -ge 2 && $# -le 3 ]] || usage
ID="$1"
PORT="$2"

# The id is the subdomain, the package name, the route and the `tenshu/app/<id>` app id.
[[ "$ID" =~ ^[a-z][a-z0-9]*(-[a-z0-9]+)*$ ]] || { echo "id must be lowercase letters/digits/hyphens: $ID" >&2; exit 2; }
case "$ID" in apex|app-kit|sdk|shared|store-frame|control|www|relay|commerce|mail) echo "id is reserved: $ID" >&2; exit 2 ;; esac
[[ "$PORT" =~ ^[0-9]{4,5}$ ]] || { echo "port must be a number: $PORT" >&2; exit 2; }
[[ ! -e "web/$ID" ]] || { echo "web/$ID already exists" >&2; exit 1; }
REGISTRY="web/apex/src/apps/registry/apps/$ID.ts"
[[ ! -e "$REGISTRY" ]] || { echo "$REGISTRY already exists" >&2; exit 1; }
# Every app's dev server runs side by side with the apex (5173): no two may share a port.
[[ "$PORT" != 5173 ]] || { echo "port 5173 is the apex's" >&2; exit 1; }
if grep -lE "\bport:\s*$PORT\b" web/*/vite.config.ts >/dev/null 2>&1; then
  echo "port $PORT is taken by: $(grep -lE "\bport:\s*$PORT\b" web/*/vite.config.ts | tr '\n' ' ')" >&2
  exit 1
fi

# Display name: given, else the id with its first letter upper-cased and hyphens as spaces.
NAME="${3:-$(printf '%s' "$ID" | tr '-' ' ' | awk '{ print toupper(substr($0,1,1)) substr($0,2) }')}"
[[ "$NAME" != *"'"* && "$NAME" != *'"'* && "$NAME" != *'|'* ]] || { echo "display name may not contain quotes or |" >&2; exit 2; }
# shellcheck source=deploy/lib/web-apps.sh
source "$REPO_ROOT/deploy/lib/web-apps.sh"
ENV_NAME="VITE_$(web_app_env_stem "$ID")_ORIGIN"
# A JS identifier for the registry entry: `home-hub` → `homeHub`.
VAR="$(printf '%s' "$ID" | awk -F- '{ out=$1; for (i = 2; i <= NF; i++) out = out toupper(substr($i,1,1)) substr($i,2); print out }')"
# After every registered app, in steps of 10 (ties sort by id, so two parallel runs picking the
# same number still render in a stable order).
MAX_ORDER="$(grep -hoE '^\s*order:\s*[0-9]+' web/apex/src/apps/registry/apps/*.ts 2>/dev/null | grep -oE '[0-9]+' | sort -n | tail -1)"
ORDER=$(( ${MAX_ORDER:-0} + 10 ))

render() { # render <template file> <destination>
  mkdir -p "$(dirname "$2")"
  sed -e "s|__ID__|$ID|g" -e "s|__NAME__|$NAME|g" -e "s|__PORT__|$PORT|g" \
      -e "s|__ENV__|$ENV_NAME|g" -e "s|__VAR__|$VAR|g" -e "s|__ORDER__|$ORDER|g" "$1" > "$2"
}

echo "==> web/$ID ($NAME, dev port $PORT, $ENV_NAME)"
while IFS= read -r src; do
  rel="${src#"$TEMPLATE"/}"
  case "$rel" in apex/*) continue ;; esac
  dest="web/$ID/${rel%.tmpl}"
  dest="${dest//__ID__/$ID}"
  render "$src" "$dest"
done < <(find "$TEMPLATE" -type f -name '*.tmpl' | sort)

echo "==> $REGISTRY (order $ORDER)"
render "$TEMPLATE/apex/registry.ts.tmpl" "$REGISTRY"

echo "==> pnpm install (links the new package; the lockfile gains one importer)"
(cd web && pnpm install >/dev/null)

cat <<EOM

Created web/$ID and its apex registry entry. Check it works:

  cd web && pnpm -F $ID build && pnpm -F $ID test && pnpm -F $ID lint
  pnpm -F apex test            # the registry + allowlist pins now include $ID
  pnpm -F $ID dev              # http://localhost:$PORT, with the apex on :5173

What is left (docs/how-to/new-web-app.md has the detail):

  1. Calls. Each method the UI calls goes in TWO places, by exact name:
       web/$ID/src/lib/proxy.ts                    ProxyMethod table + a typed wrapper
       $REGISTRY   methods (the F5 allowlist)
     The apex test fails until both agree. A new method needs a gateway handler:
       web/apex/src/lib/gateway/<namespace>.ts     an existing namespace: add it there
       web/apex/src/lib/gateway/index.ts           a NEW namespace: its module + one line
  2. SDK boundary (the slice's first PR, web-app-suite.md §4): the Kotlin @JsExport facade, then
       web/sdk/src/native/<svc>.d.ts + one reference line in web/sdk/src/native.d.ts
       web/sdk/src/<svc>.ts + its "./<svc>" entry in web/sdk/package.json + its export in _bundle.ts
  3. Display data: permissions/services in the registry entry (TODOs), the manifest's semantic
     permissions in web/$ID/vite.config.ts, and optionally launcher copy under
     appLauncher.entries.$ID in web/apex/locales/{en,de}/control.json (else the English label).
  4. Deploy (maintainer — the build and CI pick the app up by itself):
       web/apex/Caddyfile, deploy/scaleway-dev/stack/Caddyfile   a two-line site block each
       web/apex/Caddyfile                                         {\$$(web_app_env_stem "$ID")_SITE} in the apex's frame-ancestors
       web/apex/docker-compose.yml                                $(web_app_env_stem "$ID")_SITE + $(web_app_env_stem "$ID")_FRAME_ANCESTORS
       deploy/scaleway-dev/stack/dev-overlay.yml                  $(web_app_env_stem "$ID")_DOMAIN, _SITE, _FRAME_ANCESTORS
       DNS: $ID.tenshu.xyz (dev lab), then $ID.tenshu.app (deploy/dns/records.json)
  5. Gate: scripts/run-web-gate.sh, and paste its summary lines into the PR.
EOM
