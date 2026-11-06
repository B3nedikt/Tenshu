plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.kotlinx.serialization)
}

// Fit's Compose-free logic layer — domain model, the mock repository, the recording
// engine, and the view models (docs/proposals/fit-app.md). Mirrors :timeline-shared-logic
// so it can compile on iOS later; the Compose UI lives in :fit-shared-ui, which depends
// on this. Packages are shared across the two modules (`app.tenshu.fit.*`) so a screen
// and its view model resolve each other over the module boundary with no import.
//
// v1 has NO service dependencies — it is UI-over-mock. The real store replaces
// MockFitRepository behind the FitRepository interface (one Koin binding); the on-device
// source is :health-service (proposals/timeline-services.md §4).
@OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)
kotlin {
    jvmToolchain(17)
    applyDefaultHierarchyTemplate()

    android {
        namespace = "app.tenshu.fit.logic"
        compileSdk = 37
        minSdk = 24
    }

    jvm("desktop")

    // ⚠ No `js` target, and that is deliberate — see :fit-shared-file. This module used to carry
    // one (fit-app.md §9.10) purely because :fit-shared-data has an `api` dep on it and the web
    // GPX/TCX parser needed to compile; the whole logic layer, every ViewModel included, went to
    // JS for ~470 lines of parser. That is now its own module, which restores House Rule #5's
    // boundary (protocol layer → JS, app logic layer → not; drive-shared-logic never had one) and
    // unblocks :core-paging, whose paging-common publishes no JS artifact.

    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        val commonMain = getByName("commonMain") {
            dependencies {
                // Compose-free base ViewModel + viewModelScope (the VMs extend it).
                api(project(":app-liveness"))
                // `api`: ListLoadState rides FitRepository's return types and ListLoadPhase the UI
                // states, so :fit-shared-data and :fit-shared-ui both read them.
                api(project(":app-list-state"))
                // `api`: UnreachableReason rides FitRepository.controlUnreachableReason() and the
                // shell view model, so :fit-shared-data and :fit-shared-ui both read it (SK-1,
                // `core-schema-skew-surface.md`).
                api(project(":app-reachability"))
                // `api`: ActivityFile / ActivityFileSource are the picker seam on FitRepository.
                // ⚠ The dependency points THIS way — :fit-shared-file knows nothing of the logic
                // layer, which is what lets it keep a JS target this module has dropped.
                api(project(":fit-shared-file"))
                // `api`: the presentation model (`app.tenshu.fit.model`'s FitModel / DataSourceModel)
                // and the pure projections live there, so native and web fold rows by ONE
                // implementation (`fit-app.md` §Web scope). Every consumer compiles unchanged.
                api(project(":fit-projection"))
                // `api`: `FitRepository.workoutsPaged` returns `Flow<PagingData<Workout>>`, so
                // paging-common is on this module's public surface — :fit-shared-data implements
                // it, :fit-shared-ui collects it, and the iOS umbrella already exports
                // :core-paging. ⚠ This is the dependency the JS target blocked: paging-common
                // publishes no JS artifact, which is why the parser had to leave first (see above).
                api(project(":core-paging"))
                api(libs.lifecycle.viewmodel)
                implementation(libs.kotlinx.coroutines)
                implementation(libs.kotlinx.datetime)
                implementation(libs.kotlinx.serialization.core)
                // Preference *declarations* only (`FitPreferences`, plus `OwnerPreferences`'
                // `BiologicalSex`/`MeasurementSystem` on the model) — the proxy-backed readers
                // live in :fit-shared-data, so this module keeps its client-free shape.
                api(project(":preference-service"))
            }
        }
        // Shared JVM parent for androidMain + desktopMain — the default hierarchy
        // template doesn't materialise this under the Android KMP plugin. Present for
        // symmetry with :timeline-shared-logic even though v1 has no JVM-only actuals yet.
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
