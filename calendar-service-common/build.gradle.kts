plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
}

@OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)
kotlin {
    jvmToolchain(17)
    applyDefaultHierarchyTemplate()

    android {
        namespace = "org.tenshu.calendar.common"
        compileSdk = 37
        minSdk = 24
    }

    jvm()

    js {
        browser()
    }

    tasks.matching { it.name == "compileTestKotlinJs" }.configureEach { enabled = false }

    // iOS targets stay declared so commonMain remains KMP-honest (catches
    // accidental JVM-only deps), but we don't produce a `.framework` here:
    // there is no iOS app consuming this module yet, and every module
    // declaring its own framework all named `shared` was pure build-time
    // cost. When an iOS app appears, expose a proper `XCFramework` on the
    // umbrella module(s) that actually ship.
    iosX64()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        getByName("commonMain") {
            dependencies {
                // CA-5c: the proxy posts reminders, so the alert mapping lives
                // here rather than in an app module.
                api(project(":app-notify"))
                api(project(":calendar-service"))
                api(project(":core-common"))
                // The recurrence engine is calendar arithmetic over real
                // civil time — zones, DST gaps, month-length clamping. Same
                // dependency, and for the same reason, as the routines
                // engine's `FiringCalculator`.
                implementation(libs.kotlinx.datetime)
            }
        }
        getByName("commonTest") {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.test)
                implementation(project(":core-test-utils"))
            }
        }
    }
}
