plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    // `CategoryId` is `@Serializable` (the onboarding selection is saved as JSON).
    alias(libs.plugins.kotlinx.serialization)
}

// The Fit **projection** — the pure mapping from `:health-service` rows to the app's presentation
// model (`FitProjection`, `DataSourceProjection`) — plus that model (`app.tenshu.fit.model`'s
// `FitModel.kt` / `DataSourceModel.kt`) and Fit's preference declarations (`FitPreferences.kt`:
// the goal / auto-detect row ids, so web reads and writes the same rows by the same names).
//
// ⚠⚠ ONE implementation for native and web (`web-app-suite.md` §4.1's trap, the Timeline precedent
// `:timeline-projection`). Pure by construction: plain `*Input` data classes in, model types out, no
// client, no platform API. That is the whole reason this module exists and compiles to JS —
// `:fit-shared-data` (native) and `:tenshu-sdk-js` (web) both call it, and `FitProjectionTest` & co.
// in commonTest pin both readers on JVM and JS at once. Anything that needs a client, paging, a
// ViewModel or a platform API stays in `:fit-shared-logic` / `:fit-shared-data`; never add such a
// dependency here (House Rule 5: the app logic layer does not compile to JS).
@OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)
kotlin {
    jvmToolchain(17)
    applyDefaultHierarchyTemplate()

    android {
        namespace = "app.tenshu.fit.projection"
        compileSdk = 37
        minSdk = 24
    }

    jvm()

    js {
        browser()
    }

    // Fit's own iOS set (`:fit-shared-logic`), not the services' wider one.
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        getByName("commonMain") {
            dependencies {
                // `DailyAggregate.date`, `Workout.start`, … are kotlinx-datetime types.
                api(libs.kotlinx.datetime)
                // `FitProfile.sex` is `org.tenshu.preference.BiologicalSex`.
                api(project(":preference-service"))
                // `FitProjection.workout(…, streams: WorkoutStreams)` / `projectRoute(List<RouteSample>)`.
                api(project(":health-service-common"))
                implementation(libs.kotlinx.serialization.core)
                // `FitPreferences.kt`'s `FitGoalPreferences` / `AutoDetectPreferences` seams are `Flow`s.
                api(libs.kotlinx.coroutines)
            }
        }
        getByName("commonTest") {
            dependencies {
                implementation(libs.kotlin.test)
            }
        }
    }
}
