---
# Tenshu Calendar support — store-presence-and-support-pages.md §4 (S6). First-draft copy.
# Body format: support-pages/generate.ts (platform blocks, shot: slots, {source: …}).
# Every feature claim follows store/calendar/claims.md (iOS) and the code it names: calendar-ios
# for iPhone and iPad, calendar-shared-ui for Android. Calendar is not distributed on macOS or the
# web yet, so it has no page there. Both platforms' slots resolve to the
# published iPhone and Android phone captures (light, en).
# ⚠ Android does not read or copy the phone's own calendar (no READ_CALENDAR on master): do not
# claim it until that work merges.
app: calendar
name: Tenshu Calendar
lang: en
title: Tenshu Calendar support
description: Help for Tenshu Calendar on {platform}: how to get started, how your events stay private, answers to common problems, and how to reach support.
platforms: ios, android
defaultPlatform: ios
---

## Get started {#get-started}

Tenshu Calendar keeps your events and reminders encrypted and in sync across your devices.
Calendar works together with Tenshu Control, which holds your keys and connects your devices. Set
up Control first.

::: ios
1. **Install Tenshu Control** from the App Store, and open it.
   ![Tenshu Control's welcome screen on iPhone, with the buttons "Start fresh on this device", "I have Tenshu on another device" and "Recover from a recovery phrase"](shot:control/01-welcome)
2. **Set up Control.** Tap **Start fresh on this device**, then **Continue**. Write down your
   recovery phrase and keep it somewhere safe. Then tap **Turn on sync** and enter your email
   address to get a storage plan. Without one, your events stay on this iPhone.
   ![Tenshu Control saying "You're set up", with a "Turn on sync" link above the 24 words of the recovery phrase](shot:control/03-recovery-phrase)
3. **Install Tenshu Calendar** from the App Store, and open it. Calendar finds Control on its
   own. If it asks you to set up Tenshu Control, finish step 2 first. Calendar opens on
   **Month**. Tap a day to see its events below the month.
   ![Tenshu Calendar's month, with a coloured dot on each busy day and today's events beneath it](shot:01-month)
4. **Add an event.** Tap **+** on **Month** or **Agenda**. Give it a title, and if you like a
   location, notes, a calendar, a time or **All-day**, a repeat and reminders. Then tap **Save**.
   ![Creating an event in Tenshu Calendar: title, location, calendar, time, repeat and reminders](shot:04-new-event)
5. **See what is coming up.** **Agenda** lists the coming days' events across all your calendars.
   Tap an event to see when and where it is, and tap **Edit** to change it. For a repeating
   event, you choose whether to change only this occurrence or all of them.
   ![Tenshu Calendar's agenda: the coming days' events across five calendars](shot:02-agenda)
6. **Turn on reminders.** Open **Calendars** and, under **Reminders**, tap **Turn on reminders**,
   then allow notifications. iOS delivers your reminders even when Calendar is closed.
   ![Tenshu Calendar's list of calendars: Personal, Work, Family, Fitness and Travel in their colours](shot:05-calendars)
7. **Organise your calendars.** In **Calendars**, tap **New calendar** to add one with its own
   name and colour. Tap the circle beside a calendar to show or hide it on this device. Tap
   **…** beside a calendar for **Edit**, **Make default** (the calendar new events go into),
   **Import or export…** and **Delete**.
   ![A calendar's sheet in Tenshu Calendar: its name, the "Hide details on the lock screen" switch and the colours](shot:06-edit-calendar)
:::

::: android
1. **Install Tenshu Control** from Google Play, and open it.
   ![Tenshu Control's welcome screen on Android, with the buttons "Start fresh on this device", "I have Tenshu on another device" and "Recover from a recovery phrase"](shot:control/01-welcome)
2. **Set up Control.** Tap **Start fresh on this device**, then **Continue**. On **You're set
   up**, tap **Turn on sync** and enter your email address to get the free storage plan, so
   your events can reach your other devices. Then tap **Continue**. Without a plan, your events stay on
   this phone, and you can pick one later under **Services**. Write down your recovery phrase
   under **Settings** → **Set up account recovery**, and keep it somewhere safe.
   ![Tenshu Control's "Pick a plan" screen with the Free plan](shot:control/10-services)
3. **Install Tenshu Calendar** from Google Play, and open it. Calendar connects to Control on its
   own. If it says **Set up Tenshu Control**, finish step 2 first. Switch between **Month**,
   **Week**, **Day** and **Agenda** at the top, and tap **Today** to come back to today.
   ![Tenshu Calendar's month, with a coloured dot on each busy day and the day's events beneath it](shot:01-month)
4. **Add an event.** Tap **+**. Give it a title, a date and a time or **All day**, and if you like
   a repeat, reminders, a calendar, a location and notes. Then tap **Save**. To change an event,
   tap it. For a repeating event, you choose whether to change only this one or all of them.
   ![Creating an event in Tenshu Calendar: title, location, calendar, time, repeat and reminders](shot:04-new-event)
5. **Organise your calendars.** Tap the calendar icon at the top (**Calendars**); on a wide screen, tap
   **Manage calendars** in the sidebar instead. Tap **+** to add a calendar with its own name and colour, or tap a calendar to change it. The checkbox beside a
   calendar shows or hides it on this device. Tap **⋮** beside a calendar for **Make default**
   (the calendar new events go into), **Import or export…** and **Delete**.
   ![Tenshu Calendar's list of calendars: Personal, Work, Family, Fitness and Travel in their colours](shot:05-calendars)
6. **Get reminders.** Tenshu Control shows your event reminders, even when Calendar is closed. If
   your phone asks whether Tenshu Control may send notifications, allow it.
:::

### Subscribe to a calendar

You can follow a calendar someone publishes on the web, such as public holidays or a team
schedule.

::: ios
Open **Calendars**, tap **Subscribe to a calendar**, paste its address and give it a name, then
tap **Subscribe**. It updates about every 6 hours, on any network, while Calendar,
Tenshu Control or another Tenshu app is running, and **Refresh now** in its **…** menu updates it
straight away. **Pause updates** stops it from updating and
keeps its events. **Unsubscribe** removes it.
:::

::: android
Open **Calendars**, tap the small button above **+** (**Subscribe to a calendar**), paste its
address, then tap **Subscribe**. It updates about every 6 hours on Wi-Fi, and **Refresh now** in its
**⋮** menu updates it straight away. **Pause updates** stops it from updating and keeps its
events. **Unsubscribe** removes it.
:::

Only addresses that start with https:// or webcal:// work. A subscribed calendar is a copy of
its publisher's: each update replaces the events in it.

### Import or export an .ics file

Open **Calendars**, then the menu beside the calendar you want, and choose **Import or
export…**. **Import from a file** adds the events in an .ics file to that calendar. **Export to a
file** saves the calendar as an .ics file you can open in other calendar apps. Importing the same
file again updates its events instead of adding them twice.

::: android
You can also open an .ics file with Tenshu Calendar, or share one to it, and choose the calendar
its events go into.
:::

## How Tenshu Calendar keeps your events private {#privacy}

- **Your events and calendars are encrypted on your device before they are uploaded.** Tenshu's
  servers store only encrypted data, and they don't have the keys to read it. Only your own
  devices can read your events. The server can still see some facts it needs to sync: how much
  you store, how many devices you have, and when they sync.
  {source: docs/sync/threat-model.md | Threat model, invariant I1, asset A5 (user data), asset A6 (metadata) and T-T-07}
  {source: AGENTS.md | House Rule 1: the relay sees ciphertext only}
- **When you subscribe to a calendar, your device downloads it directly from its publisher.** The
  publisher sees your IP address and that you follow its calendar. Each of your devices downloads
  it for itself. Tenshu's servers never see the download.
  {source: docs/sync/threat-model.md | Threat model, asset A5, calendar subscriptions: the fetch is direct, one per device, and the relay never sees it}
  {source: calendar-service-proxy/src/commonMain/kotlin/org/tenshu/calendar/proxy/KtorIcsSubscriptionFetcher.kt | The feed fetcher: a direct HTTPS request to the publisher, with no cookies or credentials}
- **Calendar has no ads and no analytics.**
  {source: store/calendar/privacy-label.md | Calendar's privacy label: no tracking, no advertising or analytics SDK}
  {source: calendar-ios/Resources/PrivacyInfo.xcprivacy | Calendar's iOS privacy manifest: no tracking, nothing collected}
- **Without a storage plan, your events stay on this device.** Your events only go to Tenshu's
  servers, encrypted, once you turn on sync.
  {source: docs/adr/0019-storage-capacity-gating.md | ADR 0019, storage capacity gating: no subscription means zero relay traffic}

::: ios
- **Calendar does not read the other calendars on your device.**
  {source: calendar-ios/project.yml | No EventKit and no calendar usage description}
  {source: store/calendar/privacy-label.md | "Calendar does not read the system calendar (no EventKit)"}
:::

::: android
- **On Android, Calendar does not read your phone's calendar, and it cannot use the internet
  itself.** Your events reach your other devices, and subscribed
  calendars reach Calendar, through Tenshu Control.
  {source: calendar-android/src/main/AndroidManifest.xml | Android manifest: the INTERNET permission is removed, and no calendar permission is declared}
  {source: docs/sync/threat-model.md | Threat model, asset A5, calendar subscriptions: the fetch is Control's, never the app's}
:::

## Common problems {#problems}

### Calendar asks you to set up Tenshu Control

Calendar keeps your events in Tenshu Control, so Control has to be installed and set up on the
same device first. Install Tenshu Control, open it, and choose **Start fresh on this device**, or
**I have Tenshu on another device** if you already use Tenshu. Then open Calendar again.

::: ios
Once Control is set up, it does not need to stay open. Calendar works on its own.
:::

### Calendar says "Can't reach Tenshu Control"

::: ios
Calendar shows what it already has, but cannot load anything new. Tap **Set up** and follow the
steps in Tenshu Control, then return to Calendar.
:::

::: android
Calendar cannot load your events. Tap **Open Control** and check that Tenshu Control is installed
and set up, then return to Calendar.
:::

::: ios
### Calendar says "Not syncing — this device has no storage plan"

The banner at the top of Calendar says your files stay on this device: on iPhone and iPad it uses
the same words in every Tenshu app, and here it means your events. They are safe, but they
cannot reach your other devices yet. Tap **Manage storage** to open Tenshu Control, then pick a
storage plan (see below).

### Calendar says "Storage full"

The banner shows how much of your plan is used. Your events stay on this device and nothing
is deleted, but new changes can't sync until you free up space or get more. Tap **Manage
storage** to open Tenshu Control, and choose a larger plan under **Storage**.

### My events stay on this iPhone

Open Tenshu Control and go to **Settings**. If it says **Local only**, this realm has no storage
plan yet, so your data stays on this iPhone. Open **Storage** and pick a plan. The free plan needs
a verified email address. A redemption code works without one.
:::

::: android
### Calendar says "Not syncing — this device has no storage plan"

Your events are safe on this device, but they cannot reach your other devices yet. Tap **Set up
storage**, or open Tenshu Control and pick a storage plan under **Services**. The free plan needs
a verified email address. A redemption code works without one.

### Calendar says "Storage full"

Your events stay on this device and nothing is deleted, but new changes can't sync. Free up
space, or choose a larger plan in Tenshu Control under **Services**.

### Calendar says it is newer or older than Tenshu Control

Calendar and Control on the same device have to be from matching versions to keep syncing.
Update both from Google Play. Everything already on the device is safe.
:::

### I don't get reminders

::: ios
Open **Calendars**. If you see **Turn on reminders**, tap it and allow notifications. If you see
**Open Settings to allow notifications**, tap it and turn on notifications for Calendar.

Calendar sets up reminders while it is open. A reminder for an event you added on another device
is set up the next time you open Calendar on this iPhone or iPad.
:::

::: android
Tenshu Control shows your event reminders, not Calendar. Open your phone's **Settings**, find
**Tenshu Control** under your apps, and check that its notifications are allowed.
:::

Also check that the event has a reminder: edit it, and look under **Reminders**.

### A reminder says only "Reminder"

The event is in a calendar with **Hide details on the lock screen** turned on. Its reminders say
only "Reminder", without the title or location. To change it, edit the calendar in **Calendars**
and turn the switch off.

### A subscribed calendar doesn't update

Check that it isn't paused: if its menu offers **Resume updates**, tap it. Then tap **Refresh
now**. On Android, Calendar only updates on its own on Wi-Fi. If it still doesn't update, check that its
address still opens in a web browser: the publisher may have moved or removed it.

### My other device doesn't show my events

Events reach your other devices only after the device that has them has uploaded them. Check
these, in order:

1. Both devices are in the same realm. In Tenshu Control, the device list should show both.
2. The device that has the events has a storage plan (see above).
3. Both devices are online. Open Calendar on both and give it a few minutes.
4. The calendar is not hidden on this device. Hiding a calendar affects this device only.

### Calendar says "Nothing in that file could be read as an event"

The file is not a calendar file Calendar can read. Export it from your other calendar app again,
as an .ics file. If only some events could not be read, Calendar imports the rest and tells you
how many it left out.

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
