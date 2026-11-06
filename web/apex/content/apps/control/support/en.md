---
# Tenshu Control support — store-presence-and-support-pages.md §4. First-draft copy (S1).
# Body format: support-pages/generate.ts (platform blocks, shot: slots, {source: …}).
app: control
name: Tenshu Control
lang: en
title: Tenshu Control support
description: Help for Tenshu Control on {platform}: how to set up Tenshu, add your devices, keep your recovery phrase safe, and fix common problems.
platforms: ios, android, macos, web
defaultPlatform: web
---

## Get started {#get-started}

Tenshu Control is where Tenshu starts. It creates your realm, the private space your Tenshu apps
share. It holds your keys and connects your devices. Other Tenshu apps, like Tenshu Drive, need
Control set up on the same device.

::: ios
1. **Install Tenshu Control** from the App Store, and open it.
   ![Tenshu Control's welcome screen on iPhone, with the buttons "Start fresh on this device", "I have Tenshu on another device" and "Recover from a recovery phrase"](shot:01-welcome)
2. **Create your realm.** Tap **Start fresh on this device**, then **Continue**. Tenshu protects
   your keys with this iPhone's secure storage, so there is no password to remember.
   ![Tenshu Control's "Protected by your device" screen, with a "Continue" button](shot:02-protected)
3. **Save your recovery phrase.** Control now says **You're set up** and shows your recovery
   phrase. Write the 24 words down and keep them somewhere safe, offline.
   ![Tenshu Control saying "You're set up", with a "Turn on sync" link above the 24 words of the recovery phrase and an "I've saved it" button](shot:03-recovery-phrase)
4. **Turn on sync.** On the same screen, tap **Turn on sync** and enter your email address to
   get your storage plan; a new account starts on the free plan. Until you do, everything stays
   on this iPhone. Then tap **I've saved it**. You can also turn on sync later, under
   **Settings** → **Storage**.
   ![Tenshu Control's "Sign in or create your free Tenshu account" screen, with an email field](shot:08-sign-in)
5. **Add your other device.** Install Tenshu Control there and choose **I have Tenshu on another
   device**. On this iPhone, go to **Settings** → **Add a device**. One device shows a code and
   the other scans it. Check that both show the same confirmation code, then confirm.
   ![Tenshu Control's "Add a device" screen on "Show a code", with a QR code and a pairing code](shot:09-add-device)
:::

::: android
1. **Install Tenshu Control** from Google Play, and open it.
   ![Tenshu Control's welcome screen on Android, with the buttons "Start fresh on this device", "I have Tenshu on another device" and "Recover from a recovery phrase"](shot:01-welcome)
2. **Create your realm.** Tap **Start fresh on this device**, then **Continue**. Tenshu protects
   your keys with this phone's secure storage, so there is no password to remember.
   ![Tenshu Control's "Protected by your device" screen, with a "Continue" button](shot:02-protected)
3. **Save your recovery phrase.** Go to **Settings** → **Set up account recovery** and write the
   24 words down. Keep them somewhere safe, offline.
   ![Tenshu Control's Recovery Phrase screen](shot:03-recovery-phrase)
4. **Turn on sync.** Open **Services**, tap **Sign in or create an account** and pick a storage
   plan. Until you do, everything stays on this phone.
   ![Tenshu Control's "Pick a plan" screen with the Free plan](shot:10-services)
5. **Add your other device.** Install Tenshu Control there and choose **I have Tenshu on another
   device**. On this phone, go to **Settings** → **Synchronization & Devices** and tap **+**.
   One device shows a code and the other scans it. Check that both devices show the same
   confirmation code.
   ![Tenshu Control's "Add a device" screen on "Show a code", with a QR code and a manual code](shot:09-add-device)
:::

::: macos
1. **Install Tenshu Control** on your Mac, and open it. Control lives in the menu bar, at the
   top of your screen. To quit it, use **Quit** in its menu.
   ![Tenshu Control's welcome window on a Mac, with the buttons "Start fresh on this device", "I have Tenshu on another device" and "Recover from a recovery phrase"](shot:01-welcome)
2. **Create your realm.** Click **Start fresh on this device**, then **Continue**.
   ![Tenshu Control's "Protected by your device" window, with a "Continue" button](shot:02-protected)
3. **Save your recovery phrase.** Open **Recovery Phrase** and write the 24 words down. Keep them
   somewhere safe, offline.
   ![Tenshu Control's Recovery Phrase window](shot:03-recovery-phrase)
4. **Turn on sync.** Open **Services** and pick a storage plan. Until you do, everything stays
   on this Mac.
   ![Tenshu Control's Services window with the Free, Basic and Premium plans](shot:10-services)
5. **Add your other device.** Install Tenshu Control there and choose **I have Tenshu on another
   device**. On this Mac, go to **Realm Devices** → **Add new device**. Check that both devices
   show the same confirmation code.
   ![Tenshu Control's "Add Device" window with a pairing code](shot:09-add-device)
:::

::: web
1. **Open [tenshu.app/start](/start)** in your browser. In the browser, Tenshu Control is part
   of the website, so there is nothing to install.
   ![The "Welcome to Tenshu" page, with "Sign up free", "I have Tenshu already" and "Just try it locally"](shot:01-welcome)
2. **Create your realm.** Choose **Sign up free**. **Just try it locally** also works, but then
   your data lives only in this browser.
   ![The sign-up page](shot:11-sign-up)
3. **Save your recovery phrase.** Write the 24 words on paper or keep them in a password
   manager. Don't take a screenshot: your photo backup may be visible to others. Tenshu then
   checks that you saved it.
   ![The "Save your recovery phrase" page with 24 numbered words](shot:03-recovery-phrase)
4. **Turn on sync.** If you haven't picked a plan yet, open **Services** and pick one.
   ![The Services page with the Free, Basic and Premium plans](shot:10-services)
5. **Add your other device.** Install Tenshu Control there and choose **I have Tenshu on another
   device**. In the browser, open **Devices** → **Pair a new device**. The pairing code expires
   after 5 minutes. Check that both devices show the same confirmation code.
   ![The "Pair a new device" dialog with a pairing code](shot:09-add-device)
:::

## How Tenshu Control keeps your data private {#privacy}

- **Your keys are made on your devices and never leave them unencrypted.** When you add a
  device, your existing device encrypts the keys for the new one before they pass through our
  server. The server only ever sees data it cannot decrypt. {source: docs/sync/threat-model.md | Threat model, assets A1 and A4 (keys)}
  {source: docs/whitepaper.adoc | Whitepaper, adding a device}
- **Tenshu's servers cannot read your data.** They pass on and store only encrypted data. They
  do see some facts they need to work: how much you store, how many devices you have, and when
  they sync. {source: docs/sync/threat-model.md | Threat model, invariant I1, asset A6 (metadata) and T-T-07}
  {source: AGENTS.md | House Rule 1: the relay sees ciphertext only}
- **Your recovery phrase is yours alone.** Your keys are derived from its 24 words. Nobody else
  holds a copy, so nobody, including us, can restore your realm without it. Anyone who has it
  can open everything, so keep it offline. {source: docs/sync/threat-model.md | Threat model, asset A9 (recovery code), invariant I7 and T-RC-01}
- **You can use Tenshu without an account.** On one device, Tenshu works with no account and
  sends nothing to our servers. To sync, you pick a storage plan: the free plan needs an email
  address, a redemption code needs none. {source: docs/adr/0019-storage-capacity-gating.md | ADR 0019, storage capacity gating: no subscription means zero relay traffic}
  {source: docs/proposals/commerce-accounts-and-sessions.md | Commerce accounts: redeeming a code needs no email}

## Common problems {#problems}

### I lost my phone, or all my devices

If you still have one device in your realm, use it to add your new device. If you have none,
install Tenshu Control on a new device and choose **Recover from a recovery phrase**. Enter your
24 words. The recovered device becomes your only device; add the others again from it.

Without the recovery phrase and without any of your devices, nobody can recover your data,
including us.

### Tenshu says "This device doesn't have your recovery phrase"

That is expected on a device you added by pairing. Only the device that created your realm, or
one set up with the phrase, keeps a copy. Open **Recovery Phrase** on that device.

### The confirmation codes don't match when I add a device

Stop, and don't confirm. Cancel on both devices, make sure both run the latest version of
Tenshu Control, and start again.

### I added a device but nothing syncs

If Tenshu Control says **Nothing will sync yet**, your realm has no storage plan, so changes
stay on each device. Nothing is lost. Pick a plan under **Services**, or **Storage** on iPhone.

::: ios
In **Settings**, a realm without a plan shows as **Local only**.
:::

::: ios android macos
### I forgot my passphrase

If you added a passphrase, it can't be reset. Your recovery phrase is what restores your realm:
choose **Recover from a recovery phrase** on this device.
:::

::: web
### I cleared my browser's data

Tenshu keeps this browser's keys in its website data. If you clear it, this browser is no longer
part of your realm. Your other devices still have your data. Choose **I have Tenshu already** at
[tenshu.app/start](/start) to add this browser again, or recover with your recovery phrase.
:::

## Requirements {#requirements}

::: ios
- iOS 16 or later, on iPhone or iPad.
:::

::: android
- Android 7.0 or later.
:::

::: macos
- A Mac. Tenshu Control runs in the menu bar.
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
