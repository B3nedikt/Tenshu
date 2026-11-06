plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
}

/**
 * The **Compose-free core of the offline basemap** — style JSON, palette, camera framing, route
 * geometry and the archive-resolution seam, with nothing on the classpath but kotlinx-serialization.
 *
 * Extracted from `:ui-map` (`docs/proposals/ios-fit-timeline-maps-parity.md` §3.1). The measurement
 * that forced it: of `:ui-map`'s four `commonMain` files, `BasemapStyle.kt` was 598 lines with **zero**
 * `@Composable`, importing Compose for one thing — `androidx.compose.ui.graphics.Color`. The whole
 * module held 8 composables. The shareable part of a map is not its UI.
 *
 * iOS ships no Compose Multiplatform UI at all; every iOS surface is SwiftUI over Compose-free shared
 * modules. Putting `:ui-map` in an iOS app framework (`:tenshu-sdk-ios-<app>`) would drag the Compose
 * runtime + Skia into that app just to wrap a MapLibre view the app must link natively anyway. So — mirroring
 * House Rule #5, which makes the same call for the web — **the logic is shared and the iOS map view is
 * written in SwiftUI over MapLibre's native iOS SDK**. This module is that shared logic.
 *
 * ⚠ House Rule #1 travels with the style, not with the renderer: `BasemapStyleTest`'s
 * `everyUrlInTheStyleIsDeviceLocal` walks every string in the emitted style and fails on any
 * non-device-local URL. That guard belongs wherever the style is authored — which is here.
 */
@OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)
kotlin {
    jvmToolchain(17)
    applyDefaultHierarchyTemplate()

    android {
        namespace = "app.tenshu.basemap"
        compileSdk = 37
        minSdk = 24
    }

    jvm()

    // Mirrors `:vector-tile`, the nearest neighbour: declaring js keeps `commonMain` honest (it is
    // the target that catches an accidental JVM-only dependency), and there is a real consumer in
    // view — MapLibre GL JS takes a style JSON object of exactly this shape, so a web map would
    // inherit this style *and its no-egress guard* rather than re-deriving them in TypeScript.
    js {
        browser()
    }

    // The two the iOS app frameworks (`:tenshu-sdk-ios-<app>`) link. No iosX64: they have none (some
    // exported modules ship no iosX64 klib), and an unexported extra target is pure build time.
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        getByName("commonMain") {
            dependencies {
                // `implementation`, deliberately: the public API hands out the style as a **String**
                // (`offlineBasemapStyleJson`), never a `JsonObject`. That keeps kotlinx-serialization
                // off the exported Obj-C surface, and a JSON string is what both consumers actually
                // want — `BaseStyle.Json(String)` on the Compose side, `MLNStyle`/`styleJSON` on the
                // native iOS side.
                implementation(libs.kotlinx.serialization.json)
            }
        }
        getByName("commonTest") {
            dependencies {
                implementation(libs.kotlin.test)
            }
        }
    }
}

// W-MAP (`web-app-suite.md` §4): the web's copy of the basemap style is GENERATED from this module and
// committed (`WebStyleBundle.kt`); `WebStyleBundleParityTest` fails when the two differ.
tasks.register<JavaExec>("generateWebBasemapStyles") {
    group = "tenshu"
    description = "Writes web/map-kit's basemap style bundle from :basemap-core."
    val jvm = kotlin.jvm()
    dependsOn(jvm.compilations.getByName("main").compileTaskProvider)
    classpath = files(
        jvm.compilations.getByName("main").output.allOutputs,
        jvm.compilations.getByName("main").runtimeDependencyFiles,
    )
    mainClass.set("app.tenshu.basemap.WebStyleExportKt")
    args(rootProject.file("web/map-kit/src/style/basemap-styles.generated.json").absolutePath)
}
