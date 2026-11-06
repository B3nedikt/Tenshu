# Tenshu Calendar — the source of every claim in the store description

Design §4.4: a claim with no source is not published. `scripts/store-listing.py check` fails when a
sentence under the description's `HOW … PRIVATE` heading is not quoted here (`> ` line, verbatim) with
at least one `- source:` whose path exists. The wording follows Drive's and Control's reviewed claims
and this app's privacy-label draft (`store/calendar/privacy-label.md`); Calendar's support page
(`web/apex/content/apps/calendar/support/`) makes the same claims with the same sources. ⚠ A
threat-model amendment that weakens one of these has to change the listing too (House Rule 2) — it
is a public promise.

## Privacy sentences (checked)

> Your events and calendars are encrypted on your device before they are uploaded.
- source: docs/sync/threat-model.md — invariant I1 and asset A5 (user data): events, calendars and subscriptions are realm Syncables, sealed before they reach the relay
- source: AGENTS.md — House Rule 1

> Tenshu's servers store only encrypted data, and they don't have the keys to read it.
- source: AGENTS.md — House Rule 1: the relay sees ciphertext only
- source: docs/sync/threat-model.md — invariant I1; the keys (A1–A4) never reach the relay in the clear

> The server can still see some facts it needs to sync: how much you store, how many devices you have, and when they sync.
- source: docs/sync/threat-model.md — asset A6 (metadata) and T-T-07 (metadata analysis)

> When you subscribe to a calendar, your device downloads it directly from its publisher, and the publisher sees your IP address.
- source: docs/sync/threat-model.md — the calendar-subscription egress: the fetch is direct, so the publisher sees the IP
- source: calendar-ios/Sources/CalendarsScreen.swift — the Subscribe sheet says so before the user subscribes

> Each of your devices downloads it for itself.
- source: docs/sync/threat-model.md — "The subscription row syncs; the fetch does not": one IP per device

> Calendar does not read the other calendars on your device.
- source: calendar-ios/project.yml — no EventKit, no calendar usage description
- source: store/calendar/privacy-label.md — "Calendar does not read the system calendar (no EventKit)"
- source: calendar-android/src/main/AndroidManifest.xml — Android: no READ_CALENDAR / WRITE_CALENDAR, no calendar provider

> Without a storage plan, your events stay on this device.
- source: docs/adr/0019-storage-capacity-gating.md — no subscription ⇒ zero relay traffic

## Feature claims (reviewed by hand, not by the checker)

Checked against the iOS code on 2026-10-17 (`calendar-ios/Sources` and the shared modules). Paths are
under the repo root; a bare file name is in `calendar-ios/Sources/`.

| Claim | Evidence |
|---|---|
| Needs Tenshu Control on the same device | `calendar-ios/Sources/CalendarIosApp.swift` (the Set Up Tenshu Control gate) |
| Month and agenda | `CalendarMonthScreen.swift`, `CalendarAgendaView.swift` |
| Add, change, delete; all-day, location, notes, repeat; one or all occurrences | `EventEditorScreen.swift` |
| Reminders when closed; hide title on lock screen | `app-notify/src/iosMain/.../IosNotificationPresenter.kt` (scheduled local notifications); `CalendarsScreen.swift` (Hide details on the lock screen); `RemindersPermissionSection.swift` |
| Several calendars, colours, show/hide per device | `CalendarsScreen.swift` |
| Subscribe by web address; updates on its own; pause | `CalendarsScreen.swift` (Subscribe, Pause/Resume, Refresh now); `calendar-service-proxy/.../SubscriptionRefreshSchedule.kt`. ⚠ The iOS scheduler runs in-process, so the listing says "updates on its own", not a cadence |
| .ics import and export | `CalendarInteropSheet.swift` |
| iOS 16, iPhone and iPad | `calendar-ios/project.yml` (`deploymentTarget` 16.0; XcodeGen's default device family 1,2) |

**Left out on purpose — the iOS app does not do it today:** week and day views (deliberately not built on iOS — `CalendarIosApp.swift`); a task / to-do list (reminders
are event alerts); reading or writing the system calendar (no EventKit); Snooze/Dismiss actions on a reminder;
opening an event from another app or from a notification (the `tenshu-calendar` scheme has no handler).

## Deutsch (de-DE)

The German listings' privacy sentences: Google Play's (`play/de-DE/`) and the App Store's (`de-DE/`),
one claim serving both where the sentence is the same. Each names the English claim it renders in
its `- translates:` line and cites that claim's sources. `check` holds the mechanics: every German
claim names an English claim that exists, the Play section renders the English Play listing's claims
one for one and in order (a dropped caveat fails), and a changed English claim flags its translation.
The App Store pair (`de-DE/` against `en-US/`) is held the same way: one for one and in order.
Whether the German says the same thing is review's job. The wording follows the German support page
(`web/apex/content/apps/calendar/support/de.md`) where it makes the same claim.

> Deine Termine und Kalender werden auf deinem Gerät verschlüsselt, bevor sie hochgeladen werden.
- translates: "Your events and calendars are encrypted on your device before they are uploaded."
- source: docs/sync/threat-model.md — invariant I1 and asset A5 (user data): events, calendars and subscriptions are realm Syncables, sealed before they reach the relay
- source: AGENTS.md — House Rule 1

> Die Server von Tenshu speichern nur verschlüsselte Daten und haben nicht die Schlüssel, um sie zu lesen.
- translates: "Tenshu's servers store only encrypted data, and they don't have the keys to read it."
- source: AGENTS.md — House Rule 1: the relay sees ciphertext only
- source: docs/sync/threat-model.md — invariant I1; the keys (A1–A4) never reach the relay in the clear

> Einige Fakten, die der Server zum Synchronisieren braucht, sieht er trotzdem: wie viel du speicherst, wie viele Geräte du hast und wann sie synchronisieren.
- translates: "The server can still see some facts it needs to sync: how much you store, how many devices you have, and when they sync."
- source: docs/sync/threat-model.md — asset A6 (metadata) and T-T-07 (metadata analysis)

> Wenn du einen Kalender abonnierst, lädt dein Gerät ihn direkt von seinem Herausgeber, und der Herausgeber sieht deine IP-Adresse.
- translates: "When you subscribe to a calendar, your device downloads it directly from its publisher, and the publisher sees your IP address."
- source: docs/sync/threat-model.md — the calendar-subscription egress: the fetch is direct, so the publisher sees the IP
- source: calendar-ios/Sources/CalendarsScreen.swift — the Subscribe sheet says so before the user subscribes

> Jedes deiner Geräte lädt ihn selbst.
- translates: "Each of your devices downloads it for itself."
- source: docs/sync/threat-model.md — "The subscription row syncs; the fetch does not": one IP per device

> Calendar liest die anderen Kalender auf deinem Gerät nicht.
- translates: "Calendar does not read the other calendars on your device."
- source: calendar-ios/project.yml — no EventKit, no calendar usage description
- source: store/calendar/privacy-label.md — "Calendar does not read the system calendar (no EventKit)"
- source: calendar-android/src/main/AndroidManifest.xml — Android: no READ_CALENDAR / WRITE_CALENDAR, no calendar provider

> Ohne Speicherplan bleiben deine Termine auf diesem Gerät.
- translates: "Without a storage plan, your events stay on this device."
- source: docs/adr/0019-storage-capacity-gating.md — no subscription ⇒ zero relay traffic
