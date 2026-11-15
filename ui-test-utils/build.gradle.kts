plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.jetbrains.compose)
    alias(libs.plugins.kotlin.compose)
}

// Compose UI test fixtures — `NoMotion`, see docs/testing.md §13.
//
// Desktop-only on purpose: every Compose UI test in the repo runs under `desktopTest`
// (`compose.desktop.uiTestJUnit4`), and `:viewmodel-test-utils` must stay Compose-free because the
// shared-logic modules and the iOS SDKs consume it.
kotlin {
    jvmToolchain(17)

    jvm("desktop")

    sourceSets {
        getByName("desktopMain") {
            dependencies {
                // `api`: `MotionDurationScale` is this module's public signature.
                api(compose.ui)
            }
        }

        getByName("desktopTest") {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(compose.foundation)
                implementation(compose.desktop.currentOs)
                implementation(compose.desktop.uiTestJUnit4)
            }
        }
    }
}
