plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
}

// The Timeline **feed projection** — reconcile's output → consecutive-visit merge (§2b) → in-ride
// station-stop collapse (`timeline-movement-capture.md` §15, §32.8) → movement endpoints (§11) →
// day grouping — plus the presentation model it produces (`app.tenshu.timeline.model`).
//
// ⚠⚠ ONE implementation for native and web (`timeline-app.md` §Web scope, 4a, decided 2026-10-24).
// Pure by construction: plain `*Input` data classes in, `Day`/`Moment` out, no client, no platform
// API. That is the whole reason this module exists and compiles to JS — `:timeline-shared-data`
// (native) and `:tenshu-sdk-js` (web) both call it, and `TimelineProjectionTest` & co. in
// commonTest pin both readers on JVM and JS at once. Anything that needs a client, a tile or a
// platform API stays in `:timeline-shared-data`; never add such a dependency here.
@OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)
kotlin {
    jvmToolchain(17)
    applyDefaultHierarchyTemplate()

    android {
        namespace = "app.tenshu.timeline.projection"
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
                // `Day.date` is a `LocalDate`, so this is part of the model's API.
                api(libs.kotlinx.datetime)
                // `LabelSource.isUser` — the "the user named this place" veto in the collapse.
                implementation(project(":place-service"))
            }
        }
        getByName("commonTest") {
            dependencies {
                implementation(libs.kotlin.test)
            }
        }
    }
}
