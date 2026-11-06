// Photos' own iOS framework (`docs/proposals/ios-per-app-frameworks.md` P4): the `TenshuSdk` that
// photos-ios links, built from Photos' surface and runtime and `:tenshu-sdk-ios-core` only. It contains
// no other app's code, so Photos' binary carries no load command for the platform frameworks only
// other apps use.
//
// SKIE with analytics off, `baseName = "TenshuSdk"`, static, the base exports and
// `api(:tenshu-sdk-ios-core)` are applied to this module BY NAME from the root build.gradle.kts
// ("iOS app frameworks"). This file adds only what Photos' Swift reads beyond that base.
//
// ⚠ `export(...)` is not transitive, and the failure is loud only in Swift: a type Photos' Swift
// names that is missing here compiles as a prefixed name with no members. Only
// `scripts/ios-smoke.sh --build-only photos` proves this list (§5.2, R3).
plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    jvmToolchain(17)
    applyDefaultHierarchyTemplate()

    // No iosX64: `androidx.paging` ships no iosX64 klib.
    listOf(
        iosArm64(),
        iosSimulatorArm64(),
    ).forEach { target ->
        target.binaries.framework {
            // Photos' view-model layer — the VMs, the feed layout (`FeedLayout`,
            // `PhotosFeedBinding`), the zone functions and `NotConnectedPhotosRepository`. Its
            // `Photo` and `SettingsViewModel` keep their `@ObjCName("Photos…")` (D12): a rename
            // would change a Swift symbol.
            export(project(":photos-shared-logic"))
            // The Models screen: Photos' Swift binds Control's `ModelsViewModel`
            // (`PhotosModelsBinding`). ⚠ Not a cross-app CODE edge: `:control-shared-logic` is in
            // every app's graph already, through `:tenshu-sdk-ios-core` → `:control-shared-proxy`
            // (D4). Exporting it adds its names to Photos' headers, nothing to its program.
            export(project(":control-shared-logic"))
            // `InferenceCapability`, what the Models view model's switches and consents name.
            export(project(":inference-service"))
            // `TenshuPagingPresenter` / `Flow<PagingData>`, which the feed rides.
            export(project(":core-paging"))
        }
    }

    sourceSets {
        getByName("iosMain") {
            dependencies {
                // Paired with the exports above — an export must be an `api` dependency.
                api(project(":photos-shared-logic"))
                api(project(":control-shared-logic"))
                api(project(":inference-service"))
                api(project(":core-paging"))
            }
        }
        getByName("iosTest") {
            dependencies {
                implementation(libs.kotlinx.coroutines.test)
                // Test-only: `PhotosOwnedInferenceStoresTest` asserts over the stores each assembled
                // graph binds. Production reaches both through `:control-shared-proxy`.
                implementation(project(":inference-service-client"))
                implementation(project(":inference-service-proxy"))
                // …and writes the consent row a withdrawal is, which only the preference types can.
                implementation(project(":preference-service"))
                // Test-only: `PhotosOwnedInferenceStoresTest` and `PhotosOnDeviceAiTest` assemble
                // Drive's and Mia's runtimes beside Photos' to prove neither owns an inference store
                // (`ios-per-app-frameworks.md` §5.3: a cross-app test depends on the app modules
                // involved). Never on the main classpath: Photos' program holds no other app's code.
                implementation(project(":tenshu-sdk-ios-drive"))
                implementation(project(":tenshu-sdk-ios-mia"))
            }
        }
    }
}
