plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    // The relay wire envelopes (PostFrameRequest/FetchFramesResponse) are
    // @Serializable; without this plugin the annotation compiles but no
    // serializer is emitted and Ktor's reflective lookup throws at runtime.
    // See docs memory "serialization-plugin gap".
    alias(libs.plugins.kotlinx.serialization)
}

// Client/shared half of the EPC relay rendezvous channel (Phase 6b of
// docs/proposals/ios-proxy-architecture.md), extracted out of
// `:sync-service-proxy` so the iOS umbrella can export it WITHOUT dragging in
// the proxy's master-key / operation-implementation code (House Rule #1, §§2,4:
// on iOS the app is a pure EPC client; the master key stays on the proxy).
//
// It moves opaque bytes only over `/sync/epc` — the EPC handshake + session
// framing live above it in `:core-common` (`org.tenshu.core.common.epc`). The
// HTTP engine is injected by the consumer (`HttpClient` is a constructor arg);
// `iosMain` adds a Darwin-engine factory so the Swift app gets a ready transport
// without touching Ktor from Swift. The proxy-side `EpcRendezvousAcceptor` stays
// in `:sync-service-proxy` and depends on this module for the shared types.
@OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)
kotlin {
    jvmToolchain(17)
    applyDefaultHierarchyTemplate()

    android {
        namespace = "org.tenshu.epc.rendezvous"
        compileSdk = 37
        minSdk = 24
    }

    jvm()

    js {
        browser()
        useEsModules()
    }

    // Plain compilation targets only — no per-module framework. The app
    // frameworks (`:tenshu-sdk-ios-<app>`) are what ship (per-module frameworks
    // were "pure build-time cost").
    iosX64()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        getByName("commonMain") {
            dependencies {
                api(project(":core-common"))
                // SyncCryptoManager (the EPC handshake's crypto) lives in :core; mirror how
                // :core-common itself depends on it (implementation) so `EpcRendezvousChannel`
                // can drive the handshake. Still no proxy types (House Rule #1).
                implementation(project(":core"))
                api(libs.ktorClientCore)
                implementation(libs.ktorClientContentNegotiation)
                implementation(libs.ktorSerializationJson)
                implementation(libs.kotlinx.coroutines)
            }
        }
        getByName("iosMain") {
            dependencies {
                implementation(libs.ktorClientDarwin)
            }
        }
    }
}
