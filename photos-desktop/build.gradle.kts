import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.jetbrains.compose)
    alias(libs.plugins.kotlin.compose)
}

// Shared macOS code-signing (desktop-ipc-client-identity.md P0), so the desktop IPC
// peer-credential gate can attest this app. Self-skips when unconfigured.
apply(from = rootProject.file("gradle/desktop-app-signing.gradle.kts"))

// Photos desktop run target (`docs/proposals/photos-app.md` PH-1): `./gradlew :photos-desktop:run`.
// Mirrors :contacts-desktop — same desktop ViewModelStore / SavedState shims, and the same
// requirement that Control-desktop be running, since the feed is a read against its proxy.
/**
 * JDK for this app: compiles it AND becomes its jpackage-bundled runtime. 22+ because the client side
 * of the IPC gate (`ControlServerCheck` → `MacPeerAttestation`) calls `java.lang.foreign`, final in
 * 22: on a bundled 21 every attestation fails with `NoSuchMethodError` and the app refuses the
 * genuine Control. Same JDK as control-desktop and drive-desktop, which explain the toolchain split.
 */
val FFM_JDK = 25

kotlin {
    jvmToolchain(FFM_JDK)
    jvm()

    sourceSets {
        getByName("jvmMain") {
            dependencies {
                implementation(project(":photos-shared-ui"))
                implementation(project(":ui-core"))
                implementation(project(":core-common"))
                implementation(compose.desktop.currentOs)
                implementation(libs.koinCore)
                implementation(libs.koinMultiplatform)
                implementation(libs.koinComposeViewmodel)
                implementation(libs.lifecycle.viewmodel.compose)
                implementation(libs.lifecycle.runtime.compose)
                implementation(libs.savedstate)
                implementation(libs.savedstate.compose)
                implementation(libs.kotlinx.coroutines.swing)
                implementation("org.slf4j:slf4j-simple:2.0.19")
            }
        }
    }
}

// The client side of the desktop IPC gate (`ControlServerCheck` → `MacPeerAttestation`): FFM, and
// the two opens `PeerAuditToken` needs to reach a channel's file descriptor. ⚠⚠ Without the opens
// every attestation is `Unavailable` and a packaged app refuses the genuine Control; a dev run hides
// it (developer mode admits). Applied at BOTH JVM sites, `run` and the packaged app, as in
// control-desktop; `ControlServerCheckTest` checks every desktop app has them.
val PEER_GATE_JVM_ARGS = listOf(
    "--add-opens", "java.base/sun.nio.ch=ALL-UNNAMED",
    "--add-opens", "java.base/java.io=ALL-UNNAMED",
    "--enable-native-access=ALL-UNNAMED",
)

tasks.withType<JavaExec> {
    if (name == "run" || name == "jvmRun") {
        mainClass.set("app.tenshu.photos.MainKt")
        // Fix for Skiko UnsatisfiedLinkError on macOS.
        jvmArgs("-Dskiko.renderApi=METAL")
        // Dev run is not a release build: A16 developer mode lets it reach a dev-run Control, an unsigned
        // `java` process (`ControlServerCheck`). A packaged app never gets this flag.
        jvmArgs("-Dtenshu.release=false")
        jvmArgs(PEER_GATE_JVM_ARGS)
    }
}

// jpackage embeds `javaHome`, defaulting to the Gradle daemon's JVM: point it at the FFM toolchain,
// resolved tolerantly (control-desktop explains why a missing 25 must not fail configuration).
val ffmRuntime: String? = runCatching {
    javaToolchains
        .launcherFor { languageVersion.set(JavaLanguageVersion.of(FFM_JDK)) }
        .get().metadata.installationPath.asFile.absolutePath
}.getOrNull()

compose.desktop {
    application {
        ffmRuntime?.let { javaHome = it }
        // The packaged twin of the `run` args: see PEER_GATE_JVM_ARGS.
        jvmArgs += PEER_GATE_JVM_ARGS
        mainClass = "app.tenshu.photos.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Pkg)
            packageName = "TenshuPhotos"
            packageVersion = "1.0.1"
            macOS {
                // Pinned explicitly: the desktop IPC gate authenticates on the signed identifier,
                // so it must not be left to the plugin's derivation.
                bundleID = "app.tenshu.photos.ios"
                // LSMinimumSystemVersion (D10); jpackage's default (10.13) is refused for arm64-only.
                minimumSystemVersion = project.property("tenshu.macosDeploymentTarget").toString()
                // ⚠ macOS .icns must follow Apple's icon grid: the body is inset to
                // ~824/1024 (~100px transparent margin) with a continuous squircle —
                // NOT the full-bleed iOS/PWA tile, which makes the Dock icon ~25%
                // oversized vs every other app. Regen recipe: docs/adr/0026-app-icon-system.md §6.
                iconFile.set(project.file("icons/photos.icns"))
            }
        }
    }
}
