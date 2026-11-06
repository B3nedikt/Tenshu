---
# Getting started with Tenshu — store-presence-and-support-pages.md §4.7. The page the support hub
# (/apps/support/<lang>) leads new users to first.
# ⚠ No new claims: every sentence reuses wording from the Control and Drive support pages or the
# landing page (locales/en/landing.json block4). The privacy items cite their sources like a
# support page's privacy section (§4.4); ⚖️ counsel reads them with those.
# Body format: support-pages/generate.ts (shot: slots, {source: …}); `## Heading {#id}` needs an id.
lang: en
title: Getting started with Tenshu
description: How to start with Tenshu: set up Tenshu Control first, then add apps like Tenshu Drive and your other devices. How your data stays private, and where to get help.
---

## What Tenshu is {#what-is-tenshu}

Tenshu keeps your data encrypted and in sync across your devices. Your files, your photos, your
devices: all of it lives in one private space Tenshu calls your *realm*. Your realm syncs across
your devices, but only your devices hold the keys.

## Set up Tenshu Control first {#control}

Tenshu Control is where Tenshu starts. It creates your realm, the private space your Tenshu apps
share. It holds your keys and connects your devices. Other Tenshu apps, like Tenshu Drive, need
Control set up on the same device.

- **Install Tenshu Control**, open it, and tap **Start fresh on this device**. Write down your
   recovery phrase and keep it somewhere safe, offline. Then turn on sync to get your storage
   plan. Until you do, everything stays on this device.
   ![Tenshu Control's welcome screen, with the buttons "Start fresh on this device", "I have Tenshu on another device" and "Recover from a recovery phrase"](shot:control/01-welcome)

Control's support page has every step, with screenshots, for
[iPhone and iPad](/apps/control/support/ios/en), [Android](/apps/control/support/android/en),
[Mac](/apps/control/support/macos/en) and the [web](/apps/control/support/web/en).

## Add apps and your other devices {#apps-and-devices}

- **Add apps.** Install the Tenshu apps you want on the same device, like Tenshu Drive. Drive
  finds Control on its own. Drive's support page shows how to upload your first file, for
  [iPhone and iPad](/apps/drive/support/ios/en), [Android](/apps/drive/support/android/en),
  [Mac](/apps/drive/support/macos/en) and the [web](/apps/drive/support/web/en).
- **Add your other devices.** Install Tenshu Control there and choose **I have Tenshu on another
  device**. One device shows a code and the other scans it. Check that both devices show the same
  confirmation code.

## How your data stays private {#privacy}

- **Tenshu's servers cannot read your data.** They pass on and store only encrypted data, and
  only your devices hold the keys. {source: docs/sync/threat-model.md | Threat model, invariant I1 and asset A5 (user data)}
  {source: AGENTS.md | House Rule 1: the relay sees ciphertext only}
- **They do see some facts they need to work:** how much you store, how many devices you have,
  and when they sync. {source: docs/sync/threat-model.md | Threat model, asset A6 (metadata) and T-T-07}
- **Your recovery phrase is yours alone.** Nobody else holds a copy, so nobody, including us, can
  restore your realm without it. {source: docs/sync/threat-model.md | Threat model, asset A9 (recovery code), invariant I7 and T-RC-01}

## Get help {#help}

Write to [support@tenshu.app](mailto:support@tenshu.app). Tell us which app and device you use,
and what you see on screen.

**Support will never ask for your recovery phrase.** Nobody at Tenshu needs it, ever. Anyone who
has it can open everything in your account.
