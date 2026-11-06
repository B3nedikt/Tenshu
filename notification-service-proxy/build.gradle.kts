plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
}

@OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)
kotlin {
    jvmToolchain(17)
    applyDefaultHierarchyTemplate()

    android {
        namespace = "org.tenshu.notifications.proxy"
        compileSdk = 37
        minSdk = 24

        withHostTest {
        }
    }

    jvm()

    js {
        browser()
        // Matches the :core-common convention so ES-module imports between
        // KMP modules compose. See docs/web/architecture.md §12.6.1.
        useEsModules()
    }

    // commonTest fixtures still pull from `nonJsMain` (TestProxy bridges
    // runBlocking). Disable JS test compilation until Phase T lands.
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
                api(project(":notification-service-common"))
                api(project(":core-proxy"))
            }
        }
        getByName("commonTest") {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.test)
                implementation(project(":core-test-utils"))
                implementation(project(":notification-service-client"))
                implementation(project(":notification-service-test-utils"))
                implementation(project(":file-service-test-utils"))
            }
        }
    }
}
