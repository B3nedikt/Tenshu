// Contacts' own iOS framework (`docs/proposals/ios-per-app-frameworks.md` P4): the `TenshuSdk` that
// contacts-ios links, built from Contacts' surface and runtime and `:tenshu-sdk-ios-core` only. It contains
// no other app's code, so Contacts' binary carries no load command for the platform frameworks only
// other apps use.
//
// SKIE with analytics off, `baseName = "TenshuSdk"`, static, the base exports and
// `api(:tenshu-sdk-ios-core)` are applied to this module BY NAME from the root build.gradle.kts
// ("iOS app frameworks"). This file adds only what Contacts' Swift reads beyond that base.
//
// ⚠ `export(...)` is not transitive, and the failure is loud only in Swift: a type Contacts' Swift
// names that is missing here compiles as a prefixed name with no members. Only
// `scripts/ios-smoke.sh --build-only contacts` proves this list (§5.2, R3).
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
            // Contacts' view-model/repository layer — the VMs, the `ContactsRepository` seam and the
            // mock. Its `ContactsRepository` / `RepositoryResult` keep their `@ObjCName`s (D12): a
            // rename would change a Swift symbol.
            export(project(":contacts-shared-logic"))
        }
    }

    sourceSets {
        getByName("iosMain") {
            dependencies {
                // Paired with the export above — an export must be an `api` dependency.
                api(project(":contacts-shared-logic"))
                // The live read/write surface. Unexported: Swift drives `ContactsSurface`, never
                // `TenshuContactsRepository` directly.
                implementation(project(":contacts-shared-data"))
            }
        }
        getByName("iosTest") {
            dependencies {
                implementation(libs.kotlinx.coroutines.test)
                // `OwnedViewModels`: a view model bound to a live runtime is joined before `Main`
                // is reset (`docs/testing.md` §11).
                implementation(project(":viewmodel-test-utils"))
            }
        }
    }
}
