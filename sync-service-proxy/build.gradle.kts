plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    // Generates serializers for @Serializable wire DTOs (e.g. RestShareRelayClient's
    // CreateShareRequest). Without it the annotation compiles but no serializer is
    // emitted, so Ktor's reflective lookup throws "Serializer for class … not found".
    alias(libs.plugins.kotlinx.serialization)
}

@OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)
kotlin {
    jvmToolchain(17)
    applyDefaultHierarchyTemplate()

    android {
        namespace = "org.tenshu.sync.proxy"
        compileSdk = 37
        minSdk = 24
        // The background-sync notification's channel name and text (SyncPushWorker,
        // TenshuOperationWorker), translated.
        androidResources {
            enable = true
        }
    }

    jvm()

    js {
        browser()
        // Matches the :core-common convention so ES-module imports between
        // KMP modules compose. See docs/web/architecture.md §12.6.1.
        useEsModules()
    }

    // commonTest fixtures still pull from `nonJsMain` (TestProxy bridges
    // runBlocking). Disable JS test compilation until Phase T lands.
    tasks.matching { it.name == "compileTestKotlinJs" }.configureEach { enabled = false }

    // iOS targets (Control-on-iOS C4a): this module holds the in-process sync core (`SyncManagerImpl`,
    // device identity, HLC integration) that Control must run on the iPhone for both data-plane routes
    // (§C4). arm64 + simulatorArm64 only — what the `:tenshu-sdk-ios-<app>` frameworks ship; no
    // `iosX64` (paging-common has no x64 klib) and no per-module `.framework` (the app frameworks are it).
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        getByName("commonMain") {
            dependencies {
                api(project(":sync-service-common"))
                api(project(":sync-service"))
                api(project(":identity-service"))
                api(project(":core-proxy"))
                // `SyncRuntimeHolder`'s election lock — the lock primitives only, no plugin.
                implementation(libs.kotlinx.atomicfu)
                // EPC rendezvous client/shared types (RendezvousRelayClient et al.),
                // extracted in Phase 6b so the iOS umbrella can export them without
                // pulling in this proxy module. `api` because EpcRendezvousAcceptor
                // exposes RendezvousRelayClient in its constructor.
                api(project(":epc-rendezvous-client"))
                // GR-3: the membership-grant ops resolve the grantee's identity
                // (TenshuIdentity) + seal/unseal the grant payload. contact-service
                // depends only on :core, so this is acyclic.
                implementation(project(":contact-service"))
                implementation(project(":contact-service-common"))
                // ST-3b reclamation: the candidate record + live-set types live in :file-service
                // (they are file concepts). Acyclic — :file-service depends only on :core and its
                // siblings, never on sync — and it was already a commonTest dependency here.
                implementation(project(":file-service"))
                implementation(libs.kotlinx.coroutines)
                api(libs.ktorClientCore)
                api(libs.ktorClientContentNegotiation)
                api(libs.ktorSerializationJson)
            }
        }
        getByName("commonTest") {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
                implementation(project(":core-test-utils"))
                implementation(project(":sync-service-client"))
                implementation(project(":file-service"))
                implementation(project(":file-service-proxy"))
                // ProvisionalRowMoveTest (CR-2 B5 §4) executes the real `MoveFile`, whose operation
                // schema is registered by the file client module. ⚠ Acyclic: it never depends on sync.
                implementation(project(":file-service-client"))
                // SchemaDrivenContentWalkTest needs a SECOND type carrying a Syncable(OnDemand)
                // property — `Workout.Streams` — because the defect it pins only shows with more
                // than one: one non-enumerable type used to discard every entry already collected.
                // ⚠ Acyclic: :health-service depends on :core alone.
                implementation(project(":health-service-common"))
                // CR-2 B4c: `FreedContentRepairTest` revives `Workout.Streams` through the real
                // `WorkoutStreamsPropertyWriter` (`workoutStreamsPath`). ⚠ Acyclic: :health-service-proxy
                // depends on :sync-service-common, never on this module.
                implementation(project(":health-service-proxy"))
                implementation(project(":identity-service-common"))
                // MockEngine drives RelayUsageClient's request/response without a network — the
                // point of the P1b test is which HEADERS go out, which a live relay would hide.
                implementation(libs.ktorClientMock)
                implementation(project(":identity-service-proxy"))
                // TripMergerTest (I5) — the Trip merge path over the same SyncProcessor harness.
                implementation(project(":timeline-service"))
                implementation(project(":timeline-service-proxy"))
                // PlaceMergeTest (timeline-recognition-and-place-naming.md §9.2) — same harness.
                implementation(project(":place-service"))
                implementation(project(":place-service-proxy"))
            }
        }
        getByName("androidMain") {
            dependencies {
                implementation(libs.androidx.work.runtime)
                implementation(libs.ktorClientOkhttp)
            }
        }
        getByName("jvmMain") {
            dependencies {
                implementation(libs.ktorClientOkhttp)
            }
        }
    }
}

tasks.withType<Test> {
    testLogging {
        showStandardStreams = true
        events("passed", "failed", "skipped")
    }
}
