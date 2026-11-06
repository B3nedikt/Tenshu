plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.kotlinx.serialization)
}

// Control's Compose-free logic layer — view models, repositories, mappers, the EPC
// pairing client, notifications domain, and the biometric-unlock delegate contract —
// extracted out of `:control-shared` so it can compile on iOS (Control-on-iOS C2,
// docs/proposals/ios-control-handover.md; §9.3 of ios-proxy-architecture.md). The
// Compose UI (screens, ui-core, navigation) and the in-process **proxy assembly**
// (`ProxyModule`, the platform `PlatformProxyModule`, the entitlement/storage init,
// the EPC *server*, the repository *impls* that talk to the in-process client) stay
// in `:control-shared`, which depends on this module. Packages are unchanged
// (`app.tenshu.control.*`) so a screen in `:control-shared` and its view model here
// share a leaf package across the two modules with no import churn — mirroring the
// `:drive-shared-logic` / `:drive-shared-ui` split.
//
// Asymmetry vs Drive (deliberate): Drive runs no proxy, so `:drive-shared` became a
// pure `-ui` over `-logic`. Control *is* the proxy, so the existing `:control-shared`
// keeps its name and retains the proxy runtime; only the client-facing logic moves
// here. iOS binds this module's VMs to fakes (C2b) / the in-process collapse (C4).
//
// iOS targets: iosArm64 + iosSimulatorArm64 only (no iosX64 — transitively depends on
// `:core-paging`, whose `paging-common` ships no iosX64 klib).
@OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)
kotlin {
    jvmToolchain(17)
    applyDefaultHierarchyTemplate()

    android {
        namespace = "app.tenshu.control.logic"
        compileSdk = 37
        minSdk = 24
    }

    jvm("desktop")

    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        getByName("commonMain") {
            dependencies {
                // `ErrorCopyKey` — the catalogue key a typed error renders through
                // (`core-error-copy-catalogue.md` §5.0).
                api(project(":app-error-handling"))
                api(project(":core"))
                api(project(":core-common"))
                api(project(":core-paging"))

                api(project(":file-service"))
                api(project(":file-service-common"))
                // Schema-only (Workout.ID) for the Data section's realm-data ordering +
                // presentation. Control's proxy already registers the health module.
                api(project(":health-service"))
                api(project(":file-service-client"))
                api(project(":notification-service"))
                api(project(":notification-service-common"))
                api(project(":notification-service-client"))
                api(project(":ingest-service"))
                api(project(":ingest-service-common"))
                api(project(":ingest-service-client"))
                api(project(":identity-service"))
                api(project(":identity-service-common"))
                api(project(":identity-service-client"))
                api(project(":sync-service"))
                api(project(":sync-service-common"))
                api(project(":sync-service-client"))
                api(project(":preference-service"))
                api(project(":preference-service-common"))
                api(project(":preference-service-client"))
                api(project(":contact-service"))
                api(project(":contact-service-common"))
                api(project(":contact-service-client"))
                api(project(":logging-service"))
                api(project(":logging-service-common"))
                api(project(":logging-service-client"))
                api(project(":storage-service"))
                api(project(":storage-service-common"))
                api(project(":storage-service-client"))
                // The Models view model and its repository (PIO-7 PR-4): model catalog and index ops,
                // and `InferenceCapability` in the exported UiState.
                api(project(":inference-service"))
                api(project(":inference-service-common"))
                api(project(":inference-service-client"))

                // Compose-free base ViewModel + viewModelScope (the VMs extend it).
                api(libs.lifecycle.viewmodel)
                implementation(libs.kotlinx.coroutines)
                implementation(libs.kotlinx.datetime)
                implementation(libs.kotlinx.serialization.core)

                // EntitlementTokenClient / PublisherClient fetch over HTTP.
                implementation(libs.ktorClientCore)
                implementation(libs.ktorClientContentNegotiation)
                implementation(libs.ktorSerializationJson)
            }
        }

        getByName("androidMain") {
            dependencies {
                // AndroidBiometricUnlockDelegate (BiometricPrompt/BiometricManager) +
                // the FragmentActivity-backed BiometricPromptHost actual. `api` so the
                // `:control-shared` Compose resolver can reach FragmentActivity.
                api(libs.androidx.biometric)
                implementation(libs.ktorClientOkhttp)
            }
        }

        getByName("desktopMain") {
            dependencies {
                // `MacLocalAuthentication` — the in-process Touch ID check behind `DesktopAppLock`.
                implementation(project(":macos-helpers"))
                implementation(libs.kotlinx.coroutines.swing)
                implementation(libs.ktorClientOkhttp)
            }
        }

        getByName("commonTest") {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.test)
            }
        }

        getByName("desktopTest") {
            dependencies {
                implementation(project(":viewmodel-test-utils"))
                implementation(project(":core-test-utils"))
                implementation(libs.kotlinx.coroutines.test)
            }
        }
    }
}
