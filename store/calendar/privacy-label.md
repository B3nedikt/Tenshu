# Tenshu Calendar (iOS) — App Store privacy label answers

⚖️ **For counsel review before it is entered.** App Store Connect → App Privacy has no public API
(`store-presence-and-support-pages.md` §2.3), so these answers are typed into the console by hand;
this file is the reviewed source for that, and it sits beside
`calendar-ios/Resources/PrivacyInfo.xcprivacy` so a change to one shows up in review next to the other.
The two must agree (`docs/how-to/ios-testflight.md` §4).

Derived from the code on 2026-10-17. "Collected" in Apple's sense: sent off the device and readable by
us (or a partner) for longer than it takes to answer the request. End-to-end encrypted data the relay
cannot read is treated as not collected (House Rule 1) — ⚖️ that reading of Apple's definition is
the first thing to confirm, as for Control and Drive.

## Tracking

**No.** No advertising or analytics SDK, no IDFA, no App Tracking Transparency prompt, no
`identifierForVendor`. Source: `PrivacyInfo.xcprivacy` (`NSPrivacyTracking` false); no analytics or
crash-reporting dependency in `calendar-ios` or `tenshu-sdk-ios-calendar`.

## Data collected

**Data Not Collected** — what `PrivacyInfo.xcprivacy` declares.

| Apple data type | Answer | Source |
|---|---|---|
| User Content → Other User Content (events, calendars, reminders) | not collected: stored and synced end-to-end encrypted; Calendar does not read the system calendar (no EventKit) | House Rule 1; `allowed_sensitive_for` (AVFoundation only) |
| `.ics` import/export | on the device, through the system file picker; nothing leaves it unencrypted | `calendar-ios/Sources/CalendarInteropSheet.swift` |
| Everything else | not collected | |

Local reminders use `UserNotifications`; the permission is asked on the Calendars screen and owes no
purpose string.

## ⚖️ Open — answers that need a decision before they are entered

1. **The E2EE reading** above (as Control's and Drive's item).
2. **Metadata the relay sees** (sizes, device count, sync times — threat model A6, T-T-07) and
   **server access logs** — as Control's items 3 and 4.
3. **Account and email** are Control's (`store/control/privacy-label.md`); Calendar has no sign-in UI.
