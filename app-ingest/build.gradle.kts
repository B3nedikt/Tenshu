plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
}

// A small, app-neutral module for the **ingest-source registry** an app reads to show, and
// switch off, what this device is backing up (`photos-app.md` PH-4 / PH-D3).
//
// The `:app-reachability` recipe: Drive owned both pieces, Photos needed the identical ones, and
// forking them was the wrong move. It carries `DeviceIdentity` for the same reason — every
// per-device row is keyed on it, and two copies of a startup-race fix is how one of them gets
// fixed and the other does not.
//
// ⚠ It holds the **registry**, not the drain. The backup engine (`SyncedFolderController`) is
// still Drive's; moving it is PH-4b, and needs a narrower seam than `FilesRepository`.
@OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)
kotlin {
    jvmToolchain(17)
    applyDefaultHierarchyTemplate()

    android {
        namespace = "app.tenshu.ingest"
        compileSdk = 37
        minSdk = 24
    }

    jvm("desktop")
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        getByName("commonMain") {
            dependencies {
                api(project(":core-common"))
                api(project(":ingest-service"))
                api(project(":ingest-service-common"))
                api(project(":ingest-service-client"))
                // `ContentIndex` walks the realm's files and groups them by `File.ContentHash`.
                api(project(":file-service"))
                api(project(":file-service-common"))
                api(project(":file-service-client"))
                // `IngestPreferences` declares the ingest policy both apps read.
                api(project(":preference-service"))
                api(project(":preference-service-client"))
                // PH-4b: the backup engine moved here, and brings its own two dependencies —
                // the Android FGS notification content it renders, and the device name the
                // `/Backup/<device>/…` destination is built from.
                api(project(":app-liveness"))
                api(project(":identity-service"))
                api(project(":identity-service-common"))
                api(project(":identity-service-client"))
                // `DeviceIdentity` resolves this device's id from the sync status row.
                api(project(":sync-service"))
                api(project(":sync-service-client"))
                implementation(libs.kotlinx.coroutines)
            }
        }
        getByName("commonTest") {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.test)
                // `RealmContentReadsTest` reads a real realm through an in-process `TestProxy`
                // (`photos-app.md` §40). `:sync-service-common` for the move/rekey marker types
                // the file ops reach — the `RealRepositoryReadTest` precedent.
                implementation(project(":core-test-utils"))
                implementation(project(":file-service-proxy"))
                implementation(project(":sync-service-common"))
            }
        }
    }
}
