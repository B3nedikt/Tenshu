plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
}

/**
 * The inference **seam** (IN-1, `docs/proposals/inference-service.md`) — capabilities, consent and
 * provenance, with no model and no runtime anywhere near it.
 *
 * ⚠ There is deliberately no `-common` / `-client` module yet: IN-1 ships no operation, and an
 * empty module is a promise rather than a seam. They arrive with the first op (IN-3/IN-4).
 */
@OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)
kotlin {
    jvmToolchain(17)
    applyDefaultHierarchyTemplate()

    android {
        namespace = "org.tenshu.inference"
        compileSdk = 37
        minSdk = 24
    }

    jvm()

    js {
        browser()
    }

    iosX64()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        getByName("commonMain") {
            dependencies {
                api(project(":core"))
                // IN-D3: consent rides PreferenceService, and a service declaring its own
                // preferences in its own module is the `FileSyncPreferences` precedent.
                api(project(":preference-service"))
                // IN-2c 1c-3: the reactive client surface (DownloadProgress stream → Flow) needs
                // coroutines, as `:map-region-service` does for the same op and `:core` does for
                // its own `Stream.flow()`.
                implementation(libs.kotlinx.coroutines)
            }
        }
        getByName("commonTest") {
            dependencies {
                implementation(libs.kotlin.test)
            }
        }
    }
}
