# Tenshu Home (iOS) — App Store privacy label answers

⚖️ **For counsel review before it is entered.** App Store Connect → App Privacy has no public API
(`store-presence-and-support-pages.md` §2.3), so these answers are typed into the console by hand;
this file is the reviewed source for that, and it sits beside
`home-ios/Resources/PrivacyInfo.xcprivacy` so a change to one shows up in review next to the other.
The two must agree (`docs/how-to/ios-testflight.md` §4).

Derived from the code on 2026-10-17. "Collected" in Apple's sense: sent off the device and readable by
us (or a partner) for longer than it takes to answer the request. End-to-end encrypted data the relay
cannot read is treated as not collected (House Rule 1) — ⚖️ that reading of Apple's definition is
the first thing to confirm, as for Control and Drive.

## Tracking

**No.** No advertising or analytics SDK, no IDFA, no App Tracking Transparency prompt, no
`identifierForVendor`. Source: `PrivacyInfo.xcprivacy` (`NSPrivacyTracking` false); no analytics or
crash-reporting dependency in `home-ios` or `tenshu-sdk-ios-home`.

## Data collected

**Data Not Collected** — what `PrivacyInfo.xcprivacy` declares.

| Apple data type | Answer | Source |
|---|---|---|
| Everything | not collected: Home's data lives in the end-to-end encrypted realm; no HomeKit | House Rule 1; `allowed_sensitive_for` (AVFoundation only) |

⚠ Home is not usable by testers yet; re-derive this file when it gains features.

## ⚖️ Open — answers that need a decision before they are entered

1. **The E2EE reading** above (as Control's and Drive's item).
2. **Metadata the relay sees** (sizes, device count, sync times — threat model A6, T-T-07) and
   **server access logs** — as Control's items 3 and 4.
3. **Account and email** are Control's (`store/control/privacy-label.md`); Home has no sign-in UI.
