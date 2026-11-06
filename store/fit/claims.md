# Tenshu Fit — the source of every claim in the store description

Design §4.4: a claim with no source is not published. `scripts/store-listing.py check` fails when a
sentence under the description's `HOW … PRIVATE` heading is not quoted here (`> ` line, verbatim) with
at least one `- source:` whose path exists. The wording follows Drive's and Control's reviewed claims
and this app's privacy-label draft (`store/fit/privacy-label.md`); Fit's support page
(`web/apex/content/apps/fit/support/`) makes the same claims with the same sources. ⚠ A
threat-model amendment that weakens one of these has to change the listing too (House Rule 2) — it
is a public promise.

## Privacy sentences (checked)

> Your workouts and health data are encrypted on your device before they are uploaded.
- source: docs/sync/threat-model.md — invariant I1 and asset A5 (user data): workouts and health samples are realm Syncables, sealed before they reach the relay
- source: AGENTS.md — House Rule 1
- source: store/fit/privacy-label.md — Health & Fitness: synced only end-to-end encrypted

> Tenshu's servers store only encrypted data, and they don't have the keys to read it.
- source: AGENTS.md — House Rule 1: the relay sees ciphertext only
- source: docs/sync/threat-model.md — invariant I1; the keys (A1–A4) never reach the relay in the clear

> The server can still see some facts it needs to sync: how much you store, how many devices you have, and when they sync.
- source: docs/sync/threat-model.md — asset A6 (metadata) and T-T-07 (metadata analysis)

> Fit has no ads and no analytics, and it does not share your health data with anyone.
- source: fit-ios/Resources/PrivacyInfo.xcprivacy — `NSPrivacyTracking` false, no collected types
- source: store/fit/privacy-label.md — Tracking: No; item 4 (Guideline 5.1.3, ⚖️ counsel)
- source: fit-android/src/main/AndroidManifest.xml — Android: INTERNET `tools:node="remove"`; data leaves only as Control's encrypted sync

> Fit uses your location only while you record a workout.
- source: fit-shared-data/src/iosMain/kotlin/app/tenshu/fit/data/IosRecordingEngine.kt — location starts and stops with a recording
- source: fit-ios/project.yml — the location purpose strings; Fit never asks for Always
- source: fit-android/src/main/AndroidManifest.xml — Android: no ACCESS_BACKGROUND_LOCATION; location only in the recording foreground service (fit-android/src/main/java/app/tenshu/fit/android/recorder/RecordingService.kt)

> If you turn on Use Apple Maps, Apple serves the map, and sees which workout you open and when.
- source: docs/proposals/ios-apple-maps-option.md — D9/D10: off by default, per app; MapKit tile requests tell Apple which place, trip or workout is open, and when
- source: ios-basemap/Sources/MapRendererSection.swift — the in-app disclosure, and the default MapLibre renderer (MapRendererControl.swift)

> Without a storage plan, your data stays on this device.
- source: docs/adr/0019-storage-capacity-gating.md — no subscription ⇒ zero relay traffic

## Feature claims (reviewed by hand, not by the checker)

Checked against the iOS code on 2026-10-17 (`fit-ios/Sources` and the shared modules). Paths are
under the repo root; a bare file name is in `fit-ios/Sources/`.

| Claim | Evidence |
|---|---|
| Needs Tenshu Control on the same device | `fit-ios/Sources/FitIosApp.swift` (the Set Up Tenshu Control gate); `FitLocalRuntime.kt` (route 1, over Control's shared-Keychain key material) |
| Record run, walk, ride, strength; time, distance, pace, steps; GPS route | `fit-ios/Sources/RecordView.swift`; `IosRecordingEngine.kt` (CoreLocation, CMPedometer) |
| Bluetooth heart rate strap | `IosRecordingEngine.kt` (GATT Heart Rate 0x180D/0x2A37); `RecordView.swift` Connect |
| Recorded workouts added to Apple Health | `fit-shared-data/.../IosHealthKitWorkoutWriter.kt` (type, start, end only) |
| Last 30 days from Apple Health; listed types; imports again on open | `fit-shared-data/.../HealthKitImporter.kt` (types, `historyDays = 30`); the first import is the user's tap on *Import from Apple Health* (`HomeView.swift` empty state, `ProfileView.swift` Data sources) — `autoRefreshReady` keeps the launch/foreground refresh (`FitIosApp.swift`, `FitBackgroundRefresh.swift`) off until that request has been answered |
| Google Takeout .zip, .gpx, .tcx | `BatchImportView.swift`; `tenshu-sdk-ios-fit/.../FitSurface.kt` `importPickedFile` |
| History with route, laps, heart rate; simple trends | `HistoryView.swift`, `WorkoutDetailView.swift`, `WorkoutRouteMapView.swift`, `SampleTrend.swift` |
| Daily active-minutes goal | `HomeView.swift`, `ProfileEditors.swift` |
| Not a medical device | Guideline 5.1.3 wording; no diagnosis or score logic in `fit-ios/Sources` |
| Apple Health on iPad needs iPadOS 17 | HealthKit availability; without it `HealthKitImporter.kt` offers no source and Home says "Apple Health isn't available on this device" |
| iOS 16, iPhone and iPad | `fit-ios/project.yml` (`deploymentTarget` 16.0; XcodeGen's default device family 1,2) |

**Left out on purpose — the iOS app does not do it today:** the **Medical** card (medications, immunizations, allergies, conditions): nothing fills it on iOS — HealthKit
`medicalRecords` is empty — and it invites a 5.1.3 / medical-claim reading; Health Records (FHIR); workout routes
from Apple Health and writing routes, heart rate, energy or distance back; full-history import (30 days only on
iOS); automatic activity detection (the toggle only stores a preference); Apple Watch; `.fit` files (no decoder
on iOS); calorie figures (a rough estimate); QR pairing (DEV_HARNESS only).

## Google Play only (store/fit/play/) — Health Connect

> Fit reads from Health Connect only the kinds of data you allow, and it writes nothing to Health Connect.
- source: fit-shared-data/src/androidMain/kotlin/app/tenshu/fit/data/HealthConnectImporter.kt — permissionsFor(): read permissions for the chosen categories only
- source: fit-android/src/main/AndroidManifest.xml — Health Connect READ_* permissions only, no WRITE_*

## Deutsch (de-DE)

The German listings' privacy sentences: Google Play's (`play/de-DE/`) and the App Store's (`de-DE/`),
one claim serving both where the sentence is the same. Each names the English claim it renders in
its `- translates:` line and cites that claim's sources. `check` holds the mechanics: every German
claim names an English claim that exists, the Play section renders the English Play listing's claims
one for one and in order (a dropped caveat fails), and a changed English claim flags its translation.
The App Store pair (`de-DE/` against `en-US/`) is held the same way: one for one and in order.
Whether the German says the same thing is review's job. The wording follows the German support page
(`web/apex/content/apps/fit/support/de.md`).

> Deine Trainings und Gesundheitsdaten werden auf deinem Gerät verschlüsselt, bevor sie hochgeladen werden.
- translates: "Your workouts and health data are encrypted on your device before they are uploaded."
- source: docs/sync/threat-model.md — invariant I1 and asset A5 (user data): workouts and health samples are realm Syncables, sealed before they reach the relay
- source: AGENTS.md — House Rule 1
- source: store/fit/privacy-label.md — Health & Fitness: synced only end-to-end encrypted

> Die Server von Tenshu speichern nur verschlüsselte Daten und haben nicht die Schlüssel, um sie zu lesen.
- translates: "Tenshu's servers store only encrypted data, and they don't have the keys to read it."
- source: AGENTS.md — House Rule 1: the relay sees ciphertext only
- source: docs/sync/threat-model.md — invariant I1; the keys (A1–A4) never reach the relay in the clear

> Einige Fakten, die der Server zum Synchronisieren braucht, sieht er trotzdem: wie viel du speicherst, wie viele Geräte du hast und wann sie synchronisieren.
- translates: "The server can still see some facts it needs to sync: how much you store, how many devices you have, and when they sync."
- source: docs/sync/threat-model.md — asset A6 (metadata) and T-T-07 (metadata analysis)

> Fit hat keine Werbung und keine Analyse-Tools, und es gibt deine Gesundheitsdaten an niemanden weiter.
- translates: "Fit has no ads and no analytics, and it does not share your health data with anyone."
- source: fit-ios/Resources/PrivacyInfo.xcprivacy — `NSPrivacyTracking` false, no collected types
- source: store/fit/privacy-label.md — Tracking: No; item 4 (Guideline 5.1.3, ⚖️ counsel)
- source: fit-android/src/main/AndroidManifest.xml — Android: INTERNET `tools:node="remove"`; data leaves only as Control's encrypted sync

> Fit liest aus Health Connect nur die Datenarten, die du erlaubst, und schreibt nichts in Health Connect.
- translates: "Fit reads from Health Connect only the kinds of data you allow, and it writes nothing to Health Connect."
- source: fit-shared-data/src/androidMain/kotlin/app/tenshu/fit/data/HealthConnectImporter.kt — permissionsFor(): read permissions for the chosen categories only
- source: fit-android/src/main/AndroidManifest.xml — Health Connect READ_* permissions only, no WRITE_*

> Fit nutzt deinen Standort nur, während du ein Training aufzeichnest.
- translates: "Fit uses your location only while you record a workout."
- source: fit-shared-data/src/iosMain/kotlin/app/tenshu/fit/data/IosRecordingEngine.kt — location starts and stops with a recording
- source: fit-ios/project.yml — the location purpose strings; Fit never asks for Always
- source: fit-android/src/main/AndroidManifest.xml — Android: no ACCESS_BACKGROUND_LOCATION; location only in the recording foreground service (fit-android/src/main/java/app/tenshu/fit/android/recorder/RecordingService.kt)

> Ohne Speicherplan bleiben deine Daten auf diesem Gerät.
- translates: "Without a storage plan, your data stays on this device."
- source: docs/adr/0019-storage-capacity-gating.md — no subscription ⇒ zero relay traffic

### App Store only (de-DE/)

> Wenn du „Apple Maps verwenden“ einschaltest, liefert Apple die Karte und sieht, welches Training du öffnest und wann.
- translates: "If you turn on Use Apple Maps, Apple serves the map, and sees which workout you open and when."
- source: docs/proposals/ios-apple-maps-option.md — D9/D10: off by default, per app; MapKit tile requests tell Apple which place, trip or workout is open, and when
- source: ios-basemap/Sources/MapRendererSection.swift — the in-app disclosure, and the default MapLibre renderer (MapRendererControl.swift)
