plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
}

// The `OwnedViewModels` test fixture — see docs/testing.md §11.
//
// Targets are the union of what its consumers need: `:maps-shared-logic` uses it from `commonTest`
// (android + desktop + ios), `:drive-shared-ui` and `:photos-shared-ui` from `desktopTest`.
@OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)
kotlin {
    jvmToolchain(17)
    applyDefaultHierarchyTemplate()

    android {
        namespace = "org.tenshu.viewmodel.test"
        compileSdk = 37
        minSdk = 24
    }

    jvm("desktop")

    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        val commonMain = getByName("commonMain") {
            dependencies {
                // `api`: `ViewModel` and `kotlin.test` appear in this module's own signatures.
                api(libs.lifecycle.viewmodel)
                api(libs.kotlin.test)
                api(libs.kotlinx.coroutines.test)
                implementation(libs.kotlinx.coroutines)
            }
        }

        // ⚠ `runBlocking` is not resolvable from `commonMain` whatever targets are declared —
        // kotlinx-coroutines declares it in its own concurrent (jvm + native) source set, and a
        // common source set sees only the library's common API. The wrappers that need it live
        // here instead. `:core-test-utils` carries the same intermediate as `nonJsMain`.
        val blockingMain = create("blockingMain") {
            dependsOn(commonMain)
        }
        getByName("androidMain") { dependsOn(blockingMain) }
        getByName("desktopMain") { dependsOn(blockingMain) }
        getByName("iosMain") { dependsOn(blockingMain) }

        // The one `-test-utils` module with tests of its own — docs/testing.md §11.3 says why.
        getByName("desktopTest") {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.test)
            }
        }
    }
}
