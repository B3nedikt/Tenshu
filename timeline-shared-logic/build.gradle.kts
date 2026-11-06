plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.kotlinx.serialization)
}

// Timeline's Compose-free logic layer — domain model, the mock repository, and the
// view models (docs/proposals/timeline-app.md). Mirrors :drive-shared-logic so it can
// compile on iOS later; the Compose UI lives in :timeline-shared-ui, which depends on
// this. Packages are shared across the two modules (`app.tenshu.timeline.*`) so a
// screen and its view model resolve each other over the module boundary with no import.
//
// v1 has NO service dependencies — it is UI-over-mock. The real Timeline store replaces
// MockTimelineRepository behind the TimelineRepository interface (one Koin binding).
@OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)
kotlin {
    jvmToolchain(17)
    applyDefaultHierarchyTemplate()

    android {
        namespace = "app.tenshu.timeline.logic"
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
                // The presentation model (`app.tenshu.timeline.model`: Day / Moment / Trip / Place …)
                // lives with the feed projection that produces it, in a module that also compiles to
                // JS (timeline-app.md §Web scope 4a). `api`: every screen and VM speaks it.
                api(project(":timeline-projection"))
                implementation(libs.kotlinx.coroutines)
                implementation(libs.kotlinx.datetime)
                // `MeasurementSystem` — the realm-wide metric/imperial answer the distance
                // formatter branches on (fit-app.md §18.2). Declarations only.
                api(project(":preference-service"))
                // `api`: UnreachableReason rides TimelineRepository.controlUnreachableReason(),
                // so :timeline-shared-data and :timeline-shared-ui both read it (SK-1b,
                // `core-schema-skew-surface.md`).
                api(project(":app-reachability"))
                // `runCatchingCancellable` — the suspend-safe `runCatching` these view models
                // guard their repository calls with. Declarations only; nothing of core-common's
                // proxy plumbing is reachable from here.
                implementation(project(":core-common"))
                implementation(libs.kotlinx.serialization.core)
            }
        }
        // Shared JVM parent for androidMain + desktopMain — the default hierarchy
        // template doesn't materialise this under the Android KMP plugin. Present for
        // symmetry with :drive-shared-logic even though v1 has no JVM-only actuals yet.
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
