plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
}

/**
 * Registration for the inference service's synced rows (IN-3). ⚠ IN-1 deliberately did not create
 * this module: an empty one is a promise rather than a seam. It exists now because `MediaLabel` is
 * a real row that has to be materialisable on both sides of the wire.
 */
@OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)
kotlin {
    jvmToolchain(17)
    applyDefaultHierarchyTemplate()

    android {
        namespace = "org.tenshu.inference.common"
        compileSdk = 37
        minSdk = 24
    }

    jvm()

    js {
        browser()
    }

    iosX64()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        getByName("commonMain") {
            dependencies {
                api(project(":inference-service"))
                api(project(":core-common"))
            }
        }
        getByName("commonTest") {
            dependencies {
                implementation(libs.kotlin.test)
            }
        }
    }
}
