// Maps' own iOS framework (`docs/proposals/ios-per-app-frameworks.md` P4): the `TenshuSdk` that
// maps-ios links, built from Maps' surface and runtime and `:tenshu-sdk-ios-core` only. It contains
// no other app's code, so Maps' binary carries no load command for the platform frameworks only
// other apps use.
//
// SKIE with analytics off, `baseName = "TenshuSdk"`, static, the base exports and
// `api(:tenshu-sdk-ios-core)` are applied to this module BY NAME from the root build.gradle.kts
// ("iOS app frameworks"). This file adds only what Maps' Swift reads beyond that base.
//
// ⚠ `export(...)` is not transitive, and the failure is loud only in Swift: a type Maps' Swift
// names that is missing here compiles as a prefixed name with no members. Only
// `scripts/ios-smoke.sh --build-only maps` proves this list (§5.2, R3).
plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    jvmToolchain(17)
    applyDefaultHierarchyTemplate()

    // No iosX64: `androidx.paging` ships no iosX64 klib.
    listOf(
        iosArm64(),
        iosSimulatorArm64(),
    ).forEach { target ->
        target.binaries.framework {
            // Maps' view-model/repository layer — the three VMs, the `PlaceRepository` /
            // `RegionRepository` seams and `MockPlaceRepository`.
            export(project(":maps-shared-logic"))
            // The Compose-free basemap core: the offline MapLibre style JSON, its palette and the
            // camera framing the shared `ios-basemap` Swift hands to MapLibre. ⚠ `:ui-map` itself
            // is NOT exported: it would pull the Compose runtime and Skia in.
            export(project(":basemap-core"))
        }
    }

    sourceSets {
        getByName("iosMain") {
            dependencies {
                // Paired with the exports above — an export must be an `api` dependency.
                api(project(":maps-shared-logic"))
                api(project(":basemap-core"))
                // The real data layer — `PlaceProjectionRepository` over `:place-service` and
                // `ClientRegionRepository` over `MapRegionService`, assembled by `MapsLocalRuntime`.
                // Unexported: Swift drives `MapsSurface`, never these types.
                implementation(project(":maps-shared-data"))
                // The offline-basemap render terminus `MapsLocalRuntime` hands its region
                // repository. Swift never names `RegionTerminus`.
                implementation(project(":tenshu-sdk-ios-region"))
                // `MapsSurface.today()` names `kotlinx.datetime` (the real device date).
                implementation(libs.kotlinx.datetime)
            }
        }
        getByName("iosTest") {
            dependencies {
                implementation(libs.kotlinx.coroutines.test)
            }
        }
    }
}
