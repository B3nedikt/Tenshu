# PIO-7 PR-6: the overnight index wake on the iPhone

The phone-only half of PR-6 (the `BGProcessingTask` that indexes Photos' library overnight on the
charger). The plan is [`ios-on-device-inference.md`](../proposals/ios-on-device-inference.md) §16 (below:
*the plan*); this runbook records no results. Each result goes into §16.6 of the plan.

⚠ The wake is behind the on-device AI build flag, like everything of PIO-7: a build without
`TENSHU_PHOTOS_ON_DEVICE_AI=YES` registers the handler and never submits a request. Faces ride the same
pass when `TENSHU_PHOTOS_FACES=YES` is set too.

---

## 0. The build and the phone

1. A **development** build of Photos with the flag on, signed for the company team, installed from Xcode
   (scheme `PhotosIos`, build settings `TENSHU_PHOTOS_ON_DEVICE_AI=YES` and, for faces,
   `TENSHU_PHOTOS_FACES=YES`). Control provisioned, the model downloaded and *Ready*, search switched
   on in Photos' Settings (both switches), the library not yet fully indexed (a few hundred photos
   not yet seen is ideal: stop an *Index now* half way, or add photos in Drive).
2. Confirm the built `Info.plist` carries both halves (a missing one fails silently):
   `UIBackgroundModes` = `processing`, and `BGTaskSchedulerPermittedIdentifiers` contains
   `app.tenshu.photos.processing`.
3. ⚠ **Background App Refresh** must be on for Photos (Settings ▸ General ▸ Background App Refresh),
   and Low Power Mode off: either one off and iOS never grants the wake.

## 1. The wake, forced from the debugger (once, with Xcode attached)

What it shows: the handler runs, takes the store's task token, re-arms, indexes, and completes; and an
expiry stops the pass at its next photo and completes the task with `false`.

1. Run Photos from Xcode on the phone. Open it, then send it to the background (home gesture). The
   console prints `[photos-wake] armed`: the leave armed the wake (consent on, photos left).
   ⚠ **If it does not print**, nothing below is meaningful: check §0.1's switches. A line
   `[photos-wake] submit refused: …` is iOS saying no (Background App Refresh off, §0.3).
2. Bring Photos back, then pause it in Xcode (⏸, *Debug ▸ Pause*) and in the lldb console type:

   ```
   e -l objc -- (void)[[BGTaskScheduler sharedScheduler] _simulateLaunchForTaskWithIdentifier:@"app.tenshu.photos.processing"]
   ```

   Continue (▶), then send Photos to the background. **Pass:** the console shows, in this order,
   `[photos-wake] launched`, `[photos-wake] checked ms=…` (the launch's store check; note the
   figure, and that no `[gate]` main-thread retry lines appear around it — plan §16.6 item 7),
   `[photos-wake] armed` (the re-arm, before any photo),
   `[photos-wake] pass visited=… embedded=…` and `[photos-wake] ended outcome=COMPLETED … success=true`
   (or `outcome=REFUSED` if the phone warmed to `.serious`), with no `0xdead10cc` crash afterwards.
   ⚠ A forced launch runs whether or not a request was armed: only §2's real nights show that the
   arming works.
3. Repeat step 2, and while the pass runs (the line `[photos-wake] pass …` has not yet printed), pause
   again and type:

   ```
   e -l objc -- (void)[[BGTaskScheduler sharedScheduler] _simulateExpirationForTaskWithIdentifier:@"app.tenshu.photos.processing"]
   ```

   Continue. **Pass:** `[photos-wake] expired (running=true)`, then
   `[protection] token-expiry interrupt (expiry of 'task:photos-processing-wake')`, then
   `[photos-wake] ended outcome=none expired=true success=false` within ~3 s (the plan's
   `EXPIRY_STOP_MS`, a placeholder); no second `ended` line; no `0xdead10cc` crash report afterwards
   (`xcrun devicectl device info files --domain-type systemCrashLogs …`). Then *Index now* in front:
   it continues from the store (the photos the wake did are not done again).
4. ⚠ Time how long the `expirationHandler` blocks: from `expired` to the token's `QUIESCED` line. It
   is the store's B.23.5 item 3, a hard gate: if iOS allows less than ~2.5 s, the task's time cannot
   stay "no deadline" (`ios-shared-store-sqlite.md` B.23 (441)).

## 2. Three real nights

No Xcode, no forced launch: the phone on its charger overnight, locked, Photos last left in the
background (so the leave armed the wake). Before the first night, leave enough unindexed photos that
one wake cannot finish them (several thousand on the 17e, by the plan's ~37 ms a photo: ~3 minutes
per thousand).

The wake writes its own journal, so nothing needs to be attached overnight:
`Library/Application Support/photos-private/processing-wakes.log` in Photos' container (the private
inference root; a line per arm, withdrawal, launch and end, UTC times; the older half dropped past 64 KB). In the morning, unlock the phone and copy it off:

```
DEV=$(xcrun devicectl list devices | awk '/connected/{print $(NF-2)}')
xcrun devicectl device info files --device $DEV --domain-type appDataContainer \
  --domain-identifier app.tenshu.photos.ios | grep processing-wakes
xcrun devicectl device copy from --device $DEV --domain-type appDataContainer \
  --domain-identifier app.tenshu.photos.ios \
  --source "<the path the listing printed>" --destination ./processing-wakes-night1.log
```

Record, per night, into the plan's §16.6 table:

Also note any `submit refused` line, and each wake's `checked ms=` figure.

| Night | Wakes granted (`launched` lines) | Minutes per wake (`minutes=` on each `ended`) | Photos per wake (`embedded=` / `visited=`) | How each ended (`outcome=`, `expired=`) | Armed again? (an `armed` line after each launch) |
|---|---|---|---|---|---|

⚠ **A night with no `launched` line is a result, not a failure of the runbook**: iOS grants
`BGProcessingTask` windows when it chooses (§6 item 2 of the plan). Say whether the phone was on the
charger and locked. Three such nights in a row mean the request is not armed or not granted: check the
`armed` lines first.

**The plan's success line** (PR-6's row): the library finishes indexing overnight on the charger.

## 3. App Review

The `processing` background mode needs a justification in App Store Connect: *"Photos indexes the
user's photos on the device, so they can be searched by what they show, only while the phone is
charging."*
