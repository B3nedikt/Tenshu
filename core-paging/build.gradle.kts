plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
}

// Home for the `TList` -> androidx.paging bridge (`TenshuPagingSource` +
// `pagingSource`) plus the non-Compose `TenshuPagingPresenter`. Lives outside
// :core-common so that foundational module doesn't carry a presentation-adjacent
// paging dependency that every consumer would inherit. paging-common is the
// multiplatform core of androidx.paging despite the name, so the single
// `commonMain` copy serves Android, desktop AND iOS.
//
// iOS targets added in Phase 6b.5 (docs/proposals/ios-proxy-architecture.md
// §9.5): the SwiftUI Drive client cannot use `collectAsLazyPagingItems()`
// (Compose-only), so it drives the same `Flow<PagingData<…>>` through
// `TenshuPagingPresenter` (wraps `PagingDataPresenter`, the non-Compose base
// `LazyPagingItems` itself is built on — load states / retry / placeholders /
// load-next survive, unlike a hand-rolled AsyncStream).
@OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)
kotlin {
    jvmToolchain(17)
    applyDefaultHierarchyTemplate()

    android {
        namespace = "org.tenshu.core.paging"
        compileSdk = 37
        minSdk = 24
    }

    jvm("desktop")

    // Plain compilation targets only — no per-module framework. The app
    // frameworks (`:tenshu-sdk-ios-<app>`) are what ship, and the ones whose
    // Swift needs it export this module.
    //
    // NB: no `iosX64()` here. androidx.paging:paging-common 3.4.2 publishes only
    // `iosArm64` (device) + `iosSimulatorArm64` (Apple-silicon simulator) — there
    // is no `iosX64` (Intel-mac simulator) klib, so a shared `nativeMain` that
    // `api`-exposes paging-common cannot resolve for iosX64. The CI gate runs
    // `iosSimulatorArm64Test`; Apple-silicon is the only simulator arch that
    // matters. The app frameworks declare no iosX64 either, for this reason.
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        getByName("commonMain") {
            dependencies {
                implementation(project(":core"))
                implementation(libs.kotlinx.coroutines)
                // paging-common is exposed in the public API surface
                // (`Flow<PagingData<R>>` return type + the presenter), so `api`.
                api(libs.paging.common)
            }
        }

        // Presenter-only contract test; runs on every target incl. the
        // `iosSimulatorArm64` gate (proving the non-Compose path links + works
        // on K/N). Uses only the multiplatform paging-common + coroutines-test.
        getByName("commonTest") {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.test)
            }
        }

        getByName("desktopTest") {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.test)
                // TenshuPagingSourceTest builds its tContext from core-common's
                // proxy/test helpers (TraitResolverImpl, PureOperationRunner,
                // createTestContext/createCoreTestRegistry); test-only deps that
                // are not iOS-capable, so this source stays desktop-only.
                implementation(project(":core-common"))
                implementation(project(":core-test-utils"))
                implementation(libs.paging.testing)
            }
        }
    }
}
