plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    // For @Serializable on the ImportedHealth DTOs — the recorder's durable workout outbox
    // journals them as local JSON (never wire; House Rule #4 unaffected).
    alias(libs.plugins.kotlinx.serialization)
}

// The real Fit data layer — the `FitProjectionRepository` that maps the `:health-service`
// stored `Workout` entities into the app's presentation model, behind the same `FitRepository`
// seam the mock uses (docs/proposals/fit-app.md §3 D1 + timeline-services.md §6). Compose-free;
// mirrors :fit-shared-logic's targets so the app can bind either impl. Mirrors
// :timeline-shared-data's projector recipe.
@OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)
kotlin {
    jvmToolchain(17)
    applyDefaultHierarchyTemplate()

    android {
        namespace = "app.tenshu.fit.data"
        compileSdk = 37
        minSdk = 26   // Health Connect (connect-client 1.1.0) requires API 26+
    }

    jvm("desktop")

    // ⚠ No `js` target. The web GPX/TCX parser (fit-app.md §9.10) lives in :fit-shared-file, which
    // is the only part of Fit that needs to reach a browser; a target here would drag this module's
    // whole projector — and, through its `api` dep, :fit-shared-logic's ViewModels — to JS with
    // it. See :fit-shared-file's KDoc.

    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        val commonMain = getByName("commonMain") {
            dependencies {
                // The presentation model + the FitRepository interface (the seam).
                api(project(":fit-shared-logic"))

                // The health backend, read over a TenshuClient. The `-proxy` module + :core-common
                // are needed by `InProcessFitProxy`, which hosts an in-process proxy for the app
                // (mirrors TestProxy with production types; over the reusable :core-common helper).
                implementation(project(":core"))
                implementation(project(":core-common"))
                implementation(project(":core-client"))
                implementation(project(":health-service"))
                implementation(project(":health-service-common"))
                implementation(project(":health-service-client"))
                // fit-automatic-activity-detection.md §14.2: Fit reads Timeline's
                // `MovementTrace` rows to promote a captured walk/run/ride into a `Workout`.
                // Read-only and client-only — Control hosts :timeline-service-proxy, and there is
                // no per-app service allowlist, so this is the mirror of Timeline already reading
                // health workouts (`TimelineProjectionRepository`).
                implementation(project(":timeline-service"))
                implementation(project(":timeline-service-client"))
                // Goals + the owner's person, persisted as REALM preference rows
                // (fit-app.md §18.5). Control hosts :preference-service-proxy, so Fit only
                // ever needs the client half.
                api(project(":preference-service"))
                api(project(":preference-service-client"))
                // core-background-processing.md §10.6: the task-composite client
                // surface — the import registers ONE BackgroundTask so its writes ride
                // the sync lane as DeltaBatch blocks instead of one block per record.
                implementation(project(":sync-service"))
                implementation(project(":sync-service-client"))

                // `fit-data-sources-restructure.md`: the device roster, so a source row
                // shows the name the user gave that device in Control rather than the platform
                // name stamped at register time.
                //
                // ⚠ `:identity-service` itself is already here transitively — `:sync-service`
                // and `:sync-service-client` both `api` it.
                implementation(project(":identity-service-client"))
                // NOT :health-service-proxy — Control hosts the service (fit-app.md §10). Fit is a
                // client; it runs no proxy of its own. The proxy module is a *test* dependency, for
                // the harnesses that stand the service up in-process.

                implementation(libs.kotlinx.coroutines)
                implementation(libs.kotlinx.datetime)
                // The durable workout outbox (journal-before-write; replay on launch) — the
                // shared seam Timeline's visit outbox generalised into.
                api(project(":app-outbox"))
                // The Control-reachability engine (§9.14) — probe + recovery, extracted shared.
                implementation(project(":app-reachability"))
                implementation(libs.kotlinx.serialization.json)
            }
        }
        // Android and desktop are both JVM, and the file-import path is pure JDK — `javax.xml` for
        // GPX/TCX (JvmXmlReader) and `java.util.zip` for a Takeout archive (JvmArchiveReader), so
        // both platforms read activity files. Shared here rather than copied; same recipe as
        // :core-common and :fit-shared-ui.
        val jvmMain = create("jvmMain") {
            dependsOn(commonMain)
        }
        getByName("androidMain") {
            dependsOn(jvmMain)
            dependencies {
                // The Android live-import actual: a HealthImporter over Health Connect
                // (docs/proposals/fit-app.md §9.5 + timeline-services.md §4.2).
                implementation(libs.health.connect.client)
            }
        }
        getByName("desktopMain") {
            dependsOn(jvmMain)
            dependencies {
                // Binary FIT decoding: the Garmin FIT Java SDK. GPX/TCX use the JDK's XML.
                implementation(libs.garmin.fit)
            }
        }
        getByName("commonTest") {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.test)
                // The projection is pure (over lightweight inputs) — its test needs no proxy.
                // The pipeline test stands up :health-service in one in-process TestProxy + a
                // real TenshuClient, which is the only place the proxy side is needed.
                implementation(project(":health-service-proxy"))
                // The bridge test stands :timeline-service up beside :health-service in one
                // proxy, so the cross-service read is proven over the real op + wire registration
                // (a missing client-module registration is a runtime error/internal, not a
                // compile failure — `RoutineServiceClientModule`'s warning).
                implementation(project(":timeline-service-common"))
                implementation(project(":timeline-service-proxy"))
                implementation(project(":core-test-utils"))
                // The profile/goal round-trip drives real REALM preference rows through an
                // in-process proxy, so the preference service is stood up beside :health-service.
                implementation(project(":preference-service-proxy"))
                implementation(project(":preference-service-test-utils"))
            }
        }
    }
}
