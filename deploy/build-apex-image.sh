#!/usr/bin/env bash
# Build a linux/amd64 apex image from a NATIVELY-built SPA, and optionally ship it straight to a
# droplet without a registry.
#
#   ./deploy/build-apex-image.sh                        # build only, tag = short HEAD sha
#   ./deploy/build-apex-image.sh --ship 168.144.5.78    # build, then load it on that host
#   PLATFORM=linux/arm64 ./deploy/build-apex-image.sh    # cross-build for an arm64 host
#   ./deploy/build-apex-image.sh --tag 1c67fe42         # pin the tag explicitly
#   ./deploy/build-apex-image.sh --push                 # build, then publish it to GHCR
#   ./deploy/build-apex-image.sh --dist-only            # build + stage every app, no Docker (CI)
#   RELAY_URL=https://relay.example.com ./deploy/build-apex-image.sh   # non-default relay
#   CONSTRUCTION_CODE=424242 ./deploy/build-apex-image.sh              # bake the pre-launch gate
#   SIGNUP_DISABLED=1 ./deploy/build-apex-image.sh                      # close signup (site stays public)
#
# ## Why this exists
#
# The twin of deploy/build-relay-image.sh. web/apex/Dockerfile's `full` target runs BOTH toolchains
# (Kotlin/JS via Gradle, then pnpm + vite) inside an amd64 image — on an arm Mac that is QEMU twice
# over. With CI down (billing) that left no way to ship an apex image at all.
#
# ⚠⚠ A built SPA is static text: nothing in it needs amd64 EXECUTION, only the Caddy base layer
# does. So build it natively, then assemble an image whose only instructions are COPY and metadata.
#
# ⚠⚠ RELAY_URL, CONSTRUCTION_CODE and SIGNUP_DISABLED are inlined by Vite at BUILD time, so here they
# are read by the NATIVE build below — the Dockerfile's ARGs never see them on this path. Changing
# any of them means rebuilding the SPA, not just re-running the image assembly.
#
# ⚠⚠ TENSHU_EXTRA_ISSUER (`<publisherId>:<base64 key>`) is the same kind of input one layer down: it
# is read by GRADLE when the SDK bundle is compiled, and adds one commerce issuer to the bundle's
# trust set — what a plane with its own issuer seed needs (deploy/ship-dev-stack.sh sets it). Leave
# it UNSET for a production image; the build prints which it was.
#
# ⚠ SIGNUP_DISABLED is therefore what makes the public and test images DIFFERENT ARTIFACTS. Build
# the test one with it unset and the public one with it set; the tag should say which is which.
#
# ## --push (registry) vs --ship (direct load)
#
# `--push` publishes the image to GHCR; `--ship <host>` copies it to one box with
# `docker save | ssh docker load`. They compose, and they answer different questions.
#
# ⚠⚠ **Prefer `--push`.** A `--ship`-only image exists on exactly one disk and in no history: its
# tag resolves nowhere, so the box cannot be rebuilt from scratch, a second box cannot be raised
# beside it, and `terraform apply` against a fresh instance pulls a tag GHCR has never heard of.
# That is not hypothetical. Every tag deployed between 2026-07-26 and 2026-09-07 went out this
# way and NONE of them is in the registry, so production was unreproducible for six weeks and the
# Scaleway migration had nothing to feed a new instance. `--ship` is the fast follow-up to a push,
# not a substitute for one.
#
# ⚠ A push needs a CLASSIC PAT with `write:packages`. `read:packages` deploys but cannot publish,
# and GHCR reports that difference as a generic denial rather than as a scope error.
#
# `--ship` earns its keep after the push: it leaves the previous image on the box, so a rollback is
# a tag flip in /opt/tenshu/deploy.env plus a restart, with nothing to re-download.
#
# ⚠ This does NOT restart anything. Loading an image is inert; pointing apex at it is a separate,
# deliberate step (see the printed instructions).

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

IMAGE="ghcr.io/b3nedikt/tenshu-apex"
TAG=""
SHIP_HOST=""
PUSH=0
DIST_ONLY=0
# ⚠ The `prebuilt` target is COPY-only over a multi-arch base, so building for the OTHER
# architecture costs nothing and emulates nothing — only the base layer differs. Every current
# deploy target is amd64 (DigitalOcean, and Scaleway's DEV1/PRO2 line), so this default is right;
# the knob exists for an arm64 host, which the dev lab deliberately is not.
PLATFORM="${PLATFORM:-linux/amd64}"
RELAY_URL="${RELAY_URL:-https://relay.tenshu.app}"
# ⚠⚠ Inlined at build time like RELAY_URL, and the SPA's own fallback is PRODUCTION's commerce — so
# an image built for another plane without this signs up and checks out against the real one.
COMMERCE_URL="${COMMERCE_URL:-https://commerce.service.tenshu.app}"
# Each web app's partner origin (web-app-suite.md F2/F4). The apex frames every hosted app `<id>` at
# `<ID>_ORIGIN` (default `https://<id>.tenshu.app`) and answers its bridge only from there (and only
# with that app's methods, F5); every app dials the apex at APEX_ORIGIN. All are inlined at build
# time, so a dev-lab image needs the dev hostnames here (ship-dev-stack.sh passes them). The apps
# are discovered — every `web/<id>` with a vite.config.ts (deploy/lib/web-apps.sh) — so a new app
# needs no edit here.
APEX_ORIGIN="${APEX_ORIGIN:-https://tenshu.app}"
# shellcheck source=deploy/lib/web-apps.sh
source "$REPO_ROOT/deploy/lib/web-apps.sh"
WEB_APPS="$(web_app_ids | tr '\n' ' ')"
[[ -n "$WEB_APPS" ]] || { echo "no web apps found under web/ — refusing to build an apex image without them" >&2; exit 1; }
# The staging tree the Dockerfile's `prebuilt` target copies to /srv-apps (one dir per app).
APPS_DIST="web/.apps-dist"
CONSTRUCTION_CODE="${CONSTRUCTION_CODE:-}"
SIGNUP_DISABLED="${SIGNUP_DISABLED:-}"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --tag)  TAG="${2:?--tag needs a value}"; shift 2 ;;
    --platform) PLATFORM="${2:?--platform needs linux/amd64 or linux/arm64}"; shift 2 ;;
    --ship) SHIP_HOST="${2:?--ship needs a host}"; shift 2 ;;
    --push) PUSH=1; shift ;;
    # ⚠ The ONE place the web dists are built and staged. `.github/workflows/apex-image.yml` calls
    # this and then builds the `prebuilt` target itself; it used to rebuild only the apex in YAML,
    # which silently broke once the image started carrying the apps (F2 → web-app-suite F3/F4).
    --dist-only) DIST_ONLY=1; shift ;;
    -h|--help) sed -n '2,49p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "unknown arg: $1" >&2; exit 1 ;;
  esac
done

if [[ -z "$TAG" && "$DIST_ONLY" == 0 ]]; then
  TAG="$(git rev-parse --short=8 HEAD)"
  if ! git diff --quiet || ! git diff --cached --quiet; then
    echo "⚠  working tree is dirty — tagging as '$TAG' would misname this image."
    echo "   Commit, stash, or pass --tag <something-else>." >&2
    exit 1
  fi
fi

echo "==> Building the Kotlin→JS SDK bundle natively (extra issuer: ${TENSHU_EXTRA_ISSUER:-none})…"
./gradlew --console=plain :tenshu-sdk-js:jsBrowserDistribution

echo "==> Building the apex SPA + ${WEB_APPS% } natively (relay=$RELAY_URL, commerce=$COMMERCE_URL, apex=$APEX_ORIGIN, signup=$([[ -n "$SIGNUP_DISABLED" ]] && echo CLOSED || echo open))…"
for APP in $WEB_APPS; do echo "    $APP=$(web_app_origin "$APP")"; done
corepack enable >/dev/null 2>&1 || true
(
  cd web
  pnpm install --frozen-lockfile
  # shellcheck disable=SC2046 # one `VITE_<ID>_ORIGIN=…` word per app, by design
  env $(web_app_vite_origins) \
    VITE_TENSHU_RELAY_URL="$RELAY_URL" VITE_TENSHU_COMMERCE_URL="$COMMERCE_URL" \
    VITE_CONSTRUCTION_CODE="$CONSTRUCTION_CODE" \
    VITE_SIGNUP_DISABLED="$SIGNUP_DISABLED" \
    pnpm -F apex build
  for APP in $WEB_APPS; do
    VITE_APEX_ORIGIN="$APEX_ORIGIN" pnpm -F "$APP" build
  done
)

[[ -f web/apex/dist/index.html ]] || { echo "expected web/apex/dist/index.html after the vite build" >&2; exit 1; }
echo "    $(du -sh web/apex/dist | cut -f1)  web/apex/dist"
# ⚠ Staged fresh every time: the `prebuilt` target copies the WHOLE tree, so a leftover from an app
# that has since been removed would otherwise ship.
rm -rf "$APPS_DIST"
mkdir -p "$APPS_DIST"
for APP in $WEB_APPS; do
  DIST="web/$APP/dist"
  ORIGIN="$(web_app_origin "$APP")"
  [[ -f "$DIST/index.html" ]] || { echo "expected $DIST/index.html after the vite build" >&2; exit 1; }
  # ⚠ A wrong origin is invisible until a browser refuses the frame or the bridge, so check what
  # was actually inlined rather than what was asked for — both directions, for every app.
  grep -rqF "$ORIGIN" web/apex/dist/assets || { echo "apex bundle does not contain $ORIGIN ($APP)" >&2; exit 1; }
  grep -rqF "$APEX_ORIGIN" "$DIST/assets" || { echo "$APP bundle does not contain $APEX_ORIGIN" >&2; exit 1; }
  cp -R "$DIST" "$APPS_DIST/$APP"
  echo "    $(du -sh "$DIST" | cut -f1)  $DIST → $APPS_DIST/$APP"
done

if [[ "$DIST_ONLY" == 1 ]]; then
  echo "==> --dist-only: web/apex/dist and $APPS_DIST are ready for the Dockerfile's prebuilt target."
  exit 0
fi

echo "==> Assembling $PLATFORM image (COPY + metadata only — no emulation)…"
docker buildx build \
  --platform "$PLATFORM" \
  --target prebuilt \
  --file web/apex/Dockerfile \
  --tag "$IMAGE:$TAG" \
  --load \
  .

ARCH="$(docker image inspect "$IMAGE:$TAG" --format '{{.Architecture}}/{{.Os}}')"
EXPECTED_ARCH="$(echo "$PLATFORM" | sed 's#^linux/##')/linux"
[[ "$ARCH" == "$EXPECTED_ARCH" ]] || { echo "expected $EXPECTED_ARCH, got $ARCH" >&2; exit 1; }
echo "    built $IMAGE:$TAG ($ARCH)"

if [[ "$PUSH" == 1 ]]; then
  echo "==> Pushing $IMAGE:$TAG to GHCR…"
  if ! docker push "$IMAGE:$TAG"; then
    cat >&2 <<'MSG'

Push failed. The overwhelmingly likely cause is the token, not the tag: GHCR needs a CLASSIC PAT
with `write:packages`, and reports an under-scoped one as a generic denial. Re-auth with:

  echo "$GHCR_PAT" | docker login ghcr.io -u b3nedikt --password-stdin
MSG
    exit 1
  fi
  echo "    pushed $IMAGE:$TAG"
fi

if [[ -n "$SHIP_HOST" ]]; then
  echo "==> Loading it on $SHIP_HOST (no registry involved)…"
  SSH_TARGET="$SHIP_HOST"
  # ⚠ Bare hostname means root@ — correct for the DO droplets and for Scaleway, whose Ubuntu
  # images also log in as root. Pass `user@host` for anywhere that does not.
  [[ "$SSH_TARGET" == *@* ]] || SSH_TARGET="root@$SSH_TARGET"
  docker save "$IMAGE:$TAG" | gzip -1 | ssh "$SSH_TARGET" 'gunzip | docker load'
  cat <<EOF2

Image is on $SHIP_HOST but NOTHING is running it yet.
(For the DEV LAB use deploy/ship-dev-stack.sh instead — different env file and compose path.) To cut over:

  ssh $SSH_TARGET "sed -i 's|^APEX_IMAGE_TAG=.*|APEX_IMAGE_TAG=$TAG|' /opt/tenshu/apex.env \\
    && cd /opt/tenshu \\
    && docker compose --env-file /opt/tenshu/apex.env up -d --force-recreate apex"

Then mirror the tag into deploy/digitalocean-apex/terraform.tfvars (apex_image_tag), or the next
\`terraform apply\` silently rolls apex back to whatever is pinned there.
EOF2
fi
