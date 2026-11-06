# Multi-stage build for the apex web frontend (web/apex — TypeScript + React + Vite).
#
# Unlike the relay, apex has NO server-side runtime: the output is a static SPA
# (Vite `dist/`) served by Caddy. The wrinkle is the BUILD — apex's JS depends on
# the Kotlin→JS SDK bundle (`:tenshu-sdk-js`), which is gitignored and Gradle-built.
# So a self-contained image needs two toolchains in sequence:
#
#   1. JDK 21 + Gradle  → build the SDK bundle (tenshu-sdk-js/build/dist/...)
#   2. Node + pnpm      → build apex (consumes the bundle via a relative import)
#   3. Caddy            → serve dist/ with SPA fallback + automatic HTTPS
#
# The build context is the REPO ROOT (the Gradle build is multi-module AND the
# @tenshu/sdk workspace package re-exports from `tenshu-sdk-js/build/...`, one
# level above web/). The shared root .dockerignore already prunes build outputs,
# node_modules, dist, .git and worktrees so the context stays small. Build from
# the repo root, e.g.:
#
#   docker build -f web/apex/Dockerfile --build-arg VITE_TENSHU_RELAY_URL=https://relay.tenshu.app .
#
# See docs/how-to/deploy-apex-digitalocean.md.

# ---- stage 1: build the Kotlin→JS SDK bundle -------------------------------
# This is the RAM-hungry half (multi-module Kotlin/JS compile + webpack), same
# profile as the relay's fat-jar build — hence the heap caps below so it survives
# on a modest box. CI (the prebuilt-image path) runs this on a GitHub runner with
# plenty of RAM; on-box builds lean on the cloud-init swapfile.
FROM eclipse-temurin:21-jdk AS sdk
WORKDIR /src

COPY . .

# Cap the build's heaps for small deploy hosts — the repo's gradle.properties is
# tuned for a dev workstation and over-commits on a 4GB droplet (identical
# reasoning to sync-relay-server/Dockerfile).
RUN mkdir -p /root/.gradle && \
    printf 'org.gradle.jvmargs=-Xmx1536m -Dfile.encoding=UTF-8\norg.gradle.workers.max=2\norg.gradle.parallel=false\nkotlin.daemon.jvm.options=-Xmx1536m\nkotlin.native.jvmArgs=-Xmx1536m\n' \
    > /root/.gradle/gradle.properties

# Produces tenshu-sdk-js/build/dist/js/productionExecutable/tenshu-sdk-js.js
# (+ its .d.ts) — the artefact web/sdk/src/_bundle.ts imports.
RUN chmod +x ./gradlew && ./gradlew --no-daemon :tenshu-sdk-js:jsBrowserDistribution

# ---- stage 2: build the apex SPA -------------------------------------------
# ⚠ 22, not 20. `vite build` alone still works on 20.20.1 (vite 8's floor is ^20.19.0,
# and react-router 8's `>=22.22.0` is inert here because it is browser-bundled — Node
# never executes it). The floor that bites is jsdom 30's `^22.22.2 || ^24.15.0 ||
# >=26.0.0`, which is why .github/workflows/web.yml is on 22; this stage tracks it so
# the image and CI are not built on two different runtimes. Node 20 is also out of LTS.
FROM node:22-slim AS web
WORKDIR /src

# Pin the same pnpm the workspace declares (web/package.json packageManager).
RUN corepack enable && corepack prepare pnpm@10.34.5 --activate

# The relay URL is baked in at BUILD time (Vite inlines import.meta.env.*). Defaults
# to the production relay; override per-build for a different relay host. See
# web/apex/src/lib/relayUrl.ts + realOnboardingSdk.ts.
ARG VITE_TENSHU_RELAY_URL=https://relay.tenshu.app
ENV VITE_TENSHU_RELAY_URL=${VITE_TENSHU_RELAY_URL}

# Pre-launch construction gate (web/apex/src/components/ConstructionGate.tsx): when set to a
# 6-digit code, the apex is curtained until a visitor enters it. Empty (the default) = no gate.
# Polish, not security — see docs/how-to/deploy-apex-digitalocean.md. Set it per-build via apex.env
# to hide the site before marketing; clear it to lift the curtain.
ARG VITE_CONSTRUCTION_CODE=
ENV VITE_CONSTRUCTION_CODE=${VITE_CONSTRUCTION_CODE}

# Pre-launch signup gate (web/apex/src/lib/signupGate.ts): any non-empty value closes the door
# behind "Get started" while leaving the marketing site public and existing realms in the visitor's
# browser working. Empty (the default) = signup open, so a test deployment needs no configuration
# and only the public build sets it. A curtain, not security — the relay is unchanged.
ARG VITE_SIGNUP_DISABLED=
ENV VITE_SIGNUP_DISABLED=${VITE_SIGNUP_DISABLED}

# Each app's partner origin, inlined at build time (web-app-suite.md F2/F4): every app dials the
# apex at VITE_APEX_ORIGIN, and the apex frames and allows each hosted app `<id>` at its own
# `https://<id>.tenshu.app` — the production default every app's registry entry falls back to in a
# production build (web/apex/src/apps/registry). To build for other hosts, pass APP_ORIGINS as
# space-separated `VITE_<ID>_ORIGIN=…` words (deploy/lib/web-apps.sh `web_app_vite_origins` prints
# them); deploy/build-apex-image.sh does this for the `prebuilt` path.
ARG VITE_APEX_ORIGIN=https://tenshu.app
ENV VITE_APEX_ORIGIN=${VITE_APEX_ORIGIN}
ARG APP_ORIGINS=

# Bring in the web sources (the .dockerignore drops node_modules/dist/build), then
# graft the Gradle-built bundle from stage 1 back into its expected path — the
# @tenshu/sdk package imports it via ../../../tenshu-sdk-js/build/dist/...
COPY web ./web
# The screenshot manifests the support pages resolve their images through (support-pages/generate.ts,
# store-presence-and-support-pages.md §3.3). ⚠ Without them every slot silently renders as a
# placeholder. Text only: .dockerignore keeps generated captures out of the context.
COPY store ./store
COPY --from=sdk /src/tenshu-sdk-js/build ./tenshu-sdk-js/build

WORKDIR /src/web
RUN pnpm install --frozen-lockfile
# `tsc -b && vite build` — the real production gate (bare tsc --noEmit skips app files).
# APP_ORIGINS is deliberately unquoted: one `VITE_<ID>_ORIGIN=…` word per app.
RUN env $APP_ORIGINS pnpm -F apex build
# Every hosted app (deploy/lib/web-apps.sh: a `web/<id>` with a vite.config.ts, apex aside), staged
# as /src/web/.apps-dist/<id> — the same tree build-apex-image.sh stages for `prebuilt`.
# ⚠ bash, not the image's /bin/sh (dash): the helper uses BASH_SOURCE. ⚠ `rm` first: `COPY web` may
# have brought a staged tree from a local `prebuilt` build along, and `cp -R` into an existing
# directory nests instead of replacing.
COPY deploy/lib/web-apps.sh /src/deploy/lib/web-apps.sh
RUN bash -c '. /src/deploy/lib/web-apps.sh && rm -rf .apps-dist && mkdir .apps-dist && for app in $(web_app_ids); do \
      pnpm -F "$app" build && cp -R "$app/dist" ".apps-dist/$app" || exit 1; \
    done'

# ---- runtime base: Caddy + the site config, shared by both targets ---------
#
# ⚠ Split into `base` + two targets so `dist/` can come from either place WITHOUT duplicating the
# runtime definition — the same shape as sync-relay-server/Dockerfile, for the same reason.
#
#   --target full      (default) builds the SPA inside the image. What CI uses: its runners are
#                      amd64, so the Gradle + pnpm steps are native there.
#   --target prebuilt  packages an ALREADY-BUILT dist/. What `deploy/build-apex-image.sh` uses.
#
# ⚠⚠ Why `prebuilt` has to exist: on an arm host `--target full` runs BOTH toolchains under QEMU
# (Kotlin/JS + webpack, then pnpm + vite), so with CI down there is otherwise no way to ship an apex
# image. A built SPA is static text — nothing in it needs amd64 EXECUTION, only the Caddy base layer
# does. Build it natively, then assemble an image whose only instructions are COPY and metadata.
#
# ⚠ The relay URL, construction code and signup gate are inlined by Vite at BUILD time, so with `prebuilt` they
# come from the native build's env, NOT from this Dockerfile's ARGs. build-apex-image.sh passes them.
# ⚠ 2.11, not 2: the Caddyfile's logs use `roll_interval`, which caddy 2.10 refuses at startup, and
# a builder with an old cached `caddy:2` would ship it (docs/how-to/server-logging.md).
FROM caddy:2.11 AS base
# The Caddyfile + the built site are baked into the image (apex is stateless), so
# the only runtime volumes on the droplet are Caddy's ACME cert stores.
COPY web/apex/Caddyfile /etc/caddy/Caddyfile

EXPOSE 80 443

# ---- target: prebuilt — dist/ supplied by the caller ----
#
# ⚠ Requires the `!web/apex/dist` exceptions in .dockerignore: `web/**/dist` is pruned from the
# context to keep it small, and this one artefact has to come back. Build it first:
#     ./gradlew :tenshu-sdk-js:jsBrowserDistribution && pnpm -C web -F apex build
# or just use deploy/build-apex-image.sh, which does both halves and the amd64 assembly.
FROM base AS prebuilt
COPY web/apex/dist /srv
# Every hosted app at /srv-apps/<id>, staged by build-apex-image.sh (one dir per app, built for its
# origins). ⚠ Outside /srv: see the `hosted_app` snippet in web/apex/Caddyfile.
COPY web/.apps-dist /srv-apps

# ---- target: full — SPA built in-image (CI, and any amd64 host) ----
FROM base AS full
COPY --from=web /src/web/apex/dist /srv
COPY --from=web /src/web/.apps-dist /srv-apps

# ⚠⚠ `full` is LAST deliberately: with no `--target`, Docker builds the final stage, and CI passes
# none. Reordering these two silently switches CI to `prebuilt`, whose dist/ is not in the context —
# so the build would fail in a way that looks nothing like "someone moved a stage".
