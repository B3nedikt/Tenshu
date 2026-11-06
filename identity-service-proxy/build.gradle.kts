plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
}

@OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)
kotlin {
    jvmToolchain(17)
    applyDefaultHierarchyTemplate()

    android {
        namespace = "org.tenshu.identity.proxy"
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

    // commonTest fixtures still pull from `nonJsMain` (TestProxy bridges
    // runBlocking). Disable JS test compilation until Phase T lands.
    tasks.matching { it.name == "compileTestKotlinJs" }.configureEach { enabled = false }

    // iOS targets (Control-on-iOS C4a): the in-process proxy sync core must run on the iPhone for
    // both the in-process-collapse (route 1) and EPC-fallback (route 2) data planes. We declare only
    // arm64 + simulatorArm64 (what the `:tenshu-sdk-ios-<app>` frameworks ship); no `iosX64`
    // (paging-common has no x64 klib) and no per-module `.framework` (the app frameworks are it).
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        getByName("commonMain") {
            dependencies {
                api(project(":identity-service-common"))
                api(project(":core-proxy"))
                implementation(libs.kotlinx.coroutines)
                implementation(libs.ktorClientCore)
            }
        }
        getByName("commonTest") {
            dependencies {
                implementation(kotlin("test"))
                implementation(project(":core-test-utils"))
                implementation(project(":identity-service-client"))
            }
        }
    }
}
