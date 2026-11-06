plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    // For @Serializable on RouteSample/HrSample (Fit's local workout outbox journals them as
    // JSON) — inert for the wire, which rides WorkoutStreamsCodec + the schema DSL.
    alias(libs.plugins.kotlinx.serialization)
}

@OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)
kotlin {
    jvmToolchain(17)
    applyDefaultHierarchyTemplate()

    android {
        namespace = "org.tenshu.health.common"
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
                api(project(":health-service"))
                api(project(":core-common"))
                implementation(libs.kotlinx.serialization.core)
            }
        }
        getByName("commonTest") {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(project(":core-test-utils"))
            }
        }
    }
}
