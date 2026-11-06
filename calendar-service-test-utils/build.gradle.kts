plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
}

@OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)
kotlin {
    jvmToolchain(17)
    applyDefaultHierarchyTemplate()

    android {
        namespace = "org.tenshu.calendar.test"
        compileSdk = 37
        minSdk = 24
    }

    jvm()

    js { browser() }

    iosX64()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        getByName("commonMain") {
            dependencies {
                api(project(":calendar-service"))
                api(project(":calendar-service-common"))
                api(project(":calendar-service-client"))
                api(project(":calendar-service-proxy"))
                api(project(":core-common"))
                api(project(":core-test-utils"))
                // The §6.4 headless defaults are preference rows, so the
                // fixture needs the preference schema + its write op.
                api(project(":preference-service-common"))
                api(project(":preference-service-client"))
                api(project(":preference-service-proxy"))
            }
        }
    }
}
