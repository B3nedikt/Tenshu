plugins {
    id("org.jetbrains.kotlin.jvm")
    alias(libs.plugins.ktor.plugin)
    alias(libs.plugins.kotlinx.serialization)
}

// ── Bytecode target: pinned, not inherited ────────────────────────────────────────────────
//
// ⚠⚠ Without this the module declares NOTHING, so its bytecode level — and the
// `org.gradle.jvm.version` variant attribute it publishes — is whatever JDK the Gradle daemon
// happens to run on. Measured before this line: `ApplicationKt.class` major **65** (Java 21) on a
// 21 daemon; a JDK-17 machine would have produced 61. The deployed fat jar's minimum JRE was
// therefore a property of the builder's machine rather than of anything in the repo.
//
// **17, not 21**, for two reasons:
//  - Nothing here needs 21. Ktor is itself Java 8 bytecode (major 52), and the module uses no
//    JDK-21-only API (no virtual threads, no `SequencedCollection`, no structured concurrency).
//  - 21 made the relay *unconsumable* by the ~100 shared modules, which are all `jvmToolchain(17)`
//    because Android caps Kotlin's `jvmTarget` there. That asymmetry is why the EPC Phase-3b
//    end-to-end test lives in `:system-test-utils` (which pins 21 *specifically* to consume this
//    module) rather than beside its client code in `:sync-service-proxy`.
//
// ⚠ Not a deployment change: 17-target bytecode runs unchanged on the production 21 JVM. The
// reverse — bumping the shared modules to 21 — is the direction that does not work.
kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core-common"))
    implementation(project(":sync-service-common"))
    implementation(project(":identity-service-common"))

    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.cors)
    // Per-IP rate limiting on the unauth `POST /sync/realm` bootstrap (RelayConfig.genesisRateLimitPerHour);
    // X-Forwarded-For so the bucket keys on the real client behind Caddy, not Caddy's address.
    implementation(libs.ktor.server.rate.limit)
    implementation(libs.ktor.server.forwarded.header)
    implementation(libs.ktorSerializationJson)
    implementation(libs.logback.classic)

    // Durable persistence backend (docs/proposals/sync-relay-persistence.md, Phase 1).
    // SQLite-only for v1: single-node, zero-container. Postgres is a future SPI backend.
    implementation(libs.sqlite.jdbc)

    // S3BlobStore — the content plane's bytes off the block volume
    // (`sync-relay-persistence.md` §7 + §8 Ph4; `hosting-provider-economics.md` D10).
    //
    // ⚠ `url-connection-client` INSTEAD of the default Netty transport, which the `exclude`
    // drops. Measured 2026-09-01: s3 + netty = 43 jars / 12.4 MB, s3 + url-connection = 33 jars
    // / 8.6 MB — a 31% cut for calls that are plain blocking request/response, where the async
    // stack earns nothing. (For the record, the "lighter alternative" is not: the MinIO client
    // measured 21.5 MB, heavier than either, because it drags Guava + OkHttp.)
    implementation(libs.awssdk.s3) {
        exclude(group = "software.amazon.awssdk", module = "netty-nio-client")
    }
    implementation(libs.awssdk.url.connection.client)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(project(":core-test-utils"))

    // A real, third-party S3 implementation for S3BlobStore's contract run
    // (`hosting-provider-economics.md` D10 S5). Test-only, and heavy — ~140 jars — but it is the
    // only S3 we can reach: every container registry's blob CDN is unreachable from CI's network,
    // so MinIO-in-compose (`sync-relay-persistence.md` §6) cannot be pulled. Started
    // programmatically rather than via its JUnit5 extension, since this module is on JUnit4.
    testImplementation(libs.s3mock)
}

ktor {
    fatJar {
        // Distinct name from the plain `jar` task's output (`sync-relay-server.jar`).
        // They MUST NOT share a filename: both write to build/libs/, so a later
        // `jar`/`build`/`assemble` would silently clobber the fat jar with a thin one
        // (no Ktor, no :core-common) and that thin jar would get deployed. Deploy this
        // `-all` artifact — it is the only jar that bundles the dependency classes.
        archiveFileName.set("sync-relay-server-all.jar")
    }
}

application {
    mainClass.set("org.tenshu.sync.relay.ApplicationKt")
}

// ⚠⚠ `RelayConfig.fromEnv` fails CLOSED: an unset DEV_MODE means the X-Dev-Owner bypass is OFF
// (relay-metadata-minimisation.md D13). The local dev loop — `./gradlew :sync-relay-server:run`,
// which emulators, simulators, the JS integration tests and the apex dev server all point at —
// relies on dev mode, so THIS task opts in explicitly. A DEV_MODE already in the caller's shell
// wins, so `DEV_MODE=false ./gradlew :sync-relay-server:run` exercises the real JWT path locally.
// Only `run` gets this: the fat jar / Docker image carries no default and so fails closed.
tasks.named<JavaExec>("run") {
    environment("DEV_MODE", providers.environmentVariable("DEV_MODE").getOrElse("true"))
}

// ComposeEnvPassthroughTest reads docker-compose.yml, which is not otherwise an input to the test
// task — so without this a compose-only edit leaves the task UP-TO-DATE and the guard silently does
// not run. (That is exactly how the mutation check for it first "passed".)
tasks.withType<Test>().configureEach {
    inputs.file("docker-compose.yml").withPathSensitivity(PathSensitivity.RELATIVE)
    // Same reason for CaddyLogFilterTest: the Caddyfiles it checks, and the apex routes it reads.
    inputs.files(
        "Caddyfile",
        "../web/apex/Caddyfile",
        "../deploy/scaleway-dev/stack/Caddyfile",
        "../web/apex/src/RuntimeApp.tsx",
        // RelayMigrationVersionDerivationTest runs the reset script's version parser.
        "../deploy/lib/migration-max-version.sh",
    ).withPathSensitivity(PathSensitivity.RELATIVE)
}
