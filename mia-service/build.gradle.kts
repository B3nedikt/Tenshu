plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
}

@OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)
kotlin {
    jvmToolchain(17)
    applyDefaultHierarchyTemplate()

    android {
        namespace = "org.tenshu.mia"
        compileSdk = 37
        minSdk = 24
    }

    jvm()

    js {
        browser()
    }

    // iOS targets stay declared so commonMain remains KMP-honest (catches
    // accidental JVM-only deps), but we don't produce a `.framework` here:
    // there is no iOS app consuming this module yet.
    iosX64()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        getByName("commonMain") {
            dependencies {
                api(project(":core"))
                // The grammar resolves an utterance onto the FILE service's own types
                // (`FileCategory`) rather than a parallel vocabulary of its own —
                // MQ-D4a's seam rule: the grammar is Mia's, the types it produces
                // belong to the service being asked about.
                api(project(":file-service"))
                // MQ-2b: the sources Mia may be granted, and the `Preference` those grants
                // are declared as. A service declaring its own preferences in its own module
                // is the `FileSyncPreferences` precedent.
                api(project(":timeline-service"))
                api(project(":health-service"))
                api(project(":place-service"))
                api(project(":home-device-service"))
                // RE-5: routines are a Mia source of their own — see `MiaSource.ROUTINES`.
                api(project(":routine-service"))
                api(project(":calendar-service"))
                // IN-4: the label source Mia reads ("photos of a cat").
                api(project(":inference-service"))
                api(project(":preference-service"))
                // `api`, not `implementation`: the planner's own signature takes a
                // `TimeZone` (the caller's, per AskMia's KDoc), so it is part of this
                // module's surface rather than an internal detail.
                api(libs.kotlinx.datetime)
            }
        }
        getByName("commonTest") {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.test)
            }
        }
    }
}
