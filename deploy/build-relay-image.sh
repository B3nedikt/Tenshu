#!/usr/bin/env bash
# Build a linux/amd64 relay image from a NATIVELY-built fat jar, and optionally ship it straight to
# a droplet without a registry.
#
#   ./deploy/build-relay-image.sh                      # build only, tag = short HEAD sha
#   ./deploy/build-relay-image.sh --ship relay.tenshu.app   # build, then load it on that host
#   PLATFORM=linux/arm64 ./deploy/build-relay-image.sh    # cross-build for an arm64 host
#   ./deploy/build-relay-image.sh --tag 1c67fe42       # pin the tag explicitly
#   ./deploy/build-relay-image.sh --push                 # build, then publish it to GHCR
#
# ## Why this exists
#
# `sync-relay-server/Dockerfile`'s default `full` target runs Gradle INSIDE an amd64 image. On CI
# that is native and fine. On an arm Mac it is QEMU — roughly 45 minutes — and the relay droplet
# (1 vCPU / 961 MB) cannot build it at all. With CI down (billing) that left no way to ship a relay
# image, which is exactly the position we were in on 2026-08-08.
#
# ⚠⚠ The insight the `prebuilt` target encodes: a fat jar is Java bytecode and therefore
# architecture-independent. Nothing about producing the image needs amd64 EXECUTION — only the base
# layers do. So build the jar natively (seconds), then assemble an image whose only instructions are
# COPY and metadata. No RUN, no emulation.
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
# ⚠ This does NOT restart anything. Loading an image is inert; pointing the relay at it is a
# separate, deliberate step (see the printed instructions, or deploy/redeploy.sh for the registry
# path).

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

IMAGE="ghcr.io/b3nedikt/tenshu-relay"
TAG=""
SHIP_HOST=""
PUSH=0
# ⚠ The `prebuilt` target is COPY-only over a multi-arch base, so building for the OTHER
# architecture costs nothing and emulates nothing — only the base layer differs. Every current
# deploy target is amd64 (DigitalOcean, and Scaleway's DEV1/PRO2 line), so this default is right;
# the knob exists for an arm64 host, which the dev lab deliberately is not.
PLATFORM="${PLATFORM:-linux/amd64}"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --tag)  TAG="${2:?--tag needs a value}"; shift 2 ;;
    --platform) PLATFORM="${2:?--platform needs linux/amd64 or linux/arm64}"; shift 2 ;;
    --ship) SHIP_HOST="${2:?--ship needs a host}"; shift 2 ;;
    --push) PUSH=1; shift ;;
    -h|--help) sed -n '2,43p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "unknown arg: $1" >&2; exit 1 ;;
  esac
done

# Default to the short HEAD sha, matching what redeploy.sh and terraform.tfvars expect.
# ⚠ A dirty tree still tags with HEAD's sha, which would label an image that is not that commit.
if [[ -z "$TAG" ]]; then
  TAG="$(git rev-parse --short=8 HEAD)"
  if ! git diff --quiet || ! git diff --cached --quiet; then
    echo "⚠  working tree is dirty — tagging as '$TAG' would misname this image."
    echo "   Commit, stash, or pass --tag <something-else>." >&2
    exit 1
  fi
fi

echo "==> Building the fat jar natively (arch-independent bytecode)…"
./gradlew --console=plain :sync-relay-server:buildFatJar

JAR="sync-relay-server/build/libs/sync-relay-server-all.jar"
[[ -f "$JAR" ]] || { echo "expected $JAR to exist after buildFatJar" >&2; exit 1; }
echo "    $(du -h "$JAR" | cut -f1)  $JAR"

echo "==> Assembling $PLATFORM image (COPY + metadata only — no emulation)…"
docker buildx build \
  --platform "$PLATFORM" \
  --target prebuilt \
  --file sync-relay-server/Dockerfile \
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
  cat <<EOF

Image is on $SHIP_HOST but NOTHING is running it yet.
(For the DEV LAB use deploy/ship-dev-stack.sh instead — different env file and compose path.) To cut over:

  ssh $SSH_TARGET "sed -i 's|^RELAY_IMAGE_TAG=.*|RELAY_IMAGE_TAG=$TAG|' /opt/tenshu/deploy.env \\
    && cd /opt/tenshu/repo \\
    && docker compose -f sync-relay-server/docker-compose.yml \\
         --env-file /opt/tenshu/deploy.env up -d --force-recreate relay"

Then mirror the tag into deploy/scaleway-relay/terraform.tfvars (relay_image_tag) — the relay runs
on Scaleway; deploy/digitalocean is the retired box — or a rebuilt instance comes up on whatever is
pinned there.
⚠ A --ship-only tag is on this one disk and not in GHCR, so a rebuilt instance cannot pull it at
all. Pinning it in tfvars is only reproducible once it has also been pushed (--push).
EOF
fi
