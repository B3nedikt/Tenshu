plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.kotlinx.serialization)
}

// The real Timeline data layer — the `TimelineProjectionRepository` that maps the three
// backend services' stored entities (:timeline-service Moment/Trip, :place-service Place,
// :health-service Workout) into the app's presentation model, behind the same
// `TimelineRepository` seam the mock uses (docs/proposals/timeline-services.md §6). Compose-
// free; mirrors :timeline-shared-logic's targets so the app can bind either impl.
@OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)
// ⚠⚠ Forward the replay corpus path into the TEST JVM. `-D` on the Gradle command line sets the
// property on Gradle's own JVM, not the forked test one — `EmbeddingHarness` lost a run to exactly
// this, and a harness that silently skips is the one failure mode a harness must not have.
tasks.withType<Test>().configureEach {
    providers.systemProperty("tenshu.timeline.fixes").orNull?.let {
        systemProperty("tenshu.timeline.fixes", it)
        inputs.property("tenshu.timeline.fixes", it)
        // ⚠ A corpus is not a task input (it lives outside the tree and changes as the phone
        // records), so a second run would otherwise be UP-TO-DATE and replay stale output.
        outputs.upToDateWhen { false }
    }
}

kotlin {
    jvmToolchain(17)
    applyDefaultHierarchyTemplate()

    android {
        namespace = "app.tenshu.timeline.data"
        compileSdk = 37
        minSdk = 24
    }

    jvm("desktop")

    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        getByName("commonMain") {
            dependencies {
                // The presentation model + the TimelineRepository interface (the seam).
                api(project(":timeline-shared-logic"))
                // The pure feed projection (`TimelineProjection`, its `*Input`s, `PhotoSelection`),
                // shared with the web SDK so both run ONE implementation (timeline-app.md §Web scope 4a).
                api(project(":timeline-projection"))
                // The durable visit outbox rides the shared journal-then-replay seam
                // (VisitOutboxStore is a typealias onto its OutboxStore → api).
                api(project(":app-outbox"))
                // The Control-reachability engine (fit-app.md §9.14, shared).
                implementation(project(":app-reachability"))

                // The three backend services, read over a TenshuClient that reaches **Control's**
                // proxy (timeline-app.md §10). The app hosts no proxy of its own.
                implementation(project(":core"))
                implementation(project(":core-common"))
                implementation(project(":core-client"))
                implementation(project(":timeline-service"))
                implementation(project(":timeline-service-common"))
                implementation(project(":timeline-service-client"))
                implementation(project(":place-service"))
                implementation(project(":place-service-client"))
                // T-P1 (`timeline-photos.md`): the moment hero reads the user's photos out of
                // Drive's file tree. Photos are Drive files, not a new kind of data — so this is
                // the *only* thing Timeline needed to see them, and Control already hosts the
                // service. ⚠ Adding these moves Timeline's announce vector: it now declares
                // file-service to Control at session start.
                implementation(project(":file-service"))
                implementation(project(":file-service-common"))
                implementation(project(":file-service-client"))
                implementation(project(":health-service"))
                implementation(project(":health-service-client"))

                // Rail map-matching (T1/T2′, timeline-movement-capture.md §13.3) — the MVT
                // decoder + matcher. Pure and dependency-free, so this costs the app nothing
                // beyond the geometry it actually uses.
                implementation(project(":vector-tile"))
                // Device-local diagnostic breadcrumbs (single LogItem stream) — capture/sweep emit
                // here; viewable in Settings → Diagnostics. Pure, no deps.
                implementation(project(":diagnostics"))
                // The rail-lines op the refinement sweep calls (M9-4/M9-5). Control serves it; the
                // app never touches tiles or the network itself.
                implementation(project(":map-region-service"))
                implementation(project(":map-region-service-client"))
                // C4 (sync-device-catch-up.md §11.7): the file import wraps its write phase in a
                // bulk task so a Takeout export batches into DeltaBatchPayload blocks instead of
                // one SyncBlock per row. Same edge Fit's shared-data module already carries for
                // the same reason — `GenericTaskSpec` lives in :sync-service, the helper in
                // :sync-service-client.
                implementation(project(":sync-service"))
                implementation(project(":sync-service-client"))

                implementation(libs.kotlinx.coroutines)
                implementation(libs.kotlinx.datetime)
                // The owner's units, read as a REALM preference row (fit-app.md §18.2/§18.13).
                implementation(project(":preference-service"))
                implementation(project(":preference-service-client"))
                // The Google Timeline export parser (I2) reads JSON in commonMain.
                implementation(libs.kotlinx.serialization.json)
                // B1 (`takeout-import-and-recall.md`): the ZIP reader + entry router. `api`
                // because `importArchive` takes a `ZipArchive` — the platform picking the file
                // is the one that opens it, with its own inflate.
                api(project(":takeout-import"))
                // The Photon reverse-geocoding tier (place-geocoding.md G3). `api` because
                // PhotonReverseGeocoder takes the platform-built HttpClient as a constructor
                // argument (engine injected at the binding site, epc-rendezvous-client pattern).
                api(libs.ktorClientCore)
            }
        }
        getByName("androidMain") {
            dependencies {
                // S1 (timeline-movement-capture.md §17.2): LocationManagerCompat +
                // LocationRequestCompat, which is how a batch window (setMaxUpdateDelayMillis) is
                // expressed without branching on API level at every call site. There was no
                // androidMain dependency block here before this slice.
                implementation(libs.core.ktx)
            }
        }
        getByName("commonTest") {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.test)
                // The projection is pure (over lightweight inputs) — its test needs no proxy.
                // The pipeline test stands up all three services in one in-process TestProxy +
                // a real TenshuClient — the only place the proxy side is needed now: the app is a
                // client of Control's proxy and ships no proxy-side code (timeline-app.md §10).
                implementation(project(":timeline-service-proxy"))
                implementation(project(":place-service-proxy"))
                implementation(project(":health-service-proxy"))
                // B5: the photo import writes through the file service, so its test stands one up
                // in-process alongside the others.
                implementation(project(":file-service-proxy"))
                implementation(project(":core-test-utils"))
                // The geocoding-consent test stands the real map-region proxy up beside the others,
                // with a recording egress where Photon would be — so "no request left" is observed
                // at the last seam before the network, not inferred from the app side.
                implementation(project(":map-region-service-test-utils"))
                // PhotonReverseGeocoderTest rides Ktor's MockEngine — the komoot default
                // endpoint is never queried from tests.
                implementation(libs.ktorClientMock)
            }
        }
    }
}
