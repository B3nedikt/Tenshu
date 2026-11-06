// Home's own iOS framework (`docs/proposals/ios-per-app-frameworks.md` P4): the `TenshuSdk` that
// home-ios links, built from Home's surface and runtime and `:tenshu-sdk-ios-core` only. It contains
// no other app's code, so Home's binary carries no load command for the platform frameworks only
// other apps use.
//
// SKIE with analytics off, `baseName = "TenshuSdk"`, static, the base exports and
// `api(:tenshu-sdk-ios-core)` are applied to this module BY NAME from the root build.gradle.kts
// ("iOS app frameworks"). This file adds only what Home's Swift reads beyond that base.
//
// ⚠ `export(...)` is not transitive, and the failure is loud only in Swift: a type Home's Swift
// names that is missing here compiles as a prefixed name with no members. Only
// `scripts/ios-smoke.sh --build-only home` proves this list (§5.2, R3).
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
            // Home's view-model/repository layer — what home-ios binds to.
            export(project(":home-shared-logic"))
        }
    }

    sourceSets {
        getByName("iosMain") {
            dependencies {
                // Paired with the export above — an export must be an `api` dependency.
                api(project(":home-shared-logic"))
            }
        }
        getByName("iosTest") {
            dependencies {
                implementation(libs.kotlinx.coroutines.test)
            }
        }
    }
}
