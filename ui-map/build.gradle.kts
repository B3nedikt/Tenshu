plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.jetbrains.compose)
    alias(libs.plugins.kotlin.compose)
}

// :ui-map — the reusable offline MapLibre basemap surface, extracted from Maps Ph7b so
// future map consumers (Timeline, Photos) share one engine (docs/proposals/maps-app.md §6;
// see ui-map-agent.md). Public API is `OfflineBasemap` (expect/actual: Android = live MapLibre
// over bundled offline PMTiles; desktop = schematic) + `BasemapChrome`. android + desktop only.
//
// ⚠ House Rule #1: the basemap is fully offline (bundled tiles, label-free style, no glyph/
// sprite/tile egress). Every consuming *app* must strip INTERNET + ACCESS_FINE/COARSE_LOCATION
// from its manifest via tools:node="remove" (as :maps-android does) — see ui-map-agent.md.
@OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)
kotlin {
    jvmToolchain(17)
    applyDefaultHierarchyTemplate()

    android {
        namespace = "app.tenshu.ui.map"
        compileSdk = 37
        minSdk = 24
        androidResources {
            enable = true
        }
    }

    jvm("desktop")

    // iOS renderer (slice C4). maplibre-compose is a true KMP library — 0.13.0 publishes
    // iosArm64 + iosSimulatorArm64 klibs with a cinterop against the MapLibre ObjC framework —
    // so iOS gets the SAME live MapLibre render as Android over the SAME offline PMTiles and the
    // SAME commonMain style, rather than a schematic. MapKit is not an option (threat model A35:
    // Apple-served tiles would disclose the viewed region on every open).
    //
    // ⚠ The MapLibre *binary* framework is NOT in this Gradle artifact — the cinterop klib carries
    // headers only. Every consuming iOS app must add MapLibre 6.x itself (SPM or CocoaPods) and
    // order its linker flags `-framework <KotlinFramework> -framework MapLibre`. See ui-map-agent.md
    // "iOS" — that is the iOS analogue of Android's manifest permission-strip rule.
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        val commonMain = getByName("commonMain") {
            dependencies {
                implementation(project(":ui-core"))

                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
                implementation(compose.uiUtil)
                implementation(compose.components.resources)
                implementation(compose.preview)
                implementation(compose.materialIconsExtended)

                implementation(libs.kotlinx.coroutines)

                // The Compose-free basemap core: the hand-built MapLibre style JSON (and its House
                // Rule #1 no-egress guard), the palette, `routeCameraFrame`, `normalizeRoute` and the
                // `resolveBasemapSource` seam. `api`, not `implementation`: `BasemapCoord` and
                // `RegionStreamSource` are re-exported from here as typealiases, so every consumer
                // that names them needs the class on its own compile classpath.
                api(project(":basemap-core"))

                // PMTiles directory parsing + MVT decoding (M9). `api` because OfflineBasemap's
                // public surface exposes PmtilesDirectory types to its Android consumers.
                api(project(":vector-tile"))
            }
        }
        getByName("commonTest") {
            dependencies {
                implementation(libs.kotlin.test)
                @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
                implementation(compose.uiTest)
            }
        }
        val jvmMain = create("jvmMain") {
            dependsOn(commonMain)
        }
        val androidMain = getByName("androidMain") {
            dependsOn(jvmMain)
            dependencies {
                implementation(libs.ui.tooling)
                // Live MapLibre basemap (Android only — the lib's desktop target is early-stage,
                // so desktop keeps the schematic). The offline style is built as JSON at runtime.
                implementation(libs.maplibre.compose)
                // ⚠ Since 0.15 the render backend is NOT transitive: without this the module
                // compiles and the map fails at runtime. OpenGL ES, not Vulkan — see the catalog.
                runtimeOnly(libs.maplibre.compose.runtime)
            }
        }
        getByName("desktopMain") {
            dependsOn(jvmMain)
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(libs.kotlinx.coroutines.swing)
                // AM-5: `MKMapSnapshotter` pixels for a user who elected Apple Maps, via the
                // bundled `map-snapshot-helper`. ⚠ A *runtime* capability — absent on Windows and
                // Linux, where `isAvailable()` is false and the schematic stands.
                implementation(project(":macos-helpers"))
            }
        }
        val iosMain = getByName("iosMain") {
            dependencies {
                // Same library, same public API (`MaplibreMap`, `BaseStyle.Json`,
                // `rememberCameraState`) as the Android renderer uses — it is declared in the
                // library's own commonMain.
                implementation(libs.maplibre.compose)
            }
        }
        // Desktop-only tests. The bundled `.pmtiles` asset lives on disk and its tiles are gzipped,
        // so reading it needs `java.io` + `java.util.zip` — neither available in commonTest, which
        // also compiles for Android.
        getByName("desktopTest") {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(project(":vector-tile"))
            }
        }
    }
}

/**
 * Mirrors the Android `assets/` payload into the Compose-resources layout iOS expects
 * (`<dir>/files/...`, which `Res.getUri("files/…")` resolves inside the app bundle at runtime).
 * Registered below as an iosMain custom resource directory; see `IosBasemapAssets.kt` for the
 * reading half.
 *
 * MIRRORED rather than copied into the repo a second time: the archive is 1.3 MB and — load-bearing
 * — `MvtRealArchiveTest` / `RailTileReaderRealArchiveTest` / `RailMatchRealArchiveTest` open it at
 * that exact relative path (ui-map-agent.md). Keeping the Android tree as the single source means
 * the iOS copy cannot drift from the one the tests read.
 */
val iosBasemapResourcesDir: Provider<Directory> =
    layout.buildDirectory.dir("generated/iosBasemapResources")

val stageIosBasemapResources by tasks.registering(Sync::class) {
    group = "ios"
    description = "Stages the bundled PMTiles + glyph atlas as iOS Compose resources."
    from(layout.projectDirectory.dir("src/androidMain/assets"))
    into(iosBasemapResourcesDir.map { it.dir("files") })
}

// Pin the generated `Res` accessor package so call sites `import app.tenshu.ui.map.resources.Res`.
compose.resources {
    packageOfResClass = "app.tenshu.ui.map.resources"
    generateResClass = always

    // iOS has no `assets/` mechanism, so the bundled tiles + glyph atlas ride as Compose-resource
    // *files* instead. `flatMap` (not `map`) so the returned provider carries the staging task as a
    // dependency while still resolving to the directory itself.
    customDirectory(
        sourceSetName = "iosMain",
        directoryProvider = stageIosBasemapResources.flatMap { iosBasemapResourcesDir },
    )
}
