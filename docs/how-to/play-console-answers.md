# Play Console answers — the ten Android apps, task by task

**Status:** answer sheet, nothing entered or uploaded (2026-10-23). Derived from the code at
`origin/master` `ef2cd74e6` and the **release** merged manifests
(`./gradlew :<module>:processReleaseManifest`, read from
`<module>/build/intermediates/merged_manifests/release/`), not from the source manifests alone —
libraries add permissions the source never names (Drive's `INTERNET` until §13 D3, §2).

Open the Console at *Policy and programs → App content* (the "Set up your app" tasks on the
dashboard are the same list) and follow the app's section top to bottom. Each answer is a short
checklist in the Console's order. ⚖️ marks a judgement or legal call that the maintainer (or
counsel) makes, not a fact read from code. The **justification copy** for the permission forms is in
[`play-store-declarations.md`](play-store-declarations.md); this file says which form each app owes
and what to click.

> ⚠⚠ Re-check against the live Console when entering. The questions move; the answers here are
> facts about our code plus the judgements marked ⚖️, not a snapshot of Play policy, and not legal
> advice.

**Contents:** §0 answers shared by every app · §1–§10 one per app (Control first — every other app
needs it, and `tenshu.control.playUrl` waits on its listing) · §11 Data safety, per app · §12
uploading Data safety from the repo · §13 what the maintainer must decide.

---

## 0. The same for every app

### 0.1 Privacy policy

- URL: **`https://tenshu.app/legal/privacy`** (store/shared/en-US/privacy_url.txt) — the
  language-neutral URL: Caddy 302s it to `/legal/privacy/de`, `/fr` or `/en` by the browser's
  language. Enter it only once it resolves on production (Play rejects a dead link), and it must be
  the same URL for all ten apps. ⚖️ The policy must name everything §11 declares (email, purchase
  history, the geocoders, Play Billing as the purchase processor) — check that before submitting
  any app. No SDK telemetry reaches Google (§11.0: no datatransport upload, and Control calls no
  Google Play services API), so the policy names none.

### 0.2 Ads

- **"Does your app contain ads?" → No.**
- Evidence: no ad or analytics SDK in any app's release runtime classpath (checked
  `./gradlew :control:dependencies --configuration releaseRuntimeClasspath`, the widest graph: the
  only Google artefacts are Play Billing, MediaPipe, their shared `datatransport`/`firebase-encoders`
  logging plumbing — whose upload backend Control strips, §11.0 — and
  `play-services-base/-location/-tasks`, which Control never calls (§11.0). ML Kit is gone — the
  QR scanner is ZXing). No release manifest declares `com.google.android.gms.permission.AD_ID`.

### 0.3 Advertising ID (shown under App content when the target SDK is 33+)

- **"Does your app use advertising ID?" → No.** No `AD_ID` permission in any of the ten release
  manifests (`grep -c AD_ID` = 0 each).

### 0.4 Government apps

- **No** (all ten).

### 0.5 News apps

- **"Is your app a news app?" → No** (all ten).

### 0.6 Financial features

- **"My app doesn't provide any financial features"** (all ten). Control sells storage plans
  (§1), which is a digital-goods purchase through Play Billing, not a financial feature in this
  form's sense (banking, loans, payments between people, crypto, insurance).

### 0.7 Health apps declaration

- **Fit:** declare it — §7.
- **Every other app: "My app does not have any health features."** Timeline's activity recognition
  only tells walking from cycling for a trip; it is not a health feature.

### 0.8 Content rating (IARC) — the parts that are the same

- Email for IARC: **support@tenshu.app**.
- **Category: "All Other App Types"** (not Game, not Social/Communication, not
  Reference/News/Educational). ⚖️ Contacts could be argued as Communication; it has no messaging,
  so "All Other App Types" is the better fit.
- Answer **No** to every content question for every app: violence (cartoon, fantasy, realistic,
  blood, gore), fear/horror, sexuality and nudity, crude humour, profanity/language, controlled
  substances (drugs, alcohol, tobacco), gambling and simulated gambling, discrimination or hate.
  None of the apps ships any content of its own; everything shown is the user's own data.
- The interaction questions differ per app and are answered in each section:
  **users interact / share content**, **shares the user's current location with other users**,
  **digital purchases**, **unrestricted internet / web browser**.
- Expected outcome: the lowest rating in every region (IARC 3+, PEGI 3, ESRB Everyone), with an
  "In-App Purchases" notice on Control and "Users Interact" on Drive and Contacts.

### 0.9 Target audience and content

- **Age groups: "16–17" and "18 and over"**, for all ten — ✅ maintainer decision 2026-10-24 (an
  earlier draft said 18+ only; 13+ was considered). This field declares who the apps are *designed
  for*; it does not itself block installs — the content rating does that only through parental
  controls (Family Link, device restrictions), and the expected IARC outcome is PEGI 3 / ESRB
  Everyone (§0.8). Reasoning:
  - **16 is the age the published privacy notice states** (`/legal/privacy`), and the policy's
    minimum age (`docs/policy/acceptable-use-and-limits.md` §7).
  - It is valid for the consent-based features (Timeline's opt-in place-name lookup, marketing
    consent) in every EU country: GDPR Art. 8's age of digital consent is 13–16 per country.
  - Leaving out every band under 13 keeps the apps outside the **Families policy**. For Play policy,
    with no ads, the teen bands' ad rules change nothing; outside Play, a teen audience still brings
    regimes such as the UK Age Appropriate Design Code into view (⚖️ counsel).
  - ⚠ Revisit before child accounts or anything designed for children; a family plan for adults
    sharing storage does not change this.
- **"Could your store listing unintentionally appeal to children?" → No.** Plain UI, no
  characters, no games.

### 0.10 App access — the common part

Every app except Control **cannot open without Tenshu Control** set up on the same phone (it binds
Control over Binder; see each manifest's `<queries>` for `app.tenshu.control.android`). So:

- Choose **"All or some functionality in my app is restricted"**, and add one instruction set
  named *"Set up Tenshu Control first"*. Fill its **username / password** fields with the review
  sign-in (D1, §14): username `<address from Keychain commerce-review-address-google>` (shaped
  `google-review-<suffix>@tenshu.app`), password `<code from Keychain commerce-review-signin-code-google>`.
  ⚠⚠ Both go **only** into the Play Console fields, never into this file, a commit or `store/` — the
  repository is public, and the address is unpublished on purpose (T-CB-12).
- Every section below gives the text to paste. Common opening, adapted from
  `store/<app>/review/notes.txt` (which is written for App Store review; the Android wording
  differs in places):

  > This app needs Tenshu Control (app.tenshu.control.android, on Google Play) installed and set up
  > on the same phone first. Control holds the encryption keys; this app cannot open without it.
  > 1. Install Tenshu Control and open it. Tap "Start fresh on this device", then "Continue".
  > Signing in is optional: everything on one phone works without an account. The sign-in details
  > provided with this submission are for trying sync and buying storage: in Control, "Turn on sync",
  > enter the email address given as the username, and when Control asks for the code from the
  > email, enter the password instead (no email is sent to that address). Buying a storage plan
  > needs that free sign-in first.

- ⚠ The Control listing must be **live (at least on the track the reviewer uses)** before a
  client is reviewed, or the reviewer cannot follow step 1. Submit Control first and wait for it.
- Sync is optional and needs a sign-in (§1); the review sign-in above covers it.

### 0.11 App category and contact details

- Contact details: email **support@tenshu.app**, website **https://tenshu.app** (already set). No
  phone number.
- Category per app below. Tags: Play lets you pick up to five from its list; they are suggestions,
  ⚖️ choose by what the Console offers.

---

## 1. Tenshu Control — `app.tenshu.control.android` (`:control`)

What it is: the hub. Creates the realm, holds keys, pairs devices, runs the end-to-end encrypted
sync through `relay.tenshu.app`, and is the **only** app with network access besides Drive (§2) — every other app's network
traffic (map tiles, calendar subscriptions, geocoding, model downloads) goes through Control. Signs in to `commerce.service.tenshu.app` (email + one-time code)
and sells storage plans with **Play Billing** (`control-shared/src/androidMain/.../billing/PlayStoreBilling.kt`;
the Play rail is the default, `control-shared/build.gradle.kts` `tenshu.android.billingRail`
defaults to `play`).

Release permissions: `INTERNET`, `ACCESS_NETWORK_STATE`, `CAMERA`, `FOREGROUND_SERVICE`,
`FOREGROUND_SERVICE_DATA_SYNC`, `POST_NOTIFICATIONS`, `SCHEDULE_EXACT_ALARM`, `USE_BIOMETRIC`,
`USE_FINGERPRINT`, `com.android.vending.BILLING`, `WAKE_LOCK`, `RECEIVE_BOOT_COMPLETED`.

**App access**
- [ ] "All or some functionality is restricted". Instructions (name: *"Sign-in optional; review
  sign-in provided"*), with the **username / password** fields set as in §0.10 —
  `<address from Keychain commerce-review-address-google>` /
  `<code from Keychain commerce-review-signin-code-google>`, entered in the Console only:
  > Control needs no account and no sign-in on one phone. Open Control, tap "Start fresh on this
  > device", then "Continue". Home, Settings, Synchronization & Devices and Services are then
  > available. Signing in is optional; the sign-in details provided with this submission are for
  > trying sync and buying storage. Tap "Turn on sync", enter the email address given as the
  > username, and when Control asks for the code from the email, enter the password instead (no
  > email is sent to that address). The account starts on the free plan (5 GB). Buying a storage
  > plan (Services) needs that free sign-in first, and then goes through Google Play Billing.
  > Deleting the account (Services → Delete account) also asks for a code: enter the same password.
  > Adding a second device needs a second device with Control: one shows a QR code, the other scans
  > it. All data is end-to-end encrypted on the device before it reaches our servers.
- **Reviewer account — decided (§13 D1):** an unpublished review address for Google, whose own fixed
  code replaces the emailed one (`commerce-accounts-and-sessions.md` D13, threat model T-CB-12). The
  code is six digits, so it fits Control's existing code field. It stays on: Google re-reviews live
  apps and expects these credentials to keep working. Generating, deploying and rotating it: §14.
- [ ] Purchases: nothing to provide — a plan is bought through Play Billing like any app's.

**Ads** — No (§0.2).

**Content rating** (§0.8 plus):
- [ ] Users interact or exchange content: **No.** Pairing and sync are between the user's own
  devices. (Control lists and revokes the share links Drive created; the sharing itself is Drive's.)
- [ ] Shares current location with other users: **No.**
- [ ] Digital purchases: **Yes** — storage plans (subscriptions) through Play Billing.
- [ ] Unrestricted internet / browser: **No.**

**Target audience** — 16+ (§0.9).

**News / Government / Financial / Health** — No / No / none / none.

**Permission declarations owed**
- [ ] **Foreground service — `dataSync`** (`control/src/main/AndroidManifest.xml`: `ProxyService`,
  promoted **only for user-started transfers** — an upload the user started in a client app (a folder
  upload, *Back up now*), an import the user started (Timeline's or Fit's Takeout import, a manual
  Fit import), a map-region or model download started from a screen — and
  `HostPairingForegroundService`, which keeps the pairing key resident during a pairing session). The form asks, per type: what the feature
  is, the user impact if the system defers or interrupts it, a **video link**, and a use case from
  Play's list (`support.google.com/googleplay/android-developer/answer/13392821`).
  - Use case: **"Network transfer: Upload or download"** (the user starts it by uploading a file or
    folder in a client app, or by downloading a map region or a model in Control). Pairing: ⚖️ "Network transfer: Upload or download" or a
    custom use case ("keep a device-pairing session alive while the user pairs a device").
  - Impact: an upload or download the user started stops half-way when Control is backgrounded;
    a pairing session loses its key and must be restarted.
  - Video: the user uploads a file in Drive → the Control sync notification appears → the
    transfer completes with Drive in the background; and a pairing session started from
    Settings → Synchronization & Devices → +.
  - ⚠ Play's rule for this type is **user-initiated** work; its video "cannot show fully automatic
    or background backup or recovery". Show the user-started path. Copy:
    `play-store-declarations.md` §3.
  - ⚠⚠ **User-started only (§13 D7, `control-background-work-off-fgs.md`).** A client marks a
    task user-started (`userInitiatedWork { }`) and `ProxyService` promotes only for those
    (`foregroundLivenessCount`). Automatic work never holds it: the sync push and an automatic
    backup's uploads run under Control's push job (`SyncPushWorker`), the maintenance and scheduled
    index passes under their own WorkManager jobs (§5.6 there), and while a client app is open its
    binding keeps Control alive without any service. Android 15's 6 h limit is handled (`onTimeout`).
- [ ] **Foreground service — `mediaProcessing`** (API 35+; `dataSync` below 35), on WorkManager's
  `SystemForegroundService` (`FOREGROUND_SERVICE_MEDIA_PROCESSING`): **user-started photo indexing**,
  *Index now* in Settings → Models (`control-background-work-off-fgs.md` §5.6.1).
  - Use case: **"Media processing"**. The user taps *Index now*, and Control analyses their photos on
    the device so they can search them by description and group people. Nothing leaves the device.
  - Impact: deferred or interrupted, the photos the user just asked to make searchable are not
    searchable; the scheduled pass catches up only when the device is idle and charging.
  - Notification: *"Indexing photos — 1,240 of 8,500"* with a progress bar and a **Stop** action.
  - Video: Settings → Models → *Index now*, leave the app, show the notification counting, tap Stop.
- [ ] `SCHEDULE_EXACT_ALARM`: no form (the gated one is `USE_EXACT_ALARM`, not declared). Used for
  Home's routines (`control/src/main/AndroidManifest.xml` comment, RE-D5).
- [ ] `CAMERA`: no form; QR scanning for pairing only (ZXing, on-device).

**Data safety** — §11.1 — email, user IDs, purchase history, device IDs; deletion URL `https://tenshu.app/account/delete` (D2, since 2026-10-23). CSV: `store/<app>/play/data-safety.csv` (§12).

**Category**: **Tools**. Tags: e.g. *Security*, *Cloud storage*, *Sync*. ⚖️

---

## 2. Tenshu Drive — `app.tenshu.drive.android` (`:drive-android`)

Release permissions: `CAMERA`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`,
`POST_NOTIFICATIONS`, `READ_MEDIA_IMAGES`, `READ_MEDIA_VIDEO`, `READ_MEDIA_VISUAL_USER_SELECTED`,
`READ_EXTERNAL_STORAGE` (maxSdk 32), `ACCESS_MEDIA_LOCATION`, `ACCESS_NETWORK_STATE`, `WAKE_LOCK`,
`RECEIVE_BOOT_COMPLETED`. `INTERNET` stripped.

⚠ **No `INTERNET`, and it must stay that way.** Drive's release build used to hold it: the only
source was `com.google.android.datatransport:transport-backend-cct`, ML Kit's telemetry transport
behind `:ui-qr-scanner`. §13 D3 strips it with `tools:node="remove"` like the other clients, and
the scanner is ZXing (no network, no Google artefact). Drive's own code
never opens a socket; all its traffic goes through Control. ⚠⚠
`scripts/lib/check_client_no_internet.py` (run by the Android smoke gate and
`scripts/android-release.sh`) fails when any client but Control has `INTERNET` in its release
merged manifest (§13 D3).
⚠ Drive's background backup (`BackupWorker`, a WorkManager job with a `CONNECTED`/`UNMETERED`
constraint) had only ever run with `INTERNET` present. It was checked without it on an API 37
emulator (2026-10-23): JobScheduler reports `CONNECTIVITY` satisfied for Drive's UID, and a
photo added while Drive's process was frozen was ingested into Control by the content-trigger
job. **API 34–36 not verified** (no local image) — check one before relying on it there.

**App access** — restricted; instructions (§0.10 opening, then):
> 2. Open Tenshu Drive. It finds Control on its own and shows an empty file list.
> 3. Tap "+" to upload a file or photo, or to create a folder. Tap a file to open it; its actions
> include Rename, Move and Share link.
> 4. Backing up phone folders: the Synced Folders screen. Without "Turn on sync" in Control, files
> stay on the phone and Drive shows that it is not syncing; that is expected. Sharing by link
> needs sync to be on.

**Ads** — No.

**Content rating** (§0.8 plus):
- [ ] Users interact or exchange content: **Yes** — a share link lets anyone with the link view a
  file, and Manage access shares with a contact (`drive-shared-ui/.../access/`). No chat.
- [ ] Shares current location with other users: **No.** (A shared photo keeps its own EXIF location,
  `ACCESS_MEDIA_LOCATION`; that is not the user's current location.) ⚖️
- [ ] Digital purchases: **No** (plans are bought in Control).
- [ ] Unrestricted internet: **No.**

**Target audience** — 16+ (§0.9). **News / Gov / Financial / Health** — No / No / none / none.

**Permission declarations owed**
- [ ] **Photo and video permissions** (`READ_MEDIA_IMAGES`/`READ_MEDIA_VIDEO`): declare core use =
  continuous folder backup. Copy: `play-store-declarations.md` §5. The picker
  (`READ_MEDIA_VISUAL_USER_SELECTED`) covers one-off uploads.
- [ ] **Foreground service — `dataSync`**, two services:
  - `FolderUploadForegroundService` — the user picks a folder to upload. Use case **"Network
    transfer: Upload or download"**; video: "+" → upload a folder → the progress notification while
    Drive is backgrounded. Fits the policy.
  - `SyncedFolderForegroundService` — Synced Folders **"Back up now"** only: started from the
    user's tap (`SyncedFolderServiceLauncher` keys on `BackupState.BackingUp.userInitiated`), for that
    run. Use case **"Network transfer: Upload or download"**; video: Settings → Synced folders →
    **Back up now** → the progress notification while Drive is backgrounded. Fits the policy.
    ⚠⚠ **Automatic backup starts no *Drive* foreground service** (§13 D7): new photos
    are handed to Control by WorkManager jobs (`BackupWorker` — a MediaStore content-URI trigger, a
    periodic floor and continuations), so Drive's declaration and video show only the user-started
    path. The upload itself runs in Control, which holds its `dataSync` service only for the
    user-started run and pushes an automatic run's uploads under its own job — see Control's
    declaration above and D7. Copy: §3 there.
- [ ] `CAMERA`: no form; QR scanning for contact invites only.

**Data safety** — §11.2 — share-link file metadata only. CSV: `store/<app>/play/data-safety.csv` (§12).

**Category**: **Productivity**. Tags: e.g. *File manager*, *Cloud storage*, *Backup*. ⚖️

---

## 3. Tenshu Maps — `app.tenshu.maps.android` (`:maps-android`)

Release permissions: `ACCESS_NETWORK_STATE` only. `INTERNET`, `ACCESS_FINE_LOCATION` and
`ACCESS_COARSE_LOCATION` are stripped (`tools:node="remove"`, `maps-android/src/main/AndroidManifest.xml`).
Tiles are fetched by Control from `tiles.service.tenshu.app`.

**App access** — restricted; instructions:
> 2. Open Tenshu Maps. It lists your places; places come from Tenshu Timeline, so on a new phone the
> lists are empty, which is expected. Open a place to see it on the map, or the menu for "Offline
> maps" to download a region.
> Maps never uses your location and never asks for it.

**Content rating**: interact **No**; location sharing **No**; purchases **No**; internet **No**.

**Target audience** 16+ (§0.9). **News / Gov / Financial / Health**: No / No / none / none.

**Permission declarations**: none.

**Data safety** — §11.3 — no data collected. CSV: `store/<app>/play/data-safety.csv` (§12).

**Category**: **Maps & Navigation**. ⚖️

---

## 4. Tenshu Timeline — `app.tenshu.timeline.android` (`:timeline-android`)

Release permissions: `ACCESS_FINE_LOCATION`, **`ACCESS_BACKGROUND_LOCATION`**, `ACCESS_WIFI_STATE`,
`ACTIVITY_RECOGNITION`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_LOCATION`, `POST_NOTIFICATIONS`,
`RECEIVE_BOOT_COMPLETED`, **`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`**, `ACCESS_NETWORK_STATE`,
`WAKE_LOCK`. `INTERNET` stripped.

**App access** — restricted; instructions:
> 2. Open Tenshu Timeline. On a new phone it is empty: nothing has been recorded yet.
> 3. Open Settings and turn on "Capture my timeline". Timeline explains why, then asks for location
> and physical activity access, and then — in a separate step — for "Allow all the time".
> 4. Carry the phone: a place appears after you stay somewhere and move on; trips appear between
> places. While it records, Android shows the "Timeline is on" notification.
> 5. "Look up place names" and "Recognise places by Wi-Fi" are off by default.
> To see data without travelling, Settings → import a Google Takeout location-history export.

**Content rating**: interact **No**; shares location with other users **No** (the timeline syncs to
the user's own devices only); purchases **No**; internet **No**.

**Target audience** 16+ (§0.9). **News / Gov / Financial / Health**: No / No / none / none.

**Permission declarations owed**
- [ ] **Location permissions → background location** (`ACCESS_BACKGROUND_LOCATION`). Copy:
  `play-store-declarations.md` §1. Answers:
  - Feature: *Capture my timeline* — continuous recording of places visited and trips.
  - Why background: recording continues with the app closed, and must restart itself after the
    system kills it (`timeline-android/.../MainActivity.kt` `maybeAskForBackgroundLocation` KDoc:
    a background-started FGS is refused the location type without it, measured on Android 16).
  - **Demo video** (≤ 30 s is enough; YouTube unlisted or Drive link) must show, in one take on a
    release build:
    1. the in-app disclosure *before* the system dialog: Settings → "Capture my timeline" on →
       the "Before Timeline starts recording" rationale → system location dialog;
    2. the separate background step: the "Keep recording after the app closes" dialog → Continue →
       the system settings page with **Allow all the time**;
    3. the feature working: the "Timeline is on" notification, the app closed, then the feed with a
       recorded place/trip. The video must show the user-visible feature, not only settings.
  - **Prominent disclosure** (§13 D4, done 2026-10-31). Play's disclosure must say the app collects
    location **"even when the app is closed or not in use"** and for what. Both dialogs now say it,
    in EN and DE: the rationale before the location prompt (`capture_permission_reason_location`)
    and the background dialog before "Allow all the time" (`capture_background_message`,
    `timeline-android/src/main/res/values*/strings.xml`). ⚠ Keep that phrase in both if either text
    is reworded; the demo video must show it.
- [ ] **Foreground service — `location`**. Copy: §3 there. Video: the same recording, showing the
  "Timeline is on" notification.
- [ ] `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`: Play has no Console form, but the policy allows it
  only for core functions broken by Doze; keep the §2 justification ready for review questions.
  Asked from the app's own settings (`timeline-android/.../BatteryOptimization.kt`).
- [ ] `ACTIVITY_RECOGNITION`, `ACCESS_WIFI_STATE`: no form.

**Data safety** — §11.4 — precise location (opt-in place-name lookup), collected and shared ⚖️. CSV: `store/<app>/play/data-safety.csv` (§12).

**Category**: **Travel & Local**. ⚖️ (Lifestyle is the alternative.)

---

## 5. Tenshu Calendar — `app.tenshu.calendar.android` (`:calendar-android`)

Release permissions: `WAKE_LOCK`, `ACCESS_NETWORK_STATE`, `RECEIVE_BOOT_COMPLETED`,
`FOREGROUND_SERVICE` (all from libraries — WorkManager). `INTERNET` stripped; subscribed calendars
are fetched through Control. No `READ_CALENDAR`.

**App access** — restricted; instructions:
> 2. Open Tenshu Calendar. Tap "+" to create an event, then save. Open an event to edit or delete it.
> 3. Calendars: create one, or "Subscribe to a calendar" with an https or webcal address. A
> calendar's menu imports or exports .ics files. A home-screen widget shows what is next.
> Calendar does not read the phone's other calendars.

**Content rating**: interact **No** (a subscription only downloads a published calendar); location
**No**; purchases **No**; internet **No**.

**Target audience** 16+ (§0.9). **News / Gov / Financial / Health**: No / No / none / none.

**Permission declarations**: none. (`POST_NOTIFICATIONS` for reminders comes through Control/the
platform; no form.)

**Data safety** — §11.5 — no data collected. CSV: `store/<app>/play/data-safety.csv` (§12).

**Category**: **Productivity**.

---

## 6. Tenshu Contacts — `app.tenshu.contacts.android` (`:contacts-android`)

Release permissions: `READ_CONTACTS` only. `INTERNET` stripped.

**App access** — restricted; instructions:
> 2. Open Tenshu Contacts. Add a contact, or import from this phone (one contact, a .vcf file, or the
> whole address book — only then does Android ask for contacts access).
> 3. "Invite" shows a one-time QR code or link; the other person accepts it in their Tenshu app, and
> both compare a six-digit code. This needs a second phone with Tenshu.
> Contacts never writes to the phone's address book.

**Content rating**:
- [ ] Users interact or exchange content: **Yes** ⚖️ — an accepted invitation exchanges the two
  people's profile (name and public key) between them (`contact-invitation-handshake.md`). No
  messaging. Answering No is arguable; Yes is the conservative answer and only adds "Users
  Interact".
- [ ] Location **No**; purchases **No**; internet **No**.

**Target audience** 16+ (§0.9). **News / Gov / Financial / Health**: No / No / none / none.

**Permission declarations**: none (`READ_CONTACTS` needs no form; it is disclosed in Data safety).

**Data safety** — §11.5 — no data collected. CSV: `store/<app>/play/data-safety.csv` (§12).

**Category**: **Productivity** ⚖️ (Communication is the alternative; Google Contacts lists there).

---

## 7. Tenshu Fit — `app.tenshu.fit.android` (`:fit-android`)

Release permissions: 16 × `android.permission.health.READ_*` (below), `ACCESS_FINE_LOCATION`,
`ACCESS_COARSE_LOCATION`, `ACTIVITY_RECOGNITION`, `BLUETOOTH_SCAN` (neverForLocation),
`BLUETOOTH_CONNECT`, `BLUETOOTH`/`BLUETOOTH_ADMIN` (maxSdk 30), `POST_NOTIFICATIONS`,
`FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_LOCATION`, `…_CONNECTED_DEVICE`, `…_HEALTH`,
`…_DATA_SYNC`, **`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`**, `ACCESS_NETWORK_STATE`. `INTERNET`
stripped. **No `WRITE_*` health permission in release** — the eleven `health.WRITE_*` lines live in
`fit-android/src/debug/AndroidManifest.xml` (the screenshot fixture seeder) only. No background
location.

**Health Connect data types read** (`fit-android/src/main/AndroidManifest.xml`; each is requested in
code via `HealthPermission.getReadPermission(...)`, `fit-android/.../AndroidHealthPermissionRequester.kt`
and `fit-shared-data/src/androidMain/.../HealthConnectImporter.kt`):

| Permission | Record type(s) | Feature |
|---|---|---|
| `READ_EXERCISE` | `ExerciseSessionRecord` | workout history |
| `READ_EXERCISE_ROUTES` | exercise route (`ExerciseRouteResult`) | the route map on a workout |
| `READ_STEPS` | `StepsRecord` | steps, daily goal |
| `READ_DISTANCE` | `DistanceRecord` | distance |
| `READ_TOTAL_CALORIES_BURNED` | `TotalCaloriesBurnedRecord` | calories |
| `READ_NUTRITION` | `NutritionRecord` | food energy |
| `READ_HYDRATION` | `HydrationRecord` | water |
| `READ_MENSTRUATION` | `MenstruationPeriodRecord`, `MenstruationFlowRecord` | the Cycle category |
| `READ_SLEEP` | `SleepSessionRecord` | sleep |
| `READ_WEIGHT` | `WeightRecord` | weight trend |
| `READ_HEIGHT` | `HeightRecord` | body measurements |
| `READ_RESTING_HEART_RATE` | `RestingHeartRateRecord` | Browse → Vitals → "Resting HR" (latest + history) |
| `READ_HEART_RATE` | `HeartRateRecord` | heart rate on workouts |
| `READ_OXYGEN_SATURATION` | `OxygenSaturationRecord` | Browse → Vitals → "Blood oxygen" (latest reading, %) |
| `READ_RESPIRATORY_RATE` | `RespiratoryRateRecord` | Browse → Vitals → "Breathing" (latest reading, breaths/min) |
| `READ_HEALTH_DATA_HISTORY` | — (older than 30 days) | "import full history" |

Written: **nothing** (release).

**App access** — restricted; instructions:
> 2. Open Tenshu Fit. On a new phone "Start" shows no activity yet, with "Record a workout".
> 3. "Import from Health Connect": Health Connect's own permission screen appears; Fit imports the
> last 30 days, or the full history if that is allowed too. Fit never asks before this tap.
> 4. To record: Record → pick an activity → Start. Fit asks for location (while using) and physical
> activity access, and to be exempt from battery optimisation so the recording is not killed.
> Pause, resume or stop from the screen or the notification. "Connect" next to heart rate pairs a
> Bluetooth strap (Bluetooth access is asked only then).
> Fit writes nothing to Health Connect.

**Content rating**: interact **No**; location sharing **No**; purchases **No**; internet **No**.
⚖️ IARC may ask about health/medical information: Fit shows the user's own activity and gives no
medical advice (the listing says so) — answer No to "medical or treatment information".

**Target audience** 16+ (§0.9). **News / Gov / Financial**: No / No / none.

**Health apps declaration** (App content → Health apps):
- [ ] Health features: tick **Activity and fitness** (workout tracking, steps), and ⚖️ **Nutrition
  and weight management**, **Sleep management**, **Period tracking** (the Cycle category reads
  menstruation data), as the form offers. Not: medical device, disease management, clinical, or
  medical records.
- [ ] Not a medical device; no regulatory clearance claimed.
- ⚠ **Copy mismatch to fix first**: the onboarding hint `fit_onboarding_source_health_connect_hint`
  says *"Workouts, steps, sleep, vitals, **medical records**"*
  (`fit-shared-ui/src/commonMain/composeResources/values/strings.xml`, and the German twin), but Fit
  declares no `READ_MEDICAL_DATA_*` permission. A reviewer comparing the UI to the declaration sees
  a feature that does not exist (§13 D5).

**Health Connect data types** — ⚠ no longer a separate form: since 2025 the old Health Connect
request form is the Health apps declaration's **second screen**, which asks, per permission, "a
clear and detailed justification explaining how your app uses the data to benefit the user"
(`developer.android.com/health-and-fitness/health-connect/publish`). Approval is per package.
- [ ] List the 16 permissions above with the feature per row. Copy: `play-store-declarations.md` §4
  (⚠⚠ menstruation → the Cycle card; history → full-history import; routes → the route map).
- [ ] Privacy policy must describe the health data handling (§0.1).
- [ ] Data use: shown to the user, stored end-to-end encrypted, never for ads, never sold, never
  transferred except as needed to provide the feature (sync to the user's own devices).

**Other permission declarations owed**
- [ ] **Foreground service — `health`, `location`, `connectedDevice`** (one recording service,
  `foregroundServiceType="health|location|connectedDevice"`). The `health` video must show the user
  starting it: Record → Start → screen off → the recording notification with pause/stop; pair a
  strap in the same take for `connectedDevice`.
- [ ] **Foreground service — `dataSync`** (the Google Takeout import service). Use case **"Local
  processing: Import or export"**; video: the user picks a Takeout .zip → the import notification
  while Fit is backgrounded. Copy: §3 there.
- [ ] `ACCESS_FINE_LOCATION` foreground only — no background-location form (not declared).
- [ ] `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` — asked at Start (`BackgroundRunGate`, comment in the
  manifest: ColorOS killed a live recording). ⚠ Missing from `play-store-declarations.md`'s
  inventory before this change; now listed there.

**Data safety** — §11.5 — no data collected (E2EE exemption) ⚖️. CSV: `store/<app>/play/data-safety.csv` (§12).

**Category**: **Health & Fitness**.

---

## 8. Tenshu Photos — `app.tenshu.photos.android` (`:photos-android`)

Release permissions: `READ_MEDIA_IMAGES`, `READ_MEDIA_VIDEO`, `READ_MEDIA_VISUAL_USER_SELECTED`,
`READ_EXTERNAL_STORAGE` (maxSdk 32). `INTERNET` stripped.

**App access** — restricted; instructions:
> 2. Install Tenshu Drive and back up a few photos (Drive → the Synced Folders screen), or upload some
> in Drive. Photos shows what Drive backed up.
> 3. Open Tenshu Photos: the library shows the backed-up photos. Select some to add to an album or
> move to the trash; Collections → New album.

**Content rating**: interact **No**; location **No**; purchases **No**; internet **No**.

**Target audience** 16+ (§0.9). **News / Gov / Financial / Health**: No / No / none / none.

**Permission declarations owed**
- [ ] **Photo and video permissions**. ⚖️ This is the weak one: Photos lists what is on the phone in
  its own source picker ("see what is backing up from this phone, and pause a source"), but uploads
  nothing itself — Drive does. Play accepts broad access only when it is the app's core function.
  Justification: a gallery app whose core function is browsing the user's whole library, and its
  backup-source view must list every on-device folder. If Play refuses, the fallback is the photo
  picker for Photos and keeping broad access in Drive only. Copy: `play-store-declarations.md` §5.

**Data safety** — §11.5 — no data collected. CSV: `store/<app>/play/data-safety.csv` (§12).

**Category**: **Photography**.

---

## 9. Tenshu Home — `app.tenshu.home.android` (`:home-android`)

Release permissions: none (only the app's own `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`).
`INTERNET` stripped.

**App access** — restricted; instructions:
> 2. Open Tenshu Home. Without a Tenshu Hub (a home server the user runs) the device list is empty;
> that is expected and the app says so. Automations can be created without a Hub: Automations → +
> → a time of day or sunrise/sunset, then an action.
⚖️ A reviewer cannot see device switching without a Hub. Consider a short screen recording of a
Hub-backed session linked in the "any other information" field (§13 D6).

**Content rating**: interact **No**; location **No** (an automation condition "whether someone is
home" is evaluated for the user's own household; ⚖️ not sharing with other users); purchases
**No**; internet **No**.

**Target audience** 16+ (§0.9). **News / Gov / Financial / Health**: No / No / none / none.

**Permission declarations**: none.

**Data safety** — §11.5 — no data collected. CSV: `store/<app>/play/data-safety.csv` (§12).

**Category**: **House & Home**.

---

## 10. Tenshu Mia — `app.tenshu.mia.android` (`:mia-android`)

Release permissions: `RECORD_AUDIO`, `WAKE_LOCK`, `ACCESS_NETWORK_STATE`, `RECEIVE_BOOT_COMPLETED`,
`FOREGROUND_SERVICE`. **No `INTERNET`** — stripped explicitly with `tools:node="remove"` like the
other clients since §13 D3 (no dependency adds it today; the strip and
`scripts/lib/check_client_no_internet.py` keep a future one from adding it silently).

Voice: `SpeechRecognizer.createOnDeviceSpeechRecognizer` only, never the default recogniser
(`mia-android/src/main/java/app/tenshu/mia/android/AndroidVoiceInput.kt`) — no audio leaves the
phone. Mia registers a `VoiceInteractionService` so it can be chosen as the phone's assistant.

**App access** — restricted; instructions:
> 2. Open Tenshu Mia and go through the welcome pages. Type a question, e.g. "photos from last week"
> or "what's on my calendar tomorrow". Mia answers from the other Tenshu apps' data on this phone;
> on a new phone most answers say that nothing matched.
> 3. The microphone button (Android 13+, when the phone supports on-device speech recognition) asks
> for microphone access; recognition runs on the phone.
> 4. Mia can be set as the phone's assistant (Settings → Default apps → Digital assistant app).

**Content rating**: interact **No**; location **No**; purchases **No**; internet **No**.

**Target audience** 16+ (§0.9). **News / Gov / Financial / Health**: No / No / none / none.

**Permission declarations**: none. `RECORD_AUDIO` has no form; it is disclosed in-app and in Data
safety (§11.10: not collected).

**Data safety** — §11.5 — no data collected. CSV: `store/<app>/play/data-safety.csv` (§12).

**Category**: **Productivity** ⚖️ (Tools is the alternative).

---

## 11. Data safety — per app

### 11.0 The rules these answers rest on

All quotes are from Play's Data safety help page,
`https://support.google.com/googleplay/android-developer/answer/10787469` (read 2026-10-23).

- **Collected** = "transmitting data from your app off a user's device", including what libraries
  and SDKs send, "irrespective of whether data is transmitted to you or a third-party server".
- ⭐ **End-to-end encryption is exempt**, verbatim: *"User data that is sent off device, but that is
  unreadable by you or anyone other than the sender and recipient as a result of end-to-end
  encryption does not need to be disclosed. The encrypted data must not be readable by any
  intermediary entity, including the developer, and only sender and recipient may have necessary
  keys."* Everything in a realm — files, photos, places, trips, events, contacts, health records,
  home data, Mia's history — meets this: encrypted on the device, the relay holds ciphertext and no
  keys (House Rule 1; `docs/sync/threat-model.md` invariant I1, A1–A4). Sender and recipient are
  the user's own devices (and, for shared realms, the members the owner admitted — A26/A27).
  ⚠ This **replaces** `play-store-declarations.md` §6's older advice ("do not answer 'no data
  collected' because we cannot read it"); that section now points here.
- **On-device only** is exempt: data "only processed locally … and not sent off device".
- **Ephemeral** processing must still be *entered* but is not shown: memory only, "retained for no
  longer than necessary to service the specific request in real-time".
- **Shared** = transferred to a third party. Not sharing: a service provider acting on our
  instructions, a legal requirement, a **user-initiated action or prominent disclosure with
  consent**. ⚠ It also covers "on-device transfer to another app". The clients hand data to Control
  over Binder; Control is ours — the first party — so this is not sharing (⚖️ our reading: the
  definition's "third party" is "any organization other than the first party").
- **Play Billing**: payment details Google collects need not be declared when the app never sees
  them. What *we* send (the purchase token and the plan, to commerce) is ours to declare.
- **SDK telemetry: none leaves Control**. Two of Control's libraries log to Google through
  `com.google.android.datatransport` (CCT backend, `firebaselogging.googleapis.com`), on every
  billing rail unless stopped:
  - **MediaPipe Tasks** (`tasks-vision` 0.10.35, Photos face grouping): every `TaskRunner` builds a
    `TasksStatsProtoLogger` (`TasksStatsLoggerFactory`, no option to turn it off) that sends
    `COREML_ON_DEVICE_SOLUTIONS` events — session start/end, invocation counts and latencies,
    errors, with the package name and versionCode;
  - **Play Billing Library** (`billing` 9.1.0): `PLAY_BILLING_LIBRARY` events once a
    `BillingClient` is built (Play rail only).
  The runtime finds its upload backend only through the `TransportBackendDiscovery` service's
  `backend:` meta-data, and Control's manifest removes that service. With no backend, events are
  rejected at schedule time and never stored (`DefaultScheduler` looks up the `cct` backend first
  and hands the logger an `IllegalArgumentException` before `EventStore.persist`, so no upload job
  is scheduled either); any queued by an older build are deleted on the next upload pass
  (`Uploader`: *"Unknown backend … deleting event batch"*). Both libraries keep working: the
  failure reaches only their fire-and-forget schedule callback. ⚠⚠
  `checkNoTelemetryBackend<Variant>` (`control/build.gradle.kts`) fails every Control assemble,
  bundle and install if a `backend:` entry comes back — a dependency bump can rename the service.
  So: **no SDK data type to declare**. ⚖️ Revisit if a library starts uploading by another route.
  ⚖️ This covers datatransport. Google Play services call telemetry (`gms.common.internal.
  TelemetryLogging`, reported over IPC to Play services by every `GoogleApi` call) is a separate
  channel that the strip does not touch — and Control makes no such call: no code in the repo
  imports `com.google.android.gms` (no `LocationServices`, `FusedLocationProviderClient`,
  `ActivityRecognition`; Timeline and Fit use the framework `LocationManager`, in their own apps),
  `play-services-base/-location/-tasks` arrive only as Play Billing's declared dependencies, and
  Billing's own bytecode references no GMS API (it binds the Play Store's billing service itself).
  R8 confirms it for the release APK: `GoogleApi`, `TelemetryLogging` and all 122
  `gms.location` classes are removed (`control/build/outputs/mapping/release/usage.txt`), so no
  GMS client can be built. Re-check `usage.txt` after adding any Google dependency.
- **ML Kit** — no longer ships in Control or Drive (the QR scanner is ZXing, on-device).
- **What we do not declare, and why** (⚖️ each, mirroring the iOS labels' open items,
  `store/control/privacy-label.md`):
  - relay metadata (stored size, device count, sync times — threat model A6, T-T-07): part of
    providing the service, not a listed data type;
  - server access logs with IPs (Caddy, size-bounded) and the IP in the ToS-acceptance record
    (`commerce-service/.../routing/AccountRouting.kt`, `CommerceDatabase.kt` `acceptance.ip`): Play
    has no IP type; IP counts as Approximate location only when used to derive location, and we do
    not (⚖️ counsel);
  - which map area a tile request covers (Maps, through Control, `tiles.service.tenshu.app`): not
    the user's location;
  - a subscribed calendar's download (Calendar, through Control, straight to its publisher): the
    user's own request to a server they chose.

### 11.1 Control — data collected

Collects: **Yes**. Encrypted in transit: **Yes** (HTTPS to relay and commerce).
Account creation: **"Username and other authentication"** — email plus a one-time code
(`commerce-service/.../routing/AccountRouting.kt`). Outside-app accounts: not asked — Play asks it only when the app creates no accounts (§12).

| Data type | Collected / shared | Optional? | Purposes | Source |
|---|---|---|---|---|
| Personal info → **Email address** | collected | optional (only to turn on sync) | App functionality, Account management | `CommerceDatabase.kt` `account.email_normalised` |
| Personal info → **User IDs** | collected | optional | App functionality, Account management | `account.id`, `account_owner` (account ↔ realm), relay `realm`/`device` rows |
| Financial info → **Purchase history** | collected | optional (only on purchase) | App functionality, Account management | `PlayStoreBilling.kt` → `GooglePlayRouting.kt`: the purchase token (`account.iap_original_txn_id`) and plan validity (`entitlement_history`) |
| Device or other IDs | collected | optional (only with sync) | App functionality | Tenshu's own per-device id on the relay (`device` rows) |

Not ephemeral, none shared. No Diagnostics row: no SDK uploads anything (§11.0). ⚖️ Email is not declared for
*Developer communications*: Control has no marketing opt-in on Android (`marketing_consent` is
written on the web only) — re-check if that changes.

**Deletion.** Play requires, for an app that creates accounts, an in-app path **and** a web page
to request account deletion (`answer/13327111`), and Data safety asks for that page's URL. Both
exist since 2026-10-23 (`commerce-accounts-and-sessions.md` §9.7): Control → Services → *Delete
account*, and **`https://tenshu.app/account/delete`** (public, no app or sign-in needed — an emailed
code is the proof), which is what the CSV now carries for both URL answers. Deleting erases the
email, sessions and the account's identity at once and stops Stripe billing; invoices and terms
acceptances are kept (statutory, ~10 years) without the email; encrypted content is deleted 30 days
later. That last part runs because both closure flags are **open in production since 2026-10-24**
(`retention-quota-model.md` §3.3; ⚖️ counsel review of the retention text is still owed, but no
longer gates the switch). Control's `data-safety.json` therefore no longer carries the
`"upload_gate"` that made the upload command refuse it; ⚠ if either flag is ever closed again, put
the gate back (`"upload_gate": "account-closure-flags"`) before the next upload. ⚠ The apex must be deployed with the page before the URL is submitted.

### 11.2 Drive

Collects: **Yes**. Encrypted in transit: **Yes**. Account creation: **"My app does not allow users
to create an account"** (Control does). Outside-app accounts: **No**, asked because Drive creates none.

| Data type | Collected / shared | Optional? | Purposes | Source |
|---|---|---|---|---|
| Files and docs → **Files and docs** | collected | optional (only when sharing by link) | App functionality | a share link stores the file's **name, type and size** readable on the relay (`share_record`; threat model T-S-02; the listing says so) |

Deletion: **Yes** — revoking a link (Drive or Control) marks the record revoked and the nightly
sweep deletes it and its chunks 30 days later (`SqliteShareStore.gcSweep`,
`RelayConfig.DEFAULT_RETENTION_WINDOW_MS`). Deletion URL (required with "Yes"):
`https://tenshu.app/apps/drive/support/android/en#privacy`, whose privacy section says how to revoke a
link and that the server then deletes what it kept. ⚠ Not `/apps/drive/support/en`: support pages
are per platform, and that path falls through to the home page. No SDK rows: Drive holds no `INTERNET` (§2, §13 D3) and ships
no Google artefact.

### 11.3 Maps — **No data collected**

Tiles via Control (not location, §11.0). Places are E2EE.

### 11.4 Timeline

Collects: **Yes**. Encrypted in transit: **Yes**. Account creation: none.

| Data type | Collected / shared | Optional? | Purposes | Source |
|---|---|---|---|---|
| Location → **Precise location** | collected **and shared** ⚖️ | optional ("Look up place names", off by default) | App functionality (both) | `PlaceGeocodingEgress.kt` (the only gate) → komoot's Photon (`photon.komoot.io`, `PhotonGeocoder.DEFAULT_ENDPOINT`, called by Control) or `AndroidReverseGeocoder.kt` (`android.location.Geocoder`, which can use Google's servers) |

The recorded timeline, Wi-Fi fingerprints (a hashed id, opt-in) and Takeout imports are E2EE or
on-device — not declared. ⚖️ **Shared**: komoot and Google are third parties we have no processor
agreement with; the opt-in toggle with its disclosure ("a lookup sends a place's coordinates to the
lookup provider", `timeline_settings_geocoding_desc`) may qualify as user consent, which would
make it *collected, not shared*. Conservative answer: shared. The in-app text does not name komoot
or Google; the listing does. Deletion: **No** — we hold nothing, and what komoot or Google keep is
outside our reach. ⚖️

### 11.5 Calendar · 11.6 Contacts · 11.7 Fit · 11.8 Photos · 11.9 Home · 11.10 Mia — **No data collected**

Each has no `INTERNET` in its release manifest; its data reaches the network only through
Control, end-to-end encrypted. Specifically:
- **Fit**: Health Connect data and recorded workouts are E2EE — exempt. ⚖️ This is the answer
  most worth a second look, because Health Connect apps get extra review: it rests entirely on the
  E2EE exemption, and the privacy policy must say the same.
- **Contacts**: an invitation passes a sealed box through a relay mailbox; the relay never holds
  `K_link` (threat model A24/A25) — exempt.
- **Mia**: questions are answered on the device; voice uses the on-device recogniser only
  (`AndroidVoiceInput.kt`) — on-device, exempt. **The day a model or cloud service answers, this
  changes.**
- **Calendar**: subscriptions as §11.0.

Account creation: not asked for these — with "collects: No" Play hides the account and deletion questions, and refuses an answer to them (§12).

---

## 12. Uploading Data safety from the repo

The Console's Data safety page has **Export to CSV** and **Import**, and the Play Developer API has
`POST https://androidpublisher.googleapis.com/androidpublisher/v3/applications/{packageName}/dataSafety`
with body `{"safetyLabels": "<the whole CSV>"}` — standalone, not part of an edit, empty response
(`developers.google.com/android-publisher/api-ref/rest/v3/applications/dataSafety`). ⚠ An import
**overwrites** every answer in the form.

Files:
- `store/shared/play/data-safety-template.csv` — the current form as a **blank** CSV: header
  `Question ID (machine readable),Response ID (machine readable),Response value,Answer requirement,Human-friendly question label`,
  782 rows (13 general questions, 38 data types × 18 usage rows). Taken from two independent 2026
  exports that agree key for key (sceneview/sceneview and openMF/mifos-pay on GitHub). ⚠ Google's
  own linked sample (2021) is stale — it predates the account-deletion questions — so it is not
  the template.
- `store/<app>/play/data-safety.json` — the answers (§11), short and reviewable.
- `store/<app>/play/data-safety.csv` — rendered: `python3 scripts/lib/data_safety.py render`;
  `… check` fails when a CSV is stale (CI: `.github/workflows/web.yml`, beside the store-listing
  checks). Cells: `true`/`false` for yes/no questions, `true` on each chosen choice, blank
  otherwise — as current exports write them.

Upload — ✅ **done for all ten apps on 2026-10-24**, after the maintainer signed the answers off (re-run on any change):

```bash
python3 scripts/lib/play_publish.py data-safety app.tenshu.drive.android store/drive/play/data-safety.csv --dry-run
python3 scripts/lib/play_publish.py data-safety app.tenshu.drive.android store/drive/play/data-safety.csv
```

The command checks the header, that the rows are the template's, single choices, the package
against the `listing.json` beside the CSV, and **refuses a CSV containing `PLACEHOLDER`** (Control's
deletion URL had one until 2026-10-23) — all before asking for credentials. `--dry-run` needs neither credentials nor the
network. Tests: `python3 scripts/test_data_safety.py`.

⭐ **What the API accepts, measured on the first upload (2026-10-24).** Lowercase `true` is fine and
the template still matched. Three rules the form only implies, now enforced by `data_safety.py`
(tests `PlayAccountRules`): (1) with `collects: false` the whole account section is hidden, so any
answer to it is refused ("You cannot answer PSL_SUPPORTED_ACCOUNT_CREATION_METHODS"); (2) "Can users
log in with accounts created outside the app?" is asked only when the app creates no accounts
(`PSL_ACM_NONE`), so Control may not answer it; (3) `DATA_DELETION_YES` requires a `data_deletion_url`
("Response missing for PSL_DATA_DELETION_URL") — Drive's points at its support page's privacy section
(`https://tenshu.app/apps/drive/support/android/en#privacy`, which says how to revoke a share link and that this deletes what the server kept). A refused
upload changes nothing for that app. If Google changes the form, Export to CSV from the Console,
replace the template with a blank of that export, and re-render.

---

## 13. What the maintainer must decide

- ~~**D1 — Control's reviewer access** (§1)~~ — **decided (2026-10-24):** one unpublished review
  account per store (`google-review-<suffix>@tenshu.app`, `apple-review-<suffix>@tenshu.app`), each
  accepting its own fixed 6-digit code instead of a mailed one (`commerce-accounts-and-sessions.md`
  D13, T-CB-12). Built in `:commerce-service`, off until the box has the two env lines; runbook §14.
- **D2 — account deletion** (§11.1): **built** (`commerce-accounts-and-sessions.md` §9.7) — the
  in-app flow and `https://tenshu.app/account/delete`, which the CSV now names. ~~Open the two
  closure flags~~ — open in production since 2026-10-24, so the declared content deletion runs
  (⚖️ counsel still owes `acceptable-use-and-limits.md` §4). What remains: deploy the apex page, and
  settle whether Stripe's customer object must be deleted too.
- ~~**D3 — strip `INTERNET` in Drive, and explicitly in Mia**~~ — **done** (§2, §10): both strip it,
  the two ML Kit rows are gone from Drive's Data safety (ML Kit itself is gone), and
  `scripts/lib/check_client_no_internet.py` guards every client.
- **D4 — Timeline's background-location disclosure wording** (§4): "even when the app is closed or
  not in use" — **done 2026-10-31**, in both dialogs, EN and DE. Record the demo video from a build
  that has it.
- **D5 — Fit's "medical records" hint** (§7): drop it, or the Health apps reviewer sees a feature
  with no permission behind it.
- **D6 — Home's reviewer path** without a Hub (§9): text only, or a linked recording.
- **D7 — automatic backup under a `dataSync` FGS** (§2) — **done on both sides.** Drive's:
  automatic backup is WorkManager jobs, and Drive's `dataSync` service carries only the user's
  *Back up now* (`drive-synced-folders-phase2.md` §4.1a). Control's
  (`control-background-work-off-fgs.md` Phase 1): `ProxyService` promotes only for tasks marked
  user-started and for map/model downloads. An automatic backup's uploads and the sync push run under
  Control's own jobs, and since Phase 2a so do the maintenance and scheduled index passes. *Index
  now* runs as a WorkManager long-running worker under its own `mediaProcessing` (API 35+) notification
  (that doc's §5.6.1), declared separately above.
- **D8 — Photos' broad media access** (§8): keep the declaration and argue it, or move Photos to
  the photo picker.
- ⚖️ **Counsel**: the E2EE reading for every app (§11.0, especially Fit), Timeline's geocoder as
  collected vs shared, IP addresses (§11.0), the account-deletion retention text, and the under-18
  contract-capacity question behind the 16+ audience (policy §7).

## 14. Store-review sign-in — addresses, codes, the box, rotation

Why and what: `commerce-accounts-and-sessions.md` D13 / §9.8; security: threat model T-CB-12. One
review account per store, each an **unpublished** address with its **own** fixed 6-digit code that
replaces the emailed one — six digits so it fits every client's existing code field. It stays **on**:
Google re-reviews live apps and expects the App access credentials to keep working.

Where each secret lives — and nowhere else:

| Secret | Keychain (service, account `tenshu`) | Also in |
|---|---|---|
| Google review address | `commerce-review-address-google` | `deploy.env`, Play Console App access |
| Apple review address | `commerce-review-address-apple` | `deploy.env`, App Store Connect App Review Information |
| Google code (6 digits) | `commerce-review-signin-code-google` | Play Console App access — **never the box** |
| Apple code (6 digits) | `commerce-review-signin-code-apple` | App Store Connect — **never the box** |
| HMAC key | `commerce-review-signin-hmac-key` | `deploy.env` |

⚠⚠ Never paste an address, a code or the key into a terminal transcript, a commit, an issue, `store/`
or this file. Every command below reads from the Keychain and prints at most an HMAC. ⚠ `security
add-generic-password -w <value>` puts the value on its command line, where other local users' `ps`
can see it for the instant it runs — true of the address, the codes and the key alike; on a
single-user Mac that is acceptable. The same holds for `openssl dgst -hmac` below.

Why six digits are enough here: each account allows 10 wrong codes per rolling 24 hours and then
locks (persisted, so a restart does not reset it). 10⁶ codes at 10 a day is ≈ 274 years to try them
all, ≈ 0.37 % per account per year — and only for someone who already knows the address, which is a
random suffix that is published nowhere.

**1. The addresses** (once; again only if one leaks). Ten lowercase base32 characters (50 bits) as a
suffix, stored as the full address:

```bash
for store in google apple; do
  security add-generic-password -U -a tenshu -s "commerce-review-address-$store" \
    -w "$store-review-$(python3 -c 'import secrets,base64;print(base64.b32encode(secrets.token_bytes(10)).decode().lower()[:10])')@tenshu.app"
done
```

Then make mail to them land somewhere you control — reviewers may get receipts (a Play purchase, a
Stripe checkout) or bounce notices there. In Cloudflare → Email Routing for `tenshu.app`, add a custom
address for each (forward to the maintainer's inbox), or confirm the zone's **catch-all** rule already
forwards to an inbox you read. commerce itself never mails these addresses.

**2. The HMAC key** (once; rotate if `deploy.env` may have leaked):

```bash
security add-generic-password -U -a tenshu -s commerce-review-signin-hmac-key -w "$(openssl rand -hex 32)"
```

**3. The codes** (one per store; at every rotation). Six digits from a CSPRNG, never printed (`-U`
replaces an existing item):

```bash
for store in google apple; do
  security add-generic-password -U -a tenshu -s "commerce-review-signin-code-$store" \
    -w "$(python3 -c 'import secrets;print(f"{secrets.randbelow(10**6):06d}")')"
done
```

**4. The `REVIEW_SIGNIN_ACCOUNTS` value**, built from the Keychain — `address=hmac` per store, comma-
separated. It contains the addresses, so treat the output as a secret: pipe it to `pbcopy` rather
than reading it.

```bash
key="$(security find-generic-password -a tenshu -s commerce-review-signin-hmac-key -w)"
for store in google apple; do
  address="$(security find-generic-password -a tenshu -s "commerce-review-address-$store" -w)"
  hmac="$(printf %s "$(security find-generic-password -a tenshu -s "commerce-review-signin-code-$store" -w)" \
    | openssl dgst -sha256 -hmac "$key" | awk '{print $NF}')"
  printf '%s=%s\n' "$address" "$hmac"
done | paste -sd, - | tr -d '\n' | pbcopy
unset key address hmac
```

⚠ `printf %s`, not `echo`: a trailing newline changes the HMAC and that store's sign-in then fails.
(Commerce trims the *candidate*, so a reviewer's stray space is harmless.) To copy one store's address
or code into its form without printing it:
`security find-generic-password -a tenshu -s commerce-review-signin-code-google -w | tr -d '\n' | pbcopy`
(likewise `commerce-review-address-google`, and the `-apple` items), paste, then clear the clipboard.

**5. Set it on the commerce box** — two lines in `/opt/tenshu/deploy.env` (0600), edited by hand:

```
REVIEW_SIGNIN_ACCOUNTS=<paste from step 4>
REVIEW_SIGNIN_HMAC_KEY=<the Keychain commerce-review-signin-hmac-key value>
```

then recreate the container with the box's exact command:

```bash
cd /opt/tenshu/repo && docker compose -p commerce-service -f commerce-service/docker-compose.yml \
  -f /opt/tenshu/commerce.override.yml --env-file /opt/tenshu/deploy.env up -d --no-deps --force-recreate commerce
```

⚠⚠ Never change `-p`, either `-f`, or the directory: any of them makes compose treat this as a
**different project** and start a second stack beside the running one, on the same volume and ports.
The boot log says `Commerce review sign-in: ENABLED for 2 account(s)`; it never prints an address, an
HMAC or the key. Both lines or neither — commerce refuses to start with one.
⚠⚠ **Do not toggle review sign-in through Terraform on a live box.** The module's
`review_signin_accounts` / `review_signin_hmac_key` variables exist for a box built from scratch;
cloud-init writes them only on first boot, and whatever you set there lands in the instance's
`user_data` and in Terraform state. Edit `deploy.env` by hand.

**6. Enter them in the stores:** Play Console → App content → App access → the instruction set
(§0.10, §1): username the Google address, password the Google code. App Store Connect → the
version's App Review Information → Sign-in required: user name the Apple address, password the Apple
code (`app-store-release.md` §5.2).

**Rotate** periodically (say yearly) and **at once after a suspected leak** of a code (a store-side
incident) — a code, not the address, unless the address leaked too: repeat step 3 for the affected
store (or both), step 4, replace `REVIEW_SIGNIN_ACCOUNTS` on the box, recreate, and update that
store's form. At boot commerce compares each account's code fingerprint with the last boot's and
**ends every session of an account whose code changed** (or that was removed from the config), so a
reviewer signed in under the old code is signed out; the log line ends `ended the sessions of N
rotated/withdrawn review account(s)`. ⚠ At each rotation, also **check the review accounts for
purchases or subscriptions** in the admin console — reviewers are allowed to test buying, and a
subscription a reviewer started should be cancelled or refunded deliberately, not left renewing.

**Turn it off** (only if the stores no longer need it): remove both lines and recreate. The next boot
ends the review accounts' sessions.

**Locked out?** Ten wrong codes on one account within 24 hours lock it (`429`, even with the right
code) until the oldest of them is 24 hours old. A restart does not clear it — the failures are in the
store. With an unpublished address a lockout is unlikely to be hostile; to clear it by hand (which
also gives whoever caused it ten fresh guesses), delete that address's row:

```sql
DELETE FROM email_verification WHERE email_normalised = 'review-signin-failures:' || '<the review address>';
```

## What this file could not determine

- The IARC questionnaire's exact current wording is not published; §0.8 answers its known topics.
  Read each question in the Console.
- Whether Play accepts the CSV's lowercase booleans and the extra account answer on no-collection
  apps (§12).
- Whether `play-services-location` in Control's runtime classpath ever reaches the network:
  Control declares no location permission, so it cannot obtain a location, but the dependency's
  consumer was not traced.
