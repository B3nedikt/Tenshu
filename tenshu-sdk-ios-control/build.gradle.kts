// Control's own iOS framework (`docs/proposals/ios-per-app-frameworks.md` P2, the pilot): the
// `TenshuSdk` that control-ios links, built from Control's surfaces and `:tenshu-sdk-ios-core`
// only. It contains no other app's code, so Control's binary carries no load command for the
// platform frameworks only other apps use (HealthKit, CoreLocation, CoreBluetooth, CoreMotion,
// Photos) — `scripts/ios-archive.sh` checks that on every archive (D7).
//
// SKIE with analytics off, `baseName = "TenshuSdk"`, static, the base exports and
// `api(:tenshu-sdk-ios-core)` are applied to this module BY NAME from the root build.gradle.kts
// ("iOS app frameworks"). This file adds only what Control's Swift reads beyond that base.
//
// ⚠ `export(...)` is not transitive, and the failure is loud only in Swift: a type Control's Swift
// names that is missing here compiles as a prefixed name with no members. Only
// `scripts/ios-smoke.sh --build-only control` proves this list (§5.2, R3).
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
            // Control's view-model/repository layer — what control-ios binds to. Compose-free; the
            // Compose `:control-shared` is NOT exported (iOS has no Compose).
            export(project(":control-shared-logic"))
            // The paged event feed (`TenshuPagingPresenter`) the Home screen observes.
            export(project(":core-paging"))
            // `InferenceCapability`, which the Models view model's switches name.
            export(project(":inference-service"))
            // The service halves Control's view models expose to Swift.
            export(project(":identity-service-common"))
            export(project(":identity-service-client"))
            export(project(":contact-service-common"))
            export(project(":contact-service-client"))
            export(project(":notification-service-common"))
            export(project(":notification-service-client"))
            export(project(":preference-service"))
            export(project(":preference-service-common"))
            export(project(":preference-service-client"))
            export(project(":logging-service-common"))
            export(project(":logging-service-client"))
        }
    }

    sourceSets {
        getByName("iosMain") {
            dependencies {
                // Paired with the exports above — an export must be an `api` dependency.
                api(project(":control-shared-logic"))
                api(project(":core-paging"))
                api(project(":inference-service"))
                api(project(":identity-service-common"))
                api(project(":identity-service-client"))
                api(project(":contact-service-common"))
                api(project(":contact-service-client"))
                api(project(":notification-service-common"))
                api(project(":notification-service-client"))
                api(project(":preference-service"))
                api(project(":preference-service-common"))
                api(project(":preference-service-client"))
                api(project(":logging-service-common"))
                api(project(":logging-service-client"))
            }
        }
        getByName("iosTest") {
            dependencies {
                implementation(libs.kotlinx.coroutines.test)
            }
        }
    }
}
