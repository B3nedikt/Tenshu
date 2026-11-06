# Tenshu Maps — the source of every claim in the store description

Design §4.4: a claim with no source is not published. `scripts/store-listing.py check` fails when a
sentence under the description's `HOW … PRIVATE` heading is not quoted here (`> ` line, verbatim) with
at least one `- source:` whose path exists. The app has no support page yet, so the wording follows
Drive's and Control's reviewed claims and this app's privacy-label draft (`store/maps/privacy-label.md`). ⚠ A
threat-model amendment that weakens one of these has to change the listing too (House Rule 2) — it
is a public promise.

## Privacy sentences (checked)

> Maps never uses your location.
- source: ios-basemap/Sources/TenshuMapView.swift — `showsUserLocation = false`; no location request in maps-ios/Sources
- source: store/maps/privacy-label.md — Location: not collected; the purpose string exists only because MapLibre links CoreLocation
- source: maps-android/src/main/AndroidManifest.xml — Android: INTERNET, ACCESS_FINE_LOCATION and ACCESS_COARSE_LOCATION all `tools:node="remove"`

> The map is drawn on your device from the regions you download.
- source: ios-basemap/Sources/BasemapStyle.swift — the tile source is a downloaded region (else the bundled demo file); no streamed source on iOS (maps-ios/Sources/MapsMapHero.swift, B-4)

> Tenshu's map server sees which regions you download, not which places you look at.
- source: store/maps/privacy-label.md — item 4 (regions come from tiles.service.tenshu.app); ⚖️ counsel to confirm the tile host's log retention
- source: docs/proposals/ios-apple-maps-option.md — "our CDN saw one download, once" versus per-view tile requests

> Your places are encrypted on your device before they are uploaded.
- source: docs/sync/threat-model.md — invariant I1 and asset A5 (user data): places (`:place-service`) are realm Syncables, sealed before they reach the relay
- source: AGENTS.md — House Rule 1

> Tenshu's servers store only encrypted data, and they don't have the keys to read it.
- source: AGENTS.md — House Rule 1: the relay sees ciphertext only
- source: docs/sync/threat-model.md — invariant I1; the keys (A1–A4) never reach the relay in the clear

> The server can still see some facts it needs to sync: how much you store, how many devices you have, and when they sync.
- source: docs/sync/threat-model.md — asset A6 (metadata) and T-T-07 (metadata analysis)

> If you turn on Use Apple Maps, Apple serves the map, and sees which place you open and when.
- source: docs/proposals/ios-apple-maps-option.md — D9/D10: off by default, per app; MapKit tile requests tell Apple which place, trip or workout is open, and when
- source: ios-basemap/Sources/MapRendererSection.swift — the in-app disclosure, and the default MapLibre renderer (MapRendererControl.swift)

## Feature claims (reviewed by hand, not by the checker)

Checked against the iOS code on 2026-10-17 (`maps-ios/Sources` and the shared modules). Paths are
under the repo root; a bare file name is in `maps-ios/Sources/`.

| Claim | Evidence |
|---|---|
| Needs Tenshu Control on the same device | route 1: `maps-ios/Sources/MapsIosApp.swift` (the Set Up Tenshu Control gate); `tenshu-sdk-ios-maps/.../MapsLocalRuntime.kt` |
| Places come from Timeline | `maps-shared-data/.../PlaceProjectionRepository.kt` (the shared `:place-service` realm); empty-state copy in `maps-ios/Sources/PlacesView.swift` |
| List of places; open one on the map | `PlacesView.swift` (Yours tab), `MapsPlaceDetailView.swift` |
| Download a region, delete it; map only where downloaded | `maps-ios/Sources/RegionPickerView.swift` (Get / delete), `MapsPlaceDetailView.swift` (Keep … offline); no streaming on iOS: `MapsMapHero.swift` B-4, parity doc P6 |
| Apple Maps, off until turned on | `ios-basemap/Sources/MapRendererSection.swift` on the Offline maps screen; `docs/proposals/ios-apple-maps-option.md` |
| iOS 16, iPhone and iPad | `maps-ios/project.yml` (`deploymentTarget` 16.0; XcodeGen's default device family 1,2) |

**Left out on purpose — the iOS app does not do it today:** search (no search UI on iOS, and none on Android either); saving or bookmarking places (`setSaved` is a
no-op and `savedLists()` is empty — `PlaceProjectionRepository.kt`); **Add a place**, **Report a problem**,
**Edit**, **Directions** and **Share** (rows or buttons with no action, `PlacesView.swift`,
`MapsPlaceDetailView.swift`); the **For you** tab (always empty); deleting a place; showing the user's location;
routing; opening Maps from another app (the `tenshu-maps` scheme is registered but unhandled); streaming tiles
outside a downloaded region (Android only); "browse the map anywhere". The region picker now says what iOS does — the map is drawn only from
downloaded regions and a small built-in area, never from fetched tiles, and downloads stay until deleted
(`RegionPickerView.swift`) — but the listing still promises neither streaming nor cross-app sharing.

## Google Play only (store/maps/play/) — Android streams the map where no region is downloaded

> The map is drawn on your phone.
- source: ui-map/src/androidMain/kotlin/app/tenshu/ui/map/StreamedPartial.android.kt — MapLibre renders vector tiles on the device

> Where you have not downloaded a region, Tenshu Control fetches the map around the place you open from Tenshu's map server, so that server can see which area you look at.
- source: ui-map/src/androidMain/kotlin/app/tenshu/ui/map/StreamedPartial.android.kt — tiles streamed through Control for a place outside a downloaded region
- source: store/maps/claims.md — the feature table's "streaming tiles outside a downloaded region (Android only)"

> Download a region, and its map comes from your phone.
- source: ui-map/src/androidMain/kotlin/app/tenshu/ui/map/StreamedPartial.android.kt — a downloaded region is read locally first

## Deutsch (de-DE)

The German listings' privacy sentences: Google Play's (`play/de-DE/`) and the App Store's (`de-DE/`),
one claim serving both where the sentence is the same. Each names the English claim it renders in
its `- translates:` line and cites that claim's sources. `check` holds the mechanics: every German
claim names an English claim that exists, the Play section renders the English Play listing's claims
one for one and in order (a dropped caveat fails), and a changed English claim flags its translation.
The App Store pair (`de-DE/` against `en-US/`) is held the same way: one for one and in order.
Whether the German says the same thing is review's job. Maps has no German support page yet; the
wording follows Drive's reviewed German claims.

> Maps nutzt nie deinen Standort.
- translates: "Maps never uses your location."
- source: ios-basemap/Sources/TenshuMapView.swift — `showsUserLocation = false`; no location request in maps-ios/Sources
- source: store/maps/privacy-label.md — Location: not collected; the purpose string exists only because MapLibre links CoreLocation
- source: maps-android/src/main/AndroidManifest.xml — Android: INTERNET, ACCESS_FINE_LOCATION and ACCESS_COARSE_LOCATION all `tools:node="remove"`

> Die Karte wird auf deinem Handy gezeichnet.
- translates: "The map is drawn on your phone."
- source: ui-map/src/androidMain/kotlin/app/tenshu/ui/map/StreamedPartial.android.kt — MapLibre renders vector tiles on the device

> Wo du keine Region heruntergeladen hast, holt Tenshu Control die Karte um den Ort, den du öffnest, vom Kartenserver von Tenshu, deshalb kann dieser Server sehen, welches Gebiet du dir ansiehst.
- translates: "Where you have not downloaded a region, Tenshu Control fetches the map around the place you open from Tenshu's map server, so that server can see which area you look at."
- source: ui-map/src/androidMain/kotlin/app/tenshu/ui/map/StreamedPartial.android.kt — tiles streamed through Control for a place outside a downloaded region
- source: store/maps/claims.md — the feature table's "streaming tiles outside a downloaded region (Android only)"

> Lade eine Region herunter, dann kommt ihre Karte von deinem Handy.
- translates: "Download a region, and its map comes from your phone."
- source: ui-map/src/androidMain/kotlin/app/tenshu/ui/map/StreamedPartial.android.kt — a downloaded region is read locally first

> Deine Orte werden auf deinem Gerät verschlüsselt, bevor sie hochgeladen werden.
- translates: "Your places are encrypted on your device before they are uploaded."
- source: docs/sync/threat-model.md — invariant I1 and asset A5 (user data): places (`:place-service`) are realm Syncables, sealed before they reach the relay
- source: AGENTS.md — House Rule 1

> Die Server von Tenshu speichern nur verschlüsselte Daten und haben nicht die Schlüssel, um sie zu lesen.
- translates: "Tenshu's servers store only encrypted data, and they don't have the keys to read it."
- source: AGENTS.md — House Rule 1: the relay sees ciphertext only
- source: docs/sync/threat-model.md — invariant I1; the keys (A1–A4) never reach the relay in the clear

> Einige Fakten, die der Server zum Synchronisieren braucht, sieht er trotzdem: wie viel du speicherst, wie viele Geräte du hast und wann sie synchronisieren.
- translates: "The server can still see some facts it needs to sync: how much you store, how many devices you have, and when they sync."
- source: docs/sync/threat-model.md — asset A6 (metadata) and T-T-07 (metadata analysis)

### App Store only (de-DE/)

> Die Karte wird auf deinem Gerät aus den Regionen gezeichnet, die du herunterlädst.
- translates: "The map is drawn on your device from the regions you download."
- source: ios-basemap/Sources/BasemapStyle.swift — the tile source is a downloaded region (else the bundled demo file); no streamed source on iOS (maps-ios/Sources/MapsMapHero.swift, B-4)

> Der Kartenserver von Tenshu sieht, welche Regionen du herunterlädst, nicht welche Orte du dir ansiehst.
- translates: "Tenshu's map server sees which regions you download, not which places you look at."
- source: store/maps/privacy-label.md — item 4 (regions come from tiles.service.tenshu.app); ⚖️ counsel to confirm the tile host's log retention
- source: docs/proposals/ios-apple-maps-option.md — "our CDN saw one download, once" versus per-view tile requests

> Wenn du „Apple Maps verwenden“ einschaltest, liefert Apple die Karte und sieht, welchen Ort du öffnest und wann.
- translates: "If you turn on Use Apple Maps, Apple serves the map, and sees which place you open and when."
- source: docs/proposals/ios-apple-maps-option.md — D9/D10: off by default, per app; MapKit tile requests tell Apple which place, trip or workout is open, and when
- source: ios-basemap/Sources/MapRendererSection.swift — the in-app disclosure, and the default MapLibre renderer (MapRendererControl.swift)
