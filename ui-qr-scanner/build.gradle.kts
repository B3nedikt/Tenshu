plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.jetbrains.compose)
    alias(libs.plugins.kotlin.compose)
}

// The camera QR scanner (`QrScanner`), split out of `:ui-core` so that only the apps that scan
// package the camera stack. Every Android app depends on `:ui-core`; only Control (pairing) and
// Drive (contact invites) scan. ⚠ Depend on this module only from an app that declares `CAMERA`.
//
// ⚠ Android decodes with ZXing (pure Java, no network), NOT ML Kit: ML Kit's barcode model logs
// usage to Google through `datatransport` (and merged INTERNET into Drive's manifest), which the
// privacy policy cannot accept without consent. Don't bring back a Play-services/Firebase scanner.
//
// No iOS target: every iOS app here scans in its own Swift layer.
@OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)
kotlin {
    jvmToolchain(17)
    applyDefaultHierarchyTemplate()

    android {
        namespace = "app.tenshu.ui.qrscanner"
        compileSdk = 37
        minSdk = 24
        // QrFrameDecoderTest: decodes generated QR frames through the analyzer's decoder.
        withHostTest { }
    }

    jvm("desktop")

    sourceSets {
        getByName("commonMain") {
            dependencies {
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
                implementation(libs.kotlinx.coroutines)
            }
        }
        getByName("commonTest") {
            dependencies {
                implementation(libs.kotlin.test)
            }
        }
        getByName("androidMain") {
            dependencies {
                // CameraX preview + ZXing QR decoding of the analysis frames.
                implementation(libs.activity.compose)
                implementation(libs.lifecycle.runtime.compose)
                implementation(libs.zxing.core)
                implementation(libs.androidx.camera.core)
                implementation(libs.androidx.camera.camera2)
                implementation(libs.androidx.camera.lifecycle)
                implementation(libs.androidx.camera.view)
            }
        }
        getByName("desktopMain") {
            dependencies {
                implementation(compose.desktop.currentOs)
                // `HelperBinary` (bundle-first resolution of `qr-scan-helper`) and the FFM camera.
                implementation(project(":macos-helpers"))
            }
            // The compiled macOS QR-scan helper rides in the desktop jar as a classpath resource
            // (`/qr-scan-helper`); see the swiftc task below.
            resources.srcDir(layout.buildDirectory.dir("qr-helper"))
        }
    }
}

// --- macOS QR-scan helper: compile (swiftc) + embed as a desktop resource ---
// docs/proposals/desktop-qr-scanning.md §6–§7. macOS-only, and registered only on a Mac (rather
// than via `onlyIf`) so the Exec task captures no build-script closure — required for the
// configuration cache. Elsewhere the resource dir is simply empty and the runtime scanner reports
// no camera (`QrCameraScanner.forCurrentOs()` only returns the Mac scanner on a Mac host).
// `:control-desktop` and `:drive-desktop` copy the same binary into their app bundles
// (`embedQrScanHelper`).
if (System.getProperty("os.name").orEmpty().contains("mac", ignoreCase = true)) {
    val qrHelperSwift = file("src/desktopMain/swift/qr-scan-helper.swift")
    val qrHelperOutDir = layout.buildDirectory.dir("qr-helper").get().asFile
    val qrHelperBin = qrHelperOutDir.resolve("qr-scan-helper")

    val compileQrScanHelper by tasks.registering(Exec::class) {
        group = "macos"
        description = "Compiles the macOS QR-scan helper (qr-scan-helper.swift) with swiftc."
        inputs.file(qrHelperSwift)
        inputs.property("macosDeploymentTarget", project.property("tenshu.macosDeploymentTarget").toString())
        outputs.file(qrHelperBin)
        // NB: do NOT embed an Info.plist with a CFBundleIdentifier here — that gives the helper a
        // phantom TCC identity whose `authorizationStatus` mis-reports `.authorized`, breaking the
        // first-grant request/retry flow. The helper is attributed to its host app (that app's
        // NSCameraUsageDescription).
        commandLine(
            "sh", "-c",
            "mkdir -p '${qrHelperOutDir.absolutePath}' && " +
                "swiftc -O -target arm64-apple-macos${project.property("tenshu.macosDeploymentTarget")} '${qrHelperSwift.absolutePath}' -o '${qrHelperBin.absolutePath}'",
        )
    }

    tasks.named("desktopProcessResources") {
        dependsOn(compileQrScanHelper)
    }
}
