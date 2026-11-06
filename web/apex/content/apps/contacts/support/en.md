---
# Tenshu Contacts support — store-presence-and-support-pages.md §4 (S6). First-draft copy.
# Body format: support-pages/generate.ts (platform blocks, shot: slots, {source: …}).
# Every feature claim follows store/contacts/claims.md (iOS) and the code it names: contacts-ios
# for iPhone and iPad, contacts-shared-ui for Android. Contacts is not distributed on macOS or the
# web yet, so it has no page there. Both platforms' slots resolve to the
# published iPhone and Android phone captures (light, en).
# ⚠ iOS cannot add, edit, import or invite yet (claims.md "Left out on purpose"): keep the two
# platform blocks apart rather than describing Android's features on the iPhone page.
app: contacts
name: Tenshu Contacts
lang: en
title: Tenshu Contacts support
description: Help for Tenshu Contacts on {platform}: how to get started, how your contacts stay private, answers to common problems, and how to reach support.
platforms: ios, android
defaultPlatform: ios
---

## Get started {#get-started}

Tenshu Contacts keeps the people you know encrypted and in sync across your devices. Contacts
works together with Tenshu Control, which holds your keys and connects your devices. Set up
Control first.

::: ios
On iPhone and iPad, Contacts shows the contacts you added in Tenshu Contacts on your other
devices, and the people who accepted your invitation. You cannot add, edit or import a contact on
iPhone or iPad yet.

1. **Install Tenshu Control** from the App Store, and open it.
   ![Tenshu Control's welcome screen on iPhone, with the buttons "Start fresh on this device", "I have Tenshu on another device" and "Recover from a recovery phrase"](shot:control/01-welcome)
2. **Set up Control.** Tap **Start fresh on this device**, then **Continue**. Write down your
   recovery phrase and keep it somewhere safe. Then tap **Turn on sync** and enter your email
   address to get a storage plan. Without one, your data stays on this iPhone. If you already use
   Tenshu on another device, tap **I have Tenshu on another device** instead, so your contacts
   can reach this iPhone.
   ![Tenshu Control saying "You're set up", with a "Turn on sync" link above the 24 words of the recovery phrase](shot:control/03-recovery-phrase)
3. **Install Tenshu Contacts** from the App Store, and open it. Contacts finds Control on its
   own. If it asks you to set up Tenshu Control, finish step 2 first. Contacts opens on
   **Contacts**, which says **No contacts yet** until your contacts arrive from your other
   devices.
   ![Tenshu Contacts' list of contacts, with a row for possible duplicates above it](shot:01-contacts)
4. **Find someone.** Type a name, phone number, email address or organisation into the search
   field at the top of the list. If you don't see it, pull the list down. Tap a contact to see their phone numbers, email addresses,
   organisation and notes.
   ![Tenshu Contacts searching by organisation, with three colleagues found](shot:03-search)
5. **Verify a person you connected with.** Open their contact and, under **Keys**, tap
   **Verify**. Read the six
   digits to them over a call or in person. They see the same six on their side. Tap
   **They match** only if they do.
   ![A contact in Tenshu Contacts: phone numbers, email addresses, address, organisation, note and keys](shot:02-contact)
6. **Combine duplicates.** If Contacts finds people who look like the same person, the list shows
   a row such as **2 possible duplicates**. Tap it, tap a pair, and choose which one to keep. The
   other is combined into it, with its keys, cards and photo. This cannot be undone.
   ![Possible duplicates in Tenshu Contacts, each with the reason it was suggested](shot:04-duplicates)
7. **Set your name.** Open **You** and tap **Edit**. This is the name people see when you invite
   them, and the name the other Tenshu apps show for you.
   ![The You tab in Tenshu Contacts, with the name people see when you invite them](shot:06-you)
:::

::: android
1. **Install Tenshu Control** from Google Play, and open it.
   ![Tenshu Control's welcome screen on Android, with the buttons "Start fresh on this device", "I have Tenshu on another device" and "Recover from a recovery phrase"](shot:control/01-welcome)
2. **Set up Control.** Tap **Start fresh on this device**, then **Continue**. On **You're set
   up**, tap **Turn on sync** and enter your email address to get the free storage plan, so
   your contacts can reach your other devices. Then tap **Continue**. Without a plan, your data stays on
   this phone, and you can pick one later under **Services**. Write down your recovery phrase
   under **Settings** → **Set up account recovery**, and keep it somewhere safe.
   ![Tenshu Control's "Pick a plan" screen with the Free plan](shot:control/10-services)
3. **Install Tenshu Contacts** from Google Play, and open it. Contacts connects to Control on its
   own. If it says **Set up Tenshu Control**, finish step 2 first.
   ![Tenshu Contacts' list of contacts, with a row for possible duplicates above it](shot:01-contacts)
4. **Add or import your contacts.** Tap the **+** button. Choose **Add a contact** to type a
   name, or **Import contacts** to bring people in from this phone:
   - **Pick one person** chooses a single contact. Tenshu never sees the rest of your address
     book.
   - **Import a .vcf file** reads a vCard file. Any address book can export one.
   - **Import your whole address book** asks for permission to read your contacts first.

   Everyone you import is added as a new contact, even if you already have them. To combine
   duplicates, see step 7.
5. **Find someone.** Type a name, phone number, email address or organisation into **Search your
   contacts** at the top of the list. Tap a contact to see their details. To change them, use the
   icons at the top: the contact card (**Edit contact info**), the pencil (**Rename**) or the bin
   (**Delete contact**).
   ![Tenshu Contacts searching by organisation, with three colleagues found](shot:03-search)
6. **Invite someone.** Open **You** and tap **Invite someone**, then **Invite someone** again.
   Let them scan the QR code, or tap **Copy link** and send it to them. The link works once. They
   accept it in Tenshu Drive, under **Shared**. When they have, your list shows
   **"…" accepted your invitation**: tap it, read the six digits to them over a call or in
   person, tick **The codes match** if they do, and tap **Save contact**.
   ![A contact in Tenshu Contacts: phone numbers, email addresses, address, organisation, note and keys](shot:02-contact)
7. **Combine duplicates.** If Contacts finds people who look like the same person, the list shows
   a row such as **2 possible duplicates**. Tap it, tap a pair, choose which one to keep, and tap
   **Combine**. The other is combined into it, with its keys, cards and photo. This cannot be
   undone.
   ![Possible duplicates in Tenshu Contacts, each with the reason it was suggested](shot:04-duplicates)
:::

## How Tenshu Contacts keeps your contacts private {#privacy}

- **Your contacts are encrypted on your device before they are uploaded.** Tenshu's servers store
  only encrypted data, and they don't have the keys to read it. Only your own devices can read
  your contacts. The server can still see some facts it needs to sync: how much you store, how
  many devices you have, and when they sync.
  {source: docs/sync/threat-model.md | Threat model, invariant I1, assets A5 (user data), A24 (contacts and keys), A6 (metadata) and T-T-07}
  {source: AGENTS.md | House Rule 1: the relay sees ciphertext only}
- **Tenshu has no directory, so nobody can look you up.** You connect with someone only when you
  both accept an invitation. An invitation works once, and the key inside it never reaches
  Tenshu's servers. The server does see when an invitation is made and used, and the IP address
  of whoever accepts it.
  {source: docs/proposals/contact-invitation-handshake.md | Contact invitation handshake: no directory, identity only through a mutual invitation}
  {source: docs/sync/threat-model.md | Threat model, asset A25 (one-time invitations), T-CI-02 and T-CI-06 (handshake metadata)}
- **Contacts has no ads and no analytics.**
  {source: store/contacts/privacy-label.md | Contacts' privacy label: no tracking, no advertising or analytics SDK}
  {source: contacts-ios/Resources/PrivacyInfo.xcprivacy | Contacts' iOS privacy manifest: no tracking, nothing collected}
- **Without a storage plan, your contacts stay on this device.** Your contacts only go to
  Tenshu's servers, encrypted, once you turn on sync.
  {source: docs/adr/0019-storage-capacity-gating.md | ADR 0019, storage capacity gating: no subscription means zero relay traffic}

::: ios
- **Contacts does not read the address book on your device.**
  {source: contacts-ios/project.yml | No Contacts framework and no contacts usage description}
  {source: store/contacts/privacy-label.md | "Contacts does not read the system address book"}
:::

::: android
- **On Android, Contacts reads your phone's address book only when you import from it, and it
  cannot use the internet itself.** Picking one person shows Contacts only that person. Your
  contacts reach your other devices through Tenshu Control, encrypted.
  {source: contacts-android/src/main/AndroidManifest.xml | Android manifest: the INTERNET permission is removed; READ_CONTACTS only for the whole-address-book import}
  {source: contacts-shared-ui/src/androidMain/kotlin/app/tenshu/contacts/ui/screens/importing/ContactImporter.android.kt | The Android importer: the system contact picker, a .vcf file, or the address book after asking}
:::

## Common problems {#problems}

### Contacts asks you to set up Tenshu Control

Contacts keeps your contacts in Tenshu Control, so Control has to be installed and set up on the
same device first. Install Tenshu Control, open it, and choose **Start fresh on this device**, or
**I have Tenshu on another device** if you already use Tenshu. Then open Contacts again.

::: ios
Once Control is set up, it does not need to stay open. Contacts works on its own.
:::

### Contacts says "Can't reach Tenshu Control"

::: ios
Contacts shows what it already has, but cannot load anything new. Tap **Set up** and follow the
steps in Tenshu Control, then return to Contacts.
:::

::: android
Your contacts appear once Control is running again. Tap **Open Control** and check that Tenshu
Control is installed and set up, then return to Contacts.
:::

::: ios
### Contacts says "Not syncing — this device has no storage plan"

The banner at the top of Contacts says your files stay on this device: on iPhone and iPad it uses
the same words in every Tenshu app, and here it means your contacts. They are safe, but they
cannot reach your other devices yet. Tap **Manage storage** to open Tenshu Control, then pick a
storage plan (see below).

### Contacts says "Storage full"

The banner shows how much of your plan is used. Your contacts stay on this device and nothing
is deleted, but new changes can't sync until you free up space or get more. Tap **Manage
storage** to open Tenshu Control, and choose a larger plan under **Storage**.

### My contacts stay on this iPhone

Open Tenshu Control and go to **Settings**. If it says **Local only**, this realm has no storage
plan yet, so your data stays on this iPhone. Open **Storage** and pick a plan. The free plan needs
a verified email address. A redemption code works without one.
:::

::: android
### Contacts says "Not syncing — this device has no storage plan"

Your contacts are safe on this device, but they cannot reach your other devices yet. Tap **Set up
storage**, or open Tenshu Control and pick a storage plan under **Services**. The free plan needs
a verified email address. A redemption code works without one.

### Contacts says "Storage full"

Your contacts stay on this device and nothing is deleted, but new changes can't sync. Free up
space, or choose a larger plan in Tenshu Control under **Services**.

### Contacts says it is newer or older than Tenshu Control

Contacts and Control on the same device have to be from matching versions to keep syncing.
Update both from Google Play. Everything already on the device is safe.
:::

::: ios
### I can't add a contact on my iPhone

Adding, editing and importing contacts is not available on iPhone and iPad yet. Add the contact
in Tenshu Contacts on an Android phone that is in the same realm, and it appears on your iPhone.
:::

### My other device doesn't show my contacts

Contacts reach your other devices only after the device that has them has uploaded them. Check
these, in order:

1. Both devices are in the same realm. In Tenshu Control, the device list should show both.
2. The device that has the contacts has a storage plan (see above).
3. Both devices are online. Open Contacts on both and give it a few minutes.

### A contact says "Key changed"

The person is using a key you have not verified. That happens after they reinstall or set up a
new device. Open their contact, tap **Verify** under **Keys**, and compare the six digits with them again, over
a call or in person.

::: android
### Contacts says "Tenshu could not read your contacts"

Contacts may read your phone's address book only with your permission. Tap **+** → **Import
contacts** → **Import your whole address book** and allow access when Android asks. If Android
no longer asks, open your phone's **Settings** → **Apps**, choose Tenshu's **Contacts** app, and
allow its Contacts permission. Or export your contacts as a .vcf file and use **Import a .vcf
file**, which needs no permission.
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

- To sync between devices: a storage plan. The free plan has 5 GB.

## Contact {#contact}

Write to [support@tenshu.app](mailto:support@tenshu.app). Tell us which app and device you use,
and what you see on screen.

**Support will never ask for your recovery phrase.** Nobody at Tenshu needs it, ever. Anyone who
has it can open everything in your account.
