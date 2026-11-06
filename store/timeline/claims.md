# Tenshu Timeline — the source of every claim in the store description

Design §4.4: a claim with no source is not published. `scripts/store-listing.py check` fails when a
sentence under the description's `HOW … PRIVATE` heading is not quoted here (`> ` line, verbatim) with
at least one `- source:` whose path exists. The app has no support page yet, so the wording follows
Drive's and Control's reviewed claims and this app's privacy-label draft (`store/timeline/privacy-label.md`). ⚠ A
threat-model amendment that weakens one of these has to change the listing too (House Rule 2) — it
is a public promise.

## Privacy sentences (checked)

> Timeline records your location only after you turn on Record places and trips.
- source: timeline-ios/Sources/SettingsView.swift — the "Record places and trips" toggle, off by default
- source: timeline-shared-data/src/iosMain/kotlin/app/tenshu/timeline/data/capture/IosLocationCapture.kt — monitoring starts only when capture is enabled

> Your places and trips are encrypted on your device before they are uploaded.
- source: docs/sync/threat-model.md — invariant I1 and asset A5 (user data): places and moments are realm Syncables, sealed before they reach the relay
- source: AGENTS.md — House Rule 1

> Tenshu's servers store only encrypted data, and they don't have the keys to read it.
- source: AGENTS.md — House Rule 1: the relay sees ciphertext only
- source: docs/sync/threat-model.md — invariant I1; the keys (A1–A4) never reach the relay in the clear

> The server can still see some facts it needs to sync: how much you store, how many devices you have, and when they sync.
- source: docs/sync/threat-model.md — asset A6 (metadata) and T-T-07 (metadata analysis)

> On iPhone and iPad, Timeline does not look up place names, so it does not send your coordinates to a place-name service.
- source: timeline-shared-data/src/commonMain/kotlin/app/tenshu/timeline/data/geocode/PlaceGeocodingEgress.kt — the one gate for every lookup; a missing or off opt-in refuses
- source: tenshu-sdk-ios-timeline/src/iosMain/kotlin/org/tenshu/sdk/ios/TimelineSurface.kt — `geocodingAvailable()` is false on route 1, so the opt-in is never offered
- source: docs/proposals/place-geocoding.md — §5.1: iOS route-1 shows no toggle
- source: store/timeline/privacy-label.md — item 4

> You name your places yourself.
- source: timeline-ios/Sources/PlaceDetailView.swift — rename; "Unnamed place" when blank
- source: timeline-ios/Sources/ChoosePlaceSheet.swift — a free-text name, its Nearby list empty while lookups are off

> If you turn on Use Apple Maps, Apple serves the map, and sees which place or trip you open and when.
- source: docs/proposals/ios-apple-maps-option.md — D9/D10: off by default, per app; MapKit tile requests tell Apple which place, trip or workout is open, and when
- source: ios-basemap/Sources/MapRendererSection.swift — the in-app disclosure, and the default MapLibre renderer (MapRendererControl.swift)

> Without a storage plan, your timeline stays on this device.
- source: docs/adr/0019-storage-capacity-gating.md — no subscription ⇒ zero relay traffic

## Feature claims (reviewed by hand, not by the checker)

Checked against the iOS code on 2026-10-17 (`timeline-ios/Sources` and the shared modules). Paths are
under the repo root; a bare file name is in `timeline-ios/Sources/`.

| Claim | Evidence |
|---|---|
| Needs Tenshu Control on the same device | `timeline-ios/Sources/TimelineIosApp.swift` (`ControlSetUpGate`); shared Keychain + App Group in `Timeline.entitlements` |
| Record places and trips; Always permission; visit + significant-change wakes; short trips missed | `SettingsView.swift`; `IosLocationCapture.kt` (`requestAlwaysAuthorization`, `startMonitoringVisits`, `startMonitoringSignificantLocationChanges`, and its own note on short walks); no `UIBackgroundModes` |
| Day feed; trip route, distance, duration, mode | `TimelineHomeView.swift`; `MomentDetailView.swift`; modes from `timeline-shared-data/.../MovementSegmenter.kt` |
| Multi-day trip suggestion, accept or not | `TimelineHomeView.swift` ("Read as a trip" / "Not a trip"); `TripDetailView.swift` |
| Places, visits; name, merge, choose the right place | `PlaceDetailView.swift` (rename, merge, visit history); `ChoosePlaceSheet.swift` |
| Apple Maps, off until turned on | `ios-basemap/Sources/MapRendererSection.swift` in `SettingsView.swift` |
| iOS 16, iPhone and iPad | `timeline-ios/project.yml` (`deploymentTarget` 16.0; XcodeGen's default device family 1,2) |

**Left out on purpose — the iOS app does not do it today:** Google Takeout import (no iOS UI; iOS has no decompression — `takeout-import-and-recall.md`); automatic
place names, transit stop names and nearby-place suggestions (the **Look up place names** opt-in is not offered
on iOS route 1); Wi-Fi place recognition (the toggle shows, but the `wifi-info` entitlement is commented out in
`Timeline.device.entitlements`, so it does nothing on a device); photo thumbnails (visits show colour tiles
only); export, share and delete of a day, visit or place; QR pairing with Control (DEV_HARNESS only).
⚠ **When the place-name toggle ships on iOS**, the place-name sentence must change in the same PR, to: only after
you turn on Look up place names does Timeline send a place's coordinates to a place-name service (Apple's
geocoder, and komoot's public Photon server for transit stops and nearby suggestions).

## Google Play only (store/timeline/play/) — Android capture, and the opt-in lookups

> Timeline records your location only after you turn on Capture my timeline.
- source: timeline-android/src/main/java/app/tenshu/timeline/android/AndroidTimelineCaptureControl.kt — capture is off until the switch is turned on
- source: timeline-shared-ui/src/commonMain/composeResources/values/strings.xml — the "Capture my timeline" setting, "Off by default"

> Look up place names is off until you turn it on.
- source: timeline-android/src/main/java/app/tenshu/timeline/android/AndroidPlaceGeocodingControl.kt — the opt-in defaults to false
- source: timeline-shared-ui/src/commonMain/composeResources/values/strings.xml — timeline_settings_geocoding_desc

> When it is on, a place's coordinates go to a place-name service: Photon, run by komoot, or Android's own place-name lookup, which can use Google's servers.
- source: timeline-android/src/main/java/app/tenshu/timeline/android/TimelineAndroidModule.kt — ChainedReverseGeocoder: Control's Photon egress first, then `android.location.Geocoder`
- source: map-region-service-proxy/src/commonMain/kotlin/org/tenshu/mapregion/proxy/PhotonGeocoder.kt — defaults to komoot's public instance, photon.komoot.io

> Recognise places by Wi-Fi is off until you turn it on.
- source: timeline-shared-ui/src/commonMain/composeResources/values/strings.xml — timeline_settings_wifi_desc, "Off by default"

> It syncs a scrambled id, never the network name.
- source: timeline-shared-ui/src/commonMain/composeResources/values/strings.xml — timeline_settings_wifi_desc

## Deutsch (de-DE)

The German listings' privacy sentences: Google Play's (`play/de-DE/`) and the App Store's (`de-DE/`),
one claim serving both where the sentence is the same. Each names the English claim it renders in
its `- translates:` line and cites that claim's sources. `check` holds the mechanics: every German
claim names an English claim that exists, the Play section renders the English Play listing's claims
one for one and in order (a dropped caveat fails), and a changed English claim flags its translation.
The App Store pair (`de-DE/` against `en-US/`) is held the same way: one for one and in order.
Whether the German says the same thing is review's job.

> Timeline zeichnet deinen Standort erst auf, wenn du „Meine Timeline aufzeichnen“ einschaltest.
- translates: "Timeline records your location only after you turn on Capture my timeline."
- source: timeline-android/src/main/java/app/tenshu/timeline/android/AndroidTimelineCaptureControl.kt — capture is off until the switch is turned on
- source: timeline-shared-ui/src/commonMain/composeResources/values/strings.xml — the "Capture my timeline" setting, "Off by default"

> Deine Orte und Strecken werden auf deinem Gerät verschlüsselt, bevor sie hochgeladen werden.
- translates: "Your places and trips are encrypted on your device before they are uploaded."
- source: docs/sync/threat-model.md — invariant I1 and asset A5 (user data): places and moments are realm Syncables, sealed before they reach the relay
- source: AGENTS.md — House Rule 1

> Die Server von Tenshu speichern nur verschlüsselte Daten und haben nicht die Schlüssel, um sie zu lesen.
- translates: "Tenshu's servers store only encrypted data, and they don't have the keys to read it."
- source: AGENTS.md — House Rule 1: the relay sees ciphertext only
- source: docs/sync/threat-model.md — invariant I1; the keys (A1–A4) never reach the relay in the clear

> Einige Fakten, die der Server zum Synchronisieren braucht, sieht er trotzdem: wie viel du speicherst, wie viele Geräte du hast und wann sie synchronisieren.
- translates: "The server can still see some facts it needs to sync: how much you store, how many devices you have, and when they sync."
- source: docs/sync/threat-model.md — asset A6 (metadata) and T-T-07 (metadata analysis)

> „Ortsnamen nachschlagen“ ist aus, bis du es einschaltest.
- translates: "Look up place names is off until you turn it on."
- source: timeline-android/src/main/java/app/tenshu/timeline/android/AndroidPlaceGeocodingControl.kt — the opt-in defaults to false
- source: timeline-shared-ui/src/commonMain/composeResources/values/strings.xml — timeline_settings_geocoding_desc

> Wenn es an ist, gehen die Koordinaten eines Orts an einen Dienst für Ortsnamen: Photon, betrieben von komoot, oder die Ortsnamen-Suche von Android selbst, die Server von Google nutzen kann.
- translates: "When it is on, a place's coordinates go to a place-name service: Photon, run by komoot, or Android's own place-name lookup, which can use Google's servers."
- source: timeline-android/src/main/java/app/tenshu/timeline/android/TimelineAndroidModule.kt — ChainedReverseGeocoder: Control's Photon egress first, then `android.location.Geocoder`
- source: map-region-service-proxy/src/commonMain/kotlin/org/tenshu/mapregion/proxy/PhotonGeocoder.kt — defaults to komoot's public instance, photon.komoot.io

> „Orte per WLAN erkennen“ ist aus, bis du es einschaltest.
- translates: "Recognise places by Wi-Fi is off until you turn it on."
- source: timeline-shared-ui/src/commonMain/composeResources/values/strings.xml — timeline_settings_wifi_desc, "Off by default"

> Es synchronisiert eine verschleierte ID, niemals den Netzwerknamen.
- translates: "It syncs a scrambled id, never the network name."
- source: timeline-shared-ui/src/commonMain/composeResources/values/strings.xml — timeline_settings_wifi_desc

> Ohne Speicherplan bleibt deine Timeline auf diesem Gerät.
- translates: "Without a storage plan, your timeline stays on this device."
- source: docs/adr/0019-storage-capacity-gating.md — no subscription ⇒ zero relay traffic

### App Store only (de-DE/)

> Timeline zeichnet deinen Standort erst auf, wenn du „Orte und Strecken aufzeichnen“ einschaltest.
- translates: "Timeline records your location only after you turn on Record places and trips."
- source: timeline-ios/Sources/SettingsView.swift — the "Record places and trips" toggle, off by default
- source: timeline-shared-data/src/iosMain/kotlin/app/tenshu/timeline/data/capture/IosLocationCapture.kt — monitoring starts only when capture is enabled

> Auf iPhone und iPad schlägt Timeline keine Ortsnamen nach, deshalb sendet es deine Koordinaten an keinen Dienst für Ortsnamen.
- translates: "On iPhone and iPad, Timeline does not look up place names, so it does not send your coordinates to a place-name service."
- source: timeline-shared-data/src/commonMain/kotlin/app/tenshu/timeline/data/geocode/PlaceGeocodingEgress.kt — the one gate for every lookup; a missing or off opt-in refuses
- source: tenshu-sdk-ios-timeline/src/iosMain/kotlin/org/tenshu/sdk/ios/TimelineSurface.kt — `geocodingAvailable()` is false on route 1, so the opt-in is never offered
- source: docs/proposals/place-geocoding.md — §5.1: iOS route-1 shows no toggle
- source: store/timeline/privacy-label.md — item 4

> Du benennst deine Orte selbst.
- translates: "You name your places yourself."
- source: timeline-ios/Sources/PlaceDetailView.swift — rename; "Unnamed place" when blank
- source: timeline-ios/Sources/ChoosePlaceSheet.swift — a free-text name, its Nearby list empty while lookups are off

> Wenn du „Apple Maps verwenden“ einschaltest, liefert Apple die Karte und sieht, welchen Ort oder welche Strecke du öffnest und wann.
- translates: "If you turn on Use Apple Maps, Apple serves the map, and sees which place or trip you open and when."
- source: docs/proposals/ios-apple-maps-option.md — D9/D10: off by default, per app; MapKit tile requests tell Apple which place, trip or workout is open, and when
- source: ios-basemap/Sources/MapRendererSection.swift — the in-app disclosure, and the default MapLibre renderer (MapRendererControl.swift)
