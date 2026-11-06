plugins {
    id("org.jetbrains.kotlin.jvm")
}

/**
 * The JDK this module compiles with — see the toolchain block below. 25 is the
 * current LTS and is past FFM's finalisation in 22. Gradle auto-provisions it via
 * the `foojay-resolver-convention` in `settings.gradle.kts`; no system install.
 * Keep in step with the desktop apps' bundled runtime (`:control-desktop`,
 * `:drive-desktop`), which is what FFM actually runs on.
 */
val FFM_JDK = 25

// A tiny, Compose-free JVM module for **bundled macOS helper executables** — the
// swiftc-built binaries the JVM shells out to when a platform capability has no
// portable equivalent.
//
// It exists because of who needs the image decoder. `MacImageDecoder` bridges to
// ImageIO for the formats Skia can't read (HEIC above all — see
// `drive-content-preview.md` §3.1) and has **two** consumers on opposite sides of
// the app: the Compose viewer in `:ui-core`/`:drive-shared-ui`, and the proxy-side
// thumbnail generator in `:file-service-proxy`. A service module must not depend on
// a Compose UI module, so the launcher can't live in `:ui-core` — hence this
// module, which depends on nothing but the Kotlin stdlib.
//
// `HelperBinary` (bundle-first, classpath-resource-fallback resolution) is generic
// and serves `:ui-qr-scanner`'s `qr-scan-helper` too, which still builds and ships its
// binary from that module — only the *resolution* logic is shared.
// ── Toolchain: compile on a final-FFM JDK, emit JVM-17 bytecode ───────────────
//
// `java.lang.foreign` (Project Panama) is final in JDK 22, and the macOS camera
// work planned in `desktop-qr-scanning.md` §13 calls it from here — so this module
// must *compile* against a 22+ JDK. It must NOT *emit* 22+ bytecode, though: its
// consumers (`:ui-core`, `:drive-shared-ui`, `:file-service-proxy`, …) are all on
// `jvmToolchain(17)`, and a v66+ class file is unreadable to a 17-target compiler.
//
// Kotlin makes that split available where javac's `--release` does not: with no
// `-Xjdk-release`, kotlinc compiles against the *toolchain's full API* while
// `jvmTarget` decides the emitted class-file version. So FFM is visible here, and
// what lands in the jar stays consumable by the 17 world — which is why the JDK
// bump is contained to this module plus the two desktop apps (whose jpackage
// runtime must be 22+ for FFM to *run*), instead of the ~100 modules on 17.
//
// `java.{source,target}Compatibility` are pinned to match, or Gradle's
// Java/Kotlin JVM-target consistency check fails the build (there are no Java
// sources here; the setting exists to keep the two toolchains agreeing).
kotlin {
    jvmToolchain(FFM_JDK)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    testImplementation(libs.kotlin.test)
}

// `PeerAuditToken` recovers a socket's file descriptor by reflection — the JDK exposes no
// public route to it — so the test JVM needs the same opens the desktop apps pass
// (`desktop-ipc-client-identity.md` P1.1). Without them every attestation fails at the fd,
// which is *indistinguishable from a working gate rejecting a peer*: the imposter test would
// still pass, for entirely the wrong reason. That false-pass is why the suite also asserts
// what a rejection was caused by, not just that one happened.
tasks.withType<Test>().configureEach {
    jvmArgs(
        "--add-opens", "java.base/sun.nio.ch=ALL-UNNAMED",
        "--add-opens", "java.base/java.io=ALL-UNNAMED",
        "--enable-native-access=ALL-UNNAMED",
    )
}

// --- macOS image-decode helper: compile (swiftc) + embed as a jar resource ---
// Mirrors :ui-qr-scanner's compileQrScanHelper (docs/proposals/desktop-qr-scanning.md
// §6–§7). macOS-only: registering the task under a build-host check (rather than
// via `onlyIf`) keeps the Exec task free of execution-time closures that would
// capture the build script — required for the configuration cache. On other hosts
// the resource dir is simply empty and `MacImageDecoder.isAvailable()` is false.
val imageHelperOutDir = layout.buildDirectory.dir("image-helper").get().asFile

// The AM-5 map-snapshot helper rides the same rails as the image decoder: swiftc on a Mac host, the
// binary lands in a resource dir, `HelperBinary` finds it by name (bundle copy first, extracted jar
// resource for dev runs). ⚠ Its own dir, not a shared one — two Exec tasks writing one directory
// would make each other's outputs stale and re-run forever.
val mapHelperOutDir = layout.buildDirectory.dir("map-helper").get().asFile

sourceSets {
    named("main") {
        resources.srcDir(imageHelperOutDir)
        resources.srcDir(mapHelperOutDir)
    }
}

if (System.getProperty("os.name").orEmpty().contains("mac", ignoreCase = true)) {
    val imageHelperSwift = file("src/main/swift/image-decode-helper.swift")
    val imageHelperBin = imageHelperOutDir.resolve("image-decode-helper")

    val compileImageDecodeHelper by tasks.registering(Exec::class) {
        group = "macos"
        description =
            "Compiles the macOS image-decode helper (image-decode-helper.swift) with swiftc."
        inputs.file(imageHelperSwift)
        inputs.property("macosDeploymentTarget", project.property("tenshu.macosDeploymentTarget").toString())
        outputs.file(imageHelperBin)
        // mkdir runs at execution time (survives `clean`); all-string command, no
        // script-capturing closures → configuration-cache safe.
        commandLine(
            "sh", "-c",
            "mkdir -p '${imageHelperOutDir.absolutePath}' && " +
                "swiftc -O -target arm64-apple-macos${project.property("tenshu.macosDeploymentTarget")} '${imageHelperSwift.absolutePath}' -o '${imageHelperBin.absolutePath}'",
        )
    }

    val mapHelperSwift = file("src/main/swift/map-snapshot-helper.swift")
    val mapHelperBin = mapHelperOutDir.resolve("map-snapshot-helper")

    val compileMapSnapshotHelper by tasks.registering(Exec::class) {
        group = "macos"
        description =
            "Compiles the macOS map-snapshot helper (map-snapshot-helper.swift) with swiftc."
        inputs.file(mapHelperSwift)
        inputs.property("macosDeploymentTarget", project.property("tenshu.macosDeploymentTarget").toString())
        outputs.file(mapHelperBin)
        // ⚠ `-framework MapKit` — the decoder needs no explicit framework because ImageIO rides
        // Foundation; MapKit does not. No entitlements are passed and none may be: AM-5a proved a
        // snapshot renders without them, and claiming `com.apple.developer.maps` without a
        // provisioning profile is an instant SIGKILL.
        commandLine(
            "sh", "-c",
            "mkdir -p '${mapHelperOutDir.absolutePath}' && " +
                "swiftc -O -target arm64-apple-macos${project.property("tenshu.macosDeploymentTarget")} '${mapHelperSwift.absolutePath}' -o '${mapHelperBin.absolutePath}' " +
                "-framework MapKit -framework AppKit",
        )
    }

    tasks.named("processResources") {
        dependsOn(compileImageDecodeHelper, compileMapSnapshotHelper)
    }

    // Not a jar resource: only a signed bundle can claim the container, so only the bundle copy
    // (`embedAppGroupClaim` in gradle/desktop-app-signing.gradle.kts) is ever run.
    val claimHelperOutDir = layout.buildDirectory.dir("appgroup-claim").get().asFile
    val claimHelperSwift = file("src/main/swift/appgroup-claim.swift")
    val claimHelperBin = claimHelperOutDir.resolve("appgroup-claim")

    tasks.register<Exec>("compileAppGroupClaim") {
        group = "macos"
        description = "Compiles the App Group container claim helper (appgroup-claim.swift) with swiftc."
        inputs.file(claimHelperSwift)
        inputs.property("macosDeploymentTarget", project.property("tenshu.macosDeploymentTarget").toString())
        outputs.file(claimHelperBin)
        commandLine(
            "sh", "-c",
            // ⚠ `xcrun --sdk macosx`, not bare `swiftc`: a daemon started from an Xcode iOS build
            // keeps SDKROOT=…iPhoneOS…, and this now gates all ten desktop apps (drive-desktop's
            // DomainRegistrar note).
            "mkdir -p '${claimHelperOutDir.absolutePath}' && " +
                "xcrun --sdk macosx swiftc -O -target arm64-apple-macos${project.property("tenshu.macosDeploymentTarget")} '${claimHelperSwift.absolutePath}' -o '${claimHelperBin.absolutePath}'",
        )
    }
}
