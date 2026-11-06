// Calendar's own iOS framework (`docs/proposals/ios-per-app-frameworks.md` P4): the `TenshuSdk` that
// calendar-ios links, built from Calendar's surface and runtime and `:tenshu-sdk-ios-core` only. It
// contains no other app's code, so Calendar's binary carries no load command for the platform
// frameworks only other apps use (HealthKit, CoreLocation, CoreBluetooth, CoreMotion, Photos).
//
// SKIE with analytics off, `baseName = "TenshuSdk"`, static, the base exports and
// `api(:tenshu-sdk-ios-core)` are applied to this module BY NAME from the root build.gradle.kts
// ("iOS app frameworks"). This file adds only what Calendar's Swift reads beyond that base.
//
// ⚠ `export(...)` is not transitive, and the failure is loud only in Swift: a type Calendar's Swift
// names that is missing here compiles as a prefixed name with no members. Only
// `scripts/ios-smoke.sh --build-only calendar` proves this list (§5.2, R3).
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
            // Calendar's view-model/repository layer — the VMs, the `CalendarRepository` seam and
            // the layout maths (`MonthGrid`, `WeekGrid`, `DayLayout`).
            export(project(":calendar-shared-logic"))
            // ⚠ Not optional: `ResolvedOccurrence` is a field on `CalendarUiState`, so Swift reads
            // the type by name, and `export(...)` is not transitive.
            export(project(":calendar-service-common"))
        }
    }

    sourceSets {
        getByName("iosMain") {
            dependencies {
                // Paired with the exports above — an export must be an `api` dependency.
                api(project(":calendar-shared-logic"))
                api(project(":calendar-service-common"))
                // ⚠ Unexported, but on the compile classpath: `CalendarLocalRuntime` names
                // `TenshuCalendarRepository`'s client extensions (`client.calendarService`) to build
                // the live repository. Swift never sees these — it drives `CalendarSurface`.
                implementation(project(":calendar-service"))
                implementation(project(":calendar-service-client"))
                // `CalendarDeviceIdentity` is an `app.tenshu.ingest.DeviceIdentity`, which
                // `:calendar-shared-logic` holds as `implementation` (on the umbrella it arrived
                // through `:drive-shared-logic`).
                implementation(project(":app-ingest"))
            }
        }
        getByName("iosTest") {
            dependencies {
                implementation(libs.kotlinx.coroutines.test)
            }
        }
    }
}
