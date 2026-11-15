// Fit's own iOS framework (`docs/proposals/ios-per-app-frameworks.md` P4): the `TenshuSdk` that
// fit-ios links, built from Fit's surface and runtime and `:tenshu-sdk-ios-core` only. It contains
// no other app's code, so Fit's binary carries no load command for the platform frameworks only
// other apps use.
//
// SKIE with analytics off, `baseName = "TenshuSdk"`, static, the base exports and
// `api(:tenshu-sdk-ios-core)` are applied to this module BY NAME from the root build.gradle.kts
// ("iOS app frameworks"). This file adds only what Fit's Swift reads beyond that base.
//
// ⚠ `export(...)` is not transitive, and the failure is loud only in Swift: a type Fit's Swift
// names that is missing here compiles as a prefixed name with no members. Only
// `scripts/ios-smoke.sh --build-only fit` proves this list (§5.2, R3).
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
            // Fit's view-model/repository layer — the VMs, the `FitRepository` seam,
            // `MockFitRepository` and `MockRecordingEngine`.
            export(project(":fit-shared-logic"))
            // The model (`Workout`, `DailyAggregate`, `FitProfile`, …) moved out of
            // `:fit-shared-logic` into `:fit-projection` (`fit-app.md` §Web scope); `export(...)` is
            // not transitive, so without this Swift sees those types prefixed and memberless.
            export(project(":fit-projection"))
            // `HomeUiState.load` is a `ListLoadPhase` and `FitRepository`'s `aggregates()` /
            // `categories()` return `ListLoadState`; `export(...)` is not transitive.
            export(project(":app-list-state"))
            // `:preference-service` itself: `FitProfile.sex` is a `BiologicalSex`, declared there.
            export(project(":preference-service"))
            // The Compose-free basemap core the shared `ios-basemap` Swift hands to MapLibre.
            export(project(":basemap-core"))
        }
    }

    sourceSets {
        getByName("iosMain") {
            dependencies {
                // Paired with the exports above — an export must be an `api` dependency.
                api(project(":fit-shared-logic"))
                api(project(":fit-projection"))
                api(project(":app-list-state"))
                api(project(":preference-service"))
                api(project(":basemap-core"))
                // Fit's real data layer: `FitProjectionRepository` over the `:health-service` store,
                // the HealthKit importer/writer and the recording engine (HealthKit, CoreLocation,
                // CoreBluetooth, CoreMotion — Fit's alone since P4). Unexported: Swift drives
                // `FitSurface`. The `-common`/`-client` schema halves are listed because
                // `:fit-shared-data` holds them as `implementation`.
                implementation(project(":fit-shared-data"))
                implementation(project(":health-service-common"))
                implementation(project(":health-service-client"))
                // The offline-basemap render terminus `FitRegionResolver` adapts.
                implementation(project(":tenshu-sdk-ios-region"))
            }
        }
        getByName("iosTest") {
            dependencies {
                implementation(libs.kotlinx.coroutines.test)
                // `OwnedViewModels`: every view model a test builds is joined before the test ends
                // (`docs/testing.md` §11).
                implementation(project(":viewmodel-test-utils"))
            }
        }
    }
}
