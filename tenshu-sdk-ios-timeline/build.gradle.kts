// Timeline's own iOS framework (`docs/proposals/ios-per-app-frameworks.md` P4): the `TenshuSdk` that
// timeline-ios links, built from Timeline's surface and runtime and `:tenshu-sdk-ios-core` only. It contains
// no other app's code, so Timeline's binary carries no load command for the platform frameworks only
// other apps use.
//
// SKIE with analytics off, `baseName = "TenshuSdk"`, static, the base exports and
// `api(:tenshu-sdk-ios-core)` are applied to this module BY NAME from the root build.gradle.kts
// ("iOS app frameworks"). This file adds only what Timeline's Swift reads beyond that base.
//
// ⚠ `export(...)` is not transitive, and the failure is loud only in Swift: a type Timeline's Swift
// names that is missing here compiles as a prefixed name with no members. Only
// `scripts/ios-smoke.sh --build-only timeline` proves this list (§5.2, R3).
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
            // Timeline's view-model/repository layer — the VMs, the `TimelineRepository` seam and
            // `MockTimelineRepository`.
            export(project(":timeline-shared-logic"))
            // The presentation model (`Day`, `Moment`, `Trip`, `Place`, …) Timeline's Swift renders. It
            // moved out of `:timeline-shared-logic` into `:timeline-projection` (timeline-app.md §Web
            // scope 4a), and `export(...)` is not transitive — without this line those types reach
            // Swift as prefixed names with no members.
            export(project(":timeline-projection"))
            // `:preference-service` itself: the declarations (`MeasurementSystem`, …) live there,
            // and `export(...)` is not transitive.
            export(project(":preference-service"))
            // The Compose-free basemap core the shared `ios-basemap` Swift hands to MapLibre.
            export(project(":basemap-core"))
        }
    }

    sourceSets {
        getByName("iosMain") {
            dependencies {
                // Paired with the exports above — an export must be an `api` dependency.
                api(project(":timeline-shared-logic"))
                api(project(":timeline-projection"))
                api(project(":preference-service"))
                api(project(":basemap-core"))
                // Timeline's real data layer: `TimelineProjectionRepository` over the
                // `:timeline-service` / `:place-service` / `:health-service` stores, assembled by
                // `EpcTimelineClient` / `TimelineLocalRuntime`, and the capture runtime (CoreLocation:
                // `IosLocationCapture`, `IosReverseGeocoder`). Unexported: Swift drives
                // `TimelineSurface`. The `-common`/`-client` schema halves are listed because
                // `:timeline-shared-data` holds them as `implementation`.
                implementation(project(":timeline-shared-data"))
                implementation(project(":timeline-service-common"))
                implementation(project(":timeline-service-client"))
                implementation(project(":place-service-common"))
                implementation(project(":place-service-client"))
                implementation(project(":health-service-common"))
                implementation(project(":health-service-client"))
                // The offline-basemap render terminus `TimelineRegionResolver` adapts.
                implementation(project(":tenshu-sdk-ios-region"))
            }
        }
        getByName("iosTest") {
            dependencies {
                implementation(libs.kotlinx.coroutines.test)
                // Test-only: `TimelineRegionResolverWiringTest` lands a region in the *proxy's* own
                // store (`MapRegionProxyComponents.store`) so the route-1 drain has real bytes to
                // serve. Production reaches MapRegionService through the client surface.
                implementation(project(":map-region-service-proxy"))
            }
        }
    }
}
