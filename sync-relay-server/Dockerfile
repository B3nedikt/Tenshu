# Multi-stage build for the Tenshu sync relay (`:sync-relay-server`).
#
# The build context is the REPO ROOT (the Gradle build is multi-module), so deploy with
# the context at the root, e.g. from the repo root:
#
#   fly deploy --config sync-relay-server/fly.toml --dockerfile sync-relay-server/Dockerfile
#
# See docs/how-to/deploy-relay-fly.md.

# ---- build stage: assemble the Ktor fat jar ----
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src

# Copy the whole repo (multi-module Gradle). .dockerignore prunes build outputs / .git /
# node_modules / worktrees so the context stays small.
COPY . .

# Cap the build's heaps for small deploy hosts. The repo's gradle.properties is
# tuned for a dev workstation (-Xmx5G Gradle daemon + 4G Kotlin daemon + 4G
# Kotlin/Native); on a 4GB droplet those over-commit and the build OOM-kills the
# moment the Kotlin compile daemon spins up. A GRADLE_USER_HOME gradle.properties
# overrides the project values for THIS build only (dev builds keep the big
# heaps). Sized to fit comfortably inside 4GB RAM with no swap thrash.
RUN mkdir -p /root/.gradle && \
    printf 'org.gradle.jvmargs=-Xmx1536m -Dfile.encoding=UTF-8\norg.gradle.workers.max=2\norg.gradle.parallel=false\nkotlin.daemon.jvm.options=-Xmx1536m\nkotlin.native.jvmArgs=-Xmx1536m\n' \
    > /root/.gradle/gradle.properties

# The Ktor plugin's fat-jar task bundles :core-common etc. into one runnable jar.
RUN chmod +x ./gradlew && ./gradlew --no-daemon :sync-relay-server:buildFatJar

# ---- runtime base: everything about running the relay, shared by both targets ----
#
# ⚠ Split into `base` + two targets so the jar can come from either place WITHOUT duplicating the
# runtime definition. Two Dockerfiles drift; this cannot, because EXPOSE/ENTRYPOINT/heap live here
# once.
#
#   --target full      (default) builds the jar inside the image. What CI uses: its runners are
#                      amd64, so the Gradle step is native there and this is the reproducible path.
#   --target prebuilt  packages an ALREADY-BUILT jar. What `deploy/build-relay-image.sh` uses.
#
# ⚠⚠ Why `prebuilt` has to exist: on an arm host `--target full` runs Gradle under QEMU (~45 min),
# and the relay droplet (1 vCPU / 961 MB) cannot build it at all — so with CI down there was no way
# to ship a relay image. The jar is Java bytecode and architecture-independent, so nothing in it
# actually needs amd64 execution; build it natively, then assemble an image whose only steps are
# COPY and metadata.
FROM eclipse-temurin:21-jre AS base
WORKDIR /app

# Documentary; the relay actually binds $PORT (default 8080) via RelayConfig.fromEnv().
# /data is the Fly volume mount (SQLite db + blob dir); see fly.toml [[mounts]].
EXPOSE 8080

# Heap sizing. Left implicit, HotSpot's ergonomics take **25% of RAM** — on the 1 GB relay
# droplet that is a 242 MB max heap, while the JVM is the only meaningful process on the box.
# That default was the second half of the 2026-07-29 outage: an unbounded `/sync/changes`
# response needed more than the whole heap (the first half is fixed by SYNC_CHANGES_PAGE_LIMIT,
# which caps the response so this is no longer load-bearing — belt and braces).
#
# `MaxRAMPercentage` (not `-Xmx`) so the value follows the container's memory limit instead of
# pinning a number that is wrong on every other host size. 60% leaves room for the JVM's
# non-heap footprint (~125 MB observed: metaspace, code cache, thread stacks, direct buffers)
# plus Caddy and the OS.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=60.0", "-jar", "app.jar"]

# ---- target: prebuilt — jar supplied by the caller ----
#
# ⚠ Requires the `!` exception in .dockerignore: `**/build` is pruned from the context precisely to
# keep it small, and this one artefact has to come back. Build it first:
#     ./gradlew :sync-relay-server:buildFatJar
# or just use deploy/build-relay-image.sh, which does both halves and the amd64 assembly.
FROM base AS prebuilt
COPY sync-relay-server/build/libs/sync-relay-server-all.jar app.jar

# ---- target: full — jar built in-image (CI, and any amd64 host) ----
FROM base AS full
COPY --from=build /src/sync-relay-server/build/libs/sync-relay-server-all.jar app.jar

# ⚠⚠ `full` is LAST deliberately: with no `--target`, Docker builds the final stage, and CI passes
# none. Reordering these two silently switches CI to `prebuilt`, whose jar is not in the context —
# so the build would fail in a way that looks nothing like "someone moved a stage".
