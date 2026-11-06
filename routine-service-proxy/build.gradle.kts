plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
}

@OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)
kotlin {
    jvmToolchain(17)
    applyDefaultHierarchyTemplate()

    android {
        namespace = "org.tenshu.routine.proxy"
        compileSdk = 37
        minSdk = 24
    }

    jvm()

    js {
        browser()
    }

    // iOS targets stay declared so commonMain remains KMP-honest (catches
    // accidental JVM-only deps), but we don't produce a `.framework` here.
    iosX64()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        getByName("commonMain") {
            dependencies {
                api(project(":routine-service-common"))
                api(project(":core-proxy"))
                // The engine's action vocabulary is closed and local (RE-D8):
                // device commands and notifications, both over shipped ops.
                api(project(":home-device-service"))
                api(project(":notification-service"))
                // ScheduleService — the single armed next-wake item (RE-D5).
                api(project(":sync-service"))
                // TombstoneEmitter — what makes DeleteRoutine reach every device.
                api(project(":sync-service-common"))
                // RE-3: the roster's capability tier. A hub is the device that
                // publishes a HubStatus row — Device itself carries no power or
                // platform signal, and adding one would reshape a shipped
                // composite (HR#9).
                api(project(":hub-management-service"))
                api(project(":identity-service"))
            }
        }
        getByName("commonTest") {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.test)
                implementation(project(":core-test-utils"))
                implementation(project(":home-device-service-common"))
            }
        }
    }
}
