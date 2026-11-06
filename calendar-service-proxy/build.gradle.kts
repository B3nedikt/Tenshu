plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
}

@OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)
kotlin {
    jvmToolchain(17)
    applyDefaultHierarchyTemplate()

    android {
        namespace = "org.tenshu.calendar.proxy"
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
                // The `Schedule` op the reminder armer goes through.
                implementation(project(":sync-service"))
                // `SemanticMerger` — Calendar contributes two (CA-2j); sync consumes them
                // through the registry every platform's DI builds.
                api(project(":sync-service-common"))
                // CA-7's subscription fetch. ⚠ In *this* module and not the app:
                // all egress is Control's, which is what keeps Calendar's manifest
                // free of INTERNET while a subscription still works.
                api(libs.ktorClientCore)
                api(project(":calendar-service"))
                api(project(":calendar-service-common"))
                api(project(":core-common"))
                // Read side of the §6.4 headless defaults: the save-event
                // implementation resolves an absent calendar/duration from
                // the preference rows, so it needs their schema.
                api(project(":preference-service"))
                implementation(libs.kotlinx.datetime)
            }
        }
        getByName("commonTest") {
            dependencies {
                implementation(libs.ktorClientMock)
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.test)
                implementation(project(":core-test-utils"))
                implementation(project(":calendar-service-test-utils"))
                implementation(project(":preference-service-client"))
            }
        }
    }
}
