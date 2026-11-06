// The app-agnostic half of the iOS SDK (`docs/proposals/ios-per-app-frameworks.md` §5.1, P1): the
// route-1 runtime substrate (`FirstPartyLocalRuntime`), the surfaces shared Swift reads in every
// app (`RealmStorageSurface`, `SyncActivitySurface`, `ErrorCopySurface`, `EpcRelayPairing`), and
// the open-target routing. (`CryptoSmoke`, the last probe, went with Drive's Smokes tab; the iOS
// crypto backends are pinned against JVM vectors by `iosTestCheck`.)
//
// An ordinary KMP library: no framework binary and no SKIE. Every app framework exports it, from
// the root build.gradle.kts ("iOS app frameworks", D9).
//
// ⚠⚠ **Nothing here may name one app's code.** This module is exported by every app framework, so
// its whole public surface is a dead-code root in every app: one reference to Drive's surfaces put
// Drive — and PhotoKit — into all ten (§5.3, the three edges cut in P1). It depends on no
// `*-shared-logic` / `*-shared-data` module, and that is the guard: an app type does not compile
// here. ⚠ The same holds for platform frameworks: a PhotoKit, HealthKit, CoreLocation, CoreBluetooth
// or CoreMotion import here lands in every app's binary (§2.1, §3.3).
//
// ⚠⚠ `:control-shared-proxy` is the full in-process proxy graph, and it must stay whole (D4, §6):
// every app's route-1 runtime must declare the SAME service versions over the one shared store.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    jvmToolchain(17)
    applyDefaultHierarchyTemplate()

    // The app frameworks' arch set (no iosX64: `androidx.paging` ships no iosX64 klib).
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        getByName("iosMain") {
            dependencies {
                api(project(":core"))
                api(project(":core-common"))
                api(project(":core-client"))
                // `ErrorCopyKey` — the catalogue key a typed error renders through.
                api(project(":app-error-handling"))
                api(project(":sync-service-common"))
                api(project(":sync-service-client"))
                api(project(":storage-service-common"))
                api(project(":storage-service-client"))
                // ⚠ Re-exported: `:tenshu-sdk-ios-fit` and `-timeline` name `org.tenshu.file.common`
                // types without declaring the module themselves.
                api(project(":file-service-common"))
                api(project(":epc-rendezvous-client"))
                // The Compose-free proxy runtime every route-1 app assembles in-process. NOT
                // exported by any framework: Swift drives it through the surfaces, never naming the
                // proxy graph's types.
                api(project(":control-shared-proxy"))
            }
        }
        getByName("iosTest") {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.test)
                // `ContactPhotoRowMaxSharedReplicaTest`: the contact proxy over the real SQLite store.
                implementation(project(":contact-service-test-utils"))
                // `MomentSliceRowMaxSharedReplicaTest`: a timeline proxy over the real SQLite store.
                implementation(project(":timeline-service-test-utils"))
            }
        }
    }
}
