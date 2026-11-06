plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.kotlinx.serialization)
}

// Maps' Compose-free logic layer — domain model, the mock repository, and the view
// models (docs/proposals/maps-app.md §6). Mirrors :timeline-shared-logic so it can
// compile on iOS later; the Compose UI lives in :maps-shared-ui, which depends on this.
// Packages are shared across the two modules (`app.tenshu.maps.*`) so a screen and its
// view model resolve each other over the module boundary with no import.
//
// Ph0 = scaffold only (this build file). The domain model lands in Ph1, the mock
// repository in Ph2, the view models in Ph3. v1 has NO service dependencies — it is
// UI-over-mock; the real store replaces the mock behind the repository interface
// (:place-service, Ph9) with one Koin binding.
@OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)
kotlin {
    jvmToolchain(17)
    applyDefaultHierarchyTemplate()

    android {
        namespace = "app.tenshu.maps.logic"
        compileSdk = 37
        minSdk = 24
    }

    jvm("desktop")

    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        val commonMain = getByName("commonMain") {
            dependencies {
                // Compose-free base ViewModel + viewModelScope (the Ph3 VMs extend it).
                api(libs.lifecycle.viewmodel)
                // `api`: UnreachableReason rides PlaceRepository.controlUnreachableReason(),
                // so :maps-shared-data and :maps-shared-ui both read it (SK-1b,
                // `core-schema-skew-surface.md`).
                api(project(":app-reachability"))
                // `runCatchingCancellable` — the suspend-safe `runCatching` these view models
                // guard their repository calls with. Declarations only; nothing of core-common's
                // proxy plumbing is reachable from here.
                implementation(project(":core-common"))
                implementation(libs.kotlinx.coroutines)
                implementation(libs.kotlinx.datetime)
                implementation(libs.kotlinx.serialization.core)
            }
        }
        // Shared JVM parent for androidMain + desktopMain — the default hierarchy
        // template doesn't materialise this under the Android KMP plugin. Present for
        // symmetry with :timeline-shared-logic even though Ph0 has no JVM-only actuals.
        val jvmMain = create("jvmMain") {
            dependsOn(commonMain)
        }
        getByName("androidMain") { dependsOn(jvmMain) }
        getByName("desktopMain") { dependsOn(jvmMain) }

        getByName("commonTest") {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.test)
                implementation(project(":viewmodel-test-utils"))
            }
        }
    }
}
