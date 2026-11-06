plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
}

// The app-side offline-basemap region *terminus*, shared by every `:ui-map` consumer (Maps,
// Timeline, Fit) — docs/proposals/maps-region-delivery.md D5/§7. Given a TenshuClient reaching the
// proxy's MapRegionService plus a platform RegionBundleStager, it drains an installed region's
// bytes over `readRegionRange` into the app sandbox and returns the staged `.pmtiles` path for the
// basemap (`OfflineBasemap`/`RouteBasemap` `regionBundlePath`).
//
// Compose-free AND maplibre-free by construction, so an app *data* layer can depend on it without
// pulling the basemap engine's native libraries (that dependency stays in :ui-map, the render
// module). Extracted from :maps-shared-data so Timeline/Fit reuse the same render hop.
@OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)
kotlin {
    jvmToolchain(17)
    applyDefaultHierarchyTemplate()

    android {
        namespace = "app.tenshu.mapregion.app"
        compileSdk = 37
        minSdk = 24
    }

    jvm("desktop")

    // The web runtime's catalog lookup (`MapRegionPlugin` in :tenshu-sdk-js) reuses [RegionResolver] /
    // [GeoBounds] rather than re-deriving "which region covers this point" in a second copy.
    js {
        browser()
        useEsModules()
    }

    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        val commonMain = getByName("commonMain") {
            dependencies {
                implementation(project(":core"))
                implementation(project(":core-common"))
                implementation(project(":core-client"))
                implementation(project(":map-region-service"))
                implementation(project(":map-region-service-client"))

                implementation(libs.kotlinx.coroutines)
            }
        }
        getByName("commonTest") {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.test)
            }
        }
        // JVM (Android + desktop) share the file-backed stager; iOS has no stager actual yet
        // (the renderer falls back to the bundled demo there, as it does today).
        val jvmMain = create("jvmMain") {
            dependsOn(commonMain)
        }
        getByName("androidMain") {
            dependsOn(jvmMain)
        }
        getByName("desktopMain") {
            dependsOn(jvmMain)
        }
    }
}
