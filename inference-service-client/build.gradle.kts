plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
}

/**
 * Client-side registration for the inference service's rows (IN-4).
 *
 * ⚠ IN-1 deferred this module and IN-3a still did not need it: rows were only written, proxy-side.
 * It exists now because something has to **read** them through a chain — and a chain needs the
 * request objects registered here.
 */
@OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)
kotlin {
    jvmToolchain(17)
    applyDefaultHierarchyTemplate()

    android {
        namespace = "org.tenshu.inference.client"
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
                api(project(":core-client"))
                implementation(project(":core-common"))
            }
        }
    }
}
