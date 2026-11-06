# Drive — "a new folder appeared, back it up?" prompt

**Status:** Android slice shipped (2026-07-20). iOS slice shipped + the
per-album selection UI that makes the prompt live (2026-07-21). Desktop out of
scope.

Extends [`drive-synced-folders.md`](drive-synced-folders.md) /
[`drive-synced-folders-phase2.md`](drive-synced-folders-phase2.md).

---

## 1. What the feature actually is

Every gallery-backup app ships some version of *"A new folder with pictures has
been added — keep it synchronised?"* (OneDrive, Google Photos, Dropbox). It reads
as filesystem awareness. It isn't.

On both mobile OSes it is a **diff of the media library's bucket set against a
remembered baseline**:

1. Enumerate the distinct photo containers (Android `MediaStore` `BUCKET_ID`; iOS
   `PHAssetCollection`).
2. Compare against a persisted known-set.
3. A container that is new *and* passes a noise threshold → notification.
4. The answer writes to the known-set either way, so it never asks twice.

This is why every competitor's prompt is always about `Camera`, `Screenshots`,
`WhatsApp Images`, `Downloads`. Those are **media buckets** — the only folders
either OS lets an app enumerate without a per-folder user grant. The feature looks
like filesystem awareness because the folders it names happen to be real
directories; the mechanism is gallery metadata.

**Consequence for us:** the prompt can only ever cover the gallery source. The SAF
document-tree source is deliberately excluded — its "buckets" are persisted
`OPEN_DOCUMENT_TREE` grants, which can only exist because the user just picked
one. Announcing those would be telling the user about a folder they added
themselves.

## 2. Why a prompt and not silent auto-include

⚠ **This slice changed the meaning of a shipped preference.**

`DrivePreferences.BackupNewFolders` (`drive.backup.newFolders`, default **on**)
shipped with the copy *"Back up new folders automatically / Include folders that
appear later without asking"* — and **nothing ever read it**. It was an inert
toggle making a promise the engine did not keep.

It was re-pointed at the prompt rather than implemented as written:

- Silently auto-egressing a folder the user has never seen contradicts §3.7, the
  reasoning that made the master backup switch default-**off** in the first place.
  If auto-egress is a consent decision, it is a consent decision for a folder that
  appears on Tuesday too.
- The blast radius of getting it wrong is asymmetric. A missed prompt costs a
  folder that isn't backed up; a wrong auto-include uploads a folder the user
  never intended to leave the device.
- It is what Google Photos actually does, the copy notwithstanding.

Because the toggle was inert, there is **no behaviour regression** — no install has
ever observed the auto-include semantics. Copy updated to *"Ask about new folders"*.

## 3. Anti-nag design

An unsolicited notification about the user's photo folders is the most annoying
thing this app can do, so the guarantees are structural, not best-effort:

| Guarantee | Mechanism |
|---|---|
| Asked at most **once per folder, ever** | The *scan*, not the answer, folds announced ids into the known-set. Ignoring the notification is a permanent "no". |
| No prompt storm on first run | Empty known-set = "never looked" → **seed silently, announce nothing**. Covers every upgrading install. |
| No burst on a device restore | `MAX_NEW_FOLDER_ANNOUNCEMENTS_PER_SCAN = 3`, largest folder first. Over-cap buckets are left *out* of the known-set and picked up next scan — capped, not dropped. |
| No noise from scaffolding folders | `MIN_NEW_FOLDER_ITEMS = 3`. Sub-threshold buckets stay unknown so they are still announced once they grow. |
| Answerable without opening the app | Inline *Back up* / *No thanks* notification actions. |
| Independently silenceable | Its own DEFAULT-importance channel, separate from the LOW-importance backup-progress channel. |

Accepted cost of the baseline rule (§1 step 2): a device whose gallery is
genuinely empty when backup is first enabled will baseline the first bucket that
appears instead of asking about it. That bucket is almost always `Camera`, which
the setup flow default-selects anyway.

## 4. As-built (Android)

**Pure** (`drive-shared-logic`, desktop-unit-tested):

- `NewFolderDetector.kt` — `scanForNewFolders(buckets, known, selected)` →
  `NewFolderScan(announce, known)`; the `NewFolderAnnouncer` seam;
  `NewFolderDecisions.accept/dismiss`.
- `DrivePreferences.BackupKnownBuckets` — DEVICE-scoped `StringList`. Device scope
  is load-bearing: "which folders this phone has been asked about" is meaningless
  on another device, exactly like every other backup selection (§3.1).
- `BackupSource.announcesNewBuckets` — `true` for gallery, `false` for doc-tree.

**Wiring** (`SyncedFolderController.announceNewFolders`):

Runs inside the drain, which buys both trigger tiers for free — the app-alive
`ContentObserver` and the headless `BackupWorker` wake (periodic 6 h floor +
`addContentUriTrigger` on the `MediaStore` collections). A folder that appears
while Drive is closed is announced at the next wake.

It runs **before the gate**, not after: a prompt costs no bandwidth and no quota,
so being paused on cellular or out of space is no reason to withhold it. The
master switch is the only gate that applies. The known-set is persisted *before*
announcing, so a crash costs one missed prompt rather than a re-announce loop.

**Android surface** (`drive-android`):

- `AndroidNewFolderAnnouncer` — one notification per bucket, `PendingIntent`
  request codes derived from (action, bucket) so two folders can't collide into
  one another's extras. Body tap → `MainActivity.EXTRA_OPEN_SYNCED_FOLDERS` →
  `Destination.SyncedFolders`.
- `NewFolderActionReceiver` — `exported="false"` (an exported receiver would let
  any app on the device silently add gallery folders to auto-backup); `goAsync()`
  because the answer is a preference write over IPC to Control.

**No relay, wire, or stored-row change** — the known-set is a new DEVICE-scoped
preference row, additive. `TENSHU_STORAGE_FORMAT_VERSION` untouched (HR#9 N/A).
Nothing here reaches the relay (HR#1 N/A).

## 5. Media access (Android 14 partial access)

⚠ **Investigating this surfaced a larger, pre-existing bug: Drive never requested
the media permission at all.** `READ_MEDIA_IMAGES` was declared in the manifest but
no runtime request existed anywhere in the app — verified on an API 36 emulator
(`granted=false` on a fresh install, no request path in any source file). On
Android 13+ that means `MediaStore` enumeration returns nothing, so the gallery
half of Synced Folders has never worked on a modern device: the settings screen
shows "No folders with photos were found", and every drain enumerates zero
buckets. Only the SAF document-tree source (which carries its own grant) worked.

The partial-access gap could not be closed without fixing this, because the
permission *request* is precisely where full-versus-partial is decided.

### Three states, one discriminator

| API | Permission | States |
|---|---|---|
| ≤ 32 | `READ_EXTERNAL_STORAGE` | granted / not |
| 33 | `READ_MEDIA_IMAGES` (+ `VIDEO`) | granted / not |
| 34+ | + `READ_MEDIA_VISUAL_USER_SELECTED` | full / **partial** / not |

**Verified empirically on API 36** (`adb shell pm grant/revoke` + `dumpsys`):

| State | `READ_MEDIA_IMAGES` | `READ_MEDIA_VISUAL_USER_SELECTED` |
|---|---|---|
| Full | granted | **granted** |
| Partial | denied | granted |
| Denied | denied | denied |

⚠ **Check order is load-bearing.** Full access grants
`READ_MEDIA_VISUAL_USER_SELECTED` *as well*, so testing it first would report
`PARTIAL` for a fully-granted app. `READ_MEDIA_IMAGES` must be the discriminator.
(Google's reference snippet implies this by its ordering; the table above confirms
it directly rather than relying on the inference.)

### Declaring `READ_MEDIA_VISUAL_USER_SELECTED` is not optional

Without the declaration, Android 14+ runs a compatibility path: choosing "Select
photos" grants `READ_MEDIA_IMAGES` **for the app session only**. It reads as full
access, then evaporates when the process is backgrounded or killed — which is
exactly when `BackupWorker` runs. Backup would appear to work in the foreground and
silently stop forever afterwards, with no detectable signal. Declaring it converts
that into a durable, inspectable `PARTIAL`.

### What the prompt does about it

`announceNewFolders` stands down unless access is `FULL`. Under `PARTIAL`,
`MediaStore` filters every query to the user's selection, so a "new bucket" only
means *the user picked a photo from a folder they hadn't picked from before* — and,
worse, folding that into the known-set would **poison the baseline**, making the
real folders read as already-seen once full access is granted. Standing down is the
only correct behaviour, and both failure modes are asserted in tests.

The Synced Folders screen surfaces the state as an advisory notice (not a gate —
under `PARTIAL` the selected photos genuinely do back up), with an
*Allow access to all photos* action. The *Ask about new folders* switch is disabled
when access isn't `FULL`, so it can't promise something the controller will refuse
to do. The grant is re-read on every resume via `LifecycleResumeEffect` — never
cached — because the user can change it from system settings while Drive is
backgrounded, and a partial selection can expire on its own.

### Still unverified

`ContentObserver` and WorkManager's `addContentUriTrigger` behaviour under partial
access is **not documented by Google** and I could not confirm it. It doesn't affect
correctness here (the prompt stands down regardless), but if backup is ever made to
work meaningfully under partial access, that needs an on-device experiment first.

### iOS (as-built)

Same three states, same gate. `IosMediaAccessProvider` reads
`PHPhotoLibrary.authorizationStatusForAccessLevel(.readWrite)` →
`authorized`/`limited`/else, and the controller stands down on anything but
`FULL` exactly as on Android. iOS shipped limited access in iOS 14, three years
before Android, and it is **more** common in the wild because the system picker
offers it on the first prompt — so this is the more load-bearing half of the two.
Settings gains the twin notice (advisory, with *Open Settings*), re-read on appear
and after the consent dialog since iOS offers no change notification for it.

⚠ Read against `.readWrite`, not `.addOnly` — the latter reports a grant that
permits *writing* to the library but not reading it, the opposite of what backup
needs. No ordering trap here, unlike Android: it is one enum with `limited` as its
own case.

The prompt itself is bridged rather than posted from Kotlin, mirroring
`BackgroundUploadBridge`: Kotlin owns detection, the known-set and applying the
answer; Swift owns the `UNUserNotificationCenter` request, its action category and
the delegate that receives the tap (`NewFolderPrompt.swift`), answering back through
`DriveBackupSurface.answerNewFolderPrompt`. The category is registered in
`App.init` — a notification whose category is unknown at delivery renders with no
buttons — and notification authorization is requested when backup is switched on,
not at launch.

#### ⚠⚠ An iOS album is not an Android bucket, and the difference is load-bearing

An Android bucket is a **directory**: a photo lives in exactly one. An iOS album is
a **view**: an asset in an album is *also* in the user library. Offering albums
alongside a selected "All Photos" would upload every album member twice — once to
`/Backup/<device>/All Photos`, once to `/Backup/<device>/<Album>` — and the §3.5
dedup backstop would not catch it, because it dedups per destination path and these
are different paths. (Content-addressing means one blob, but two file rows: the user
sees the same photo twice.)

So `IosMediaSourceProvider` enumerates user albums **only when All Photos is not
selected**, i.e. exactly when they stop being redundant. It offers hand-made albums
plus a curated set of smart albums (`Screenshots`, `Favourites`, `Selfies`,
`Panoramas`) — the ones that carry real user intent. `Recents` stays excluded: it *is*
the user library under another name, so offering it would reintroduce the overlap the
mode split exists to prevent.

**The picker is what makes the prompt live** (`BackupScopeView`). It is a
two-way **mode** choice — "All Photos" or "Selected albums" — not a flat multi-select,
because All Photos and an album overlap: selecting both would back the same photo up to
two destination paths. Modelling it as a mode makes that exclusivity the *shape* of the
screen (see `BackupScopeSelection`, unit-tested) rather than a rule the UI must remember
not to break. The new-album prompt has its own toggle, sibling to the master switch
exactly as on Android, disabled in All Photos mode where a new album's contents are
already covered.

⚠ An overlapping photo (in two selected albums) is deduped **in the drain**, not here —
`SyncedFolderController`'s per-drain seen-set uploads it once, to the first selected
album's folder. Without that, the random-`contentId`/fresh-CEK pipeline would
store and bill two full copies (`file-content-addressing.md`); the dedupe lives
client-side precisely because a plaintext-derived relay ref would be a
confirmation-of-file oracle.

#### Structural limits that remain

- **No content-trigger equivalent.** `PHPhotoLibraryChangeObserver` fires only
  in-process while running (and `IosMediaSourceProvider.observeChanges` is still
  `emptyFlow()`). Off-app you get `BGAppRefreshTask` — opportunistic, hours to days,
  degrading if the user rarely opens Drive. The prompt would arrive *eventually*,
  not promptly. Android's content trigger has no iOS twin.
- **Albums only.** Files.app / iCloud Drive folders are unreachable without a
  per-folder `UIDocumentPicker` grant, and even then there is no change
  notification. Same shape as the SAF exclusion, but stricter.

**Desktop:** out of scope. Control's resident watcher owns desktop ingest and
there is no OS gallery to diff against; it binds `FullMediaAccessProvider`.

## 6. Verification

- `NewFolderDetectorTest` (7 cases) — baselining, one-shot announce, threshold
  hysteresis, burst cap spillover, already-selected suppression, unmount
  resilience.
- `SyncedFolderControllerTest` (24) — announce-through-drain, master-switch
  suppression (and that suppression does *not* consume the known-set),
  announce-while-paused-off-policy, and stand-down under both `PARTIAL` and
  `DENIED` (asserting the known-set is *not* seeded from a filtered view).
- Permission-state signature verified on an API 36 emulator across all three
  states (§5 table) — this is what the check order depends on.
- `NewFolderPromptIosTest` (3) — the Kotlin↔Swift seam: what the announcer forwards,
  inert-without-a-bridge, and lazy bridge resolution (install order is not
  guaranteed between `App.init` and runtime install).
- `:tenshu-sdk-ios:iosSimulatorArm64Test` (22 classes) + `drive-ios` `xcodebuild`
  green — the Swift build is the only thing that compiles the SwiftUI/delegate
  changes, so Kotlin tests alone would not have caught them.
- `:drive-shared-ui:desktopTest` + `:drive-android:compileDebugKotlin` +
  `:drive-desktop:compileKotlinJvm` green; `scripts/android-smoke.sh` green (HR#8).
- ⚠ **On-device pass pending** — notification posting, the two inline actions, the
  body-tap deep link, the `POST_NOTIFICATIONS`-denied path, and the permission
  dialog itself are all framework behaviour that unit tests can't reach. On iOS the
  same applies to PhotoKit enumeration, `UNUserNotificationCenter` delivery and the
  notification actions; the album-overlap rule in particular is only exercised
  against a real library.

## 7. Open / next

1. **On-device pass** (§6) — now also covers the permission dialog itself, the
   partial/denied notices, and confirming gallery backup works end-to-end for the
   first time on Android 13+.
2. **Album artwork in the picker.** `BackupScopeView` is text-only; a thumbnail per
   album would read far less flat next to Photos.app. Deferred — cosmetic, and it
   pulls in `PHImageManager` thumbnail loading.
3. **Re-surfacing a dismissed folder.** Today the only path back is the Synced
   Folders screen's folder list, which is correct but undiscoverable if the user
   dismissed by accident. A "previously declined" section is the obvious fix if it
   ever comes up in feedback; deliberately not built on speculation.
