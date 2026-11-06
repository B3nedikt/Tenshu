# Tenshu Photos (iOS) — App Store privacy label answers

⚖️ **For counsel review before it is entered.** App Store Connect → App Privacy has no public API
(`store-presence-and-support-pages.md` §2.3), so these answers are typed into the console by hand;
this file is the reviewed source for that, and it sits beside
`photos-ios/Resources/PrivacyInfo.xcprivacy` so a change to one shows up in review next to the other.
The two must agree (`docs/how-to/ios-testflight.md` §4).

Derived from the code on 2026-10-17; re-checked against the shipping build settings and the privacy
policy on 2026-10-24. "Collected" in Apple's sense: sent off the device and readable by
us (or a partner) for longer than it takes to answer the request. End-to-end encrypted data the relay
cannot read is treated as not collected (House Rule 1) — ⚖️ that reading of Apple's definition is
the first thing to confirm, as for Control and Drive.

## Tracking

**No.** No advertising or analytics SDK, no IDFA, no App Tracking Transparency prompt, no
`identifierForVendor`. Source: `PrivacyInfo.xcprivacy` (`NSPrivacyTracking` false); no analytics or
crash-reporting dependency in `photos-ios` or `tenshu-sdk-ios-photos`.

## Data collected

**Data Not Collected** — what `PrivacyInfo.xcprivacy` declares (an empty
`NSPrivacyCollectedDataTypes`).

| Apple data type | Answer | Source |
|---|---|---|
| User Content → Photos or Videos | not collected: Photos shows what Drive's backup put in the realm, which leaves a device only encrypted. Photos itself reads no photo library (no PhotoKit; no `NSPhotoLibraryUsageDescription`) | `photos-ios/project.yml`; House Rule 1 |
| Search History | not collected: search matches file names, and tags another device wrote and sync delivered, in the local replica; the query never leaves the device | `photos-ios/Sources/SearchScreen.swift`; `PhotoSearchViewModel.kt` |
| Sensitive Info / Other User Content (on-device labels, embeddings, faces) | not collected — and not made in the shipping build: on-device AI is off (`TENSHU_PHOTOS_ON_DEVICE_AI = NO`), so there is no indexing, no face grouping (PIO-8) and no Models row. Where it is switched on, inference runs on the device (Core ML) and the results are stored in the encrypted realm | `photos-ios/project.yml`; `inference-service.md` |
| Diagnostics | not collected: `:diagnostics` is device-local | `diagnostics/.../LogItem.kt` |
| Everything else | not collected | `scripts/ios-archive.sh` `allowed_sensitive_for` (AVFoundation only) |

The shipping build downloads no model (the Models row exists only with on-device AI on). Where it is on,
the model files come from Tenshu's own CDN (`models.service.tenshu.app`) and only when the user asks
for them in the settings (as the privacy policy says, `web/apex/support-pages/privacy.ts`, "Maps and
models from our servers"); the request carries no user data beyond what any download does (IP address,
see item 2). ⚠ Re-derive this file the day the flag turns on.

## ⚖️ Open — answers that need a decision before they are entered

1. **The E2EE reading** above (as Control's and Drive's item).
2. **Metadata the relay sees** (sizes, device count, sync times — threat model A6, T-T-07) and
   **server access logs** — as Control's items 3 and 4.
3. **Account and email** are Control's (`store/control/privacy-label.md`); Photos has no sign-in UI.
