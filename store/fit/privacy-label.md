# Tenshu Fit (iOS) — App Store privacy label answers

⚖️ **For counsel review before it is entered.** App Store Connect → App Privacy has no public API
(`store-presence-and-support-pages.md` §2.3), so these answers are typed into the console by hand;
this file is the reviewed source for that, and it sits beside
`fit-ios/Resources/PrivacyInfo.xcprivacy` so a change to one shows up in review next to the other.
The two must agree (`docs/how-to/ios-testflight.md` §4).

Derived from the code on 2026-10-17. "Collected" in Apple's sense: sent off the device and readable by
us (or a partner) for longer than it takes to answer the request. End-to-end encrypted data the relay
cannot read is treated as not collected (House Rule 1) — ⚖️ that reading of Apple's definition is
the first thing to confirm, as for Control and Drive.

## Tracking

**No.** No advertising or analytics SDK, no IDFA, no App Tracking Transparency prompt, no
`identifierForVendor`. Source: `PrivacyInfo.xcprivacy` (`NSPrivacyTracking` false); no analytics or
crash-reporting dependency in `fit-ios` or `tenshu-sdk-ios-fit`.

## Data collected

**Data Not Collected** — what `PrivacyInfo.xcprivacy` declares, on the E2EE reading.

| Apple data type | Answer | Source |
|---|---|---|
| Health & Fitness → Health, Fitness (HealthKit import, recorded workouts, heart rate from a strap, steps) | not collected: read from HealthKit / the sensors on the device and synced only end-to-end encrypted | `fit-shared-data/.../HealthKitImporter.kt`, `IosRecordingEngine.kt`; House Rule 1 |
| Location → Precise Location (workout routes) | not collected: recorded only during a workout, synced end-to-end encrypted | `IosRecordingEngine.kt` |
| Everything else | not collected | `allowed_sensitive_for` |

## ⚖️ Open — answers that need a decision before they are entered

1. **The E2EE reading** above (as Control's and Drive's item).
2. **Metadata the relay sees** (sizes, device count, sync times — threat model A6, T-T-07) and
   **server access logs** — as Control's items 3 and 4.
3. **Account and email** are Control's (`store/control/privacy-label.md`); Fit has no sign-in UI.
4. ⚖️ **HealthKit rules** (App Review Guideline 5.1.3, the HealthKit terms): health data may not be
   used for advertising or disclosed to third parties, and may not be stored in iCloud. Fit stores it
   in the user's own end-to-end encrypted realm on Tenshu's relay. Counsel to confirm that is
   compatible, and that the privacy policy says it.
5. Fit writes recorded workouts back to Apple Health (`NSHealthUpdateUsageDescription`); that stays
   on the device.
