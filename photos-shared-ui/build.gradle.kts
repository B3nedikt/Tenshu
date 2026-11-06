plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.jetbrains.compose)
    alias(libs.plugins.kotlin.compose)
}

// Photos' Compose UI — the feed grid, the viewer, App(), and the Koin assembly both shells start
// from (`docs/proposals/photos-app.md` PH-1).
//
// ⚠ android + desktop only, deliberately: no CMP UI ships on iOS (the B-1 rule), so an iOS shell
// is SwiftUI over the SAME view models in `:photos-shared-logic`, which stays Compose-free for
// exactly this reason.
@OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)
kotlin {
    jvmToolchain(17)
    applyDefaultHierarchyTemplate()

    android {
        namespace = "app.tenshu.photos.shared"
        compileSdk = 37
        minSdk = 24
        androidResources {
            enable = true
        }
    }

    jvm("desktop")

    sourceSets {
        getByName("commonMain") {
            dependencies {
                implementation(project(":ui-core"))
                // Realm storage, for `RealmStorageBanner` (drive-synced-folders.md §8b.5c).
                implementation(project(":storage-service"))
                implementation(project(":storage-service-common"))
                implementation(project(":storage-service-client"))
                // `api` so both app shells get the view-model and repository types transitively.
                api(project(":photos-shared-logic"))
                implementation(project(":core-common"))
                api(project(":file-service"))
                api(project(":file-service-common"))
                api(project(":file-service-client"))
                api(project(":photo-service"))
                api(project(":photo-service-common"))
                api(project(":photo-service-client"))
                api(project(":app-ingest"))
                // K-4: `collectAsLazyPagingItems` for the feed grid. The `Flow<PagingData<…>>`
                // itself comes from `:photos-shared-logic`, which `api`-exposes `:core-paging`.
                implementation(libs.paging.compose)

                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
                implementation(compose.components.resources)
                implementation(compose.materialIconsExtended)

                implementation(libs.koinCore)
                implementation(libs.koinMultiplatform)
                implementation(libs.koinComposeViewmodel)
                implementation(libs.lifecycle.viewmodel.compose)
                implementation(libs.lifecycle.runtime.compose)
                implementation(libs.kotlinx.coroutines)
                // ⚠ The runtime only — no compiler plugin, matching `:contacts-shared-ui`.
                // `@Serializable` on the navigation routes is the sibling convention;
                // `KmpNavHost` routes by TYPE and the back stack is in memory, so nothing
                // here is actually serialised today.
                implementation(libs.kotlinx.serialization.core)
                // The feed's day headers: local midnight + the header text. ⚠ Here and not in
                // `:photos-shared-logic`, so the grouping there stays pure and timezone-free.
                implementation(libs.kotlinx.datetime)
            }
        }

        // M5: the grid's tile player on macOS — AVFoundation over the `:macos-helpers` FFM bridge,
        // the same dependency `:drive-shared-ui`'s viewer carries. ⚠ `runCatching` around every
        // touch of it in `MotionTilePlayer.desktop.kt`: this module is on toolchain 17 and the
        // bridge needs 22+, so a `desktopTest` that reaches it dies with `NoClassDefFoundError`.
        getByName("desktopMain") {
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(project(":macos-helpers"))
            }
        }

        // ⚠⚠ `core-ktx` for `WindowInsetsControllerCompat` alone — immersive mode's Android half
        // (`photos-viewer-about-screen.md` §3.1b). Compose Multiplatform has no common API for the
        // OS bars, so this is the one dependency the seam costs.
        getByName("androidMain") {
            dependencies {
                implementation(libs.core.ktx)
                // The `BackHandler` actual — Photos navigates on Compose state, so system back
                // needs intercepting explicitly (see `BackHandler.kt`).
                implementation(libs.activity.compose)
            }
        }

        // ⚠ The grid is a *measurement* fact — it fails by drawing the wrong thing, never by
        // throwing — so the only honest test renders it at a real window size and reads it back.
        getByName("desktopTest") {
            dependencies {
                implementation(project(":core-test-utils"))
                implementation(project(":viewmodel-test-utils"))
                implementation(compose.desktop.uiTestJUnit4)
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
            }
        }
    }
}

// ADR 0016 mirror: pin the generated `Res` accessor package so call sites can
// `import app.tenshu.photos.shared.resources.Res` predictably.
compose.resources {
    packageOfResClass = "app.tenshu.photos.shared.resources"
    generateResClass = always
}
