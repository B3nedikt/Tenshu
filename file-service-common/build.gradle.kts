plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
}

@OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)
kotlin {
    jvmToolchain(17)
    applyDefaultHierarchyTemplate()

    android {
        namespace = "org.tenshu.file.common"
        compileSdk = 37
        minSdk = 24

        withHostTest {
        }
    }

    jvm()

    js {
        browser()
    }

    // commonTest sources import TestProxy from :core-test-utils' nonJsMain
    // (runBlocking-bridging, not portable to JS). Disable JS test compilation
    // here until Phase T sets up real JS test infrastructure
    // (see docs/web/roadmap.md). Main JS compilation is unaffected.
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
                api(project(":file-service"))
                api(project(":core-common"))
                api(project(":notification-service-common"))
                // The `referenceProperty` registration references FileModification.Device
                // (typed Property.Value<Device>). `implementation`, not `api`, so it stays
                // off consumers' classpaths. See core-reference-property.md §3.4.
                implementation(project(":identity-service"))
                // The cross-config `fileSyncSettings` serviceDataRelation hangs off
                // `PreferenceService.ID` (drive-offline-online.md §"Follow-up"). `:file-service`
                // already depends on `:preference-service` but only as `implementation`, so it
                // isn't transitive — depend on it directly here for `PreferenceService.ID`.
                implementation(project(":preference-service"))
            }
        }
        getByName("commonTest") {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.test)
                implementation(project(":core-test-utils"))
                // Registers Device (schema + DTO) so the resolution test can save + deref it.
                implementation(project(":identity-service-common"))
            }
        }
        getByName("androidHostTest") {
            dependencies {
                implementation(libs.paging.testing)
            }
        }
    }
}
