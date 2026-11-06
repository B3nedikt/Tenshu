plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.kotlinx.serialization)
}

// Photos' Compose-free half (`docs/proposals/photos-app.md` PH-1): the feed's read, its paging
// state machine and the view models.
//
// ⚠ Compose-free by construction (House Rule #5, the `:calendar-shared-logic` precedent): this is
// the half an iOS shell consumes, so no Compose dependency may ever be added here. The Compose UI
// lives in `:photos-shared-ui`.
@OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)
kotlin {
    jvmToolchain(17)
    applyDefaultHierarchyTemplate()

    android {
        namespace = "app.tenshu.photos.logic"
        compileSdk = 37
        minSdk = 24
    }

    jvm()

    // ⚠ No `js` target and no `iosX64()`, deliberately — the reasoning is
    // `:calendar-shared-logic`'s, verbatim: nothing consumes a JS twin of an app's logic layer
    // (HR #5 shares the *protocol* layer, not this one), and the iOS umbrella cannot link an
    // Intel-simulator slice at all.
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        getByName("commonMain") {
            dependencies {
                api(project(":file-service"))
                api(project(":file-service-common"))
                api(project(":file-service-client"))
                api(project(":core-common"))
                // PH-2: albums, ordering and flags.
                api(project(":photo-service"))
                api(project(":photo-service-common"))
                api(project(":photo-service-client"))
                // PH-3: the Places section joins the feed against `Place` rows. ⚠ Read-only, and
                // read by the APP — `:file-service` must not learn what a place is (`PhotosNear`).
                api(project(":place-service"))
                api(project(":place-service-common"))
                api(project(":place-service-client"))
                // The one reachability engine the shell's empty state reads, so "Control is not
                // running" never renders as "you have no photos".
                api(project(":app-reachability"))
                // S2: the label and semantic search sources. ⚠ Read-only, and both are absent on
                // every device until IN-3b ships a backend — the screen is built to read as
                // complete without them.
                api(project(":inference-service"))
                api(project(":inference-service-common"))
                api(project(":inference-service-client"))
                // PH-4: the shared ingest-source registry behind the Settings tab.
                api(project(":app-ingest"))
                // K-4: the feed's keyset pager. `api`, not `implementation`, because the view
                // model's `Flow<PagingData<…>>` is paging-common's type in the public surface —
                // the same reason `:drive-shared-logic` exposes it.
                api(project(":core-paging"))
                api(libs.lifecycle.viewmodel)
                implementation(libs.kotlinx.coroutines)
                // PIO-0 (`photos-ios.md` §1.2): `PhotoDates` moved here from the Compose module so
                // the SwiftUI shell sections the feed by the same local day and year Android does.
                // `:calendar-shared-logic` carries the same dependency for the same reason.
                implementation(libs.kotlinx.datetime)
                // §36 step 2: the Collections snapshot — a small file in Photos' own sandbox, because a
                // cache read through Control is useless exactly when Control is slow. `:app-outbox`'s
                // per-platform file store (atomic writes on Android, desktop and iOS) is the seam;
                // `api` so the shells can bind `FileOutboxStore` / `NsFileOutboxStore`.
                api(project(":app-outbox"))
                implementation(libs.kotlinx.serialization.json)
            }
        }

        getByName("commonTest") {
            dependencies {
                // ⚠ Test-only, and only for `RealRepositoryReadTest`'s trash cases. `moveFile`
                // reaches the DataId-change → reference-migration primitive
                // (`file-content-addressing.md` §3.3.4), which needs `tenshu/sync/rekeyMigrationMarker`
                // — a type `:sync-service-common` registers. On a device Control has every service
                // loaded, so this gap exists in the fixture and nowhere else; without it the move
                // fails with "No composite factory registered", which reads like a product bug.
                implementation(project(":sync-service-common"))
                // K-4: `asSnapshot()` collects a `Flow<PagingData<…>>` into a plain list, which
                // is what lets the feed's day-header rule be asserted without a renderer.
                implementation(libs.paging.testing)
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.test)
                // ⚠⚠ The proxy side, so `TenshuPhotosRepository` — the REAL one — can be read
                // through in one in-process `TestProxy`. Until this, every photos test injected
                // `MockPhotosRepository`, which replaces the whole class: none of the repository's
                // casts, field reads or folds had ever been executed by a test.
                // The `DataSourceReadTest` precedent (`fit-shared-data`).
                implementation(project(":photo-service-proxy"))
                implementation(project(":file-service-proxy"))
                implementation(project(":core-test-utils"))
                implementation(project(":viewmodel-test-utils"))
            }
        }
    }
}
