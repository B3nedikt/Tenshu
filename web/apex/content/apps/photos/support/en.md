---
# Tenshu Photos support — store-presence-and-support-pages.md §4 (S6). First-draft copy.
# Body format: support-pages/generate.ts (platform blocks, shot: slots, {source: …}).
# Every feature claim follows store/photos/claims.md (iOS) and the code it names: photos-ios for
# iPhone and iPad, photos-shared-ui and photos-shared-logic for Android. Photos is not distributed
# on macOS or the web yet, so it has no page there. iOS slots resolve to the published iPhone
# captures (ios/iphone69, light, en); Photos has no published Android phone captures
# (android/phone), so the Android block shows Control's only.
# ⚠ iOS has no face grouping (PIO-8), no share action and, in the shipping build, no on-device AI
# (TENSHU_PHOTOS_ON_DEVICE_AI = NO): keep the two platform blocks apart rather than describing
# Android's People on the iPhone page. Neither platform can share, export or edit a photo (PH-G11).
app: photos
name: Tenshu Photos
lang: en
title: Tenshu Photos support
description: Help for Tenshu Photos on {platform}: how to get started, how your photos stay private, answers to common problems, and how to reach support.
platforms: ios, android
defaultPlatform: ios
---

## Get started {#get-started}

Tenshu Photos shows your photos and videos in one library, the same on each of your devices.
Tenshu Drive backs up your photos, and Photos shows what has been backed up, from this device and
your others. Photos works together with Tenshu Control, which holds your keys and connects your
devices. Set up Control first.

::: ios
On iPhone and iPad, Photos does not back anything up itself: Tenshu Drive does.

1. **Install Tenshu Control** from the App Store, and open it.
   ![Tenshu Control's welcome screen on iPhone, with the buttons "Start fresh on this device", "I have Tenshu on another device" and "Recover from a recovery phrase"](shot:control/01-welcome)
2. **Set up Control.** Tap **Start fresh on this device**, then **Continue**. Write down your
   recovery phrase and keep it somewhere safe. Then tap **Turn on sync** and enter your email
   address to get a storage plan. Without one, your data stays on this iPhone. If you already use
   Tenshu on another device, tap **I have Tenshu on another device** instead, so your photos can
   reach this iPhone.
   ![Tenshu Control saying "You're set up", with a "Turn on sync" link above the 24 words of the recovery phrase](shot:control/03-recovery-phrase)
3. **Back up your photos with Tenshu Drive.** To put photos from this iPhone in your library,
   install Tenshu Drive from the App Store and back up your photos there. Drive asks for access to
   your photo library. Photos never does. If your photos are backed up from another device
   already, you can skip this step.
4. **Install Tenshu Photos** from the App Store, and open it. Photos finds Control on its own. If
   it asks you to set up Tenshu Control, finish step 2 first. Photos opens on **Library**, which
   says **No Photos Yet** until your backed-up photos arrive.
   ![Tenshu Photos' library on iPhone: a mosaic of photos, grouped by day](shot:01-library)
5. **Browse your library.** The button at the top of **Library** changes how it is grouped: a
   mosaic, or by day, month or year. Drag the scrubber at the side to jump through the years.
   ![Tenshu Photos' library grouped by month](shot:05-months)
6. **Open a photo.** Tap it to see it full screen. Pinch to zoom, and swipe to the next one. Tap
   the heart to add it to your **Favourites**. Under **More**, **About** shows its date, details
   and albums, and you can **Add to Album** or **Move to Trash**.
   ![One photo full screen in Tenshu Photos, marked as a favourite with its heart](shot:04-viewer)
7. **Make albums.** Open **Collections** and tap **New album**. To add photos, touch and hold one
   in **Library**, tap the others you want, and tap **Add to Album**. In an album, the
   **Album options** button renames or deletes it. Deleting an album keeps its photos.
   ![An album in Tenshu Photos, with its photos in a grid](shot:03-album)
8. **Find duplicates and empty the trash.** **Collections** also has your **Favourites**, your
   years, **Duplicates** and the **Trash**. **Duplicates** finds exact copies of the same photo.
   Each set says **N identical copies — tap the one to keep**: tap that copy, and confirm **Move
   the other copies to the trash?** with **Move to Trash**. With the **Delete permanently** switch
   on, the other copies are deleted for good instead (**Delete for Good**). In the **Trash**, restore a photo,
   delete it for good, or empty the whole trash.
   ![Tenshu Photos' Collections: Favourites, Duplicates, albums with their covers, the years and the Trash](shot:02-collections)
9. **Search.** Tap **Search** and type part of a file name, such as `IMG_74`.
:::

::: android
1. **Install Tenshu Control** from Google Play, and open it.
   ![Tenshu Control's welcome screen on Android, with the buttons "Start fresh on this device", "I have Tenshu on another device" and "Recover from a recovery phrase"](shot:control/01-welcome)
2. **Set up Control.** Tap **Start fresh on this device**, then **Continue**. On **You're set
   up**, tap **Turn on sync** and enter your email address to get the free storage plan, so
   your photos can reach your other devices. Then tap **Continue**. Without a plan, your data stays on
   this phone, and you can pick one later under **Services**. Write down your recovery phrase
   under **Settings** → **Set up account recovery**, and keep it somewhere safe.
   ![Tenshu Control's "Pick a plan" screen with the Free plan](shot:control/10-services)
3. **Back up your photos with Tenshu Drive.** To put photos from this phone in your library,
   install Tenshu Drive from Google Play and set up backup there. If your photos are backed up from
   another device already, you can skip this step.
4. **Install Tenshu Photos** from Google Play, and open it. Photos connects to Control on its
   own. If it says **Set up Tenshu Control**, finish step 2 first. Photos opens on **Photos**,
   your library.
5. **Browse your library.** The button at the top changes how it is grouped: a mosaic, or by day,
   month or year. Drag the scrubber at the side to jump through the years. Tap a photo to see it
   full screen, pinch to zoom, and swipe to the next one. Tap the star to make it a favourite.
6. **Make albums.** Open **Collections** and tap **New album**. To add photos, touch and hold one
   in your library, tap the others you want, and tap the **+** icon (**Add to album**). The bin
   icon moves them to the trash. In a photo, **More** offers the same two.
7. **Find duplicates and empty the trash.** **Collections** also has your favourites, your years,
   **Duplicates** and the **Trash**. **Duplicates** finds exact copies of the same photo. Each set
   says **N identical copies — tap the one to keep**: tap that copy, and confirm **Move the other
   copies to the trash?** with **Move to Trash**. With the **Delete permanently** switch on,
   the other copies are deleted for good instead (**Delete for Good**). In the **Trash**, which **Settings** opens
   too, restore a photo, delete it for good, or empty the whole trash.
8. **Search.** Tap the magnifying glass at the top of your library and type part of a file name.
9. **Back up from Photos, and see what is backing up.** Once this phone backs up at least one
   folder, **Settings** lists them under **Backed up from this device**, with what each is doing.
   Turning one off stops it backing up. Below it:
   - **Back up a folder** lists this phone's photo folders. A folder you turn on is backed up by
     Photos or Drive, whichever is running: the two share one backup. Photos does not ask for
     access to your photos itself, so if the list is empty, allow it in your phone's **Settings**
     → **Apps** → **Photos** → **Permissions** → **Photos and videos**.
   - **Skip photos already in Tenshu** doesn't upload a photo whose exact copy is already saved,
     even from another device.
   - **Play videos in the grid** lets videos play silently in your library, one at a time.

   With nothing backed up from this phone yet, **Settings** shows only the **Trash** and says to
   set up backup in Tenshu Drive.
10. **Group photos by people (optional).** On Android, Photos can show the people in your photos
    under **Collections** → **People**. It is off until you turn it on: in Tenshu Control, open
    **Settings** → **Models**, download the face models, and turn on **Group photos by people**
    for **This device**. People appear after the phone has looked through your photos, which it
    does while it is idle and charging.
:::

## How Tenshu Photos keeps your photos private {#privacy}

- **Your photos are encrypted on your device before they are uploaded.** Tenshu's servers store
  only encrypted data, and they don't have the keys to read it. Only your own devices can read
  your photos. Your albums and favourites are encrypted too. The server can still see some facts
  it needs to sync: how much you store, how many devices you have, and when they sync.
  {source: docs/sync/threat-model.md | Threat model, invariant I1, assets A5 (user data) and A6 (metadata), T-T-07}
  {source: AGENTS.md | House Rule 1: the relay sees ciphertext only}
- **Photos has no ads and no analytics.**
  {source: store/photos/privacy-label.md | Photos' privacy label: no tracking, no advertising or analytics SDK}
  {source: photos-ios/Resources/PrivacyInfo.xcprivacy | Photos' iOS privacy manifest: no tracking, nothing collected}
- **Without a storage plan, your photos stay on this device.** Your photos only go to Tenshu's
  servers, encrypted, once you turn on sync.
  {source: docs/adr/0019-storage-capacity-gating.md | ADR 0019, storage capacity gating: no subscription means zero relay traffic}

::: ios
- **Photos does not read the photo library on your device.** Tenshu Drive backs up the photos
  you choose, and Photos shows them from your encrypted library.
  {source: photos-ios/project.yml | No PhotoKit and no photo library usage description}
  {source: store/photos/privacy-label.md | "Photos itself reads no photo library"}
:::

::: android
- **On Android, Photos cannot use the internet itself.** Your photos reach your other devices
  through Tenshu Control, encrypted. If you allow it to see your photos and videos, Photos uses
  that only to list this phone's folders for **Back up a folder**, and backs up only the folders
  you turn on.
  {source: photos-android/src/main/AndroidManifest.xml | Android manifest: the INTERNET permission is removed; READ_MEDIA_IMAGES and READ_MEDIA_VIDEO to list the phone's folders}
  {source: photos-shared-ui/src/commonMain/kotlin/app/tenshu/photos/ui/screens/settings/SettingsScreen.kt | Settings: Back up a folder, one backup shared with Drive}
- **Grouping by people happens on your phone, and only once you turn it on.** Tenshu Control
  finds the faces on the phone. The face library it uses, MediaPipe, sends nothing to Google or
  anyone else. The groups are kept in your encrypted library, like your albums.
  {source: docs/proposals/inference-service.md | Inference service: on-device models, two-switch consent where absence means no}
  {source: control/src/main/AndroidManifest.xml | Control's Android manifest removes the Google datatransport upload backend}
:::

## Common problems {#problems}

### Photos asks you to set up Tenshu Control

Photos keeps your library in Tenshu Control, so Control has to be installed and set up on the
same device first. Install Tenshu Control, open it, and choose **Start fresh on this device**, or
**I have Tenshu on another device** if you already use Tenshu. Then open Photos again.

::: ios
Once Control is set up, it does not need to stay open. Photos works on its own.
:::

### Photos says "Can't reach Tenshu Control"

::: ios
Photos shows what it already has, but cannot load anything new. Tap **Set up** and follow the
steps in Tenshu Control, then return to Photos.
:::

::: android
Your photos appear once Control is running again. Tap **Open Control** and check that Tenshu
Control is installed and set up, then return to Photos.
:::

### My library is empty

Photos shows only what Tenshu Drive has backed up, on this device or another one in your realm.
Check these, in order:

1. Tenshu Drive is backing up photos on at least one of your devices.
2. The device that backs them up has a storage plan, if the photos come from another device (see
   below).
3. Both devices are online. Open Photos on both and give it a few minutes.

::: ios
### A photo says "This photo hasn't been downloaded to this device yet"

The photo is in your library, but its image has not reached this iPhone yet. It arrives once the
device that backed it up has uploaded it and both devices are online. That device needs a storage
plan (see below).

### Photos says "Not syncing — this device has no storage plan"

The banner at the top of Photos says your files stay on this device: on iPhone and iPad it uses
the same words in every Tenshu app, and here it means your photos. They are safe, but they cannot
reach your other devices yet. Tap **Manage storage** to open Tenshu Control, then pick a storage
plan (see below).

### Photos says "Storage full"

The banner shows how much of your plan is used. Your photos stay on this device and nothing is
deleted, but new photos can't sync until you free up space or get more. Tap **Manage storage** to
open Tenshu Control, and choose a larger plan under **Storage**.

### My photos stay on this iPhone

Open Tenshu Control and go to **Settings**. If it says **Local only**, this realm has no storage
plan yet, so your data stays on this iPhone. Open **Storage** and pick a plan. The free plan needs
a verified email address. A redemption code works without one.

### People stays empty on my iPhone

Photos on iPhone and iPad does not group faces itself yet. **People** shows the people grouped
on an Android phone in your realm, once they have synced. **Places** shows the places Tenshu
Timeline has synced.
:::

::: android
### Photos says "Not syncing — this device has no storage plan"

Your photos are safe on this device, but they cannot reach your other devices yet. Tap **Set up
storage**, or open Tenshu Control and pick a storage plan under **Services**. The free plan needs
a verified email address. A redemption code works without one.

### Photos says "Storage full"

Your photos stay on this device and nothing is deleted, but new photos can't sync. Free up space,
or tap **Manage storage** and choose a larger plan in Tenshu Control under **Services**.

### Photos says it is newer or older than Tenshu Control

Photos and Control on the same device have to be from matching versions to keep syncing. Update
both from Google Play. Everything already on the device is safe.

### People is empty

The line under **People** says why:

- **To see people here, turn on "Group photos by people"**: open Tenshu Control, **Settings** →
  **Models**, and turn it on for **This device**.
- **To see people here, download the face models**: download them in Tenshu Control, under
  **Settings** → **Models**.
- **People appear after this device indexes your photos**: the phone looks through your photos
  while it is idle and charging. Leave it on the charger for a while.
:::

### I can't share or edit a photo

Photos cannot share, export or edit photos yet.

## Requirements {#requirements}

::: ios
- iOS 16 or later, on iPhone or iPad.
- Tenshu Control, installed and set up on the same device.
- Tenshu Drive, to back up photos from this device.
:::

::: android
- Android 7.0 or later.
- Tenshu Control, installed and set up on the same device.
- Tenshu Drive, to back up photos from this device.
:::

- To sync between devices: a storage plan. The free plan has 5 GB.

## Contact {#contact}

Write to [support@tenshu.app](mailto:support@tenshu.app). Tell us which app and device you use,
and what you see on screen.

**Support will never ask for your recovery phrase.** Nobody at Tenshu needs it, ever. Anyone who
has it can open everything in your account.
