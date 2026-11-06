# Tenshu Timeline (iOS) — App Store privacy label answers

⚖️ **For counsel review before it is entered.** App Store Connect → App Privacy has no public API
(`store-presence-and-support-pages.md` §2.3), so these answers are typed into the console by hand;
this file is the reviewed source for that, and it sits beside
`timeline-ios/Resources/PrivacyInfo.xcprivacy` so a change to one shows up in review next to the other.
The two must agree (`docs/how-to/ios-testflight.md` §4).

Derived from the code on 2026-10-17. "Collected" in Apple's sense: sent off the device and readable by
us (or a partner) for longer than it takes to answer the request. End-to-end encrypted data the relay
cannot read is treated as not collected (House Rule 1) — ⚖️ that reading of Apple's definition is
the first thing to confirm, as for Control and Drive.

## Tracking

**No.** No advertising or analytics SDK, no IDFA, no App Tracking Transparency prompt, no
`identifierForVendor`. Source: `PrivacyInfo.xcprivacy` (`NSPrivacyTracking` false); no analytics or
crash-reporting dependency in `timeline-ios` or `tenshu-sdk-ios-timeline`.

## Data collected

⚖️ **Conservatively declared: Location → Precise Location, not linked to the user, not used for
tracking, App Functionality** (`PrivacyInfo.xcprivacy`), for the third-party geocoder below. The
recorded timeline itself is not collected.

| Apple data type | Answer | Source |
|---|---|---|
| Location → Precise Location (the recorded places and trips) | not collected: captured on the device (`CLVisit`, significant-change monitoring, behind the Settings opt-in) and synced only end-to-end encrypted | `timeline-shared-data/.../capture/IosLocationCapture.kt`; House Rule 1 |
| Location → Precise Location (geocoder lookups) | ⚖️ **declared**, see item 4 | `timeline-shared-data/.../geocode/PlaceGeocodingEgress.kt` (the one gate every lookup goes through); `map-region-service-proxy/.../PhotonGeocoder.kt` |
| Wi-Fi network (place recognition) | not collected, and not read today: the `wifi-info` entitlement is commented out in `Timeline.device.entitlements`, so `NEHotspotNetwork.fetchCurrent` answers nil | `timeline-shared-data/.../wifi/IosConnectedWifi.kt` |
| Everything else | not collected | `allowed_sensitive_for` (AVFoundation, CoreLocation) |

## ⚖️ Open — answers that need a decision before they are entered

1. **The E2EE reading** above (as Control's and Drive's item).
2. **Metadata the relay sees** (sizes, device count, sync times — threat model A6, T-T-07) and
   **server access logs** — as Control's items 3 and 4.
3. **Account and email** are Control's (`store/control/privacy-label.md`); Timeline has no sign-in UI.
4. **Coordinates sent to a third-party geocoder.** The place-name picker
   (`reverseGeocodeCandidates`) and the transit-stop fallback (`nearestTransitStop`, when the offline
   tiles cannot name a stop) go through the in-process proxy to komoot's public Photon instance
   (`photon.komoot.io`, `PhotonGeocoder.DEFAULT_ENDPOINT`) with the coordinate in the request. The
   device-tier label lookup uses Apple's `CLGeocoder`. All three sit behind the place-name opt-in
   (`IosPlaceGeocodingControl`, off by default): `PlaceGeocodingEgress.kt` is the only code that
   calls these lookups and refuses unless the opt-in is on (`place-geocoding.md` §5.1). **No shipped
   iOS build sends anything to komoot today**, because route-1 does not surface the toggle yet
   (`TimelineSurface.geocodingAvailable()`; `tech-debt.md`). The manifest keeps the Precise Location
   (not linked, App Functionality) declaration for when the toggle ships, since we cannot see what
   komoot keeps. ⚖️ Counsel: is a coordinate sent to a third party that may log it "collected"; does
   Apple's own `CLGeocoder` count; and must the label say "Third-Party" for komoot?
5. **If the Wi-Fi entitlement is turned on**, the SSID/BSSID is read on the device and stored only
   end-to-end encrypted. Our reading: still not collected. Re-check this file in that change.
