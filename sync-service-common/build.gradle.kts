plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
}

@OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)
kotlin {
    jvmToolchain(17)
    applyDefaultHierarchyTemplate()

    android {
        namespace = "org.tenshu.sync.common"
        compileSdk = 37
        minSdk = 24
    }

    jvm()

    js {
        browser()
    }

    // commonTest pulls in TestProxy from :core-test-utils' nonJsMain. Mirrors the
    // matching block in :core-common, :file-service-common, :notification-service-common.
    tasks.matching { it.name == "compileTestKotlinJs" }.configureEach { enabled = false }

    listOf(
        iosX64(),
        iosArm64(),
        iosSimulatorArm64()
    )

    sourceSets {
        getByName("commonMain") {
            dependencies {
                api(project(":sync-service"))
                api(project(":core-common"))
            }
        }
        getByName("commonTest") {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.test)
                implementation(project(":core-test-utils"))
            }
        }
    }
}
