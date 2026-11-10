pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Drive"
include(":diagnostics")
include(":ui-core")
// The camera QR scanner — its own module so only the apps that scan package the camera stack.
include(":ui-qr-scanner")
// Compose-free home for the swiftc-built macOS helper binaries (ImageIO decode bridge),
// shared by the Compose viewer and the proxy-side thumbnail generator.
include(":macos-helpers")
// Its Windows twin: Win32 over FFM (DPAPI, later peer pid, tray, power, Cloud Files) —
// docs/proposals/desktop-windows-port.md.
include(":windows-helpers")
// Its Windows twin: Win32 over FFM (DPAPI, later peer pid / tray / power / Cloud Files) —
// docs/proposals/desktop-windows-port.md.
include(":windows-helpers")
// Reusable offline MapLibre basemap surface (extracted from Maps Ph7b; used by Maps, later Timeline).
include(":ui-map")
// The Compose-free core of that basemap — style JSON, palette, camera framing, source resolution.
// Split out of :ui-map so iOS can share the map's *logic* and write the view in SwiftUI over
// MapLibre's native SDK, instead of dragging the Compose runtime into every iOS app
// (docs/proposals/ios-fit-timeline-maps-parity.md §3.1).
include(":basemap-core")
include(":vector-tile")
include(":takeout-import")
include(":inference-service")
include(":inference-service-client")
include(":inference-service-common")
include(":inference-service-proxy")
include(":drive-android")
include(":drive-shared-ui")
include(":drive-shared-logic")
include(":control")
include(":control-shared")
include(":control-shared-logic")
include(":control-shared-proxy")
include(":control-desktop")
include(":hub")
// Hub management service (hub proposal §8, H4): synced status + command rows.
include(":hub-management-service")
include(":hub-management-service-common")
include(":hub-management-service-client")
include(":hub-management-service-proxy")
// Home device service (home-device-service.md HD-1): bridged smart-home devices as
// realm data — inventory + state + command rows, generalising the H4 pattern.
include(":home-device-service")
include(":home-device-service-common")
include(":home-device-service-client")
include(":home-device-service-proxy")
// Routines engine (routines-engine.md RE-1): automations as realm data, evaluated
// and executed locally by an engine that runs in every proxy — no cloud round-trip.
include(":routine-service")
include(":routine-service-common")
include(":routine-service-client")
include(":routine-service-proxy")
include(":drive-desktop")
// Timeline — a standalone Tenshu app (docs/proposals/timeline-app.md). UI-only v1
// over a mock repository; mirrors Drive's module split.
// The pure feed projection + presentation model, shared by native and web (timeline-app.md §Web scope 4a).
include(":timeline-projection")
include(":timeline-shared-logic")
include(":timeline-shared-data")
include(":timeline-shared-ui")
include(":timeline-desktop")
include(":timeline-android")
// Fit — the health & fitness app (docs/proposals/fit-app.md). UI-only v1 over a mock
// repository; mirrors the Timeline module split. Real store = :health-service, later.
// The OS-notification seam (calendar-app.md §4.3 gap 2), opened by Calendar's reminders but
// scoped as a platform slice: ten sites hand-roll NotificationCompat today.
include(":app-notify")
include(":app-liveness")
include(":app-list-state")
include(":app-outbox")
include(":app-reachability")
// The catalogue key an app renders a typed error through
// (`core-error-copy-catalogue.md`). Same family, same reason as `:app-reachability`: the
// Compose UI, the Compose-free VM layer and the iOS/JS SDKs all need to name it, and none of
// `:core`, `:ui-core` or `:core-common` can hold it without being the wrong layer.
include(":app-error-handling")
// The ingest-source registry an app reads to show and switch off what this device backs up
// (`photos-app.md` PH-4). Same family, same reason as `:app-reachability`.
include(":app-ingest")
include(":fit-shared-file")
include(":fit-projection")
include(":fit-shared-logic")
include(":fit-shared-data")
include(":fit-shared-ui")
include(":fit-desktop")
include(":fit-android")
// Maps — Places (slim), a standalone Tenshu app (docs/proposals/maps-app.md). Ph0
// scaffold: four modules mirroring the Timeline split; UI-only over a mock later.
include(":maps-shared-logic")
include(":maps-shared-data")
include(":maps-shared-ui")
include(":map-region-app")
include(":maps-desktop")
include(":maps-android")
// Home — the smart-home app (docs/proposals/home-and-mia-apps.md). M1 scaffold: UI-only
// over a mock device repository, mirroring the Timeline/Fit/Maps split. Mia (the
// standalone assistant) is a sibling app and arrives with M3.
include(":home-shared-logic")
include(":home-shared-ui")
include(":home-desktop")
include(":home-android")
// Mia query layer (mia-query-layer.md MQ-1): the assistant's backend. Proxy-side
// because only the proxy reads across every service (MQ-D2); an utterance becomes a
// typed QueryPlan that LOWERS TO AN ORDINARY CHAIN (MQ-D3) and passes one validator
// (MQ-D10) before it executes.
include(":mia-service")
include(":mia-service-common")
include(":mia-service-client")
include(":mia-service-proxy")
// Mia — the standalone assistant app (docs/proposals/home-and-mia-apps.md §4). M3:
// chat-first over a mock conversation backend; Home embeds the same chat surface.
include(":mia-shared-logic")
include(":mia-shared-ui")
include(":mia-desktop")
include(":mia-android")
// Contacts — the app over :contact-service (docs/proposals/contacts-app.md). Five modules
// mirroring the Maps split. Unlike its siblings the *read* surface is real on day one
// (§2), so the mock is only the Ph1 stand-in and Ph2 swaps in :contact-service.
include(":contacts-shared-logic")
include(":contacts-shared-data")
include(":contacts-shared-ui")
include(":contacts-desktop")
include(":contacts-android")

// Photos (docs/proposals/photos-app.md PH-1). Four modules, not five: the real repository lives
// in `:photos-shared-logic` beside the view models rather than in a `-shared-data` twin, because
// it needs no Compose and no platform delegate — it is a chain read over the Control proxy's
// `:file-service`. The iOS shell is `photos-ios/` — an XcodeGen project, not a Gradle module
// (`photos-ios.md` PIO-1), exactly as Calendar's is.
// `:photo-service` — albums, ordering and flags (`photos-app.md` PH-2). ⚠ Singular `photo-`, like
// every other service (`file-service`, `place-service`): a service is named for the thing it holds
// a statement about, while the *app* modules are plural (`photos-`). The distinction is not
// cosmetic — `:photo-service` is a platform surface any app may read, not Photos' private store.
include(":photo-service")
include(":photo-service-common")
include(":photo-service-client")
include(":photo-service-proxy")
include(":photo-service-test-utils")

include(":photos-shared-logic")
include(":photos-shared-ui")
include(":photos-desktop")
include(":photos-android")
include(":core")
include(":core-aidl")
include(":core-proxy")
include(":core-common")
include(":core-client")
include(":core-paging")
include(":file-service")
include(":file-service-common")
include(":file-service-client")
include(":file-service-proxy")
include(":core-test-utils")
include(":viewmodel-test-utils")
include(":file-service-test-utils")
include(":notification-service")
include(":notification-service-common")
include(":notification-service-client")
include(":notification-service-proxy")
include(":notification-service-test-utils")

// Timeline service — the Moment/Trip spine for the Timeline app (docs/proposals/timeline-services.md §5).
include(":timeline-service")
include(":timeline-service-common")
include(":timeline-service-client")
include(":timeline-service-proxy")
include(":timeline-service-test-utils")
// Health service — workouts/activities for the Timeline app (docs/proposals/timeline-services.md §4).
include(":health-service")
include(":health-service-common")
include(":health-service-client")
include(":health-service-proxy")
include(":health-service-test-utils")
// Place service — POIs for the Timeline app (docs/proposals/timeline-services.md §3).
include(":place-service")
include(":place-service-common")
include(":place-service-client")
include(":place-service-proxy")
include(":place-service-test-utils")
// Map-region service — offline basemap region delivery for Maps/Timeline map surfaces
// (docs/proposals/maps-region-delivery.md D5/D6). Proxy owns catalog + canonical store; apps
// reach regions only through this IPC surface. Slice 2a: catalog/installed/delete + verify.
include(":map-region-service")
include(":map-region-service-common")
include(":map-region-service-client")
include(":map-region-service-proxy")
include(":map-region-service-test-utils")
// Tiles manifest signer — a JVM build tool that assembles catalog.json from region
// definitions + pmtiles extracts and signs it with the in-tree Ed25519 (the SAME primitive
// the client verifies with). Phase 1 of docs/proposals/maps-region-delivery.md.
include(":tiles-signer")
include(":model-signer")

include(":ingest-service")
include(":ingest-service-common")
include(":ingest-service-client")
include(":ingest-service-proxy")
include(":ingest-service-test-utils")
include(":preference-service")
include(":preference-service-common")
include(":preference-service-client")
include(":preference-service-proxy")
include(":preference-service-test-utils")
include(":contact-service")
include(":contact-service-common")
include(":contact-service-client")
include(":contact-service-proxy")
include(":contact-service-test-utils")
// Calendar — docs/proposals/calendar-app.md. CA-0 shipped the schema + the
// recurrence engine; CA-1 adds the operation surface (`-client` / `-proxy`)
// that makes Calendar a Mia query-layer source and action target (§6.3).
include(":calendar-service")
include(":calendar-service-common")
include(":calendar-service-client")
include(":calendar-service-proxy")
include(":calendar-service-test-utils")
// CA-1b: the Compose-free repository layer an iOS shell can consume.
include(":calendar-shared-logic")
// CA-2: the Compose month + agenda views (android + desktop).
include(":calendar-shared-ui")
include(":calendar-desktop")
include(":calendar-android")
include(":storage-service")
include(":storage-service-common")
include(":storage-service-client")
include(":storage-service-proxy")
include(":storage-service-test-utils")
include(":logging-service")
include(":logging-service-common")
include(":logging-service-client")
include(":logging-service-proxy")
include(":logging-service-test-utils")
include(":identity-service")
include(":identity-service-common")
include(":identity-service-client")
include(":identity-service-proxy")
include(":sync-service")
include(":sync-service-common")
include(":sync-service-client")
include(":sync-service-proxy")
include(":epc-rendezvous-client")
include(":sync-relay-server")
include(":commerce-service")
include(":tenshu-sdk-js")
// Per-app iOS frameworks (`docs/proposals/ios-per-app-frameworks.md`): the app-agnostic core every
// app framework exports, and the region stager the map-drawing apps share. Each iOS app links its
// own `:tenshu-sdk-ios-<app>`; the old shared umbrella `:tenshu-sdk-ios` was removed in P5.
include(":tenshu-sdk-ios-core")
include(":tenshu-sdk-ios-region")
// Control's own framework — the P2 pilot.
include(":tenshu-sdk-ios-control")
// Drive's own framework — P3.
include(":tenshu-sdk-ios-drive")
// The other eight — P4.
include(":tenshu-sdk-ios-calendar")
include(":tenshu-sdk-ios-contacts")
include(":tenshu-sdk-ios-home")
include(":tenshu-sdk-ios-mia")
include(":tenshu-sdk-ios-maps")
include(":tenshu-sdk-ios-photos")
include(":tenshu-sdk-ios-timeline")
include(":tenshu-sdk-ios-fit")
// IN-3m-iOS: the Core ML measurement harness. Never shipped, in no gate; its own XCFramework.
include(":inference-harness-ios")
include(":system-test-utils")
