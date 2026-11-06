plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
}

@OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)
kotlin {
    jvmToolchain(17)
    applyDefaultHierarchyTemplate()

    android {
        namespace = "org.tenshu.ingest.proxy"
        compileSdk = 37
        minSdk = 24
        withHostTest { }
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
                api(project(":ingest-service-common"))
                api(project(":core-proxy"))
            }
        }
        getByName("commonTest") {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.test)
                implementation(project(":core-test-utils"))
                implementation(project(":ingest-service-client"))
                implementation(project(":ingest-service-test-utils"))
            }
        }
    }
}
