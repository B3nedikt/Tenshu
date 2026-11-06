plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
}

@OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)
kotlin {
    jvmToolchain(17)
    applyDefaultHierarchyTemplate()

    android {
        namespace = "org.tenshu.timeline.common"
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
                api(project(":timeline-service"))
                api(project(":core-common"))
            }
        }
        getByName("commonTest") {
            dependencies {
                // The pure ingestion algorithms (MomentReconciler / TripSuggester / TripDetector)
                // live here and are tested here — plain data in, plain data out, no proxy needed.
                implementation(libs.kotlin.test)
            }
        }
    }
}
