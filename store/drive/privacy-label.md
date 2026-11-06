# Tenshu Drive (iOS) — App Store privacy label answers

⚖️ **For counsel review before it is entered.** App Store Connect → App Privacy has no public API
(design §2.3), so these answers are typed into the console by hand; this file is the reviewed source
for that, and it sits beside `drive-ios/Resources/PrivacyInfo.xcprivacy` so a change to one shows up
in review next to the other. The two must agree (`docs/how-to/ios-testflight.md` §4).

Derived from the code on 2026-10-15. "Collected" in Apple's sense: sent off the device and readable by
us (or a partner) for longer than it takes to answer the request. End-to-end encrypted data the relay
cannot read is treated as not collected (House Rule 1) — ⚖️ that reading of Apple's definition is
the first thing to confirm.

## Tracking

**No.** No advertising or analytics SDK, no IDFA, no App Tracking Transparency prompt, no
`identifierForVendor`.
Source: `PrivacyInfo.xcprivacy` (`NSPrivacyTracking` false); no analytics or crash-reporting
dependency in `drive-ios` or the `tenshu-sdk-ios*` modules.

## Data collected

**Data Not Collected** — what `PrivacyInfo.xcprivacy` declares today (an empty
`NSPrivacyCollectedDataTypes`) — **unless counsel answers item 1 below with yes.**

| Apple data type | Answer | Source |
|---|---|---|
| User Content → Photos or Videos, Other User Content (files, file and folder names) | not collected: encrypted on the device before upload, the relay holds ciphertext | House Rule 1; threat model I1, A5, A6; photo backup uploads sealed blobs only (`BackgroundUploadManager.kt`) |
| Contact Info, Identifiers | not collected *by Drive*: the account and its email are Control's (`store/control/privacy-label.md`). Drive has no sign-in UI | `drive-ios/Sources` (no commerce UI) |
| Diagnostics | not collected: no crash reporter, `:diagnostics` is device-local | `diagnostics/.../LogItem.kt` |
| Location, Contacts, Health, Financial, Purchases, Browsing, Search, Sensitive Info, Usage Data | not collected | no such framework linked (`scripts/ios-archive.sh` `allowed_sensitive_for`) |

The photo library (`NSPhotoLibraryUsageDescription`) and camera (`NSCameraUsageDescription`,
`drive-ios/project.yml`) are read on the device; what leaves it is encrypted.

## ⚖️ Open — answers that need a decision before they are entered

1. **Share links store a file's name, type and size readable by us.** When the user creates a share
   link, the relay's `share_record` keeps `file_name`, `mime_type` and `total_size` in plaintext
   (`sync-relay-server/.../storage/RelayDatabase.kt`; `docs/file-sharing.md` §4; threat model T-S-02
   and the I1 carve-out). Only the content is encrypted, under the key in the link. This is a
   deliberate, user-initiated publication, and the record is tied to the realm. ⚖️ If counsel reads it
   as "Other User Content, linked, App Functionality", **both this label and Drive's
   `PrivacyInfo.xcprivacy` change** — today the manifest says Drive collects nothing.
2. **Entitlement token.** Drive's runtime can fetch a relay entitlement token from commerce when a
   commerce session exists on the device (`DriveLocalRuntime.kt`, `PlatformProxyModule.kt` iosMain);
   otherwise it uses the synced entitlement record. It sends no new data type, but whether Drive ever
   holds that session on iOS was not determined.
3. **Metadata the relay sees** (sizes, device count, sync times — threat model A6, T-T-07) and
   **server access logs** — as Control's items 3 and 4.
