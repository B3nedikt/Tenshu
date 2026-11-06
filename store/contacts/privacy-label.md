# Tenshu Contacts (iOS) — App Store privacy label answers

⚖️ **For counsel review before it is entered.** App Store Connect → App Privacy has no public API
(`store-presence-and-support-pages.md` §2.3), so these answers are typed into the console by hand;
this file is the reviewed source for that, and it sits beside
`contacts-ios/Resources/PrivacyInfo.xcprivacy` so a change to one shows up in review next to the other.
The two must agree (`docs/how-to/ios-testflight.md` §4).

Derived from the code on 2026-10-17. "Collected" in Apple's sense: sent off the device and readable by
us (or a partner) for longer than it takes to answer the request. End-to-end encrypted data the relay
cannot read is treated as not collected (House Rule 1) — ⚖️ that reading of Apple's definition is
the first thing to confirm, as for Control and Drive.

## Tracking

**No.** No advertising or analytics SDK, no IDFA, no App Tracking Transparency prompt, no
`identifierForVendor`. Source: `PrivacyInfo.xcprivacy` (`NSPrivacyTracking` false); no analytics or
crash-reporting dependency in `contacts-ios` or `tenshu-sdk-ios-contacts`.

## Data collected

**Data Not Collected** — what `PrivacyInfo.xcprivacy` declares.

| Apple data type | Answer | Source |
|---|---|---|
| Contact Info / Contacts (the user's address book in Tenshu) | not collected: stored and synced end-to-end encrypted; Contacts does not read the system address book (no Contacts framework) | House Rule 1; `allowed_sensitive_for` (AVFoundation only) |
| Everything else | not collected | |

## ⚖️ Open — answers that need a decision before they are entered

1. **The E2EE reading** above (as Control's and Drive's item).
2. **Metadata the relay sees** (sizes, device count, sync times — threat model A6, T-T-07) and
   **server access logs** — as Control's items 3 and 4.
3. **Account and email** are Control's (`store/control/privacy-label.md`); Contacts has no sign-in UI.
4. **Invitations.** The contact-invitation handshake passes a sealed box through a relay mailbox;
   the relay never holds `K_link` (`contact-invitation-handshake.md`, threat model A24/A25). Our
   reading: not collected. ⚖️ Confirm that the mailbox's metadata (which realm exchanged with which,
   and when) is covered by item 2.
