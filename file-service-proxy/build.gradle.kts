plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
}

@OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)
kotlin {
    jvmToolchain(17)
    applyDefaultHierarchyTemplate()

    android {
        namespace = "org.tenshu.file.proxy"
        compileSdk = 37
        minSdk = 24

        withHostTest {
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

    // iOS targets stay declared so commonMain remains KMP-honest (catches
    // accidental JVM-only deps), but we don't produce a `.framework` here:
    // there is no iOS app consuming this module yet, and every module
    // declaring its own framework all named `shared` was pure build-time
    // cost. When an iOS app appears, expose a proper `XCFramework` on the
    // umbrella module(s) that actually ship.
    iosX64()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        getByName("commonMain") {
            dependencies {
                api(project(":file-service-common"))
                // PhotosNear reuses PlaceProximity's haversine — the ONE distance rule, rather
                // than a fourth copy. ⚠ Schema module only; file-service still knows nothing
                // about Place ROWS (PhotosNear takes a coordinate, never a place name).
                implementation(project(":place-service"))
                api(project(":core-common"))
                api(project(":sync-service-common"))
                api(project(":notification-service-proxy"))
                // storage-capacity-gating.md Phase 3: Tier-2 enforcement — the
                // StorageCapacityHolder + ensureCapacity guard (+ typed errors,
                // transitively from -common) consulted by the write/share ops.
                api(project(":storage-service-proxy"))
                // FileSyncSettingsRelationMaintainer reads the synced `file.sync.*`
                // `DeviceSetting` rows (preference domain) to derive the
                // `fileSyncSettings` projection (drive-offline-online.md §"Follow-up").
                // `:file-service-common`'s preference dep is `implementation`
                // (non-transitive), so depend on it directly here.
                implementation(project(":preference-service"))
            }
        }
        getByName("commonTest") {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.test)
                implementation(project(":file-service-test-utils"))
                // Test-only: the File-rooted history query's leaf is
                // `relations(FileModification.Device)`, so the device-name end-to-end
                // test registers identity + saves a Device (the FileDetailsScreen
                // consumer shape). Identity stays off the main classpath.
                implementation(project(":identity-service-common"))
                // Test-only: `FileSyncSettingsRelationMaintainerTest` registers the
                // preference module so it can save the `file.sync.*` `DeviceSetting`
                // rows the maintainer folds into the `fileSyncSettings` projection.
                implementation(project(":preference-service-common"))
            }
        }
        getByName("androidMain") {
            dependencies {
                implementation(project(":core-aidl"))
                implementation(libs.core.ktx)
            }
        }
        getByName("jvmMain") {
            dependencies {
                // PDF first-page thumbnail at upload (drive-content-preview.md
                // Phase 2b): the JVM has no native PDF rasteriser, so GetPreview
                // renders a PDF's first page via PDFBox — same dep + version the
                // drive-shared-ui desktop PDF viewer uses.
                implementation(libs.pdfbox)
                // HEIC (and other ImageIO-only formats) at thumbnail-generation
                // time: `ImageIO.read` has no HEIF plugin, so a HEIC uploaded from
                // a Mac would otherwise get no thumbnail at all. Compose-free
                // module by design — see drive-content-preview.md §3.1.
                implementation(project(":macos-helpers"))
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
