plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
}

@OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)
kotlin {
    jvmToolchain(17)
    applyDefaultHierarchyTemplate()

    android {
        namespace = "org.tenshu.file.test"
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
                api(project(":core-test-utils"))
                api(project(":file-service"))
                api(project(":file-service-common"))
                api(project(":file-service-client"))
                api(project(":file-service-proxy"))
                // `:file-service-proxy`'s `CreateFolderImplementation` /
                // `DeleteFileImplementation` write `Notification` entities; we
                // need `initNotificationService` to register the relation
                // observer for them to land in `NOTIFICATIONS`.
                api(project(":notification-service-proxy"))
                // The registry mirrors production by carrying the preference
                // module: `:file-service-proxy` reads `file.sync.*` `DeviceSetting`
                // rows directly (sync policy + the local-cache ceiling), and its own
                // dep on preference-service-common is `implementation`, so it does
                // not reach here transitively.
                api(project(":preference-service-common"))
            }
        }
        // FileTestProxy depends on :file-service-proxy.
        val nonJsMain = create("nonJsMain") {
            dependsOn(commonMain)
        }
        getByName("jvmMain") { dependsOn(nonJsMain) }
        getByName("androidMain") { dependsOn(nonJsMain) }
        getByName("iosMain") { dependsOn(nonJsMain) }
    }
}
