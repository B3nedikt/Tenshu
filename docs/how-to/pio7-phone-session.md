# PIO-7: the phone session before `TENSHU_PHOTOS_ON_DEVICE_AI` flips

One ordered session on the maintainer's iPhone 17e. It covers every phone-only item standing between
PIO-7 (on-device search by description in Photos on iOS) and turning on the build flag
`TENSHU_PHOTOS_ON_DEVICE_AI`. The plan is
[`ios-on-device-inference.md`](../proposals/ios-on-device-inference.md) (below: *the plan*). The
figures go beside Android's in [`inference-service.md`](../proposals/inference-service.md) §15.

This file is a runbook. **It records no results.** Each step names the doc section its result goes
into. Each pass/fail line is quoted from the doc that owns it. ⚠ Where a doc gives a
**placeholder** or gives no criterion, the step says so, and this runbook does not invent one: the
step is then a *record*, not a check.

Bare § numbers are the plan's; §15.x is `inference-service.md`'s (the plan has no §15). Doc
conventions used here: 📱 = only a phone can show it. *(placeholder)* = a constant the plan
itself marks as a placeholder. **Unpinned** = no doc states a pass/fail for it.

---

## ⭐ What is left for the phone before the flip (2026-11-09)

The short list. It is everything in [§14.18.16](../proposals/ios-on-device-inference.md) that only the
phone can still close; the steps below it (Parts A–C) say how to run each one, and where the result is
recorded. One install of the flag-on development build (B0) carries 1–8; 9–11 are destructive and go last.
⚠ This list is the exception to *records no results*: its ✅ rows say what was already seen, so the
session does not run them again.

**Already shown, 2026-11-09** (iPhone 17e, iOS 26.6.1, flag on, development build):
- ✅ The model downloads over Wi-Fi and finishes *Preparing* (B3 step 2, B4 step 1 — not timed).
- ✅ The preflight passes: `preflight.verdict` passed, attempts 0 (B4).
- ✅ Consent given, in the screen's order (B5 step 4).
- ✅ *Index now* indexes the library — **22 photos** (B6 step 1, in part).
- ✅ "cat" finds all 5 cats, after the relevance-floor fix (B6 step 1, in part: not "dog", not ≥ 200).
- ✅ The idle hint and the iOS 26 indexing copy (§14.18.16 item 15).

⚠ PR-6's overnight wake (§16) has its own runbook, [`photos-processing-wake.md`](photos-processing-wake.md): run it after this session's flag-on install, on the same build.

**Owed, in this order:**

| # | Do | Pass when | Closes |
|---|---|---|---|
| 1 | **H5 as written**: ≥ 200 photos in the library, *Index now*, search "dog". Once, from Xcode with Main Thread Checker on | dogs come back first; no Main Thread Checker report naming `UIDevice` | H5 (§14.18.16 item 2), §14.19.9 · B6 step 1 |
| 2 | Relaunch Photos twice over the `Ready` model; ask Mia (its own process) for photos by description | the row reads *Ready* with no *Checking* and no feature question; Mia's line names Photos' Settings | items 4, 7 (7 is answered in code; *Checking* seen at a launch is the finding §14.18.16 item 7 describes) · B5 step 2, B6 step 7 |
| 3 | With the model `Ready`, leave and return ten times, once with a search in flight | the state does not move; the search is answered | §14.17.6 item 12 · B5 step 5 |
| 4 | **iOS 26 continuation**: *Index now* over a long library, leave Photos; then stop it from the live activity; then let a second one finish in the background | the system shows *"Indexing your photos"* with done of total; a stop ends at the next photo and the section says *Stopped when you left Photos*; a finished task ends as done and the count has grown | PR-7, §15.6 items 1, 3, 8 · B6 step 8 |
| 5 | Same pass, recorded rather than judged: a long walk at `0 of 0`; a refused submit (`NotPermitted`, `ImmediateRunIneligible`); a swipe-away with a task live; heat touching `.serious` in front | — a record: write down what happened | §15.6 items 2, 4, 6, 7, 9 · B6 step 8 |
| 6 | A foreground pass under Low Power Mode, and unplugged below 20 %; read Photos' footprint over a full pass with a "dog" search during it; time the pass per photo; note whether a memory warning is logged before any jetsam | each stops with its reason and restarts by itself when cleared; footprint recorded beside the phone's limit; per-photo time and the warning are a **record** (no criterion) | item 12, §14.19.9 (unnumbered: *Low Power Mode and battery transitions*, *memory over a full pass*, *per-photo timing in the product path*, *a memory warning before a jetsam*), D10 / D12 ① · B6 steps 3–6 |
| 7 | Delete from *Ready* and read free space; download again and answer the feature question *Not now*; then download through the interruptions: force-quit; Wi-Fi off (cellular on); leave and lock; kill from Xcode in the background; reboot | the row goes and the space returns (the towers at once); *Not now* writes nothing (read the rows); force-quit restarts from zero; Wi-Fi off says *Waiting for Wi-Fi.* and uses no cellular data; the rest finish and install with no tap | §14.16 items 1–7, 9–10, §14.18.7 13–15, 17, 18, 22 · B3, B5 |
| 8 | Under ~2.85 GB free, press *Download*; free space before, after a cancel, after the prepare | refused before a byte moves, with the two figures Settings shows | §14.16 items 8, 11, §14.18.7 16, item 6 · B1, B2, B4 |
| 9 | The screens: the Settings button on iOS 26's bar; VoiceOver and the largest text on each state met; sign out with Models open | every sentence and button read and fit; no screen stays up over the wall | §14.18.7 19, 21, 23, 24 · B1, B7 |
| 10 | Kills that count: background the moment a download completes; kill in *Preparing*; delete with Photos backgrounded; then two Xcode-stop kills during *Checking*, **Try again**, a third kill; two swipe-aways during a load | `inference/coreml` has two directories and no `tmp`, then none; two kills → *"Photos stopped trying…"* with Try again; after the retry's kill → *"This iPhone couldn't run the model well enough…"* with **no** Try again (one press per key, §14.18.16 item 10); swipe-aways never stop it. ⚠ A kill *after* a pass counts from zero: a pass resets the count, so one later kill leaves the model *Ready* | items 1, 8, 9, 10, §14.17.6 8–10 · B8 |
| 11 | Last: start a download, install the **flag-off** build over it, launch | Photos is not relaunched for the transfers; nothing installs; the phone ends on flag off | item 5 (the Swift drain) · B9 |

⚠ Not the phone's, and not on this list: D12 ⓪'s iOS 17 load (no iOS 17 runtime for Xcode 27.2,
§14.18.16 item 2), a floor-class iPhone (D10/D12), an update's row (no second catalog entry), and the
App Store privacy label.

---

## 0. Before the session: what can run today

| Part | Needs | State on 2026-10-14 |
|---|---|---|
| **A. IN-3m-iOS** (the harness, §4) | the harness app and the side-loaded archives. No App Group | ✅ **Runnable.** The harness claims no capability (§4.4) |
| **B. The product build** (Photos with the flag on) | ⚠⚠ **an installable App Group build: S0b (a)–(d)** | ⛔ **Blocked.** [`ios-signing.md`](ios-signing.md) §3, *"Not safe to install yet"*, and [`ios-background-scheduling.md`](../proposals/ios-background-scheduling.md) §4.1.1. The plan's own preamble: *"Every later 📱 check waits for S0b."* Check that block before starting Part B |
| **B, the indexing steps** (B6) | the above **and 5e-2 merged** | ⛔ Until 5e-2, `OnDeviceModelsCapabilities.indexing` is `false`, so no screen offers *Index now* on iOS (§14.19). Photos has no launch argument that starts a pass |
| **C. Across an iOS update** | a pending iOS update | optional, last (§9.5) |

So **if S0b (a)–(d) has not landed, the session is Part A alone**, and Part A is still worth a
session of its own: IN-3m-iOS is the gate the status line says *"no later phase may read … as
passed"*. Run B1–B5 when the App Group build can be installed, and B6 once 5e-2 has also merged. The
order below holds for any subset.

**Bring:**
- The Mac with Xcode and `xcodegen`; the iPhone `00008150-0011085C1E85401C`; its charger.
- `export TENSHU_TEAM_ID=752LWAZMKZ` in the shell. ⚠⚠ `local.properties` still carries
  `tenshu.teamId=FAYT82GWD7` for the macOS build ([`ios-signing.md`](ios-signing.md), header), and
  `run-in3m.sh` falls back to it and then **refuses** (exit 2). Never sign anything with
  `FAYT82GWD7` (§4.4).
- The Core ML export directory with its `export-manifest.json`. ⚠ §4.4's `~/tenshu-models/export-coreml`
  does not exist on the maintainer's Mac; the export there is `~/tenshu-models/coreml-export-3fd1918106d4`. §15.13's run used vision `a30d4dde…` and text `628f7f32…`, the
  archives the catalog publishes (§12.1).
- The **JPEG corpus** of 2026-10-07: 554 files, corpus id `10b25ab76b09…` (§9.7). ⚠ If it is gone
  from the Mac, rebuild it by §15.12's recipe and record the new id. The rows then compare with
  §15.13 by recipe only, as §4.4's 2026-10-07 note already concedes.
- A **HEIC sample** from the iPhone's own library, as originals (not "Most Compatible" exports).
  ⚠ No doc fixes its size. The harness prints *"NOT an IN-3m figure"* below `MIN_IMAGES = 200`
  (`In3mHarness.kt`), so bring ≥ 200 if the windows should mean anything.
- For Part B: the route-1 set already installed and signed in on the phone ([`ios-signing.md`](ios-signing.md)
  §3, §6), Wi-Fi with cellular as a fallback, and a way to fill the disk to under ~2.85 GB free.
  ⚠ No doc names how to fill it.

**A session log.** Keep one text file next to the reports. For each step, log the time, the OS
build, whether the phone is plugged in, the battery %, Low Power Mode, and what was seen. Every
harness report line already carries the battery, Low Power Mode and `thermalState`.

---

## Part A — IN-3m-iOS (§4.4; owed per §9.7 and `inference-service.md` §15.13.7)

⭐ **Run 2026-10-22/23**, all of A0–A8: `inference-service.md` §15.14. The ⚠ notes marked *(2026-10-23)*
below are what that session tripped on.

⭐ *(2026-10-23)* **To read the phone's thermal state without loading a model**, launch a row that does
not exist: `run-in3m.sh run thermal-probe`. The header prints `thermalState` and the row stops at
`UNKNOWN_ROW`, adding no heat. Use it before any step that needs a cool start.

⚠⚠ **Never `run-in3m.sh uninstall` in this session.** The harness keeps each run's per-image
vectors on the phone, named by OS build. §9.5 and Part C need them across the next iOS update.

### A0. Build, install, side-load (no measurement)

*Preconditions:* the phone is unlocked and plugged in. No other app is in the foreground.

```bash
export TENSHU_TEAM_ID=752LWAZMKZ
inference-harness-ios/run-in3m.sh self-test                       # no phone; must print "self-test: OK"
inference-harness-ios/run-in3m.sh report ./in3m-ios-before.txt    # what the phone holds from 2026-10-07
inference-harness-ios/run-in3m.sh build device
inference-harness-ios/run-in3m.sh install
inference-harness-ios/run-in3m.sh push-models ~/tenshu-models/coreml-export-3fd1918106d4
inference-harness-ios/run-in3m.sh push-photos <the JPEG corpus dir>
```

- **Skip build + install** if the harness from 2026-10-07 is still installed and
  `git log -- inference-harness-ios inference-service-proxy/src/iosMain` shows nothing since. An
  install over an existing app keeps its container [I].
- **Record:** the two sha256 values `push-models` verified, and the corpus id and count that
  `push-photos` printed. ⚠ *(2026-10-23)* `push-models` prints only *"copying …"*: it refuses a tar
  off the manifest and says nothing on a match, so record the two values from `export-manifest.json`.
- **Pass:** `push-models` exits 0. Its own check is that both tars are the bytes the manifest names.
  `push-photos` prints the corpus id. ⚠ The manifest goes last (§4.4, §9.2 item 14). Photos from an
  earlier push stay in `Documents/photos/`; the harness ignores them.

### A1–A3. Three runs on the charger: cold, warm, +20 minutes idle

*Preconditions:* the phone has been idle and cool long enough to call it *cold*. ⚠ No doc gives a
temperature or a duration. The harness prints the `thermalState` at start and warns when a row
starts at ≥ `.serious`. Plugged in and unlocked. The harness turns off the idle timer while it is in
front. ⚠ *(2026-10-23)* **Charge first.** A3's first try started at `.serious`: the phone had sat
its 20 minutes charging from 10 %, which heats it more than any pass. Start A1 above ~80 %.

```bash
inference-harness-ios/run-in3m.sh run coreml-ane          # A1 cold
inference-harness-ios/run-in3m.sh run coreml-ane          # A2 warm: start it as soon as A1 exits
# A3: leave the phone idle on the charger for 20 minutes, unlock it, then:
inference-harness-ios/run-in3m.sh run coreml-ane          # A3 +20 min idle
inference-harness-ios/run-in3m.sh report ./in3m-ios-A.txt
```

- `coreml-ane` is the shipped row (§4.1), and it is the row §15.13.1 says needs *"the warm run"*.
  ⚠ No doc says whether the other two rows (`coreml-all`, `coreml-cpu`) or the text tower owe the
  three-run sequence. Adding them is the maintainer's call. Each row is its own launch (`run-in3m.sh`
  header).
- **Record:** each row's block from the report: median, p90, first-50 → last-50 and the ratio,
  decode vs predict, wall clock, `phys_footprint`, `thermalState` per 50, battery, and the printed
  *§15.9 row 3* line.
- **Pass/fail (§4.5, row 1):** *"last-50 (decode + predict) > 500 ms, or throttle > 2"* fires
  §15.9 row 3 on iOS. The harness prints `FIRES` or `does not fire`. ⚠ A ratio below 1 is **not** a
  throttle answer (`inference-service.md` §15.13.1). The warm run's first-50 is the *"device
  already warm"* figure (§15.4). Each `run` exits 0 only for `MEASURED`. A row that ends without
  `IN3M_DONE` (`CoreDeviceError 4016`, §9.7) is re-run, not counted.
- **Goes into:** a new run section beside `inference-service.md` §15.13, in its form; the *Three
  runs* row of §15.13.7's and the plan's §9.7's state tables; `models.md` (PR-1's row in §5).

### A4. A sustained pass (§9.7 *still owed*: "a pass long enough to heat the phone")

⚠ **Unpinned, and the harness has no mode for it.** One harness pass is ~20 s over 554 photos.
Back-to-back `run coreml-ane` launches are the nearest thing the tree can do. That is a stand-in no
doc defines, with a relaunch gap between passes.
- **Record:** `thermalState` per 50 across the launches, and the first launch that reports
  ≥ `.serious`.
- **Read against:** §4.5 row 3: *"`thermalState` ≥ `.serious` inside a realistic pass on the
  charger"* decides the foreground pass's backoff. It feeds `REST_AFTER_HEAT_MS` (60 s,
  *(placeholder)*, §14.19.1) and the absent chunk N (§14.19.8, §14.18.16 item 13). ⚠ No doc gives N
  or a target rest.
- **Goes into:** §14.19.8 and §14.18.16 item 13; D5's row (§7).

### A5. One run on battery (D5)

*Preconditions:* unplug. Low Power Mode **off**. Record the battery %.

⚠ *(2026-10-23)* **Unplugged, `devicectl` cannot reach the phone** (`CoreDeviceError 4016`, *"unavailable"*)
unless Xcode's *Connect via network* was set for it while it was on the cable. Without that, start
the row on the phone: swipe IN-3m away, open it, pick **`coreml-ane`** (the left segment) and
**`vision`**, *Recompile first* off, tap **Run**, wait for `outcome: MEASURED`, then plug in and run
`report`. ⚠ Check the header reads *unplugged*: a phone still on power at 100 % reads *full*.

```bash
inference-harness-ios/run-in3m.sh run coreml-ane
inference-harness-ios/run-in3m.sh report ./in3m-ios-A5.txt
```

- **Record:** the battery at start and at end. ⚠ They are directional only, in 5 % steps (§4.1).
  Also `thermalState` per 50 and the report's *unplugged* state.
- **Read against (§4.5):** *"the on-battery run reaches `.serious` inside 200 photos, or the battery
  delta is steep"* → iOS never indexes on battery below some level. ⚠ *"Steep"* has no figure.
  D5's 20 % line is the recommendation, to *"revisit with the harness's battery run"*.
- **Goes into:** §9.7 / §15.13.7 *One run on battery*; D5 (§7).

### A6. Open time after a reboot

*Preconditions:* plug in again. Reboot the phone and unlock it. **Launch nothing else first.**
(If Part B runs in the same session, the reboot of B3 step 7 can serve here too: launch the harness
first after that reboot, before Photos.)

⚠⚠ *(2026-10-23)* **Open a tower warm just before the reboot**, or the step measures something else.
A tower's first open after about two weeks unused took 2.7–4.7 s with or without a reboot; opened
warm and then rebooted, it took ~100 ms (§15.14.4). `xcrun devicectl device reboot --device <udid>`
reboots from the Mac; the phone then needs one unlock with the passcode.

```bash
inference-harness-ios/run-in3m.sh run coreml-ane
inference-harness-ios/run-in3m.sh run coreml-ane text
inference-harness-ios/run-in3m.sh report ./in3m-ios-A6.txt
```

- **Record:** each tower's `open` ms, and its footprint after open.
- **Read against (§4.5):** *"open after a reboot is slow again"* → the launch warm-up is required.
  ⚠ *Slow* has no figure. Compare with the opens in §15.13's table (77 ms vision, 41 ms text, on an already compiled model).
- **Goes into:** §9.7 / §15.13.7 *One launch after a reboot*; §3.2's warm-up note.

### A7. (Optional) The compile and the disk again, for D14

```bash
inference-harness-ios/run-in3m.sh run coreml-ane vision recompile
inference-harness-ios/run-in3m.sh run coreml-ane text recompile
```

- **Record:** unpack / compile / first-open ms and the free space at each step.
- **Read against (§4.5):** *"`compileModel` takes more than a few seconds, or the free-space delta
  is ≈ 2 × the package"*, and *"the free-space delta is far below the package size"*. ⚠ D14's open
  question, *"where the space taken at first open is kept, and whether it is returned"* (§9.7), has
  no method in any doc: **unpinned**.
- **Goes into:** D14 (§7); §3.4's budgets if the figures move.

### A8. The HEIC sample (last in Part A: it replaces the corpus manifest)

⭐ *(2026-10-23)* **The phone's own photos, by Tenshu Drive.** Its iPhone backup keeps HEIC originals,
so `~/Library/CloudStorage/TenshuDrive-TenshuDrive/Backup/iPhone/All Photos` can be the source: copy
the `.HEIC` files to a local directory and check them (`sips -g format -g model`, and the gain map)
before the push. ~50 photos answer A8's question (decode against predict); 200 are needed only for
the windows.

```bash
inference-harness-ios/run-in3m.sh push-photos <the HEIC sample dir>
inference-harness-ios/run-in3m.sh run coreml-ane
inference-harness-ios/run-in3m.sh report ./in3m-ios-A8.txt
```

- ⚠ After this push the harness measures the HEIC manifest. Going back to the JPEG corpus means
  pushing all ~3 GB again. That is why this step comes last.
- **Record:** the corpus id and count, decode vs predict, and the per-photo median and windows.
- **Read against:** §4.5 rows 1–2. *"decode ms ≫ predict ms"* means a pass is decode-bound.
  ⚠ No doc sets a HEIC-specific criterion: the step answers *"what a real iOS library decodes"*
  (§4.4).
- **Goes into:** §9.7 / §15.13.7 *A HEIC sample*.

### Part A does **not** close

- ⚠⚠ **D10 and D12 ①'s footprint.** That is *"`phys_footprint` of Photos itself, both sessions open
  through `CoreMlInferenceBackend`, over a pass … with a `photosLike` query during it"* (D10). The
  harness runs one tower per process and is not the backend (§15.13.5). → B6.
- ~~**`physicalMemory` on the iPhone 17e.**~~ ⭐ **Closed by A1 on 2026-10-22:** 8 081 145 856
  bytes (7.53 GiB), above the placeholder gate's 6 979 321 856 (§14.2; `inference-service.md`
  §15.14.7).
- **A floor-class iPhone** (D12): another phone, not this session.

---

## Part B — the product build (⛔ S0b (a)–(d); B6 also ⛔ 5e-2)

The numbered lists this part runs are §14.16's (items 1–11), §14.17.6's (7–12), §14.18.7's
(13–24), §14.18.16's (1–14) and §14.19.9's prose list. Each step cites them by number, so a result
goes back next to its item. **Order:** one install of the flag-on build; the non-destructive steps
on the first download; deletes, kills that count, sign-out and the flag-off reinstall last.

### B0. Build and install Photos with the flag on (one install for B1–B9)

Follow [`ios-signing.md`](ios-signing.md) §3 for the device build and install, with
`photos-ios/PhotosIos.xcodeproj`, scheme `PhotosIos`, and `TENSHU_PHOTOS_ON_DEVICE_AI=YES` on the
`xcodebuild` line. Never edit `project.yml`'s `"NO"` (§14.2, *The build flag*).

```bash
plutil -p <path>/PhotosIos.app/Info.plist | grep TenshuPhotosOnDeviceAi    # must read "YES"
codesign -d --entitlements - --xml <path>/PhotosIos.app | plutil -p -       # the App Group (ios-signing.md §6)
```

- **Closes:** §14.5 *"The flag was never built on"* for a device build.
- **Record:** the build's `CFBundleVersion` (see §14.18.16 item 3; that decision is not the phone's).

### B1. Before any download: the screens, and too little disk

1. **§14.18.7 item 21.** The Settings button on the Library on iOS 26's bar, beside the density
   button, with photographs under the bar. Then *Settings → Models* by hand. **Unpinned** beyond
   the item's text.
2. **§14.16 item 11, first reading.** Record the free space (Settings → General → iPhone Storage)
   before any download.
3. **§14.16 item 8 + §14.18.7 item 16.** Fill the disk to under ~2.85 GB free. Press *Download*.
   **Pass:** *"`download` refuses before a byte moves, with the two figures"*, as row 7a's
   *"Needs N GB free. This iPhone has M GB."* The figures must match Settings → iPhone Storage.
   ⚠ The 2.58 GB budget is measured, but its 256 MiB margin is a *(placeholder)*
   (`CoreMlDiskBudget.PLACEHOLDER_MARGIN_BYTES`, §14.14). Free the space afterwards.
4. **§14.18.7 items 19 and 24.** Run them on every state this session meets, starting here.
   VoiceOver reads each state's sentence and each button. Each sentence fits at the largest
   accessibility text size. The device rows' switches read name, status and value. **Unpinned**
   beyond that.

### B2. Cellular, and a cancel (costs mobile data)

5. **§14.16 item 3.** Start a download with *Use cellular data*, then turn Wi-Fi off. **Pass:** *"it
   goes on over cellular"*.
6. Cancel it. **§14.16 item 11, second reading:** free space after a cancelled download.
   **Unpinned:** the item asks for the figure, not a bound.

### B3. One Wi-Fi download, run through every interruption (§14.16 items 1, 2, 4–7, 9, 10; §14.18.7 items 13–15, 22)

Do the cheap restart first, then let one download carry the rest:

1. **§14.16 item 6.** Start on Wi-Fi, then **force-quit** Photos after a few %. **Pass:** *"iOS
   cancels the transfers; opening Photos starts them again from zero"*.
2. Start again on Wi-Fi (Wi-Fi only, the default). **§14.16 item 1:** time the whole download
   (~743 MB, three transfers, the bar, the commit). ⚠ *"the text tower comes from the bucket,
   never the edge"* (§14.12). Note the transfer times.
3. **§14.16 item 9 + §14.18.7 item 22.** Turn Low Data Mode on for the Wi-Fi network, then join a
   hotspot. **Pass (item 22):** neither says *Waiting for Wi-Fi*. Item 9 only asks to *"say what
   happened"*: **unpinned**.
4. **§14.16 item 2 + §14.18.7 items 15, 22.** Wi-Fi off, cellular on. **Pass:** *"every transfer
   waits, none fails, no cellular data is used"* (check Settings → Mobile Data → Photos). The row
   shows 8a, *"Waiting for Wi-Fi."* Wi-Fi back on: *"they go on"*, and the sentence goes away.
5. **§14.16 item 10.** While the text tower is downloading, turn on flight mode for a minute, and do
   it several times. **Record:** whether the bar restarts and whether the file still verifies.
   **Unpinned:** *"if it can be provoked"*.
6. **§14.16 item 4 + §14.18.7 item 13.** Leave Photos and lock the phone. **Pass:** *"the transfers
   finish; opening Photos installs the model"*, and the screen shows *"the bar is where the system
   got to"*, then *Preparing*, *Checking* and *Ready* *"with no tap"*. ⚠ To keep §14.17.6 item 7's
   foreground timing (B4), come back **before** the download completes.
7. **§14.16 item 7.** Reboot mid-download, unlock, then open Photos. (A6's harness launch can go
   first.) **Unpinned** beyond *"open Photos after unlocking"*.
8. **§14.16 item 5.** Leave Photos and kill it from Xcode while it is in the background (not a
   force-quit). **Pass:** *"the same, through `handleEventsForBackgroundURLSession`"*.
9. **§14.18.7 item 14.** Kill Photos mid-download and reopen the Models screen. **Pass:** *"row 8
   without a tap"*.
10. Let the download complete **in the foreground**, then go to B4.

- **Goes into:** each item's line in §14.16 / §14.18.7, marked done or failed with the date, and
  §14.16's *"Nothing on a phone, and no real background `URLSession` anywhere"* bullet.

### B4. Prepare and the first verdict (§14.17.6 item 7; §14.18.16 items 6–7)

1. **§14.17.6 item 7.** **Pass:** *"`Preparing` for about ten seconds, then `AwaitingVerdict`, then
   the preflight's answer"*. Time each tower.
2. **§14.16 item 11, third reading** and **§14.18.16 item 6:** the free space after it, against the
   on-screen estimate (§14.17.5). **Unpinned:** *"as a user meets them"*.
3. If the answer is not `Ready`: record the standing and the sentence, and stop Part B here. The
   preflight's thresholds are in §14.6. ⚠ `PLACEHOLDER_MIN_AVAILABLE_BYTES` (256 MiB) is a
   *(placeholder)*, and so is the 6.5 GiB `physicalMemory` floor (§14.2).

### B5. With the model `Ready` (§14.18.7 items 18, 20; §14.17.6 item 12; §14.18.16 items 7–8)

1. **§14.18.7 item 18.** The feature question after the first install. Answer *Not now*.
   **Pass:** *"*Not now* writes nothing (read the rows)"*.
2. **§14.18.16 item 7.** Relaunch twice with consent still off. **Record:** whether each launch goes
   through *Checking* and asks the feature question again. **Unpinned:** the item is the question
   itself.
3. **§14.18.7 item 20, first half.** With consent off, the search bar's `noConsent` line names
   Photos' Settings. (Mia's three lines are 5e-2's, §14.18.16 item 4 → B6.)
4. Give both consents (H5's *consent*, in the order the screen asks).
5. **§14.17.6 item 12.** Leave and return to Photos ten times. **Pass:** *"the state does not move
   and a search in flight is answered"*.
6. **§14.18.16 item 8 / §14.5 item 0.** *"that the scene's write lands before iOS suspends Photos"*.
   No doc gives a readout of the record file on a phone: **unpinned** as a direct check. Its
   observable consequence is in B8.

### B6. The foreground pass and H5 (⛔ needs 5e-2)

1. **H5** (§5, PR-5's row): *Index now* over ≥ 200 photos, then search "dog". **Pass:** *"'dog'
   finds dogs"*, as the row words it. That is the condition for the flag (§14.1's 5e row).
   - ⚠ **Once, under Main Thread Checker** (§14.19.9): run Photos from Xcode
     with *Main Thread Checker* on (scheme → Run → Diagnostics; on by default for a Debug run),
     press *Index now*, and let the pass reach a few photos. `IosIndexingSignals` sets
     `UIDevice.batteryMonitoringEnabled` once from the Koin `single` (first resolved on
     `Dispatchers.Default`) and reads `batteryLevel`/`batteryState` per photo on the pass's
     dispatcher. **Pass:** no Main Thread Checker report naming `UIDevice`. **Fail:** one does — the
     one-time enable (at least) then hops to `Dispatchers.Main`; nothing in the code changed for it yet.
2. **§14.19.9, per-photo timing in the product path.** **Unpinned:** no doc names where the product
   path reports it.
3. **D10 / D12 ① / §14.19.9 *memory over a full pass*.** Read Photos' `phys_footprint` over the pass,
   with a `photosLike` search during it, against the phone's foreground memory limit (D10's closing
   condition). ⚠ No doc names the instrument. The thresholds this would replace are
   *(placeholders)*: 6.5 GiB (§14.2) and 256 MiB (§14.6).
4. **§14.19.9, Low Power Mode and battery transitions** (§14.19.1's table is the criterion):
   - Low Power Mode on mid-pass: **pass** = it stops (`LOW_POWER_MODE`). Off again: it starts again by
     itself.
   - Unplugged below 20 %: **pass** = it stops (`BATTERY_LOW`). On a charger, or above 20 %: it starts
     again. ⚠ 20 % is D5's recommendation, to be revisited after A5.
   - Heat (`.serious`): stops; starts again after `.fair` or below **and** `REST_AFTER_HEAT_MS`
     (60 s, *(placeholder)*). ⚠ No doc says how to heat the phone on purpose. Record it if A4 or
     this pass reaches `.serious`.
5. **§14.19.9, leaving mid-pass.** Leave Photos during a long walk. **Pass (§14.19.2):** the pass is
   cancelled inside the `beginBackgroundTask` window, both towers close, and the next press resumes
   from the store. **Record:** how long the cancel takes to land. **Unpinned:** no doc gives the
   window's length or a bound. Also record *"a suspension that catches a pass anyway"*, if one
   happens.
6. **§14.19.9, memory warning before a jetsam.** **Pass (§14.19.1):** a memory warning cancels the
   pass, closes both towers, and *"nothing restarts it but a press"*. ⚠ No doc says how to raise a
   memory warning on a device: **unpinned** as a method.
7. **§14.18.7 item 20, second half** (5e-2): Mia's three iOS lines name Photos' Settings.
8. **PR-7, *Index now* continues** (`ios-on-device-inference.md` §15.6; iOS 26+ phone, flag on, S0b
   switch **off** — with it on PR-7 declines, §15.4). ⚠ Item 5 above then needs a refused request to
   be seen at all: on iOS 26 an accepted task keeps the pass going.
   - Press *Index now* over a long library and leave Photos. **Pass:** the pass goes on; the system
     shows *"Indexing your photos"* with its progress (done of total once the walk ends).
   - Stop the task from the system's live activity. **Pass:** the pass stops at its next photo, the
     towers close, the section says *Stopped when you left Photos*, and the next press continues
     from the store.
   - Let a continued pass finish in the background. **Pass:** the live activity ends as done; the
     store count has grown when Photos is opened again.
   - **Record:** whether a long walk (progress `0 of 0`) is expired as stalled; whether the
     submission is refused (`NotPermitted`: §15.7 Q3, a background mode; `ImmediateRunIneligible`:
     the press stays a foreground pass, and a leave cancels it as in item 5); a swipe-away with a
     task live.
   - **Record, in front:** whether a brief heat deferral happens at all — `.serious` touched and
     cleared before the pass's ending is read. On iOS 26 such a pass ends with *Index now* offered
     and photos left (`ios-on-device-inference.md` §15.2 step 7, §15.6 item 9); if it is seen,
     the rejected alternative there is to be revisited.

### B7. Before the deletes: sign-out (§14.18.7 item 23)

With the Models screen or the feature screen open, sign out or reset. **Pass:** *"no screen stays up
over the wall, and the next one is built over the new runtime"*. Sign back in. ⚠ This is
destructive for the realm's local state. B8 needs a new download either way.

### B8. Deletes, re-downloads, and kills that count (§14.17.6 items 8–10; §14.18.7 item 17; §14.18.16 items 1, 8, 9, 11)

Each item below needs a fresh prepare, and a fresh prepare follows a download. Expect up to three
more ~743 MB downloads.

1. **§14.18.7 item 17 + §14.17.6 item 10.** Delete from *Ready*. **Pass:** *"the row goes, and the
   space returns (the towers at once)"*, and *"`inference/coreml` is empty when `DeleteModel` has
   returned"*. ⚠ No doc gives a readout of `inference/coreml` on a phone other than free space.
2. Download #2. **§14.17.6 item 8.** Send Photos to the background the moment the download
   completes, then reopen it. **Pass:** *"The model is compiled then, and `inference/coreml` holds
   two directories and no `tmp`"*. Then delete again with Photos sent to the background at once
   (item 10's second half).
3. Download #3. **§14.17.6 item 9.** Kill Photos during *Preparing* (Xcode stop) and reopen it.
   **Pass:** *"the tower is compiled again; no directory without a `ready` file is left
   afterwards"*. ⚠ The compile is bracketed (§14.18.16 item 8), so this kill counts **one**
   interrupted load (§14.5 item 0).
4. **§14.18.16 items 1 and 9: a second counted kill.** Kill Photos in the foreground during
   *Checking*, or during a tower open after `Ready`. **Pass (§14.5 item 0, D12):** two make the key
   `Unsuitable`, with the `StoppedLoading` sentence *"Photos stopped trying after it closed twice
   while loading the model on this iPhone."* (placeholder copy, M11) and **Try again**. ⚠ An Xcode
   stop is a SIGKILL. It stands in for jetsam and is not jetsam: whether *"jetsam or Core ML kills at
   the moments the record brackets"* stays **unpinned**.
5. **§14.18.16 item 11 (observable half).** In that state, delete the model. **Pass (§14.5 item 0,
   *No download the key would answer at once*):** the row offers Try again, not Download.
6. Press **Try again**. **Pass:** the fail is withdrawn and the next load runs. Bring the model back
   to `Ready`, or kill it once more: **Pass** then *"This iPhone couldn't run the model well enough…"* with no Try
   again (one press per key, §14.18.16 item 10).
7. **§14.5 item 0 / §14.19.9, swipe-away is not a kill.** During a load, swipe Photos away from the
   app switcher, twice. **Pass:** the phone does **not** become `Unsuitable`. A swipe-away of a
   running app is recorded by `applicationWillTerminate` and counted as transient. A `StoppedLoading`
   after two swipe-aways means that callback did not land. This is also B5 step 6's observable
   consequence.

### B9. Last: the flag-off reinstall and the drain (§14.18.16 item 5; §14.16 *Codex round 11*)

Start a download with the flag-on build. While it is in flight, build and install the **flag-off**
build over it (the same `xcodebuild` line without `TENSHU_PHOTOS_ON_DEVICE_AI=YES`). Launch it.
- **What it tests:** *"that a flag-off build's `getAllTasks` lists an enabled build's tasks, and that
  cancelling them ends the relaunches"* (§14.16). **Unpinned** as a readout: no doc says how to
  observe the relaunches ending. The journal, the staging and an installed model have *"no
  reclaimer in such a build"* (§14.16), so this leaves the phone holding them.
- The phone ends the session on the shipping configuration: flag off.

---

## Part C — across an iOS update (optional, only if one is pending)

After Part A (and B, if run), update iOS, then:

```bash
inference-harness-ios/run-in3m.sh run coreml-ane
inference-harness-ios/run-in3m.sh report ./in3m-ios-C.txt
```

The harness writes the new OS build's vectors beside the old ones (§4.1, *the per-image vectors,
kept on the phone*). The cosines across the update set §3.2's probe threshold (§9.5). ⚠ No doc says
how to compute them from the two files: **unpinned**. A flag-on Photos with a pass behind it also
loses its index on the update, because the fingerprint holds the OS build (§14.18.16 item 3). Record
what the coverage line says; its wording is 5e-2's (§14.19.6).

---

## Not in this session

- D12 ⓪: the load of both towers on an **iOS 17 simulator** (§14.18.16 item 2; Xcode 27.2 offers
  no iOS 17 runtime, checked 2026-11-09).
- §14.18.16 item 3 (build numbering: archives are numbered, development builds are written down),
  item 10 (the *Try again* cap: one press per key), item 11 and item 14: decided or built, not
  phone readings.
- An update's row (§14.17.6 item 11; §14.16's update case). It needs a second catalog entry for the
  same model, and none is published.
- A floor-class iPhone (D10, D12).
