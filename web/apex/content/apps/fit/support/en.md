---
# Tenshu Fit support — store-presence-and-support-pages.md §4 (S6). First-draft copy.
# Body format: support-pages/generate.ts (platform blocks, shot: slots, {source: …}).
# Every feature claim follows store/fit/claims.md (iOS) and the Android code it names; Fit is not
# distributed on macOS or the web yet, so it has no page there. Both platforms' slots resolve to the
# published iPhone and Android phone captures (light, en).
app: fit
name: Tenshu Fit
lang: en
title: Tenshu Fit support
description: Help for Tenshu Fit on {platform}: how to get started, how your health data stays private, answers to common problems, and how to reach support.
platforms: ios, android
defaultPlatform: ios
---

## Get started {#get-started}

Tenshu Fit records your workouts and keeps your activity and health data encrypted and in sync
across your devices. Fit works together with Tenshu Control, which holds your keys and connects
your devices. Set up Control first.

Fit is for keeping track of your activity. It is not a medical device and gives no medical
advice.

::: ios
1. **Install Tenshu Control** from the App Store, and open it.
   ![Tenshu Control's welcome screen on iPhone, with the buttons "Start fresh on this device", "I have Tenshu on another device" and "Recover from a recovery phrase"](shot:control/01-welcome)
2. **Set up Control.** Tap **Start fresh on this device**, then **Continue**. Write down your
   recovery phrase and keep it somewhere safe. Then tap **Turn on sync** and enter your email
   address to get a storage plan. Without one, your data stays on this iPhone.
   ![Tenshu Control saying "You're set up", with a "Turn on sync" link above the 24 words of the recovery phrase](shot:control/03-recovery-phrase)
3. **Install Tenshu Fit** from the App Store, and open it. Fit finds Control on its own. If it
   asks you to set up Tenshu Control, finish step 2 first. Fit opens on **Today**, which says
   **No activity yet** until you bring in data or record a workout.
   ![Tenshu Fit's Today screen: active minutes against the daily goal, steps, distance and energy, this week's bars and the morning's run](shot:01-home)
4. **Bring in your activity from Apple Health.** Tap **Import from Apple Health**, on **Today**
   or under **Profile** → **Data sources**, and choose what Fit may read. Fit imports the last
   30 days, and imports again each time you open it.
   ![Tenshu Fit's Profile, with Tenshu Health and Apple Health under "Data sources" and Google Takeout under "Batch import"](shot:07-profile)
5. **Record a workout.** Tap **Record**, pick **Run**, **Walk**, **Ride** or **Strength**, then
   **Start**. The first time, Fit explains that it maps your route, then asks for your location.
   When you're done, tap **Finish**, then **Save workout**. Your workout appears in **History**,
   with its route on a map.
   ![A run in Tenshu Fit: time, distance, pace and heart rate, the route on a map, and a heart-rate chart](shot:03-workout)
:::

::: android
1. **Install Tenshu Control** from Google Play, and open it.
   ![Tenshu Control's welcome screen on Android, with the buttons "Start fresh on this device", "I have Tenshu on another device" and "Recover from a recovery phrase"](shot:control/01-welcome)
2. **Set up Control.** Tap **Start fresh on this device**, then **Continue**. On **You're set
   up**, tap **Turn on sync** and enter your email address to get the free storage plan, so
   your data can reach your other devices. Then tap **Continue**. Without a plan, your data stays on
   this phone, and you can pick one later under **Services**. Write down your recovery phrase
   under **Settings** → **Set up account recovery**, and keep it somewhere safe.
   ![Tenshu Control's "Pick a plan" screen with the Free plan](shot:control/10-services)
3. **Install Tenshu Fit** from Google Play, and open it. Fit connects to Control on its own. If
   it says **Set up Tenshu Control**, finish step 2 first.
   ![Tenshu Fit's Home screen on Android: today's active minutes against the daily goal, steps, distance and energy, and this week's energy bars](shot:01-home)
4. **Allow Health Connect.** The first time you open Fit, it asks to read your health data from
   Health Connect. Choose what to allow. Fit imports the last 30 days, and imports again each
   time you open it. Health Connect shares older data only if you allow it: go to **Profile** →
   **Data sources** → **Health Connect** and tap **Import your full history**.
   ![Tenshu Fit's Profile on Android, with Health Connect under "Data sources" and Google Takeout under "Batch import"](shot:07-profile)
5. **Record a workout.** Tap **Record**, pick **Run**, **Walk**, **Ride** or **Strength**, then
   **Start**. The first time, Fit explains what it uses while you record. Tap **Continue** and
   allow location. When you're done, tap **Finish**, then **Save workout**. Your workout appears
   in **History**, with its route.
   ![A run in Tenshu Fit on Android: time, distance, pace and heart rate, the route, and a heart-rate chart](shot:03-workout)
:::

## How Tenshu Fit keeps your health data private {#privacy}

- **Your workouts and health data are encrypted on your device before they are uploaded.**
  Tenshu's servers store only encrypted data, and they don't have the keys to read it. Only your
  own devices can read your health data. The server can still see some facts it needs to sync:
  how much you store, how many devices you have, and when they sync.
  {source: docs/sync/threat-model.md | Threat model, invariant I1, asset A34 (health and fitness data), asset A6 (metadata) and T-T-07}
  {source: AGENTS.md | House Rule 1: the relay sees ciphertext only}
- **Fit has no ads and no analytics, and it does not share your health data with anyone.**
  {source: store/fit/privacy-label.md | Fit's privacy label: no tracking, health data never shared}
  {source: fit-ios/Resources/PrivacyInfo.xcprivacy | Fit's iOS privacy manifest: no tracking, nothing collected}
- **Fit uses your location only while you record a workout.**
  {source: fit-shared-data/src/iosMain/kotlin/app/tenshu/fit/data/IosRecordingEngine.kt | iOS recorder: location starts and stops with a recording}
  {source: fit-android/src/main/AndroidManifest.xml | Android manifest: GPS only during an active workout, no background location}
- **Without a storage plan, your data stays on this device.** Fit works on one device with no
  account. Your data only goes to Tenshu's servers, encrypted, once you turn on sync.
  {source: docs/adr/0019-storage-capacity-gating.md | ADR 0019, storage capacity gating: no subscription means zero relay traffic}

::: ios
- **Apple Maps is off unless you turn it on.** Fit draws workout maps from map data on your
  device. If you turn on **Use Apple Maps** under **Profile**, Apple serves the map, and sees
  which workout you open and when. {source: docs/proposals/ios-apple-maps-option.md | Apple Maps option, D9/D10: off by default, per app}
  {source: ios-basemap/Sources/MapRendererSection.swift | The in-app Apple Maps disclosure}
:::

::: android
- **On Android, Fit itself cannot use the internet.** It has no internet permission. Your data
  reaches your other devices through Tenshu Control, encrypted.
  {source: fit-android/src/main/AndroidManifest.xml | Android manifest: the INTERNET permission is removed}
  {source: docs/proposals/fit-app.md | Fit app, §10: Fit runs on Control's proxy and keeps no store of its own}
:::

## Common problems {#problems}

### Fit says "Set up Tenshu Control"

Fit keeps your health data in Tenshu Control, so Control has to be installed and set up on the
same device first. Install Tenshu Control, open it, and choose **Start fresh on this device**,
or **I have Tenshu on another device** if you already use Tenshu. Then open Fit again.

::: ios
Once Control is set up, it does not need to stay open. Fit works on its own.
:::

### Fit says "Can't reach Tenshu Control"

::: ios
Fit shows what it already has, but cannot load anything new or record. Tap **Set up** and follow
the steps in Tenshu Control, then return to Fit.
:::

::: android
Fit cannot load your health data. Tap **Open Control** and check that Tenshu Control is
installed and set up, then return to Fit.
:::

::: ios
### My data stays on this iPhone

Open Tenshu Control and go to **Settings**. If it says **Local only**, this realm has no storage
plan yet, so your data stays on this iPhone. Open **Storage** and pick a plan. The free plan needs a
verified email address. A redemption code works without one.
:::

::: android
### Fit says "Not syncing — this device has no storage plan"

Your data is safe on this device, but it cannot reach your other devices yet. Tap **Set up
storage**, or open Tenshu Control and pick a storage plan under **Services**. The free plan
needs a verified email address. A redemption code works without one.

### Fit says "Storage full"

Your data stays on this device and nothing is deleted, but new data can't sync. Free up space,
or choose a larger plan in Tenshu Control under **Services**.
:::

::: ios
### Fit doesn't show my Apple Health data

Fit reads Apple Health only after you tap **Import from Apple Health**, and only the types you
allowed. It brings in the last 30 days. To let Fit read more types, change its access in the
Health app, then open Fit again.
:::

::: ios
### Fit says "Apple Health isn't available on this device"

Apple Health on iPad needs iPadOS 17 or later. You can still record workouts, or import a file
(see below).
:::

::: android
### Fit says it needs permission to read your health data

Tap **Try again**: Fit opens Health Connect, where you allow Fit to read your data.

### Fit only has my last 30 days

Health Connect shares only the last 30 days unless you allow older data. Go to **Profile** →
**Data sources** → **Health Connect** and tap **Import your full history**.
:::

### My Google Fit history is missing

::: android
Google Fit does not pass its history to Health Connect, so Fit cannot read it from there. If
Fit shows a notice about Google Fit under **Profile** → **Data sources**, follow it. For your
older Google Fit workouts, import a Google Takeout export.
:::

Download your Google Fit data from Google Takeout, then go to **Profile** → **Batch import** →
**Google Takeout**, tap **Import again** and pick the .zip file. You can also pick a single .gpx
or .tcx workout file.

### My other device doesn't show my workouts

Workouts reach your other devices only after the device that has them has uploaded them. Check
these, in order:

1. Both devices are in the same realm. In Tenshu Control, the device list should show both.
2. The device that has the workouts has a storage plan (see above).
3. Both devices are online. Open Fit on both and give it a few minutes.

::: android
### My recording stopped early

Some phones stop apps running in the background to save battery. If yours stopped a recording,
Fit says **Your phone stopped the recording**; tap the notification to pick it up where it left
off. To prevent it, open **Record**: if Fit shows a hint about battery optimisation or your
phone's background settings, tap it and allow Fit to run in the background.
:::

## Requirements {#requirements}

::: ios
- iOS 16 or later, on iPhone. On iPad, Apple Health needs iPadOS 17 or later.
- Tenshu Control, installed and set up on the same device.
:::

::: android
- Android 8.0 or later.
- Tenshu Control, installed and set up on the same device.
- To import from Health Connect: Android 9 or later, with Health Connect on your phone.
:::

- To sync between devices: a storage plan. The free plan has 5 GB.

## Contact {#contact}

Write to [support@tenshu.app](mailto:support@tenshu.app). Tell us which app and device you use,
and what you see on screen.

**Support will never ask for your recovery phrase.** Nobody at Tenshu needs it, ever. Anyone who
has it can open everything in your account.
