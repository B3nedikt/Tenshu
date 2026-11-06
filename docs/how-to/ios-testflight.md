# How-to: archive an iOS app and upload it to TestFlight

**Status (2026-10-17):** the pipeline exists for **all ten apps**. Control's first build,
`1.0 (202610141319)`, was uploaded to App Store Connect on 2026-10-14. Drive has no app record
yet, so its upload fails with *"App record with bundle identifier … not found"*. The other eight
(Photos, Maps, Timeline, Calendar, Contacts, Fit, Home, Mia) archived and exported a
signed `.ipa` on 2026-10-17, passing every check; none has been uploaded, and none has an App Store
Connect app record yet. Check each App ID against §7.1 before the first upload.
⚠ Home and Mia archive but are not usable by testers yet.

Development signing — getting a build onto your own phone — is a different document:
[`ios-signing.md`](ios-signing.md).
From an uploaded build to an App Store submission is
[`app-store-release.md`](app-store-release.md).

## 1. The command

```bash
scripts/ios-archive.sh control drive            # archive, export a signed .ipa, upload nothing
scripts/ios-archive.sh --upload control drive   # the same, then upload to App Store Connect
```

Output lands in `build/ios-archive/<Scheme>/`: the `.xcarchive`, `archive.log`, `export.log`, and
`export/<Scheme>.ipa`. The Kotlin link logs to `build/ios-archive/sdk.log`. A run takes about ten minutes for two apps, five of them the Kotlin link.

`--upload` refuses a tree with uncommitted changes, because an uploaded build has to name a commit.

## 2. Prerequisites

- **The company team** in `local.properties` (`tenshu.teamId=752LWAZMKZ`) and that Apple ID signed
  in to Xcode, or an App Store Connect API key in `ASC_KEY_ID` / `ASC_ISSUER_ID` / `ASC_KEY_PATH`.
- **Signing is automatic.** The archive is signed for development and the export re-signs it with
  the team's *Apple Distribution* certificate and an *iOS Team Store* profile, both of which Xcode
  creates on demand (`-allowProvisioningUpdates`). The first export on 2026-10-13 created them.
- **For `--upload` only:** an app record in App Store Connect for each bundle id
  (`app.tenshu.<app>.ios`). The script cannot create one.
- **The capabilities each App ID needs** (§7.1), registered in the developer portal. On 2026-10-17 automatic
  signing (`-allowProvisioningUpdates`) exported all eight without a manual registration, so it
  registered what each entitlement file asks for; check the portal rather than rely on that.

### 2.1 The API key, and how the upload runs

The key is the robust route: an Xcode Apple-ID session expires and wants two-factor, a key does not.
App Store Connect → Users and Access → Integrations → App Store Connect API → **Team Keys**,
role **Admin** (the export also creates certificates and profiles). Download the `.p8` once, keep it
at `~/.appstoreconnect/private_keys/AuthKey_<KEYID>.p8` (`chmod 600`; the script refuses another file
name, because altool finds the key by it), and export `ASC_KEY_ID=<KEYID>`,
`ASC_ISSUER_ID=<the UUID above the key list — not the team id>`, `ASC_KEY_PATH=<that file>`. A `!`
shell in an agent session started before you edited `~/.zshrc` does not see them: `source ~/.zshrc`
first. A new worktree has no `local.properties` — copy it, or the script stops with *no team*.

**With a key, `--upload` exports the `.ipa` and uploads it with `xcrun altool --upload-app`**
(log: `build/ios-archive/<Scheme>/upload.log`); without one it keeps xcodebuild's own upload over
the Xcode account. ⚠ The reason: on 2026-10-15, with Xcode 27.0, xcodebuild's upload failed at its
first request — the store configuration from `contentdelivery.itunes.apple.com` — with
`Cannot verify client's JWT headers (3004)` (in the `.xcdistributionlogs` bundle), surfacing as
`exportArchive Failed to Use Accounts` / `No Accounts with App Store Connect Access`. The same key
exported locally (the export only logs that failure) and altool uploaded with it at once. Pending
agreements were found and accepted in App Store Connect during that hunt, so if xcodebuild's upload
fails this way for the Apple ID too, check **Business → Agreements** and the developer account for
terms waiting for the Account Holder first.

## 3. What the build is

| | Where it is set | Value |
|---|---|---|
| Version (`CFBundleShortVersionString`) | `MARKETING_VERSION` in [`ios-release/release.yml`](../../ios-release/release.yml) | `1.0`, the same as Android's `versionName` |
| Build number (`CFBundleVersion`) | passed by the script | the UTC minute, `YYYYMMDDHHMM`, or `--build-number N` |
| Kotlin framework | `AppFramework` in `ios-release/release.yml` | the app's own **release** framework (`:tenshu-sdk-ios-control`, `:tenshu-sdk-ios-drive`) |
| Orientations | `ReleaseApp` in `ios-release/release.yml` | all four on iPad, three on iPhone |

**The build number.** App Store Connect refuses a number it has already seen for a version, so the
script never reuses one. A development build keeps the constant `1`.

**Each app: its own framework, one spec.** Control links `:tenshu-sdk-ios-control` and Drive
`:tenshu-sdk-ios-drive`, each a `TenshuSdk` built from that app's code and the shared core only
([`ios-per-app-frameworks.md`](../proposals/ios-per-app-frameworks.md) P2, P3). Each `project.yml`
applies `AppFramework`, which links the framework by search path and name in every configuration:
Debug links the debug framework, Release the release one. There is no `project.archive.yml`; the
script archives `<Scheme>.xcodeproj`. ⚠ A Release build from Xcode therefore links the release
framework too — several minutes the first time.

⚠ **After switching an existing checkout to this, clean the app's derived data once.** Measured
2026-10-14 for Control: the first incremental build after the switch kept the old umbrella link in
`ControlIos.debug.dylib` (Fit's and Drive's classes and all five sensitive frameworks were still
in it); the next build relinked. A fresh derived data never shows it. Drive's switch (P3) is the
same change, and so are P4's eight.

**All ten apps can be archived** (since 2026-10-17): each applies `ReleaseApp` and `AppFramework`,
ships a `PrivacyInfo.xcprivacy`, and has a `spec_for` entry and an allowlist in
`scripts/ios-archive.sh`, taken from its first archive (`ios-per-app-frameworks.md` §10.5).

⚠⚠ **Archive with the script.** It links the release framework in a large Gradle heap first and
runs the checks below, which a Product → Archive in Xcode does not.

**The linked-framework check** ([D7](../proposals/ios-per-app-frameworks.md)). After archiving, the
script runs `otool -L` on the executable and fails if it loads a privacy-sensitive system framework
(HealthKit, CoreLocation, CoreBluetooth, CoreMotion, Photos, Contacts, EventKit, …) that is not on
that app's allowlist in `allowed_sensitive_for`. That turns "Control links HealthKit" into a local
failure instead of an ITMS-90683 email. ⚠ It is an allowlist, not attribution: it cannot tell
Kotlin's auto-link hints from the app's own Swift imports, only that the set did not grow. The
found set is written to `build/ios-archive/<Scheme>/linked-frameworks.txt`.

⚠ **The release link needs a larger Gradle heap than the repo default.** Linking both slices at
`-Xmx5g` fails with `Java heap space`. [`scripts/ios-build-sdk.sh`](../../scripts/ios-build-sdk.sh)
therefore links one slice and asks for `-Xmx10g`, for that invocation only.

## 4. The privacy manifest

Each app ships `Resources/PrivacyInfo.xcprivacy`. The accessed-API list is an audit of the linked
binary, not of the Swift sources — most of the calls come from the shared Kotlin framework:

| Category | Reason | What calls it |
|---|---|---|
| UserDefaults | `CA92.1` | the app's own device-local preferences |
| File timestamp | `C617.1`, `3B52.1` | the local store (`stat`, `fstat`, `lstat`); the modification date of a file picked for upload |
| Disk space | `E174.1` | the local store checks free space before writing (`statfs`, `fstatfs`) |
| System boot time | `35F9.1` | `systemUptime`, for elapsed time |

To repeat the audit after the framework changes, on an exported app binary:

```bash
nm -u Payload/<Scheme>.app/<Scheme> | grep -E '_(l?stat|fstat|statfs|fstatfs|getattrlist|mach_absolute_time)'
strings -a Payload/<Scheme>.app/<Scheme> | grep -E '^(systemUptime|standardUserDefaults|activeInputModes)$'
```

⚠ **Not every app has all four.** Measured on the eight first archives (2026-10-17): UserDefaults
only in Maps, Timeline, Fit and Mia; boot time only in Photos and Fit (Photos also declares disk
space `85F4.1`, because a model download that does not fit tells the user the free space). Each
manifest lists what its own executable calls; an embedded `MapLibre.framework` carries its own.

⚠ **The collected-data section is a declaration, and it is the maintainer's to confirm.** Control
declares the account email address (sign-in and billing); Drive declares nothing, on the ground
that what it sends is end-to-end encrypted, and so do Photos, Maps, Calendar, Contacts, Fit, Home and
Mia. ⚖️ **Timeline declares Precise Location** (not linked, App Functionality): its place-name
picker and transit-stop fallback send a coordinate to komoot's public Photon geocoder. Each app's
answers and open ⚖️ items are in `store/<app>/privacy-label.md`. The same answers are owed again in App Store Connect's
privacy questionnaire, and the two have to agree.

## 5. What the script checks

After the archive, on the product itself: the bundle id, the build number, that
`PrivacyInfo.xcprivacy` is in the bundle, and that the build log names the release framework and
never processed the debug one. A failed check stops that app before export.

## 6. Not verified, and not decided

- **Upload is proven for Control only.** What App Store Connect reports after processing (warnings
  by email) has not been read back into this document.
- ⚠⚠ **The release build has never run on a phone.** The Kotlin code in it is optimised
  differently from every build tested so far, and Kotlin/Native has miscompiled this codebase
  before (`tech-debt.md`). Install the first TestFlight build and walk through it before inviting
  anyone.
- **Export compliance.** Every app's `project.yml` sets `ITSAppUsesNonExemptEncryption: true`:
  libsodium ships standard algorithms beside Apple's crypto, so the answer is "Standard encryption
  algorithms", never "None". France is covered by ANSSI dossier 34767729 (all ten apps).
  ⚠ A new algorithm or key length owes that declaration an update.
- **Push.** No app carries an `aps-environment` entitlement; `sync-push-wake.md` says what changes
  when one does.
- **The store link to Control.** `TENSHU_CONTROL_APP_STORE_ID` is empty, so Drive's "get Control"
  screen says Control is not on the App Store yet. A TestFlight tester needs Control from
  TestFlight as well.

## 7. Setting up another app

All ten are set up; this is the recipe for an eleventh, and §7.1 is what each existing App ID needs.

1. Give the app its own framework first (`ios-per-app-frameworks.md` P3/P4: a
   `:tenshu-sdk-ios-<app>` module, its case in `scripts/ios-build-sdk.sh`). Then in its
   `project.yml`: include `../ios-release/release.yml`, add `ReleaseApp` and `AppFramework` to the
   target's templates, set `TENSHU_SDK_MODULE`, and make the prebuild phase run
   `scripts/ios-build-sdk.sh <app>` — copy control-ios or drive-ios. An app without its own
   framework cannot be archived (§3).
2. Give it an allowlist in `allowed_sensitive_for` in `scripts/ios-archive.sh`: the sensitive
   frameworks it really uses (`ios-per-app-frameworks.md` §4).
3. Write its `Resources/PrivacyInfo.xcprivacy`. ⚠ Do not copy the collected-data section: Fit
   reads health data and Timeline records location, and both have entitlements
   (`healthkit`, `wifi-info`) that Control and Drive do not.
4. Add the app to `spec_for` in `scripts/ios-archive.sh` and run it without `--upload`.

⚠ **The display name must not be a system app's name.** `CFBundleDisplayName` "Photos" was rejected
on 2026-10-17 with ITMS-90129 ("bundle name or display name that is already taken"): Apple reserves
the names of its own apps (Photos, Maps, Calendar, Contacts, Home, …). Every app is therefore
"Tenshu <App>". The home screen truncates after ~12–13 characters ("Tenshu Calen…"); the store shows
the full name.

### 7.1 Capabilities each App ID needs

Register these on the App ID (`app.tenshu.<app>.ios`, team 752LWAZMKZ) in the developer portal
before the first export, or the App Store profile will not cover the entitlements and the export
fails. Read from each app's `*.device.entitlements` and `project.yml` on 2026-10-17. Background
modes and purpose strings are `Info.plist` keys and need no registration; they are listed so review
can check them against the app.

| App | App ID capabilities (entitlements) | Background modes (`Info.plist`) | Purpose strings |
|---|---|---|---|
| Control | App Groups (`group.app.tenshu.shared`); Keychain sharing needs no registration | `fetch` | Camera, Face ID |
| Drive | App Groups | `fetch` | Camera, Photo Library |
| Photos | App Groups | — | — |
| Maps | App Groups | — | Location When In Use, Location Always (MapLibre's code; Maps uses none) |
| Timeline | App Groups. ⚠ **Access Wi-Fi Information** only once `com.apple.developer.networking.wifi-info` is uncommented in `Timeline.device.entitlements` — today it is not, so Wi-Fi place recognition is off | — (visit / significant-change monitoring relaunch the app) | Location When In Use, Location Always |
| Calendar | App Groups | — | — (reminders use notifications, which need none) |
| Contacts | App Groups | — | — |
| Fit | App Groups, **HealthKit** | `location`, `fetch` | Health Share, Health Update, Location When In Use, Location Always (MapLibre's code; Fit asks for When In Use only), Motion, Bluetooth Always |
| Home | App Groups | — | — |
| Mia | App Groups | — | — |

Every app's `keychain-access-groups` (its own group and `app.tenshu.shared`, Team-prefixed) is
covered by the App ID's prefix and needs nothing registered. No app has Push Notifications, iCloud,
Associated Domains or Sign in with Apple.
