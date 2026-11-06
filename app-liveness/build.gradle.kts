plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
}

// A tiny, app-neutral module for **background-task liveness**: the generic idle ⇄ in-flight ⇄
// terminal edge logic that drives a platform foreground service + notification from a controller's
// `StateFlow`. Extracted from Drive's `NotificationLiveness` (it lived in `:drive-shared-logic`)
// once a second app — Fit's Takeout import — needed the identical mechanic; forking it was the
// wrong move (the Drive KDoc said as much). Coroutines only, Android-free, all targets, so any app's
// shared-logic or shell can depend on it without pulling a heavier module in.
@OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)
kotlin {
    jvmToolchain(17)
    applyDefaultHierarchyTemplate()

    android {
        namespace = "app.tenshu.liveness"
        compileSdk = 37
        minSdk = 24
    }

    jvm("desktop")
    js { browser() }
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        getByName("commonMain") {
            dependencies {
                implementation(libs.kotlinx.coroutines)
            }
        }
        getByName("androidMain") {
            dependencies {
                // ProcessLifecycleOwner, behind AppForegroundState: the process-wide foreground
                // signal armForegroundService retries on. The only Android code in this module —
                // commonMain stays Android-free, so every other target is unaffected.
                implementation(libs.lifecycle.process)
            }
        }
        getByName("commonTest") {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.test)
            }
        }
    }
}
