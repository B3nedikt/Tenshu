# Calendar — bringing in the calendars a user already has

**Status:** v0.4 (2026-10-14). **CX-0 + CX-1a (*Copy once*) and CX-1b (*Keep in sync*) built on
Android**, and **CX-2a + CX-2b (*Copy once* and *Keep in sync* from EventKit) on iOS**; as-built in
§11, which records where each deviates from §4–§9. **P2-a fixed (2026-11-02):** the mirror's
freshness stamp is a per-device row, and *Stop syncing* now syncs at all (§11, P2-a). **§7 fixed
(2026-11-04):** feeds and `.ics` imports namespace their ids too. **CX-2c (macOS) built
(2026-11-04)** and checked on a real Mac. **P2-b (2026-11-05):** the chooser marks a source a mirror
already brings in and refuses *Copy once* for it (§11). The direct sign-ins (CX-3, CX-4) are design.
⭐ **Direction set by the maintainer the same day (§2):** one-way only; the **OS calendar store
first, on every platform that has one**; Android first; a direct Google sign-in where there is no OS
store; Microsoft later. Q1 settled (the reader is the app's); Q2, Q4 and Q6 implemented as recommended, Q3 *without* a window (§11, CX-1b) — all revisitable; Q5 open. ⚠ **v0.2 reversed v0.1's
Q1 recommendation** — v0.1 rested on a premise that was false (§4.4).

> ⚠ **Re-cut 2026-11-05** ([`core-schema-evolution.md`](core-schema-evolution.md) Appendix A.6): no build had
> reached a user, so `CalendarService` is back at **`version = 1`** with the current shape, and every
> step below (the empty 1→2 and 2→3, and `CalendarServiceMigrationV3toV4`) is **deleted**. The HR#9
> notes in §4–§11 record how the work landed, not live chain steps.

**Relates to:** [`calendar-app.md`](calendar-app.md) §2.2 (which put CalDAV and account calendars
out of v1 — this doc is the follow-up it left room for), §2.4 + CA-7 (subscriptions: the engine
this reuses), CA-6 (import), §3 D1–D3 (the data shape every source has to land in), §4.6 (the macOS
tray agent); [`ios-background-scheduling.md`](ios-background-scheduling.md) (why iOS refreshes in
the foreground only, today); threat model A5 (the CA-6/CA-7 amendments).

**Why.** Calendar ships `.ics` import/export and URL subscriptions, and neither is a migration story
for a normal person: an export is five screens in someone else's app, and a Google "secret address"
is a settings page most users will never find. What people actually have is *a phone that already
shows their Google / iCloud / work calendar*. The gesture this doc designs is one tap from there:
**pick a calendar you already have → *Copy once* or *Keep in sync*.**

**Headline findings.**

- ⭐⭐ **On Android, "connect Google" *is* the OS-store phase.** Google's own sync adapter has
  already signed in and fills `CalendarContract`; Outlook for Android (its *Sync calendars* setting)
  and DAVx5 (iCloud, Nextcloud, Fastmail, any CalDAV) write to the same provider. So CX-1 delivers
  Google — plus most of Microsoft and every CalDAV server — with **no OAuth client, no network and no
  credential in Tenshu at all**. On Apple, EventKit is the same thing for every account added in
  Settings. The direct sign-ins (§6) are therefore for the places with no OS store, not the start.
- ⭐ **Almost nothing below the reader is new.** A *Keep in sync* calendar is a CA-7 subscription
  whose fetcher reads the OS store instead of HTTPS: the same write path, the same "read-only is
  derived" rule, and a sweep that becomes explicit deletes from a diff. A *Copy once* is CA-6's
  import without a file.
- ⚠⚠ **On Android the reader belongs in the Calendar app, not in Control** (Q1, recommended in
  v0.2). Every OS-data permission in this repo lives in the app that uses it — Contacts, Fit,
  Timeline — and Control holds none. Those apps also run their own background work for that data,
  so Calendar can too: a job woken **when the OS calendar changes** — a trigger Control's
  scheduler does not even have (§4.4).
- ⚠⚠ **The binding is the election.** A subscription is fetched by every device for itself (CA-7a);
  an OS calendar is visible on **one** device only. So a mirror row names the device that refreshes
  it, and no `ExecutorClaim` is needed (§5.1).
- ⚠⚠ **Mirror event ids must be namespaced per mirror.** A meeting invite carries the *same*
  `UID` in every attendee's calendar, so keying by bare `UID` (CA-6/CA-7's rule) makes two mirrors
  fight over one row. CA-6 imports and CA-7 feeds had it too, and now use the same rule (§7).
- ⚠⚠ **A refresh writes only what changed.** CA-7 rewrites every event on every `200`, which is
  tolerable behind a six-hourly `ETag`. A mirror woken by every provider change that did the same
  would turn a calendar of thousands of events into a stream of sync writes that change nothing
  (§5.3).
- ⚠ **"Desktop" means macOS.** `control-desktop` packages `Dmg`/`Pkg` only (`calendar-app.md`
  §4.6), so desktop OS integration is EventKit — which process reads it is a CX-2 call. Windows and
  Linux have no packaged app to integrate.

---

## 1. Goal & scope

**In scope.**

- One-way, **read-only into Tenshu**: an external calendar's events become Tenshu events in the
  ordinary encrypted realm, and sync to every device from there.
- **Two modes, chosen per calendar** (D5):
  - **Copy once** — a migration. The events land in an ordinary, *editable* Tenshu calendar,
    reminders included, and the link ends there. For someone leaving Google.
  - **Keep in sync** — a mirror. A *read-only* Tenshu calendar, refreshed on a schedule, swept like
    a feed. For someone who keeps a work calendar they cannot leave.
- **Sources:** the OS calendar store on Android, iOS and macOS (CX-1, CX-2); a direct Google
  sign-in (CX-3); Microsoft Graph (CX-4).

**Out of scope, deliberately.**

- **Two-way sync.** Writing back needs per-source conflict handling against our own merge model,
  and it is the part that made `calendar-app.md` §2.2 put CalDAV out of v1. One-way keeps every
  external calendar read-only on our side, which is what makes the sweep safe.
- **Writing Tenshu calendars *into* the OS store** (so the stock widget or a watch shows them). A
  different feature; if it is ever built, §5.10's echo trap comes with it.
- **A CalDAV client of our own.** DAVx5 on Android and the native account settings on Apple already
  bring every CalDAV server into the OS store — CX-1/CX-2 inherit them.
- **Invitations / RSVP.** Still structurally blocked (`calendar-app.md` §2.3). A mirrored invite is
  read-only like everything else in a mirror.

---

## 2. Decisions — maintainer, 2026-10-13

| # | Decision |
|---|---|
| **D1** | **One-way only.** Tenshu reads from the source and never writes to it. |
| **D2** | **The OS calendar store first, on every platform that has one** — Android `CalendarContract`, iOS and macOS EventKit. The OS has already done the sign-in, so Tenshu holds no credential. |
| **D3** | **Android first.** ⭐ On Android this also delivers Google (and Outlook-with-sync, and DAVx5) without a sign-in of ours. |
| **D4** | **Direct REST sign-in only where there is no OS store:** Google first (the web, and anyone who will not add the account to their OS), **Microsoft later**. |
| **D5** | **Two modes, the user's choice per calendar:** *Copy once* or *Keep in sync* (§1). |

---

## 3. What each platform's store gives us

| Platform | Store | What is in it | Permission | Reader runs in | Background refresh |
|---|---|---|---|---|---|
| **Android** | `CalendarContract` | Google (sync adapter); Outlook app with *Sync calendars* on; Samsung / Exchange accounts; DAVx5 (iCloud, Nextcloud, Fastmail…) | `READ_CALENDAR` (runtime), asked at point of use | **the Calendar app** (Q1) | a job in Calendar, triggered by changes to the provider, plus a daily fallback (§4.4) |
| **iOS** | EventKit | iCloud, Google, Exchange / Outlook.com, CalDAV accounts added in Settings | full access to events — iOS 17 `requestFullAccessToEvents` + `NSCalendarsFullAccessUsageDescription`; ⚠ the iOS 16 floor also needs `NSCalendarsUsageDescription` and the pre-17 request API | the Calendar app (route 1: the app *is* the runtime) | ⚠ **foreground only today** — iOS binds `NoOpPlatformScheduler` until `ios-background-scheduling.md` S1+ |
| **macOS** | EventKit | as iOS, from System Settings → Internet Accounts | TCC calendar prompt, attributed to the reading bundle — **TenshuCalendar.app** | ✅ **the Calendar app, in-process over FFM** (CX-2c — §11) | the reading process |
| **Windows / Linux** | — | — | — | no packaged app | — |
| **Web** | none | — | — | no web Calendar app yet (`web/` has `apex` and `drive`) | CX-3 |

⭐ **The "log in with Google" button on Android is an OS intent.** If the account is not on the
phone yet, `Settings.ACTION_ADD_ACCOUNT` with `EXTRA_ACCOUNT_TYPES = ["com.google"]` opens the OS's
own sign-in; the calendars appear in `CalendarContract` once its sync adapter runs. Nothing of the
sign-in passes through Tenshu.

⚠ **A mirror is exactly as fresh as the OS's own sync.** If the user has calendar sync off for that
account, the provider holds stale rows and so will we. The picker should show the account's sync
state, and the mirror's *last updated* line should not imply more than it knows.

⚠ **macOS needs the signed `.app`.** The TCC prompt is attributed by bundle identity, which a bare
`swiftc` dev binary of the tray agent lacks — the same trap `PendingRequestNotifier.swift` already
guards for notifications (`calendar-app.md` §4.6). Expect the reader to no-op in dev builds.

⚠ **Android work profiles.** A work account in a work profile has its own calendar provider; a
personal-profile Calendar sees it only through the cross-profile ("enterprise") URIs, which an admin
must allow per package. Expect the common "my work calendar" case on a managed phone **not to appear
in the picker** — unverified, and worth a device check before the copy promises otherwise.

---

## 4. Shape — a mirror is a subscription with a different fetcher

### 4.1 The neutral record, and why nothing below it is new

Each reader produces the same two types, in `:calendar-service-common`:

- **`ExternalCalendar`** — `sourceKind` (`ANDROID_PROVIDER` / `APPLE_EVENTKIT` / `GOOGLE_API` /
  `MS_GRAPH`), `sourceKey`, a display label (account + name), colour, whether it holds events.
- **`ExternalEvent`** — `uid`, title, location, notes, start/end in §3 D3's shape (`(LocalDateTime,
  tzid)`, floating, or a date range for all-day), RRULE string, exception dates, `recurrenceId` for
  an override, reminder offsets.

A pure mapper turns `ExternalEvent` into the **`ImportedEvent`** that `IcsImport` already produces,
including a synthesized `VEVENT` for `EventSource.raw`. ⚠ A blank `raw` would not break export —
`IcsExport` reads blank as *authored here* and writes from our fields — but the sidecar is where the
source's own `UID`, and anything the source exposes that we do not model (organizer, attendees, a
URL), survives. §5.2 is why the `UID` part stops being optional.

⭐ From `ImportedEvent` down, the write side exists: `SubscriptionRefresher.apply` (write series →
sweep → write overrides, in that order and for a reason) and CA-6's write path. ⚠ The series-before-
overrides ordering should be lifted into one proxy-side writer, not copied — it is the kind of rule
that drifts when it exists twice.

### 4.2 Where each half runs

The **reader and the diff run in the app**; the **writes run in the proxy**.

- **`ExternalCalendarReader`** — an interface in `:calendar-shared-logic` (Compose-free,
  iOS-capable), bound per platform: `CalendarContract` on Android, EventKit on iOS. The
  `ContactImporter` / `HealthImporter` shape. It lists what *this device* can see and reads one
  calendar's events as `ExternalEvent`s.
- **The diff** — also app-side, against an **app-local cache** of the last snapshot per mirror (source
  event key → content hash). It yields upserts and deletes. ⚠ A missing cache (fresh install,
  cleared data) means one **full reconcile** against the calendar's rows in the realm, never an
  assumption that the realm is empty.
- **`ApplyExternalEvents(calendarId, namespace, upserts, deletes)`** — one new proxy op, batched (the
  Fit `RecordImportedHealth` precedent, §9.16 there). ⚠⚠ **Chunked**: a first import of a busy
  calendar is far past Binder's ~1 MB transaction buffer, so the app sends bounded chunks, every
  series chunk before any override chunk. Steady-state refreshes are small because only the diff
  crosses.
- **`SaveCalendarMirror` / `DeleteCalendarMirror`.**

HR#3 holds: non-suspend interfaces, `IoOperation` + `TaskInteractor`. *Copy once* is the same path
with no mirror row, no cache and no deletes.

### 4.3 Schema — `CalendarMirror`

`mirrorId`, `calendarId`, **`deviceId`** (the binding, §5.1), `sourceKind`, `sourceKey`,
`sourceLabel`, `enabled`, `remindersEnabled` (§5.7), `lastRefreshedEpochMs`, `snapshotDigest` (§5.3).

⚠⚠ **As built, the stamp is not on this row** (§11, P2-a): a field the bound device writes routinely
makes every such write a newer version of the whole row, and a newer version beats a stop made
elsewhere. It is `CalendarMirrorStamp`, one row per mirror per device.

- ⭐ `mirrorId` is **derived from `sourceKind + sourceKey`**, following `CalendarSubscription.idFor`:
  two devices that mirror the same source offline converge on one row instead of two calendars.
- ⚠⚠ **HR#9: `CalendarService` 1 → 2 (post-release numbering)**, with the empty step registered — without it the bump
  puts the service below its own derived floor and the store-open gate wipes (the CA-7a lesson).
  Regenerate `SchemaShapeBaseline`.
- ⚠ **Read-only stays derived**, and widens: "a subscription **or a mirror** points at this
  calendar". Today the rule is restated at its call sites — `CalendarInteropViewModel`'s
  import-target filter, the Compose and SwiftUI `CalendarsScreen`'s *no plain Delete*,
  `CalendarManagementViewModel`'s feed lookup. ⚠ Give it one helper (`isExternallyFilled`) before
  adding a second source, or the mirror will be missing from exactly one of them.

### 4.4 ⚠⚠ Why the reader is in the app (v0.2 reverses v0.1)

v0.1 recommended Control, because *"only Control owns a scheduler — no app in this repo runs one"*.
**That was wrong.** Timeline runs its own location capture service and re-arms it at boot
(`TimelineCaptureForegroundService`, `CaptureBootReceiver`); Fit runs a recorder service, a watchdog
alarm and an import service — each in the app that holds the OS permission. What is true is
narrower: no *app* registers work with `PlatformScheduler`, which is Control's.

With the premise gone, the rest points the same way:

- **The repo's pattern.** Every OS-data permission lives in the app that uses it: `READ_CONTACTS` in
  Contacts (asked at the one row that needs it), Health Connect in Fit, location in Timeline.
  Control holds none, and would gain its first one here.
- **Least privilege.** Control already holds the keys and every realm in plaintext. Adding
  `READ_CALENDAR` there would also expose every OS calendar the user did *not* choose to import if
  Control were ever compromised. In Calendar the permission sits in the narrowest process that
  needs it.
- **The prompt says the right thing.** *"Allow Calendar to access your calendar?"* needs no
  explanation; *"Allow Tenshu Control…"* needs a screen of it, plus Control's first runtime-permission
  UI, plus a hand-off from Calendar to get there.
- **Play review.** A calendar app reading calendars is self-evidently its core function. A proxy
  asking for it is a harder case to argue.
- **Revocation is natural.** Revoking Calendar's permission, or uninstalling Calendar, stops the
  mirror. In Control it would outlive the app it serves.
- ⭐ **A better trigger.** Android can run a job **when a content provider changes** (a JobScheduler
  content-URI trigger, wrapped by WorkManager's `addContentUriTrigger`). The OS sync adapter writes
  the provider when the server changes, so a mirror refreshes when there is something to refresh,
  not on the hour. Control's `ScheduleSpec` knows only `ONE_SHOT` and `PERIODIC`. A daily periodic
  job stays as the fallback.
- **iOS already works this way** (route 1: the app is the runtime), so both phones follow one rule.

⭐ The rule that falls out: **OS data is the app's; the network is Control's.** The direct sign-ins
(§6) therefore stay in Control, because Calendar strips `INTERNET` and keeps it stripped — the same
line CA-7 drew for feeds.

**What it costs, honestly:** the snapshot crosses IPC (hence the app-side diff and chunking in §4.2);
Calendar becomes the first app to register WorkManager/JobScheduler work, so its refreshes do not
appear in Control's schedule registry; and a background job must bind Control (allowed from a job,
and a no-op when Control is not set up). ⚠ A content-URI job is one-shot by design, so it must
re-enqueue itself every time it runs, or the mirror silently stops after its first change.

---

## 5. The traps

### 5.1 ⚠⚠ The binding is the election

A feed is fetched by every device for itself, because every device can reach it (CA-7a). An OS
calendar is visible on **one** device. So `CalendarMirror.deviceId` names the refresher, and
the refresh job skips every mirror bound elsewhere (the app already resolves its own id through
`CalendarDeviceIdentity`). Two devices that both claim one source
offline resolve by ordinary LWW on the row: the loser stops refreshing, and nothing else is needed.
⚠ This is the `ExecutorClaim` problem `calendar-app.md` §4.2 refused for *reminders*, and correctly —
here the opposite holds, because exactly one device can do the work at all.

⚠ **A bound device that disappears freezes its mirror** — Q6. The events stay, and the calendar must
say *"Last updated from Pixel 8, 9 days ago"* rather than look current.

### 5.2 ⚠⚠ Namespaced ids

CA-6 and CA-7 key an event by its bare `UID`, deliberately: re-importing a file converges, and a
calendar imported and later subscribed to does not double. That rule breaks for mirrors, because
**a meeting invite carries the same `UID` in every attendee's calendar** — mirror your own work
calendar and a colleague's shared one and both write the same row. Each refresh moves it to the
other calendar, and each sweep sees it gone from its own.

- **Mirror:** `eventId = hash(mirrorId, uid)`.
- **Copy once:** `hash(targetCalendarId, uid)` — re-importing into the same calendar still
  converges, while two calendars no longer collide. ⚠ Adopt an existing bare-`UID` row *only* if it
  already lives in the target calendar, so a calendar first brought over as an `.ics` file does not
  double when copied again from the OS.

⚠⚠ **Namespacing costs the export its `UID`.** `IcsExport` owns `UID` and writes it from `eventId`,
so a namespaced event would leave Tenshu carrying our hash — an exported invite would no longer
match the meeting it came from, and re-importing the file anywhere else would duplicate rather than
converge. The exporter has to prefer the sidecar's `UID` when there is one, which is why §4.1's
synthesized `raw` must carry it.

### 5.3 ⚠⚠ Write only what changed

`SubscriptionRefresher` upserts every event on every `200`. Behind a six-hourly `ETag` that is
tolerable; behind a mirror woken by every change to *any* calendar in the provider, it is thousands
of sync writes that change nothing.

1. **Snapshot digest** — the `304` analogue. Hash the mapped snapshot; equal to
   `snapshotDigest` ⇒ stamp `lastRefreshedEpochMs` and touch nothing else. ⭐ This is what makes the
   change trigger cheap: most provider changes belong to calendars the user never mirrored.
2. **Per-event diff** — when the digest differs, diff against the app-local cache (§4.2) and send only
   what changed. ⚠ The cache is a hint, the realm is the truth: on a miss, reconcile against the
   realm's rows. ⚠ Also worth knowing whether `saveEvent` of an identical row emits a sync delta — if
   it does, a full reconcile must diff too rather than upsert everything.

### 5.4 ⚠⚠ The all-day trap, in opposite directions

`calendar-app.md` §3 D3's warning — all-day events drift a day when read as instants — hits the
two platforms from opposite sides:

- **Android** stores an all-day `DTSTART` as **UTC** midnight with `EVENT_TIMEZONE = UTC`. Read the
  date in UTC; reading it in the device zone moves every holiday back a day west of Greenwich.
- **EventKit** hands an all-day `startDate` as **local** midnight. Read it in the local calendar;
  reading it in UTC moves it forward a day east of Greenwich.

One test per platform pinning a date in a zone on each side of UTC, or it ships wrong for half the
planet.

### 5.5 ⚠ Android rows

- Read **`Events`**, never `Instances` — `Instances` is expanded occurrences, the materialisation
  `calendar-app.md` §3 D2 forbids.
- An exception is a row with `ORIGINAL_ID` / `ORIGINAL_INSTANCE_TIME`. ⚠ An exception with
  `STATUS = STATUS_CANCELED` is a **deleted occurrence** — an `EXDATE`, not an event.
- Skip `DELETED = 1` rows (deletion pending the sync adapter).
- A master overlapping the window is found by **`LAST_DATE`** (null = unbounded), not `DTSTART` —
  "every Monday since 2015" starts long before any window.

### 5.6 ⚠ EventKit rows

- Predicate queries return **occurrences**, over a span of at most **four years** — chunk.
  Collapse occurrences back to their master by identifier, and read `recurrenceRules` off it.
- `EKRecurrenceRule` is structured, so the RRULE string comes from our side —
  `RecurrenceRule.format()` already exists in `-common` (CA-6).
- A detached occurrence (`isDetached`, `occurrenceDate`) is an override.
- ⚠⚠ **Verified (2026-11-01, iOS 27.2 SDK headers): EventKit exposes no exception dates** — only
  `isDetached` and `occurrenceDate`. A deleted occurrence simply does not come back, so `EXDATE`s
  are inferred by expanding the rule over the window the reader queried and diffing against what
  EventKit returned (`AppleConversion`). Sound only inside that window.

### 5.7 ⚠ Reminders — off in a mirror by default

The source's own app already alerts for these events, and Tenshu reminders fire on **every** device
(`calendar-app.md` Q4) — importing them means every alert twice on the phone. So a mirror imports no
reminders unless `remindersEnabled` (Q2). *Copy once* imports them: that user is leaving the other
app.

### 5.8 ⚠ The window

A mirror reads `[now − 12 months, ∞)` (Q3). ⚠ **The sweep must be confined to the same window** —
otherwise every event that ages out of the window is deleted on the next refresh, and a year of
history evaporates one day at a time. Rows wholly before the window are left alone. *Copy once* reads
everything (on EventKit: chunk back a fixed number of years, because there is no "all").

### 5.9 Rules the expander does not support

Nothing new: the same `CUSTOM` limitation as `.ics` and feeds — imported, shown read-only, expanded as
far as the expander reaches. ⚠ Mirrors make it common (Exchange recurrences are adventurous), so
count and log what the expander declines rather than dropping it silently.

### 5.10 ⚠ The echo

If Tenshu ever writes its own calendars into the OS store (out of scope, §1), every reader must
exclude Tenshu's own account — or each refresh re-imports Tenshu's events into a mirror of
themselves.

### 5.11 ⚠ Source identity on Apple is local

`EKCalendar.calendarIdentifier` is device-local and can change after an account re-sync, so
`sourceKey` there is (account title, calendar title) with the identifier as a hint. A rename at the
source therefore looks like a new calendar: the UI must offer **re-point this mirror** rather than
quietly creating a second one. On Android, `Calendars._SYNC_ID` + account is stable for Google.

---

## 6. The direct sign-ins

### 6.1 Google (CX-3)

- **API:** Calendar API v3, `calendar.readonly`. `events.list` with a **`syncToken`** returns only
  what changed — the `ETag` analogue, and a better one. Events carry `iCalUID`, `recurrence[]` as
  RRULE strings, `recurringEventId` + `originalStartTime` for overrides, `status = cancelled` for
  deletions — all of which map straight onto `ExternalEvent`.
- **Where:** in the proxy, over Ktor, which already does Control's egress — macOS Control now; the
  browser when a web Calendar exists. ⚠ A browser OAuth flow gets no refresh token, so a web mirror
  refreshes while the tab is open. That is acceptable precisely because the mirror *syncs*: a phone
  with an OS mirror keeps the calendar fresh for every device, the web included.
- ⚠⚠ **The refresh token is device-local and never enters the realm.** A synced token would hand
  every device — and every future device — a credential to the user's Google account. Keychain on
  Apple, Keystore-wrapped elsewhere. The mirror's device binding (§5.1) falls out of the same fact.
- ⚠⚠ **This is the feature A5 said would need its own conversation.** The CA-7 amendment reads:
  *"a calendar feed is a public document, and one that needed a secret would be a different feature
  with a different consent conversation"*. A new credential class held by Control, plus egress to
  Google — **HR#2 amendment lands with CX-3.**
- ⚖️ **Google's OAuth verification.** Calendar scopes are *sensitive* (not *restricted*): the app
  needs Google's verification — a verified domain, a privacy policy, a scope justification — but no
  third-party security assessment. Unverified, it is capped at 100 users. The Google Cloud project
  and the policy text are the maintainer's to own (Q5).

### 6.2 Microsoft (CX-4, later)

Microsoft Graph (`Calendars.Read`, MSAL, delta queries). ⭐ **Much of it arrives earlier, for free:**
Outlook for Android writes into `CalendarContract` when *Sync calendars* is on (CX-1), and an
Exchange / Outlook.com account added in iOS or macOS Settings reaches EventKit (CX-2). ⚠ Never EWS:
Exchange Online disables it by default from 2026-10-01 and removes it on 2027-04-01 (Microsoft
message center MC1227454).

---

## 7. Bare-`UID` keying in feeds and imports — fixed (2026-11-04)

**The finding.** CA-7's refresh keyed events by bare `UID`, so two subscribed feeds that shared a
`UID` took turns owning one row: `SubscriptionRefresher.write` upserted `eventId = uid` with *its*
calendar's id, the next feed's refresh moved the row to the other calendar, and the event was in
one calendar at a time (hide that calendar and it was gone) with every refresh rewriting it. CA-6's
`importIcs` had the same shape: importing into B a `UID` that lived in A **moved** it out of A
rather than copying it. The web SDK's import (`tenshu-sdk-js` `importEvents`) is a twin and had
it too.

**As built.** Every path that brings an event in now stores it under one rule,
`CalendarEventIds.inCalendar(calendarId, uid, idsAlreadyInThatCalendar)` in
`:calendar-service-common`: `"$calendarId~$uid"`, or the bare `uid` when a row is **already stored
under it in that calendar**. That is §5.2's rule, moved out of the app's `ExternalCopyIds` (which
now delegates) because the feed refresh runs in the proxy. The rule has one scheme, not two. The
refresher, the native import and the web import all use it, and a series and its overrides use
the same snapshot of ids, so they agree.

- ⚠⚠ **CA-7a's *imported-then-subscribed converges* gives way.** It was never convergence: a
  subscription always gets a calendar of its own (`addSubscription` writes `"$subscriptionId-cal"`;
  no UI subscribes into an existing calendar). So keying by bare `UID` *moved* the user's
  imported events into the feed's calendar, where the sweep owned them and **unsubscribing deleted
  them**. Data loss beats a duplicate, so the property goes. Now the imported calendar keeps its
  copy, and the subscribed calendar has its own. (The property was stated only in
  `SubscriptionRefresher.idFor`'s KDoc; it had no test. Its replacement is pinned:
  `subscribing_to_a_feed_never_takes_events_from_a_calendar_the_user_imported`.) A client that
  points a subscription at an existing calendar through the raw op still gets no duplicate: the
  refresh adopts the bare rows already in that calendar.
- ⚠ **Re-importing into the same calendar still converges**, and so does an `.ics` file exported
  from a phone copy imported back into the copy's calendar. Both name the event `"$calendarId~$uid"`,
  so the old `ExternalCopyIds.importId` special case is gone.
- ⚠⚠ **No migration, and no HR#9 bump.** No stored-row or wire shape changes: `eventId` was
  always an opaque string. Existing rows are not re-keyed. The adoption half of the rule keeps a
  pre-fix bare-`UID` row under its id when it is already in the calendar being written, so an
  upgrade neither doubles it nor deletes and recreates it (which would drop its reminders and
  dismissals). A row the old code had *moved* stays where it is, because there is no record of
  where it came from. The next refresh of the calendar it left writes that calendar a namespaced
  copy of its own. For an import, the user re-imports.
- ⚠ **A mixed-version realm churns until the stale device updates.** An old device still writes a
  bare row into a feed's calendar and its sweep deletes the namespaced one. A new device writes it
  back on its next refresh. The feed is authoritative, so nothing is lost, but the rows flap.
  Clients already move in lockstep (HR#9), so this does not justify a fencing bump.
- ⚠ **Imports have no sweep, so a mixed realm can strand a duplicate.** If a new client imports
  `work~u` and an old one later imports the same file into `work`, the old one writes a bare `u`
  beside it. Every later import adopts the bare row and leaves `work~u` behind for good. The risk
  is low because clients update in lockstep. The user can delete the stray copy.
- ⚠ **An exceptions-only file no longer patches a series in another calendar.** Its overrides
  attach to `"$target~$uid"` (or a bare `uid` *in the target*), so a series stored under a bare id
  in a different calendar is left alone. That is intended, since importing into B should never
  change A.
- ⚠⚠ **Exports keep the file's `UID`.** A namespaced event exports the sidecar's `UID`
  (`ExportableEvent.uid`, CX-1a), and every imported or fetched event has a sidecar. ⚠ This
  applies on **both** exporters. The web `exportIcs` did not pass `uid` at first, so it would have
  written `UID:work~meet@corp`. That was caught in review and is pinned by
  `anExportCarriesTheImportedUid_notTheNamespacedId`.

**Verified:** `SubscriptionRefreshTest` +3 (two feeds share a `UID`, imported-then-subscribed then
unsubscribed, a bare row is adopted), `IcsRoundTripTest` +2 (import into a second calendar copies,
re-import onto a pre-fix bare row converges), `CalendarWebTest` +4 (the web import's ids, an
export that keeps the `UID`, one file into two calendars, and a bare row adopted through
`Calendar.importIcs`). Four
tests that addressed an imported event by its bare `UID` now use the namespaced id. One of them
(`a_file_survives_import_export_and_import_again`) passed **vacuously** once ids changed, since both
sides read an empty reminder list, so it now also asserts the reminder is there.

---

## 8. Phasing

- **CX-0 — the shared half, no platform code.** `ExternalCalendar` / `ExternalEvent` + the mapper
  (with `VEVENT` synthesis); `CalendarMirror` and the HR#9 bump; `ApplyExternalEvents` and the app-side diff over a **fake
  reader**;
  the lifted writer with namespaced ids (§5.2) and write-only-what-changed (§5.3); `IcsExport`
  preferring the sidecar's `UID` (§5.2); read-only generalised (§4.3). ⭐ Verifiable entirely by unit tests, like CA-0 — and, like CA-0, where the
  decisions that are expensive to change later live.
- **CX-1 — Android (and so Google, Outlook-with-sync and DAVx5).** `CalendarContractReader` in
  the Calendar app; `READ_CALENDAR` in Calendar's manifest, asked at *Add from this phone* and nowhere
  else (the Contacts precedent). The refresh job: content-URI trigger + daily fallback, re-enqueued on
  every run (§4.4). Calendar's picker groups by account, offers both modes, and has *Add account*
  (§3). ⚠ `androidMain` ⇒ **HR#8**.
- **CX-2 — Apple.** iOS: an EventKit reader in the Calendar app, ⚠⚠ the usage strings in
  **`project.yml`** (`Info.plist` is generated and gitignored), both the iOS 16 and iOS 17 permission
  paths, refresh on foreground until `ios-background-scheduling.md` gives iOS a scheduler. macOS: the
  reader in a Swift helper inside Calendar's bundle per §4.4, or in Control's tray agent over
  `BridgeClient` if that proves impractical for a JVM app (decide here). ⚠ Settle §5.6's
  exception-date question on a device first.
- **CX-3 — Google sign-in** (§6.1). ⚖️ Gated on Q5. Threat-model amendment with the code.
- **CX-4 — Microsoft Graph** (§6.2).

⭐ **A reasonable first cut is CX-0 + CX-1 with *Copy once* only:** the migration story on Android,
no schedule, no binding semantics exercised yet — and the highest-value gesture in this doc.

---

## 9. Open questions

| # | Question | Recommendation |
|---|---|---|
| **Q1** | On Android, does the reader run in **Control** or in the **Calendar app**? | ✅ **The Calendar app** — maintainer, 2026-10-13 (revised in v0.2 — §4.4). Every OS-data permission in the repo lives in the app that uses it and Control holds none; the permission sits in the narrowest process; the prompt names the app the user is in; and a content-URI trigger refreshes on change, which Control's scheduler cannot. ⚠ Cost: the snapshot crosses IPC (app-side diff + chunking, §4.2), and Calendar runs its own job. v0.1's case for Control rested on *"no app runs background work"*, which is false (Timeline, Fit). |
| **Q2** | Do mirrors import reminders? | **Off by default, a per-mirror switch** (§5.7). |
| **Q3** | How much history does a mirror hold? | **12 months back, nothing limited forward**, sweep confined to the window (§5.8). |
| **Q4** | How often does an OS mirror refresh? | **When the provider changes** (content-URI trigger), **plus a daily fallback** and on opening Calendar. Unlike CA-7 the cadence discloses nothing — the read never leaves the device — so the only cost is battery and writes, which §5.3 bounds. |
| **Q5** | ⚖️ Who owns the Google Cloud project, the verification and the privacy-policy text? | The maintainer — a gate on CX-3, not a design question. |
| **Q6** | What happens to a mirror whose bound device is gone? | **It freezes visibly** ("last updated from …"), and any device that can see the source offers *Keep in sync from this device instead*, which rewrites `deviceId`. |

---

## 10. Threat-model & house-rule notes

- **HR#1** intact. The OS-store phases make no network request at all; everything lands in the
  ordinary encrypted realm.
- **HR#2.** CX-0…CX-2 touch none of A1–A4, A9, the relay's data shape or the sync protocol, so no
  amendment is *owed*. ⚠ Worth one A5 paragraph at CX-1 anyway: third-party calendar content now
  enters the realm by reference and is replicated to every device — a managed work calendar ends up
  on a personal laptop. It is inbound rather than a disclosure, but the user should be told so.
  **CX-3 owes a real amendment** (§6.1).
- **HR#3** — `ApplyExternalEvents` and the mirror ops take the `RefreshSubscriptions` shape.
- **HR#8** — CX-1 changes `androidMain` (Calendar's manifest and its job).
- **HR#9** — CX-0: `CalendarService` 1 → 2 with the empty step, baseline regenerated. No
  `TENSHU_STORAGE_FORMAT_VERSION` change, no relay redeploy (E2e).
- **HR#9** — P2-a: `CalendarService` 3 → 4 with a **real** step (`CalendarServiceMigrationV3toV4`
  drops the mirror's stamp field), both baselines and `PinnedServiceVersions` updated. **HR#2:**
  P2-a's tombstones use the existing `DeleteBlock` path unchanged, so the threat model needs no
  amendment.
- ⚖️ Play: `READ_CALENDAR` is an ordinary runtime permission, not one of the restricted groups
  that need a declaration form — but the personal-data **prominent disclosure** rule applies, and
  Calendar's pre-prompt explanation is that disclosure. Requested by Calendar, it is plainly the
  app's core function (§4.4).

---

## 11. As built

### CX-0 + CX-1a — the shared half, and *Copy once* on Android (2026-10-13)

The maintainer's first cut (§8): the shared half plus *Copy once* from Android's calendar
provider. **Built where it deviates from §4–§8:**

- ⭐ **No `ExternalEvent` type.** §4.1 proposed a neutral record; `ImportedEvent` — what every
  `.ics` path already produces — turned out to *be* that record. A reader's whole job is to emit it,
  so the source-specific code is one conversion and everything downstream is shared.
- ⚠ **`CalendarMirror` is not built**, deliberately. *Copy once* has no binding, no schedule and no
  diff, so the composite would have had a schema and no writer — the exact state CA-6a found
  `EventSource` in. It lands with *Keep in sync* (call it CX-1b), with its own HR#9 bump; so do the
  app-side diff/cache (§4.2) and `ApplyExternalEvents`' deletes.
- **`ApplyExternalEvents(events, sources, reminders, overrides)`** — four required lists of the
  existing composites (the `RecordImportedHealth` shape), applied events → sidecars → reminders
  (*set*, diffed so a dismissal survives) → overrides. Returns how many events were **new**, which
  is what lets a second copy say "up to date" instead of "copied 412 events" again. A blank end gets
  the same implied end `SaveEvent` gives. ⚠ The caller chunks at 200 (Binder's ~1 MB buffer).
  Series go before overrides, CA-6's order — ⚠ but it is **not load-bearing** on this path:
  CA-6's reason was `saveEvent`'s exception-date read, which the batch op never does, and
  overrides join their series at read time.
- ⚠⚠ **HR#9: `CalendarService` 1 → 2** for the added op, with the empty `fromVersion = 1` step —
  the CA-6 / CA-8 call. `SchemaShapeBaseline` regenerated.
- **Ids (§5.2):** the target calendar is `copy-` + a hash of the source's kind and stable key, so a
  second copy — from this phone or another — lands in the same calendar; each event is
  `"$calendarId~$uid"`, plain concatenation rather than a hash (a 32-bit hash over thousands of
  events collides often enough to lose one). An existing bare-`UID` row in the target is adopted.
- ⚠⚠ **The export keeps the source's `UID`.** `ExportableEvent` gained `uid`; the repository fills
  it from the sidecar (`IcsImport.uidOf`) for a series *and* its overrides. Existing rows are
  unaffected — for a CA-6 or CA-7 event the sidecar's `UID` already equals the id.
- **The conversion** (`ProviderConversion`, common, pure) carries every §5.4/§5.5 rule: UTC
  all-day dates, `Events` not `Instances`, cancelled exceptions as `EXDATE`s, `DELETED` rows
  skipped, `DURATION` in Android's `P3600S` shape, the `EXDATE` column in every shape the provider
  writes, `UID` → server id → row id with a fall-through when two series share a `UID`. Reminders
  come across only for methods that notify (not email/SMS); ⚠⚠ offsets are **signed**, so only
  `MINUTES_DEFAULT` (`-1`) is dropped — Google writes an all-day "on the day at 9:00" as `-540`. The sidecar carries the source `UID`
  plus `RDATE`/`EXRULE`, which we cannot model.
- **The reader** (`ProviderCalendarReader`, `androidMain` of `:calendar-shared-logic`) copies
  columns and decides nothing. `READ_CALENDAR` is in **Calendar's** manifest (§4.4), asked for by
  the *Copy from this phone* button and nowhere else; a `SecurityException` becomes
  `ExternalCalendarAccessDenied`, which the sheet explains.
- **The UI:** a small FAB on the calendars screen (Android only — the reader is bound in
  `CalendarApplication`, and its absence hides the flow), a sheet listing the OS's calendars by
  account with unsynced ones shown but disabled, and *Add an account* → `Settings.ACTION_ADD_ACCOUNT`,
  unfiltered. The copy states that it is **once** — later changes at the source are not copied.
- ⚠ A first copy names the calendar after the source, or `Name (account)` when the name is taken;
  an existing target keeps the name and colour the user gave it.

**Verified:** 48 new tests — `ProviderConversionTest` 21, `ExternalCopyViewModelTest` 13,
`ExternalCopyRepositoryTest` 6 (real proxy, real op, real storage, read back through the occurrence
resolver and the exporter), `ExternalCopyIdsTest` 4, `ApplyExternalEventsTest` 4 (one over the
wire, serialized the way Binder frames it). **9 mutations, all killed** — all-day read in the
row's zone, cancelled exception dropped, email reminders kept, ids not namespaced, `added`
counting what was offered, reminders rewritten wholesale (loses a dismissal), the override's
export `UID` dropped, a re-copy renaming the user's calendar, the double-tap guard removed.
⚠ The first all-day test **could not have caught** that mutation — it set `EVENT_TIMEZONE = UTC`,
as the provider contract says, so a reader trusting the column would pass it. Found on review,
before the mutation run; the test that kills it puts a non-UTC zone on an all-day row.
Beyond the new suites: `calendar-shared-logic` 444 · `calendar-service-proxy` 67 ·
`calendar-service-common` 167 · `calendar-shared-ui` desktop 25 · and every module that depends on
Calendar — `control-shared-proxy` 105, `mia-service-proxy` 122, `mia-service` 51,
`system-test-utils` 93 (incl. `SchemaShapeBaselineEnforcementTest`, regenerated: exactly the 30
calendar lines 1 → 2 and the one new op line). JS main compiles for all five `:calendar-service*`
modules (their JS *test* compile is disabled by the build, as before). Android: both
`compileAndroidMain`s and `:calendar-android:assembleDebug` — the APK declares `READ_CALENDAR`
and still strips `INTERNET`. ⚠ **Not verified:** the iOS lane (Linux cannot compile Kotlin/Native
for Apple — `calendar-shared-logic` targets iOS, so the new common code is unverified there), and any real provider — no emulator
calendar has been read, no Google account copied, and the permission prompt, the sheet and the
add-account intent have never been seen on a device. §5.4's all-day rule and §5.5's exception rules
are pinned by tests over hand-built rows shaped to the platform's documentation, not by rows a real
sync adapter wrote. ⚠ `androidMain` changed ⇒ **HR#8 owed**.

**Device pass (2026-11-01, OnePlus CPH2581, real Google account).** Permission asked at the button
only; unsynced calendars shown disabled with the reason; a 57-event Google calendar (5 series, one
edited occurrence) copied to 57 events, 57 sidecars, 1 override, and a re-copy reported "up to
date". Two defects found and fixed: ⚠⚠ a `minutes >= 0` filter meant to drop `-1` also dropped
every **after-midnight all-day reminder** (`-540`), and every row promised "a **new** Tenshu
calendar" although a re-copy updates the first one's (now "Copy again into "<name>""). ⚠ An
interrupted copy (Control killed mid-op) left **events without sidecars or reminders** — the op is
not atomic; a second copy repairs it. Expected, not a defect: the edited occurrence's own reminder
is not copied (§5.5, an override has none in our model).

**Threat model:** an A5 amendment landed with this slice, though HR#2 did not require one (§10):
the copy is inbound and makes no request, but it replicates a calendar from one phone's account to
every device in the realm, and the user should be able to read that.

**§7 since:** CA-7 feeds and CA-6 imports now use the same rule (fixed 2026-11-04, §7).

### CX-1b — *Keep in sync* on Android (2026-10-14)

Stacked on CX-1a. The second mode: a **read-only** Tenshu calendar that follows a calendar the
phone already holds. **Built where it deviates from §4–§9:**

- **`CalendarMirror`** (`CalendarService` 2 → 3, empty `fromVersion = 2` step, baseline
  regenerated): `mirrorId` derived from the source, `calendarId`, **`deviceId`** (the binding,
  §5.1), source kind/key/label, `remindersEnabled`, `lastRefreshedEpochMs`. ⚠ **No
  `snapshotDigest` on the row** — §4.3 put it there; it lives app-local instead
  (`MirrorDigestStore`), because a synced digest would be a sync write per refresh, and losing it
  costs one full diff. ⚠ No pause switch: *Stop syncing* is the off switch.
- **Ops:** `SaveCalendarMirror` (the whole row, always — the bound device writes about itself),
  `DeleteCalendarMirror` (**keeps the events**: the calendar becomes ordinary and editable — the
  opposite of `DeleteSubscription`, because a mirror is the user's own calendar). `DeleteCalendar`
  now **cascades** to a mirror filling it, or the next refresh would re-create the calendar the
  user just deleted.
- ⚠⚠ **`ApplyExternalEvents` now *sets* overrides per batch event**, like reminders. When a
  source moves an occurrence back, its exception disappears there, and nothing in the service ever
  deleted an override — an upsert-only write would show it moved forever. So each series now
  travels in the same call as its overrides (the copy path too), and the order note from CX-1a is
  replaced by this stronger rule.
- **The diff runs against the realm, not an app-local cache** (§4.2 proposed a cache). The realm
  is the truth and a cache can drift; reading one calendar's events, overrides and reminders per
  *changed* refresh is cheap. The app-local digest skips even that when the source is unchanged.
  A blank source end is not compared (the op implies one), and exception dates compare as sets.
  Deletes go one `DeleteEvent` per id — a source rarely deletes more than a handful at once.
- ⚠⚠ **Two refusals the doc did not have:** an **empty read is never applied** (the provider is
  briefly empty while an account re-syncs, and a sweep would empty the mirror), and a source whose
  **sync is off** (`holdsEvents = false`) is left alone (the provider may drop its events). Cost:
  a source the user genuinely emptied is not mirrored until it has an event again.
- **A source the device no longer lists** (account removed) leaves the mirror exactly as it is.
- **Q2 answered as recommended:** reminders **off** by default, a per-mirror switch, and the switch
  is in the digest so toggling it re-syncs at once.
- ⚠ **Q3 answered *no window*, against the recommendation.** Confining the sweep to a window is
  exactly the subtle rule that loses history (§5.8), and *Copy once* already brings the whole
  calendar; v1 mirrors everything. Revisit if a real calendar proves too large.
- **Q4 answered as recommended**, on `JobScheduler` directly (no new dependency): a content-URI
  job on `Events`, `Reminders` and `Calendars`, batched 30 s / at most 10 min, **re-armed on every
  run** (content-trigger jobs are one-shot); plus a persisted daily job, because content triggers
  do not survive a reboot. Armed at app start and — through `MirrorBackgroundScheduler` — the
  moment a mirror starts. `RECEIVE_BOOT_COMPLETED` is now declared rather than inherited.
- **Q6 answered as recommended:** the mirror's id derives from the source, not the device, so
  choosing *Keep in sync* on a second phone **rebinds** the same mirror and calendar.
- ⚠ **The stamp is coarse** — an unchanged refresh re-stamps at most hourly, since the change
  trigger fires for edits to *any* calendar on the phone and the stamp is a synced row.
- ⚠ **A mirror never lands in the calendar a *Copy once* of the same source made** — converting
  an editable copy into a mirror would let its first refresh delete what the user added.
- **UI:** a tap in the chooser now asks *Copy once* / *Keep in sync* (with *Also remind me from
  Tenshu*, off). A mirrored calendar's row says where it is kept in sync from and when it last
  updated, on every device; *Refresh now* only where the source is; *Stop syncing (keep events)*.
  A mirrored calendar is no longer offered as an `.ics` import target.
- ⚠ **Mirror methods are a separate `CalendarMirrorRepository`**, which `TenshuCalendarRepository`
  also implements (one Koin instance behind both), so screens and fakes that know nothing about
  mirrors did not change.

**Review fixes (Codex, 2026-11-01).** ⚠⚠ *Read-only* was asserted, not enforced: the editor
offered every calendar and opened any event for editing, so a user's edit was overwritten and an
added event swept by the next refresh. Now one flag, `CalendarSummary.readOnly` (a CA-7
subscription **or** a mirror — the gap was older than CX-1b), drives it: the editor offers and
preselects only writable calendars, opens an event in a read-only one with a notice and no
Save/Delete, and every public write entry point refuses; *Make default* is withheld too, because
Mia's headless create files into the default. ⚠⚠ A **partly** unreadable snapshot (a series whose
row has no `DTSTART`) reached the sweep, which deleted that series; the snapshot now carries
`unreadableUids` — every id the skipped row could have been stored under — and the sweep spares
them; a skipped *exception* row flags its series (`incompleteOverridesFor`) and the refresh resends
the stored override with it, since the op *sets* overrides. ⚠⚠ Every read-modify-write of the
mirror row (start, stamp, reminder switch) and every refresh write shares one in-process lock with
*Stop syncing*, and a refresh re-checks under it that its mirror still exists — a stop or a
reminder change can no longer be undone by a refresh already in flight. A stop from another
device still arrives by sync. A restarted mirror never re-fills the calendar a stopped one left
behind (it gets its own), and the diff compares sidecars, so an `RDATE`/`EXRULE`-only change lands.
⚠⚠ **Fixed in P2-a (below) — was open at round 14 (independent review): the freshness stamp lived in
the shared `CalendarMirror` row**, and `stampMirror` re-saved the whole row. A bound phone that was offline while another device
stopped the sync (or deleted the calendar, or switched reminders off) re-saves the row on its next
due stamp; its write is newer, so the row comes back (`docs/sync/design.md` §2.6), the calendar turns
read-only again, and the phone's next refresh can sweep events the user added meanwhile. The fix is a
reshape — the stamp in a per-device row, or a field-scoped op — and is left for a follow-up; until
then, *Stop syncing* on a device other than the bound one is safe only while the bound phone is
online. Tracked in `tech-debt.md` *"Mirror stamp can resurrect a stopped mirror"*.

**Verified:** 18 new tests — `MirrorSyncTest` 10 (fake reader → `MirrorRefresher` → the real
repository → the op → storage → the read path), `ExternalCopyViewModelTest` +5,
`ApplyExternalEventsTest` +3 (overrides set per batch, mirror row round-trip, calendar delete
cascades), `CalendarInteropViewModelTest` +1. **12 mutations, all killed** — an empty read
applied, a sync-off source applied, a mirror bound elsewhere refreshed, the digest never skipping,
a stamp on every refresh, the diff writing everything, no sweep, reminders ignoring the switch,
overrides upserted rather than set, a calendar delete leaving its mirror, the background job not
armed when a mirror starts, a mirrored calendar offered as an import target. Calendar, Control and
Mia suites green (`calendar-service-common` 167, `-proxy` 70, `calendar-shared-logic` 460,
`calendar-shared-ui` 25, `control-shared-proxy` 110, `mia-service-proxy` 122, `mia-service` 51);
`SchemaShapeBaselineEnforcementTest` green at version 3 (31 lines 2 → 3, plus the mirror composite,
its id provider and the two ops); the four `calendar-service*` modules compile for JS; the APK
builds with the job service and `RECEIVE_BOOT_COMPLETED`, and still no `INTERNET`.
⚠ **Not verified:** the iOS lane (as in CX-1a), the smoke gate (HR#8 — no emulator here), and
**anything the JobScheduler does on a device**: that the content trigger fires on a provider edit,
that it re-arms, that the daily job survives a reboot. Those need a maintainer's phone before
*Keep in sync* is described as automatic anywhere a user reads it.

### CX-2a — *Copy from this iPhone* (built 2026-11-01)

`AppleConversion` (common, pure) turns EventKit rows into the same `ImportedEvent`s as every other
path: occurrences fold back into one series by `calendarItemExternalIdentifier` (else the device-local
`eventIdentifier`); the RRULE is formatted from the structured `EKRecurrenceRule`; a detached
occurrence is an override keyed by its `occurrenceDate`; `EXDATE`s are inferred inside the read
window (§5.6); all-day events are read in the **device zone**, the opposite of Android's UTC (§5.4);
a `BYSETPOS` rule is skipped and its series spared (`unreadableUids`), never flattened; `sourceKey` is
(source type, account, title), never `calendarIdentifier` (§5.11). New `ExternalSourceKind.APPLE_EVENTKIT`.

As built on top: `EventKitCalendarReader` (`iosMain`) copies fields and decides nothing — one
predicate over today − 1 y … today + 2 y (under EventKit's 4-year cap), the rule and alarms off the
series master. ⚠ Its `EKEventStore` is built on first read, not with the view model: the view model
exists before the grant, and a store made before it can go on listing nothing.
`CalendarSurface.externalCopyViewModel()` passes no mirror repository, so iOS offers *Copy once* only
(*Keep in sync* is CX-2b). The Swift shell asks at the button — `requestFullAccessToEvents` on 17+,
`requestAccess(to: .event)` on 16 — and a refusal opens the sheet on a reason with *Open Settings*,
because a second request returns at once without a prompt. Both usage strings live in `project.yml`
(iOS 17 terminates on `requestFullAccessToEvents` without the FullAccess one).

⚠⚠ **A moved occurrence has identifiers of its own** (seen on the iOS 27.2 simulator, local
calendar): `eventIdentifier` and `calendarItemExternalIdentifier` both gain `/RID=<seconds>`, it
carries no rule, and `eventWithIdentifier` on it answers with itself. Grouped as-is it became a
second series *and* its slot read as a deleted occurrence. `AppleConversion.seriesIdentifier` strips
the suffix for grouping, the uid and the reader's master lookup, and the series' own fields come from
an unmoved occurrence. Unit tests cannot have found this — the rows were shaped from the headers —
so it was found by seeding a series in the simulator's EventKit store and reading it back through
the real reader.

Further rules: a copied series gets the same `VEVENT` sidecar `ProviderConversion` writes,
so an export carries the source's `UID` rather than the namespaced id; a yearly rule on
`daysOfTheYear` / `weeksOfTheYear` is skipped like `BYSETPOS` (no `BYYEARDAY`/`BYWEEKNO` in the
expander), and so is a yearly day selector (weekdays, ordinal or not, or month days) with no month —
⚠ EventKit means the whole year, our expander pins a month-less yearly rule to the anchor's month;
a series' title, notes and place come from the resolved master, never from an edited occurrence; `firstDayOfTheWeek` maps to `WKST`; and deletions are inferred only for occurrences
whose **instant** falls inside the predicate's instant window — an event in another zone sees the
window's edges on different dates.

**Verified:** on the iOS 27.2 simulator — the permission prompt with our string, the chooser
(local, Birthdays, a subscribed holiday calendar with 85 events), and a seeded weekly series with
one deleted and one moved occurrence read back as one series, one inferred `EXDATE` and one
override. ⚠ **Not verified:** the save into a real realm (the simulator has no Control provisioned,
so the copy ends on *could not be saved*, as it should), any CalDAV/Exchange account, whose
detached-occurrence ids may differ, and a real iPhone. Still open: the macOS reader decision.

### CX-2b — *Keep in sync* on iOS (built 2026-11-01)

No new mechanism: `CalendarSurface` hands the installed repository (it is `TenshuCalendarRepository`,
so also the `CalendarMirrorRepository`) and a `MirrorRefresher` — its own `EventKitCalendarReader`,
`UserDefaultsMirrorDigestStore`, the clock — to the copy, management and interop view models, so the
Compose-tested rules (bound-device only, digest skip, an empty read never applied, a stopped mirror
keeps its events) are the ones iOS runs. On the not-connected surface both are null and the sheet
copies once.
⚠⚠ **Foreground is the whole schedule.** `CalendarSurface.refreshMirrors()` runs on launch and on
every return to the foreground, after the shared-replica rehydrate; coalesced (a second call while one
runs is dropped) and cheap when nothing changed. There is no background scheduler for Calendar on iOS
and EventKit has no content trigger, so the sheet promises "updated each time you open Calendar",
never Android's "whenever it changes", and the row shows when it last refreshed.
⚠⚠ **A windowed snapshot is complete only inside its window**. EventKit reads
today − 1 y … + 2 y, so `ExternalSnapshot.window` (`SnapshotWindow`) carries that span and the mirror
sync deletes only series with an occurrence certainly inside it, and keeps stored exception dates and
changed occurrences outside it. Without it, every mirrored event was swept once it aged past a year,
and old cancellations and moves were undone on each refresh. Judged on civil dates with a day's
margin each side, so the error is always to keep. Android's provider read is whole (`window = null`).
Precedence inside a windowed sync: a stored move is kept when its original slot or its new date is
outside the window — EventKit queries by where an occurrence is *now* — and wins over a cancellation
inferred for that slot; it is dropped once the slot is seen again unmoved
(`ExternalSnapshot.observedOccurrenceDates`); and a changed occurrence actually read beats a stored
cancellation of its slot (the resolver applies exception dates first). Moves are judged by their **effective** date: a stored
move whose new date is covered and that the read no longer returns was cancelled, so its original
slot becomes a cancellation. ⚠ That is indistinguishable from a second move to beyond the window
(over a year back or two ahead), which is then lost from the mirror — accepted as far the rarer case.
⚠ A recurring series absent from the read that still recurs outside the window is ambiguous
(every covered occurrence cancelled, or the series deleted): it is read as cancellations of its
covered slots, so history and far-future occurrences survive and a deleted series is cancelled slot
by slot as the window advances; only a series wholly inside the window is swept.
The digest includes the window, so an advancing window reconciles once a day.
⚠ Revoked access is silent by design: the refresher reports it per mirror and leaves the stamp, so the
row visibly goes stale instead of the app erroring on open.
**Verified:** the shared mirror and copy suites run on the iOS simulator (509 tests in the module,
including `MirrorSyncTest` and `ExternalCopyViewModelTest`), a digest-store and a surface test, the
Swift compile. ⚠ **Not verified:** any of it against a real realm — the decision sheet, the synced row
and a foreground refresh need a provisioned Calendar (a real iPhone, or a simulator with Control),
which this session did not have. An `EKEventStoreChanged` observer while the app is open, and a
`BGAppRefreshTask`, are the obvious next steps once iOS background scheduling lands.

### P2-a — the stamp leaves the mirror row, and a stop syncs (2026-11-02)

The round-14 finding CX-1b left open. **`CalendarService` 3 → 4.**

- **The stamp is its own row.** `CalendarMirrorStamp` (`stampId = "$mirrorId@$deviceId"`,
  `mirrorId`, `deviceId`, `lastRefreshedEpochMs`), a `maintained` relation `mirrorStamps`, and a
  `SaveCalendarMirrorStamp` op. `stampMirror` writes only that row, and only for a mirror bound
  here. `CalendarMirror` loses the field and `SaveCalendarMirror` the parameter. ⚠⚠ The rule this
  leaves: **nothing the bound device writes routinely may live on the mirror row.** Only a user's
  own choice (start, rebind, the reminder switch) saves it.
- ⚠ **Keyed by device, and only the bound device's row is read.** A phone rebound away writes its
  own row until it hears of the rebind, which cannot touch the new device's freshness. Starting or
  rebinding resets this device's stamp to 0, so a row it left from an earlier binding, or an orphan
  a stale stamp wrote after a stop, never claims freshness. The reminder switch writes the **bound**
  device's row to 0 (the one stamp a device other than its owner writes, and only ever 0).
- ⚠⚠ **The finding under the finding: *Stop syncing* never synced.** `SyncObserver.onDataDeleted`
  is a deliberate no-op, and the tombstone is each delete op's duty. `DeleteCalendarMirror` emitted
  none, so a stop on any device but the bound one stayed on that device. The phone kept the mirror,
  and its next stamp (at most an hour later, offline or not) re-sent the row and undid the stop
  everywhere. Moving the stamp alone would only have hidden that. `deleteMirrorRow` now tombstones
  the mirror row and its stamp rows, and `calendarServiceProxyImplementationFactory` takes a
  `tombstoneEmitter`, wired on Android, desktop, iOS, the web runtime and the system-test harness.
  ⚠ **No other Calendar delete emits one yet.** Events, calendars, overrides, reminders and
  subscriptions still delete on one device only: `tech-debt.md` *"Calendar deletes emit no
  tombstones"*.
- **The step is real.** `CalendarServiceMigrationV3toV4` drops the stamp field from stored rows and
  from v3 relay history. The old stamp is **not** carried over: a step is pure and cannot write the
  new row. Nothing is lost that a refresh does not give back, because an absent stamp reads as 0 and
  the bound device's next refresh reconciles in full and stamps. Until then other devices show *not
  updated yet*.
- ⚠ **Still open, by design:** a user action on a stale device (the reminder switch, *Keep in sync*
  again) is a whole-row save and can still bring back a mirror stopped elsewhere while that device
  was offline. It is the user's own newer choice, and so not the automatic resurrection this fixed.
  A refresh already under way on the stale phone can also still write events into the calendar
  before the stop arrives (the CX-1b note above).

**Verified:** `CalendarMirrorStopSystemTest` (new, two real devices through the relay, partitioned by
the production push gate). The tablet stops the mirror while the phone is offline, the phone then
stamps, and the stop wins on both devices, in both orders the writes reach the relay. Before the
fix both cases failed. The stop's tombstone never left the tablet. **2 mutations, both killed in both
orders:** a whole-row re-save put back into `stampMirror` (*"[phone] the stop wins…"*, the mirror
back) and the tombstone dropped (*"…the tablet's stop reached the relay"*). Single-device:
`MirrorSyncTest` +3 (a stamp saves only the stamp row; only the bound device's stamp is read; start
resets it), `ApplyExternalEventsTest` (stamp round-trip, stamps go with the mirror),
`CalendarServiceMigrationTest` (a v3 row keeps everything but its stamp). `scripts/run-gate.sh`
green apart from the 2 known `macos-helpers` codesign reds, with JS and iOS lanes green;
`:tenshu-sdk-ios-calendar:iosSimulatorArm64Test` green; Android smoke gate (`ANDROID_SERIAL=emulator-5554`) passed, after reinstalling the two canaries, which were stale pre-reset installs from 2026-10-22.
⚠ **Not verified on a device:** a real stop between two phones.

### CX-2c — the macOS reader: decided (2026-11-04)

✅ **In the Calendar desktop app, in-process, over `:macos-helpers`' FFM Objective-C bridge** — not a
Swift helper in the bundle, not Control's tray agent. §4.4's rule (*OS data is the app's*) holds, the
TCC prompt names TenshuCalendar.app, and there is no second binary to build, bundle, sign or speak
to. The bridge is already shipped for AVFoundation (the camera) and LocalAuthentication, whose
`(BOOL, NSError *)` completion block is exactly EventKit's access-request shape.
⚠ Costs: `:calendar-desktop` moves to the FFM JDK toolchain (25) like Drive and Control; the
usage strings go in the jpackage `Info.plist` (`NSCalendarsFullAccessUsageDescription`, macOS 14+,
and `NSCalendarsUsageDescription` before it); and ⚠⚠ an unbundled `:calendar-desktop:run` has no
usage string, where touching EventKit makes TCC **abort the process** — so the reader reports
*unsupported* unless the running bundle declares one, and only the packaged `.app` can copy.
⚠ Signing: the default build is not hardened, so no entitlement is needed; the opt-in
`-Ptenshu.hardenedRuntime` build would also need `com.apple.security.personal-information.calendars`.

As built: `MacEventKit` (`:macos-helpers`) copies EventKit values out plain (status, the
macOS 14 / pre-14 access request over the existing `(BOOL, NSError *)` global block, calendars,
occurrences with their resolved master — looked up by the `/RID=`-stripped series id); Calendar's
`MacCalendarReader` (`:calendar-shared-ui` desktopMain) maps them into the common `AppleConversion`
with the iOS window, so the snapshot carries its `SnapshotWindow` and the windowed mirror rules apply
unchanged. `desktopDeviceCalendarModule()` binds the reader, a `java.util.prefs` digest store, the
`MirrorRefresher` and a `DesktopMirrorScheduler` (refresh at launch, then every 15 minutes while
the app runs — ⚠ nothing while it is closed, and the copy says *"regularly while Calendar is open"*).
`DeviceCalendarAccess` gained `deviceName` / `permissionHint` / `syncCadence`, so the shared sheet
says *this Mac* and points at System Settings › Privacy & Security › Calendars.
⚠ Loading `MacEventKit` on a pre-22 JVM throws a `LinkageError` — the module's tests run on 17 —
so `macCalendarsUsable()` reads that as unsupported.
**Verified:** compiles (desktop, the app on JDK 25, Android); the unbundled guard is tested on 17
and 25 (no reader bound, no EventKit call); the EventKit-numbering mapping; the packaged `.app`
carries both usage strings, bundles JDK 25.0.3 and passes `--enable-native-access`.
✅ **Checked on a real Mac (2026-11-04, maintainer's machine, every desktop app reinstalled from
master `68625692a`):** the TCC prompt named Tenshu Calendar, the calendar list loaded, *Copy once* and
*Keep in sync* both completed, and Calendar's stdout showed no error. ⚠ Checked by the maintainer's
eye, not measured: no event count or store read was taken, and a change made in Apple Calendar
arriving on the next refresh was not separately confirmed.

### P2-b — the chooser knows what a mirror already brings in (2026-11-05)

**The finding (maintainer's Mac + iPhone, 2026-11-05).** iCloud "Arbeit" was kept in sync from the
Mac. On the iPhone, the chooser said *"Bring into a new Tenshu calendar"* for it, *Copy once* made
"Arbeit (iCloud)" beside the mirror, and every event showed twice. Not a key mismatch: the store held
both under one source hash (`mirror-3a6e2599-cal` and `copy-3a6e2599`), so the two devices computed
the same `sourceKey`. The chooser's *"copied before"* lookup checked the derived **copy** id only, and
never the mirrors — and since a mirror and a copy never share a calendar (CX-1b above), the copy
landed beside it by design.
- ⭐ **No duplicate filter.** Matching events by title and time across calendars hides two genuinely
  different events; the duplicate is prevented at the one place it is made instead.
- `ExternalCopyStep.Choosing.mirroredInto` maps a source (matched on kind **and** key, the pair the
  mirror id derives from) to a `MirroredSource` — Tenshu's calendar name, whether this device is
  bound, the mirror's reminder switch. The row says *"Kept in sync from another device, in “Arbeit”"*.
- ⚠⚠ **A mirrored source offers no *Copy once*, and `ExternalCopyViewModel.copy` refuses it** — the
  rule lives in the view model so the SwiftUI sheet cannot forget it. The decision offers *Keep in sync
  from this device instead* (the Q6 rebind — same mirror, same calendar) or, when bound here, *Update
  now*. The reminder checkbox starts from the mirror's own switch, because a rebind with "off" turns it
  off.
- The reverse order (copied once, then kept in sync) is still allowed — the mirror must not take over
  an editable copy — but the decision now says the copy stays and can be deleted afterwards.
- ⚠ A duplicate made before this fix stays until the user deletes the copy (it is an ordinary,
  deletable calendar).
