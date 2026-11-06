plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    // Generates serializers for @Serializable wire DTOs (HttpErrorReportUploader).
    // Without it the annotation compiles but no serializer is emitted, so Ktor's
    // reflective lookup throws "Serializer for class … not found" at runtime.
    alias(libs.plugins.kotlinx.serialization)
}

@OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)
kotlin {
    jvmToolchain(17)
    applyDefaultHierarchyTemplate()

    android {
        namespace = "org.tenshu.logging.proxy"
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
                api(project(":logging-service-common"))
                api(project(":core-proxy"))
                implementation(libs.kotlinx.coroutines)
                api(libs.ktorClientCore)
                api(libs.ktorClientContentNegotiation)
                api(libs.ktorSerializationJson)
            }
        }
        getByName("commonTest") {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.test)
                implementation(project(":core-test-utils"))
                implementation(project(":logging-service-client"))
                implementation(project(":logging-service-test-utils"))
            }
        }
    }
}
