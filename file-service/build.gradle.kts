plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
}

@OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)
kotlin {
    jvmToolchain(17)
    applyDefaultHierarchyTemplate()

    android {
        namespace = "org.tenshu.file"
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
        getByName("commonMain") {
            dependencies {
                api(project(":core"))
                api(project(":notification-service"))
                // For FileModification.Device (a reference value property → Device).
                // `implementation`, not `api`: consumers that never touch
                // FileModification.Device don't pull identity-service transitively;
                // those that do (e.g. :drive-shared) opt in with their own dep.
                // See docs/proposals/core-reference-property.md §3.4.
                implementation(project(":identity-service"))
                // For the file-synchronisation settings model (FileSyncPreferences +
                // SyncPolicy adapters) — Preference / PreferenceAdapter / PreferenceScope.
                // `implementation`: consumers (drive-shared, control-shared) already
                // declare their own preference-service dep.
                implementation(project(":preference-service"))
            }
        }
        getByName("commonTest") {
            dependencies {
                implementation(libs.kotlin.test)
                // The reclamation live-set walk is suspend (its folder listing does IO in
                // production), so its tests need runTest — same dependency the sibling
                // *-proxy modules already carry.
                implementation(libs.kotlinx.coroutines.test)
            }
        }
    }
}
