# Multi-stage build for the Tenshu commerce issuer (`:commerce-service`).
#
# Mirrors sync-relay-server/Dockerfile. The build context is the REPO ROOT (the Gradle build is
# multi-module), so build with the context at the root, e.g. from the repo root:
#
#   docker build -f commerce-service/Dockerfile -t tenshu-commerce .
#
# See docs/how-to/deploy-commerce-digitalocean.md. This is a SEPARATE trust boundary from the relay
# (commerce-billing-plane.md D1): it owns the issuer's private Ed25519 seed (A29) + the email
# whitelist (A28); neither ever reaches the relay. Deploy it independently of the relay.

# ---- build stage: assemble the Ktor fat jar ----
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src

# Copy the whole repo (multi-module Gradle). .dockerignore prunes build outputs / .git /
# node_modules / worktrees so the context stays small.
COPY . .

# Cap the build's heaps for small deploy hosts (same rationale as the relay Dockerfile): the repo's
# gradle.properties is tuned for a dev workstation and over-commits on a 4GB droplet. A
# GRADLE_USER_HOME gradle.properties overrides the project values for THIS build only.
RUN mkdir -p /root/.gradle && \
    printf 'org.gradle.jvmargs=-Xmx1536m -Dfile.encoding=UTF-8\norg.gradle.workers.max=2\norg.gradle.parallel=false\nkotlin.daemon.jvm.options=-Xmx1536m\nkotlin.native.jvmArgs=-Xmx1536m\n' \
    > /root/.gradle/gradle.properties

# The Ktor plugin's fat-jar task bundles :core-common + :identity-service into one runnable jar.
RUN chmod +x ./gradlew && ./gradlew --no-daemon :commerce-service:buildFatJar

# ---- runtime base: everything about running the issuer, shared by both targets ----
#
# ⚠ Split into `base` + two targets so the jar can come from either place WITHOUT duplicating the
# runtime definition — the same shape as sync-relay-server/Dockerfile, for the same reason. Two
# runtime stages drift; this cannot, because EXPOSE/ENTRYPOINT live here once.
#
#   --target full      (default) builds the jar inside the image. What CI uses: its runners are
#                      amd64, so the Gradle step is native there and this is the reproducible path.
#   --target prebuilt  packages an ALREADY-BUILT jar. What `deploy/build-commerce-image.sh` uses.
#
# ⚠⚠ Why `prebuilt` has to exist: on an arm host `--target full` runs Gradle under QEMU (~45 min),
# so with CI down there is otherwise no way to ship a commerce image. The jar is Java bytecode and
# architecture-independent, so nothing in it needs amd64 EXECUTION — only the base layers do. Build
# it natively, then assemble an image whose only instructions are COPY and metadata.
FROM eclipse-temurin:21-jre AS base
WORKDIR /app

# Documentary; the service actually binds $PORT (default 8081) via CommerceConfig.fromEnv().
EXPOSE 8081

ENTRYPOINT ["java", "-jar", "app.jar"]

# ---- target: prebuilt — jar supplied by the caller ----
#
# ⚠ Requires the `!` exception in .dockerignore: `**/build` is pruned from the context precisely to
# keep it small, and this one artefact has to come back. Build it first:
#     ./gradlew :commerce-service:buildFatJar
# or just use deploy/build-commerce-image.sh, which does both halves and the amd64 assembly.
FROM base AS prebuilt
# Stateless service (no DB / volume): the issuer seed + whitelist come from env at boot.
COPY commerce-service/build/libs/commerce-service-all.jar app.jar

# ---- target: full — jar built in-image (CI, and any amd64 host) ----
FROM base AS full
COPY --from=build /src/commerce-service/build/libs/commerce-service-all.jar app.jar

# ⚠⚠ `full` is LAST deliberately: with no `--target`, Docker builds the final stage, and CI passes
# none. Reordering these two silently switches CI to `prebuilt`, whose jar is not in the context —
# so the build would fail in a way that looks nothing like "someone moved a stage".
