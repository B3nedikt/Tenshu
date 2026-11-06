plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
}

@OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)
kotlin {
    jvmToolchain(17)
    applyDefaultHierarchyTemplate()

    android {
        namespace = "org.tenshu.photo.test"
        compileSdk = 37
        minSdk = 24
    }

    jvm()

    js {
        browser()
    }

    tasks.matching { it.name == "compileTestKotlinJs" }.configureEach { enabled = false }

    // iOS targets stay declared so commonMain remains KMP-honest (it catches an accidental
    // JVM-only dependency), but no `.framework` is produced here — the umbrella module that
    // actually ships exposes one. The sibling services all say the same.
    iosX64()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        getByName("commonMain") {
            dependencies {
                api(project(":photo-service"))
                api(project(":photo-service-common"))
                // ⚠ Both halves: the *client* module registers the service RO factory and the
                // *proxy* module the op implementations, and a fixture missing either fails at
                // first use rather than at wiring time.
                api(project(":photo-service-client"))
                api(project(":photo-service-proxy"))
                api(project(":core-common"))
                api(project(":core-test-utils"))
            }
        }

    }
}
