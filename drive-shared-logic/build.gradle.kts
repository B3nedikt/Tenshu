plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.kotlinx.serialization)
}

// Drive's Compose-free logic layer — view models, repositories, mappers, domain,
// preferences — extracted out of the old `:drive-shared` so it can compile on iOS
// (Phase 6b.5, docs/proposals/ios-proxy-architecture.md §9.3). The Compose UI
// (screens, ui-core, DI assembly, the preview subsystem) stays in
// `:drive-shared-ui`, which depends on this module. Packages are unchanged
// (`app.tenshu.drive.*`): a screen in `:drive-shared-ui` and its view model here
// share a leaf package across the two modules, which Kotlin resolves over the
// module dependency with no import — so the UI half needed no import churn.
//
// iOS targets: iosArm64 + iosSimulatorArm64 only (no iosX64 — this transitively
// depends on `:core-paging`, whose `paging-common` ships no iosX64 klib; §9.5
// as-built). The umbrella's iosX64 decision is deferred to the export slice.
@OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)
kotlin {
    jvmToolchain(17)
    applyDefaultHierarchyTemplate()

    android {
        namespace = "app.tenshu.drive.logic"
        compileSdk = 37
        minSdk = 24
    }

    jvm("desktop")

    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        val commonMain = getByName("commonMain") {
            dependencies {
                // `DeviceIdentity` moved here in PH-4 (`photos-app.md` §14.2): every per-device
                // preference row in this module is keyed on it, and Photos needed the same
                // startup-race retry — two copies is how one of them gets repaired and the other
                // does not.
                api(project(":app-ingest"))
                api(project(":app-liveness"))
                // `api`, not `implementation`: ListLoadState is in FilesRepository's return type
                // and ListLoadPhase in the UI states, so :drive-shared-ui reads both.
                api(project(":app-list-state"))
                api(project(":core"))
                api(project(":core-common"))
                api(project(":core-paging"))

                api(project(":file-service"))
                api(project(":file-service-common"))
                api(project(":file-service-client"))
                // `ControlReachability` for the Control-unavailable surface (ADR 0025). `api`, since the
                // reason type appears in `SyncBanner.evaluate`'s signature.
                api(project(":app-reachability"))
                // `ErrorCopyKey` — the catalogue key a typed error renders through
                // (`core-error-copy-catalogue.md` §5.0).
                api(project(":app-error-handling"))
                api(project(":notification-service"))
                api(project(":notification-service-common"))
                api(project(":notification-service-client"))
                api(project(":ingest-service"))
                api(project(":ingest-service-common"))
                api(project(":ingest-service-client"))
                api(project(":sync-service"))
                api(project(":sync-service-common"))
                api(project(":sync-service-client"))
                api(project(":preference-service"))
                api(project(":preference-service-common"))
                api(project(":preference-service-client"))
                api(project(":contact-service"))
                api(project(":contact-service-common"))
                api(project(":contact-service-client"))
                api(project(":storage-service"))
                api(project(":storage-service-common"))
                api(project(":storage-service-client"))
                api(project(":identity-service"))
                api(project(":identity-service-common"))
                api(project(":identity-service-client"))

                // Compose-free base ViewModel + viewModelScope (the ~21 VMs extend it).
                api(libs.lifecycle.viewmodel)
                implementation(libs.kotlinx.coroutines)
                implementation(libs.kotlinx.datetime)
                implementation(libs.kotlinx.serialization.core)
            }
        }
        // Shared JVM source set spanning androidMain + desktopMain (the default
        // hierarchy template doesn't materialise this parent under the Android KMP
        // plugin). Houses the JVM `platformShareHostOverride` actual (env/sysprop);
        // iOS gets its own actual in iosMain.
        val jvmMain = create("jvmMain") {
            dependsOn(commonMain)
        }
        getByName("androidMain") { dependsOn(jvmMain) }
        getByName("desktopMain") { dependsOn(jvmMain) }

        getByName("commonTest") {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.test)
            }
        }
    }
}
