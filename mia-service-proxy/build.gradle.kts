plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
}

@OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)
kotlin {
    jvmToolchain(17)
    applyDefaultHierarchyTemplate()

    android {
        namespace = "org.tenshu.mia.proxy"
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
                api(project(":mia-service-common"))
                api(project(":core-proxy"))
                // MQ-3a: ConfirmMiaAction dispatches to the shipped IssueDeviceCommand.
                implementation(project(":home-device-service-proxy"))
                implementation(project(":calendar-service"))
                implementation(project(":calendar-service-client"))
                implementation(project(":calendar-service-common"))
                implementation(project(":calendar-service-proxy"))
                // MQ-2's sources. ⚠ The SERVICE modules only (types + relation ids) — the
                // lowering builds chains, it does not call these services' clients.
                implementation(project(":timeline-service"))
                implementation(project(":health-service"))
                implementation(project(":place-service"))
                // IN-4: the label rows Mia reads.
                implementation(project(":inference-service"))
                // IN-4 excludes IN-3a's index marker from citations — the constant lives with the
                // indexer that writes it.
                implementation(project(":inference-service-proxy"))
                implementation(project(":home-device-service"))
                // RE-5: reading routine rows to resolve a name, and `RunRoutineNow` to fire one.
                implementation(project(":routine-service"))
            }
        }
        getByName("commonTest") {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.test)
                implementation(project(":core-test-utils"))
                implementation(project(":file-service-proxy"))
                implementation(project(":file-service-client"))
                implementation(project(":file-service-test-utils"))
                implementation(project(":mia-service-client"))
                // RE-5: seeding `Routine` rows so the name resolution can be asserted.
                implementation(project(":routine-service-common"))
                implementation(project(":routine-service-client"))
                implementation(project(":notification-service-proxy"))
                implementation(project(":home-device-service-proxy"))
                implementation(project(":timeline-service-common"))
                implementation(project(":timeline-service-client"))
                implementation(project(":health-service-common"))
                implementation(project(":health-service-client"))
                implementation(project(":place-service-common"))
                implementation(project(":place-service-client"))
                implementation(project(":inference-service-common"))
                implementation(project(":inference-service-client"))
                implementation(project(":inference-service-proxy"))
                implementation(project(":home-device-service-common"))
                implementation(project(":home-device-service-client"))
            }
        }
    }
}
