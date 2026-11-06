plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
}

@OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)
kotlin {
    jvmToolchain(17)
    applyDefaultHierarchyTemplate()

    android {
        namespace = "org.tenshu.logging.test"
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
        val commonMain = getByName("commonMain") {
            dependencies {
                api(project(":logging-service-client"))
                api(project(":core-test-utils"))
                api(project(":logging-service-proxy"))
            }
        }
        val nonJsMain = create("nonJsMain") {
            dependsOn(commonMain)
        }
        getByName("jvmMain") { dependsOn(nonJsMain) }
        getByName("androidMain") { dependsOn(nonJsMain) }
        getByName("iosMain") { dependsOn(nonJsMain) }
    }
}
