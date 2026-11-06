plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
}

// Home's Compose-free logic layer — domain model, the mock device repository, and the view
// models (docs/proposals/home-and-mia-apps.md §6). Mirrors :timeline-shared-logic /
// :fit-shared-logic so it can compile on iOS later; the Compose UI lives in
// :home-shared-ui, which depends on this. Packages are shared across the two modules
// (`app.tenshu.home.*`) so a screen and its view model resolve each other over the module
// boundary with no import.
//
// M1 has NO service dependencies — it is UI-over-mock. The real device service replaces
// MockHomeDevicesRepository behind the HomeDevicesRepository interface (one Koin binding);
// the proxy-side `:home-device-service` is a later phase (proposal §8).
@OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)
kotlin {
    jvmToolchain(17)
    applyDefaultHierarchyTemplate()

    android {
        namespace = "app.tenshu.home.logic"
        compileSdk = 37
        minSdk = 24
    }

    jvm("desktop")

    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        val commonMain = getByName("commonMain") {
            dependencies {
                // Compose-free base ViewModel + viewModelScope (the VMs extend it).
                api(libs.lifecycle.viewmodel)
                implementation(libs.kotlinx.coroutines)
                implementation(libs.kotlinx.datetime)
                // The storage tile's real data source (hub proposal §13 H4):
                // ClientHubStatusSource reads hub-authored HubStatus rows through
                // the client surface. `api` — the client module transitively
                // exposes :core-common, whose TenshuClient appears in the
                // source's public constructor. This is Home's FIRST Tenshu
                // client dependency (the app was deliberately client-less
                // through M1–M5); the mock repository stays the default binding
                // and the honest fallback.
                api(project(":hub-management-service-client"))
                // HD-D5: the Devices tab's real source — ClientDeviceSource
                // joins HomeDevice/DeviceState rows through the client
                // surface; the mock repository stays the honest fallback.
                api(project(":home-device-service-client"))
                // `ControlReachability` for the shell's Control-unavailable surface (ADR 0025).
                // `api`, since the reason type appears in `HomeReachability`'s signature.
                api(project(":app-reachability"))
                // RE-1: the Automations tab's real source — ClientRoutineSource
                // reads synced Routine rows and derives lastRunAt from the
                // RoutineRun receipts; the mock repository stays the fallback.
                // The common module comes along for the program codec, which is
                // where the UI model is translated into the engine vocabulary.
                api(project(":routine-service-client"))
                api(project(":routine-service-common"))
            }
        }
        // Shared JVM parent for androidMain + desktopMain — the default hierarchy
        // template doesn't materialise this under the Android KMP plugin. Present for
        // symmetry with :timeline-shared-logic even though M1 has no JVM-only actuals.
        val jvmMain = create("jvmMain") {
            dependsOn(commonMain)
        }
        getByName("androidMain") { dependsOn(jvmMain) }
        getByName("desktopMain") { dependsOn(jvmMain) }

        getByName("commonTest") {
            dependencies {
                implementation(project(":viewmodel-test-utils"))
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.test)
            }
        }
    }
}
