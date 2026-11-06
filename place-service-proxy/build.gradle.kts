plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
}

@OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)
kotlin {
    jvmToolchain(17)
    applyDefaultHierarchyTemplate()

    android {
        namespace = "org.tenshu.place.proxy"
        compileSdk = 37
        minSdk = 24
    }

    jvm()

    js {
        browser()
        // Matches the :core-common convention so ES-module imports between
        // KMP modules compose. See docs/web/architecture.md §12.6.1.
        useEsModules()
    }

    // commonTest fixtures pull the runBlocking-bridged TestProxy from nonJsMain.
    tasks.matching { it.name == "compileTestKotlinJs" }.configureEach { enabled = false }

    iosX64()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        getByName("commonMain") {
            dependencies {
                api(project(":place-service-common"))
                api(project(":core-proxy"))
                // SemanticMergerRegistry is in `initPlaceService`'s signature (§9.2), so it is
                // part of this module's public API — `api`, not `implementation`.
                api(project(":sync-service-common"))
            }
        }
        getByName("commonTest") {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.test)
                implementation(project(":core-test-utils"))
                implementation(project(":place-service-client"))
                implementation(project(":place-service-test-utils"))
            }
        }
    }
}
