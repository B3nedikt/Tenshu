plugins {
    id("org.jetbrains.kotlin.jvm")
    alias(libs.plugins.ktor.plugin)
    alias(libs.plugins.kotlinx.serialization)
}

// ── Bytecode target: pinned, not inherited ────────────────────────────────────────────────
//
// ⚠⚠ As in `:sync-relay-server`: without a toolchain the bytecode level — and the
// `org.gradle.jvm.version` variant it publishes — is whatever JDK the Gradle daemon runs (major 65
// on a 21 daemon, 61 on a 17 one), so the deployed image's minimum JRE would be a property of the
// builder's machine, not of anything in the repo.
//
// ⚠ `:system-test-utils` consumes this module as well as the relay, so both must target 17;
// otherwise Gradle refuses with *"'project ':commerce-service'' is only compatible with JVM
// runtime version 21 or newer"*.
//
// 17 for the same reasons as the relay: nothing here needs 21 (Ktor is itself Java 8 bytecode;
// no virtual threads, no `SequencedCollection`, no structured concurrency), and 21 makes the
// module unconsumable by the ~100 shared modules, which are all `jvmToolchain(17)` because
// Android caps Kotlin's `jvmTarget` there.
//
// ⚠ Not a deployment change: 17-target bytecode runs unchanged on the production 21 JVM.
kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core-common"))             // Ed25519Backend, BouncyCastleEd25519Backend
    implementation(project(":identity-service"))         // EntitlementSignature.message(...) + publicKeyFor

    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.server.html.builder) // admin console server-rendered table (§4.5)
    // X-Forwarded-For unwrapping, so the per-IP OTP budget and the recorded acceptance IP name the
    // real client rather than Caddy (commerce-end-to-end-dry-run.md CE-2; mirrors the relay).
    implementation(libs.ktor.server.forwarded.header)
    // Browser access for the apex account surface. Native clients are not subject to the
    // same-origin policy, so this service needed no CORS until the web one existed.
    implementation(libs.ktor.server.cors)
    implementation(libs.ktorSerializationJson)
    implementation(libs.logback.classic)

    // Outbound Stripe REST (commerce-accounts-and-sessions.md §6). Hand-rolled over ktor-client (no
    // stripe-java SDK — it would pull a large dep + its own HTTP client, against this module's grain).
    implementation(libs.ktorClientCore)
    implementation(libs.ktorClientCio)

    // Durable account store (commerce-accounts-and-sessions.md §4). Postgres (multi-connection,
    // backups, statutory retention) behind DATABASE_URL; in-memory is the default. Pooled via
    // HikariCP (unlike the relay's single-connection SQLite).
    implementation("org.postgresql:postgresql:42.7.13")
    implementation("com.zaxxer:HikariCP:7.1.0")

    // Real OTP delivery (commerce-end-to-end-dry-run.md CE-2 / D3). Jakarta Mail over SMTP —
    // deliberately SMTP rather than a vendor HTTP API, because every transactional-email provider
    // speaks it, so choosing/replacing a provider stays a `deploy.env` change. Angus Mail is the
    // reference implementation of the jakarta.mail API.
    implementation("org.eclipse.angus:angus-mail:2.0.5")

    testImplementation(libs.kotlin.test)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktorClientMock) // stub Stripe REST for StripeClient tests
}

application {
    mainClass.set("org.tenshu.commerce.ApplicationKt")
}

ktor {
    fatJar {
        // Distinct name from the plain `jar` task's output, same footgun as the relay:
        // both write to build/libs/, so a shared filename would let a later thin `jar`
        // clobber the fat jar. Deploy this `-all` artifact — it bundles the deps.
        archiveFileName.set("commerce-service-all.jar")
    }
}

// CommerceMigrationVersionDerivationTest and CommerceStatutoryProbeTest run shell from deploy/, which is
// not otherwise an input to the test task: without this an edit there leaves the task UP-TO-DATE and the
// guards silently do not run (a mutation check of the statutory probe then "passes" on a stale result).
tasks.withType<Test>().configureEach {
    inputs.files(
        "../deploy/lib/migration-max-version.sh",
        "../deploy/reset-test-data.sh",
    ).withPathSensitivity(PathSensitivity.RELATIVE)
}
