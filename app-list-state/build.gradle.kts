plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
}

// A tiny, app-neutral module for **the third state of a list read**: `ListLoadState` (the
// repository-boundary shape that carries rows OR a failure) and `ListLoadPhase` (the UI-state
// companion for surfaces that keep their rows in a plain `List` field).
//
// Extracted from `:drive-shared-logic` (where it lived as `app.tenshu.drive.data`) once Fit needed
// the identical distinction on Home and Browse. Forking it was the wrong move for the same reason
// `:app-liveness` was extracted: the bug it prevents — *absence of data read as evidence of absence
// of data* — is not Drive's, it is every IPC-backed app's, and a second copy is a second place for
// the `Failed` arm to get folded back into `emptyList()`.
//
// ⚠ Deliberately dependency-free: no coroutines, no Compose. A repository returns
// `Flow<ListLoadState<T>>` and a Compose-free view model maps it to a `ListLoadPhase`, so this has
// to be reachable from `*-shared-logic` (iOS, Compose-free) as well as from `*-shared-ui`.
@OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)
kotlin {
    jvmToolchain(17)
    applyDefaultHierarchyTemplate()

    android {
        namespace = "app.tenshu.liststate"
        compileSdk = 37
        minSdk = 24
    }

    jvm("desktop")
    js { browser() }
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        getByName("commonTest") {
            dependencies {
                implementation(libs.kotlin.test)
            }
        }
    }
}
