plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
}

@OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)
kotlin {
    jvmToolchain(17)
    applyDefaultHierarchyTemplate()

    android {
        namespace = "org.tenshu.core.test"
        compileSdk = 37
        minSdk = 24
    }

    jvm()

    js {
        browser()
    }

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
        val commonMain = getByName("commonMain") {
            dependencies {
                api(project(":core"))
                api(project(":core-common"))
                api(libs.kotlin.test)
                api(libs.kotlinx.coroutines.test)
                implementation(libs.kotlinx.atomicfu)
            }
        }
        // TestProxy.handleRequest bridges with runBlocking to keep tests synchronous;
        // that's not portable to JS. Hosting that file here keeps it off the JS klib
        // without giving up JS compilation of the rest of the test fixtures.
        val nonJsMain = create("nonJsMain") {
            dependsOn(commonMain)
        }
        getByName("jvmMain") { dependsOn(nonJsMain) }
        getByName("androidMain") { dependsOn(nonJsMain) }
        getByName("iosMain") { dependsOn(nonJsMain) }
    }
}
