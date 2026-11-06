---
# Tenshu Drive support — store-presence-and-support-pages.md §4. First-draft copy (S1).
# Body format: support-pages/generate.ts (platform blocks, shot: slots, {source: …}).
app: drive
name: Tenshu Drive
lang: en
title: Tenshu Drive support
description: Help for Tenshu Drive on {platform}: how to get started, how your files stay private, answers to common problems, and how to reach support.
platforms: ios, android, macos, web
defaultPlatform: web
---

## Get started {#get-started}

Tenshu Drive keeps your files encrypted and in sync across your devices. Drive works together
with Tenshu Control, which holds your keys and connects your devices. Set up Control first.

::: ios
1. **Install Tenshu Control** from the App Store, and open it.
   ![Tenshu Control's welcome screen on iPhone, with the buttons "Start fresh on this device", "I have Tenshu on another device" and "Recover from a recovery phrase"](shot:control/01-welcome)
2. **Set up Control.** Tap **Start fresh on this device**, then **Continue**. Write down your
   recovery phrase and keep it somewhere safe. Then tap **Turn on sync** and enter your email
   address to get a storage plan. Without one, your files stay on this iPhone.
   ![Tenshu Control saying "You're set up", with a "Turn on sync" link above the 24 words of the recovery phrase](shot:control/03-recovery-phrase)
3. **Install Tenshu Drive** from the App Store, and open it. Drive finds Control on its own.
   If it asks you to set up Tenshu Control, finish step 2 first.
   ![Tenshu Drive's empty file list, saying "No files yet"](shot:07-empty)
4. **Upload a file.** Tap **+**, then **Choose File** or **Choose Photo**.
   ![Tenshu Drive's add menu, with "New Folder", "Choose File" and "Choose Photo"](shot:05-add)
5. **See it on another device.** Set up Tenshu Control on your other device and choose
   **I have Tenshu on another device**. On this iPhone, open Control and go to **Settings** →
   **Add a device**. Check that both devices show the same code. Open Drive on the other
   device. Your file appears within a few minutes.
   ![Tenshu Control's "Add a device" screen on "Show a code", with a QR code and a pairing code](shot:control/09-add-device)
:::

::: android
1. **Install Tenshu Control** from Google Play, and open it.
   ![Tenshu Control's welcome screen on Android, with the buttons "Start fresh on this device", "I have Tenshu on another device" and "Recover from a recovery phrase"](shot:control/01-welcome)
2. **Set up Control.** Tap **Start fresh on this device**. Then open **Services** and pick a
   storage plan, so your files can reach your other devices. Without one, your files stay on
   this phone. Write down your recovery phrase under **Settings** → **Set up account recovery**,
   and keep it somewhere safe.
   ![Tenshu Control's "Pick a plan" screen with the Free plan](shot:control/10-services)
3. **Install Tenshu Drive** from Google Play, and open it. Drive connects to Control on its own.
   If it says **Set up Tenshu Control**, finish step 2 first.
   ![Tenshu Drive's empty file list, saying "No files here yet"](shot:07-empty)
4. **Upload a file.** Tap **+**, then **File** under **Upload**.
   ![Tenshu Drive's add menu, with "Folder" and "File" under "Create", and "File", "Folder upload" and "Images" under "Upload"](shot:05-add)
5. **See it on another device.** Set up Tenshu Control on your other device and choose
   **I have Tenshu on another device**. On this phone, open Control and go to **Settings** →
   **Synchronization & Devices** → **+**. Check that both devices show the same code. Open Drive on
   the other device. Your file appears within a few minutes.
   ![Tenshu Control's "Add a device" screen on "Show a code", with a QR code and a manual code](shot:control/09-add-device)
:::

::: macos
1. **Install Tenshu Control** on your Mac, and open it. Control lives in the menu bar, at the
   top of your screen.
   ![Tenshu Control's welcome window on a Mac, with the buttons "Start fresh on this device", "I have Tenshu on another device" and "Recover from a recovery phrase"](shot:control/01-welcome)
2. **Set up Control.** Click **Start fresh on this device**. Then open **Services** and pick a
   storage plan, so your files can reach your other devices. Without one, your files stay on
   this Mac. Write down your recovery phrase when Control shows it, and keep it somewhere safe.
   ![Tenshu Control's Services screen with the Free, Basic and Premium plans](shot:control/10-services)
3. **Install Tenshu Drive** on your Mac, and open it. Drive connects to Control on its own.
   If it says **Set up Tenshu Control**, finish step 2 first.
   ![Tenshu Drive's empty file list on a Mac, saying "No files here yet"](shot:07-empty)
4. **Upload a file.** Click **Upload**, then **File**.
   ![Tenshu Drive's upload menu, with "File", "Folder upload" and "Images"](shot:05-add)
5. **See it on another device.** Set up Tenshu Control on your other device and choose
   **I have Tenshu on another device**. On this Mac, open Control and go to
   **Realm Devices** → **Add new device**. Check that both devices show the same code. Open
   Drive on the other device. Your file appears within a few minutes.
   ![Tenshu Control's "Add a device" window, with "Scan a code" and "Show a code"](shot:control/09-add-device)
:::

::: web
1. **Open [tenshu.app/start](/start)** in your browser. In the browser, Tenshu Control is part
   of the website, so there is nothing to install.
   ![The "Welcome to Tenshu" page, with "Sign up free", "I have Tenshu already" and "Just try it locally"](shot:control/01-welcome)
2. **Set up Tenshu.** Choose **Sign up free**. Save your recovery phrase: write the 24 words on
   paper or keep them in a password manager. Don't take a screenshot.
   ![The "Save your recovery phrase" page with 24 numbered words](shot:control/03-recovery-phrase)
3. **Open Drive.** Go to [tenshu.app/drive](/drive).
   ![Tenshu Drive in the browser, with an empty file list and an Upload button](shot:07-empty)
4. **Upload a file.** Click **Upload** and pick a file.
   ![Tenshu Drive in the browser while a file uploads](shot:08-uploading)
5. **See it on another device.** Set up Tenshu Control on your phone or computer and choose
   **I have Tenshu on another device**. In the browser, open **Devices** → **Pair a new
   device**. Check that both show the same code. Open Drive on the other device. Your file
   appears within a few minutes.
   ![The Devices page in the browser, adding a new device](shot:control/09-add-device)
:::

## How Tenshu Drive keeps your files private {#privacy}

- **Your files are encrypted on your device before they are uploaded.** Tenshu's servers store
  only encrypted data, and they don't have the keys to read it. Only your own devices can read
  your files. {source: docs/sync/threat-model.md | Threat model, invariant I1 and asset A5 (user data)}
  {source: AGENTS.md | House Rule 1: the relay sees ciphertext only}
- **File and folder names are encrypted too.** The server can still see some facts it needs to
  sync: how much you store, how many devices you have, and when they sync. It cannot see what
  your files are called or what is in them. One exception: when you share a file by link, the
  server stores that file's name, type and size with it, because the person you share it with
  sees them anyway. {source: docs/sync/threat-model.md | Threat model, asset A6 (metadata), T-T-07 and T-S-02 (share links)}
- **A share link carries its own key.** The key is the part of the link after `#k=`. Your
  browser never sends that part to our server, so the server can deliver the file but cannot
  open it. Anyone with the link can view the file until you revoke it. Someone who already
  opened it keeps their copy. {source: docs/adr/0018-file-sharing-mode-a.md | ADR 0018, file sharing: the URL fragment is the capability}
  {source: docs/file-sharing.md | File sharing: what the relay sees and what revoking does}
- **Deleting a share link deletes what the server keeps for it.** Open **Shared**, then **My
  shares**, and tap **Revoke** on the link. The link stops working at once, and Tenshu's servers
  delete the file's name, type and size and its encrypted copy 30 days later. A link that expires
  is deleted the same way. {source: docs/file-sharing.md | File sharing: what the relay sees and what revoking does}
- **Without a storage plan, nothing leaves your device.** Drive works on one device with no
  account. Your files only go to Tenshu's servers, encrypted, once you turn on sync.
  {source: docs/adr/0019-storage-capacity-gating.md | ADR 0019, storage capacity gating: no subscription means zero relay traffic}

## Common problems {#problems}

### Drive says "Set up Tenshu Control"

Drive keeps your files in Tenshu Control, so Control has to be installed and set up on the
same device first. Install Tenshu Control, open it, and choose **Start fresh on this device**,
or **I have Tenshu on another device** if you already use Tenshu. Then open Drive again.

::: ios
Once Control is set up, it does not need to stay open. Drive works on its own.
:::

::: web
In the browser, you set up Tenshu at [tenshu.app/start](/start). There is nothing to install.
:::

::: ios
### Drive says "Can't reach Tenshu Control"

Drive shows the files it already has, but cannot load new ones. Tap **Set up** and follow the
steps in Tenshu Control, then return to Drive.
:::

::: ios
### My files stay on this iPhone

Open Tenshu Control and go to **Settings**. If it says **Local only**, this realm has no storage
plan yet, so nothing leaves this iPhone. Open **Storage** and pick a plan. The free plan needs a
verified email address. A redemption code works without one.
:::

::: android macos
### Drive says "Not syncing — this device has no storage plan"

Your files are safe on this device, but they cannot reach your other devices yet. Open Tenshu
Control and pick a storage plan under **Services**. The free plan needs a verified email
address. A redemption code works without one.
:::

::: web
### My files stay in this browser

Without a storage plan, Tenshu keeps everything in this browser. Open **Services** in Tenshu
and pick a plan. The free plan needs a verified email address.
:::

### My other device doesn't show my files

Files reach your other devices only after the device that has them has uploaded them. Check
these, in order:

1. Both devices are in the same realm. In Tenshu Control, the device list should show both.
2. The device that has the files has a storage plan (see above).
3. Both devices are online. Open Drive on both and give it a few minutes, longer for large files.

::: ios
### Photo backup doesn't back up

Drive needs access to your photo library. If it says **Drive cannot see your photos**, tap
**Open Settings** and allow access. If you shared only selected photos, Drive backs up only
those. iOS lets Drive back up in the background only now and then. Open Drive to catch up, or
tap **Back up now** under **Photo Backup**.
:::

::: android
### Photo backup doesn't start

Backup needs storage. If Drive says **Backup needs storage**, add storage in Tenshu Control.
If the backup status says **Paused**, the reason follows: **out of space**, **on mobile data**,
**not charging**, **no connection**, or **you stopped backup**. If you stopped it yourself, tap
**Resume**. Turning a single folder on does not undo the pause.
:::

::: android macos
### Drive says "Storage full"

Your files stay on this device and nothing is deleted, but new changes can't sync. Delete files
you no longer need, or choose a larger plan in Tenshu Control under **Services**.

### Drive says this device isn't recognised

Open Tenshu Control and add this device to your realm again. Don't use **Forget this device**
first: anything still waiting to upload would be lost.
:::

::: web
### I cleared my browser's data

Tenshu keeps this browser's keys in its website data. If you clear it, this browser is no longer
part of your realm. Files you synced are still on your other devices. Choose **I have Tenshu
already** at [tenshu.app/start](/start) to add this browser again.
:::

## Requirements {#requirements}

::: ios
- iOS 16 or later, on iPhone or iPad.
- Tenshu Control, installed and set up on the same device.
:::

::: android
- Android 7.0 or later.
- Tenshu Control, installed and set up on the same device.
:::

::: macos
- A Mac with macOS 13 Ventura or later.
- Tenshu Control, installed and set up on the same Mac.
:::

::: web
- A current web browser.
- Tenshu keeps its keys in your browser's website data, so don't clear it for tenshu.app.
:::

- To sync between devices: a storage plan. The free plan has 5 GB.

## Contact {#contact}

Write to [support@tenshu.app](mailto:support@tenshu.app). Tell us which app and device you use,
and what you see on screen.

**Support will never ask for your recovery phrase.** Nobody at Tenshu needs it, ever. Anyone who
has it can open everything in your account.
