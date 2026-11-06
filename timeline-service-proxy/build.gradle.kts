plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
}

@OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)
kotlin {
    jvmToolchain(17)
    applyDefaultHierarchyTemplate()

    android {
        namespace = "org.tenshu.timeline.proxy"
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
                api(project(":timeline-service-common"))
                api(project(":core-proxy"))
                // SemanticMerger + resolveListField for TripMerger (I5, §5.2). `api`, not
                // `implementation`: SemanticMergerRegistry is in `initTimelineService`'s signature
                // (timeline-recognition-and-place-naming.md §9.2).
                api(project(":sync-service-common"))
                // P4e-2: a Visit's cluster key needs its place's coordinates (§10.3).
                // place-roles.md PR-2: PlaceRoleInferencePass writes PlaceRole rows from here — this
                // is the only direction of the timeline↔place edge that exists, and the reason the
                // inference lives in the timeline proxy rather than the place one.
                implementation(project(":place-service"))
                // PlaceRoleInferencePass buckets visits by local hour-of-day, so it needs a real
                // timezone. `:timeline-service-common` stays dep-free (the detector takes wall-clock
                // millis); the conversion happens here, at the boundary. `control-shared-proxy` sets
                // the precedent for a proxy module carrying this.
                implementation(libs.kotlinx.datetime)
            }
        }
        getByName("commonTest") {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.test)
                implementation(project(":core-test-utils"))
                implementation(project(":timeline-service-client"))
                implementation(project(":timeline-service-test-utils"))
                // MomentReconciliationPassTest stands up timeline + place together (production shape).
                implementation(project(":place-service-common"))
                implementation(project(":place-service-proxy"))
            }
        }
    }
}
