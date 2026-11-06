# shellcheck shell=bash
#
# The web apps the apex image hosts on their own origins (ADR 0020; web-app-suite.md F2/F4), for
# the scripts that build and ship that image. Source it, don't execute it.
#
# ⚠ The list is DISCOVERED, not written down: an app is a `web/<id>` with a `vite.config.ts`,
# apex aside — the rule `scripts/run-web-gate.sh` uses, and the one the apex's registry test holds
# the registry to (`web/apex/src/apps/registry/registry.test.ts`). So a new app joins the image by
# existing, and cannot be in the image without being in the apex's registry (or the reverse).
#
# Per app `<id>`, everything else is derived from the id, by the same convention the registry test
# pins: the build-time env var `VITE_<ID>_ORIGIN`, the script input `<ID>_ORIGIN`, and the
# production origin `https://<id>.tenshu.app`.
#
# ⚠ Bash 3.2 (what macOS ships, and build-apex-image.sh runs on the maintainer's Mac): no
# associative arrays, no ${x^^}.

# Every hosted app id, sorted. Run from anywhere; reads the repo the script lives in.
web_app_ids() {
  local root dir name
  root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
  # ⚠ `if`, not `[ … ] && echo`: under the callers' `set -eo pipefail` a loop whose last test was
  # false fails the whole pipeline.
  for dir in "$root"/web/*/; do
    name="$(basename "$dir")"
    if [ "$name" != apex ] && [ -f "$dir/vite.config.ts" ] && [ -f "$dir/package.json" ]; then
      echo "$name"
    fi
  done | sort
}

# `drive` → `DRIVE`, `home-hub` → `HOME_HUB`.
web_app_env_stem() {
  printf '%s' "$1" | tr '[:lower:]-' '[:upper:]_'
}

# The origin an app is built for: `$<ID>_ORIGIN` if the caller set it, else production's host.
web_app_origin() {
  local var
  var="$(web_app_env_stem "$1")_ORIGIN"
  if [ -n "${!var:-}" ]; then echo "${!var}"; else echo "https://$1.tenshu.app"; fi
}

# `VITE_DRIVE_ORIGIN=https://drive.tenshu.app VITE_CONTACTS_ORIGIN=…` — the apex build's view of
# every app's origin, as `env` arguments.
web_app_vite_origins() {
  local id
  for id in $(web_app_ids); do
    printf 'VITE_%s_ORIGIN=%s\n' "$(web_app_env_stem "$id")" "$(web_app_origin "$id")"
  done
}
