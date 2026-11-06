// Drive's own iOS framework (`docs/proposals/ios-per-app-frameworks.md` P3): the `TenshuSdk` that
// drive-ios links, built from Drive's surfaces and `:tenshu-sdk-ios-core` only. It contains no
// other app's code, so Drive's binary carries no load command for the platform frameworks only
// other apps use (HealthKit, CoreLocation, CoreBluetooth, CoreMotion). It keeps Photos: the
// photo backup (`IosPhotoLibrary`, `IosMediaSourceProvider`, `IosPhotoUploadSource`) is Drive's.
// `scripts/ios-archive.sh` checks the set on every archive (D7).
//
// SKIE with analytics off, `baseName = "TenshuSdk"`, static, the base exports and
// `api(:tenshu-sdk-ios-core)` are applied to this module BY NAME from the root build.gradle.kts
// ("iOS app frameworks"). This file adds only what Drive's Swift reads beyond that base.
//
// ⚠ `export(...)` is not transitive, and the failure is loud only in Swift: a type Drive's Swift
// names that is missing here compiles as a prefixed name with no members. Only
// `scripts/ios-smoke.sh --build-only drive` proves this list (§5.2, R3).
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
            // Drive's view-model/repository layer — what drive-ios binds to — and the non-Compose
            // paging presenter it api-exposes (`TenshuPagingPresenter`, `Flow<PagingData>`).
            export(project(":drive-shared-logic"))
            export(project(":core-paging"))
            // `ListLoadPhase` / `ListLoadState`, which `FilesRepository` rides through
            // `FakeFilesRepository`.
            export(project(":app-list-state"))
            // The service halves Drive's surfaces and view models expose to Swift.
            export(project(":file-service-common"))
            export(project(":file-service-client"))
            export(project(":ingest-service-common"))
            export(project(":ingest-service-client"))
            export(project(":identity-service-common"))
            export(project(":identity-service-client"))
            export(project(":contact-service-common"))
            export(project(":contact-service-client"))
            export(project(":notification-service-common"))
            export(project(":notification-service-client"))
            // `:preference-service` itself, not only its halves: the declarations live there.
            export(project(":preference-service"))
            export(project(":preference-service-common"))
            export(project(":preference-service-client"))
        }
    }

    sourceSets {
        getByName("iosMain") {
            dependencies {
                // Paired with the exports above — an export must be an `api` dependency.
                api(project(":drive-shared-logic"))
                api(project(":core-paging"))
                api(project(":app-list-state"))
                api(project(":file-service-common"))
                api(project(":file-service-client"))
                api(project(":ingest-service-common"))
                api(project(":ingest-service-client"))
                api(project(":identity-service-common"))
                api(project(":identity-service-client"))
                api(project(":contact-service-common"))
                api(project(":contact-service-client"))
                api(project(":notification-service-common"))
                api(project(":notification-service-client"))
                api(project(":preference-service"))
                api(project(":preference-service-common"))
                api(project(":preference-service-client"))
            }
        }
        getByName("iosTest") {
            dependencies {
                implementation(libs.kotlinx.coroutines.test)
            }
        }
    }
}
