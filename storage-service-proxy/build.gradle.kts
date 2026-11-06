plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
}

@OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)
kotlin {
    jvmToolchain(17)
    applyDefaultHierarchyTemplate()

    android {
        namespace = "org.tenshu.storage.proxy"
        compileSdk = 37
        minSdk = 24

        withHostTest {
        }
    }

    jvm()

    js {
        browser()
        useEsModules()
    }

    tasks.matching { it.name == "compileTestKotlinJs" }.configureEach { enabled = false }

    iosX64()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        getByName("commonMain") {
            dependencies {
                api(project(":storage-service-common"))
                api(project(":core-proxy"))
                // web-storage-status.md Bucket 2: the relocated entitlement-derived
                // provider reads the synced `Entitlement` (a downward dep — identity
                // does not depend on storage, so no cycle). Exposed in the public
                // `entitlementToFigures(Entitlement?, …)` signature, hence `api`.
                api(project(":identity-service"))
                implementation(libs.kotlinx.coroutines)
            }
        }
        getByName("commonTest") {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.test)
                implementation(project(":core-test-utils"))
                implementation(project(":storage-service-client"))
                implementation(project(":storage-service-test-utils"))
                implementation(project(":identity-service-common"))
            }
        }
    }
}
