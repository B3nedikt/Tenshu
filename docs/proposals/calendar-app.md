# Calendar — initial requirements & pre-design decision record

**Status:** v2.2 — **CA-0 … CA-8 COMPLETE** (2026-08-05 → 08-09); as-built notes in **§11**.
The phase map is finished: Calendar ships on Android, desktop and iOS, with recurrence, reminders
(pre-armed on Apple platforms), ICS import/export, subscriptions, a home-screen widget and a full
native iOS shell. ⚠ What remains is not a phase — it is the verification this environment cannot
do (see below) plus two named gaps: *Snooze* / *Dismiss* on an iOS reminder, and week/day time
grids on a native shell. (`.ics` import/export on iOS, once listed here, shipped in CA-6c.)
⭐ **Next track (2026-10-13): bringing in the calendars a user already has** — one-way, from the
OS calendar store first, then a direct Google sign-in, Microsoft later →
[`calendar-external-sources.md`](calendar-external-sources.md). It follows up §2.2's
*account calendars* row; two-way CalDAV stays out.
⚠⚠ **Until 2026-11-04 no Calendar delete synced** (tombstones, §11 *Deletes reach every device*):
every delete now goes through `deleteRow`, which tombstones it.
⚠⚠ No iOS code — Swift *or* Kotlin/Native — compiles in this environment, so CA-8 is written
with everything decidable pushed into tested `commonMain`; see the CA-8a note in §11.
Requirements settled at v0.4. Opened off
[`milestones.md`](../milestones.md) §Calendar. This doc answers three questions asked going in:
what does the platform already give us (§4), what does "80 % parity with the stock apps" actually
contain (§2), and how much UI do we genuinely have to write ourselves (§5).
✅ **v0.3: Q1–Q14 all answered** in the maintainer session of 2026-08-05 — **§8 is now a decision
table, not an open-questions list**, and design can start.

⭐⭐ **v0.4 (same day) supersedes v0.3's desktop-reminder answer.** Asked whether macOS has an
official mechanism for this: it has **two**, and one of them — `UNUserNotificationCenter` — is
**already running in this repo**, in Control's macOS tray agent, missing only a trigger. New
**§4.6**. Desktop reminders therefore stop being a hand-rolled scheduler and become an extension
of working Swift, which also **removes the condition v0.3 put on Q8**.

⭐ **v0.2 resolves v0.1's one open ambiguity** (§1): the "immediate value for Mia" is **the
assistant app**, and the driving use case is **recording an event by utterance**, not reading one
back. That is a *write* path, and §6 is the new section working out what it costs — including
four things Calendar forces open in the Mia design, and one requirement it adds to Calendar's own
service layer. ✅ **Those four landed the same day** as
[`mia-query-layer.md`](mia-query-layer.md) **v0.2 / MQ-D4a** (Q11–Q13 resolved); that doc is now
the source of truth for the Mia half, and §6 is the rationale behind it.

Sibling to [`contacts-app.md`](contacts-app.md), [`maps-app.md`](maps-app.md),
[`timeline-app.md`](timeline-app.md) and [`fit-app.md`](fit-app.md); Calendar is the same
"parallel-lane productivity app" class and should reuse their shape wholesale.

**Headline findings.**

- ⭐ **The alarm/punctuality machinery is real and reusable — but it is not the Hub's.** It lives
  in `:sync-service-proxy` (`PlatformScheduler.register(item, punctual)` → Android
  `PunctualAlarmScheduler`), was *born* in the routines engine (RE-D5), and is
  platform-generic. Calendar can take it as-is on Android.
- ⚠⚠ **What Calendar must NOT reuse is the part that looks most relevant**: the routines engine's
  `ExecutorClaim` / `PresenceResolver` elect **one** device to fire a routine exactly once across
  the household. A calendar reminder has the opposite requirement — it must fire on **every**
  device the user carries. Reuse the scheduling class, not the election.
- ⚠⚠ **Reminders do not actually work on two of three platforms yet.** Desktop, iOS and the hub
  all bind `NoOpPlatformScheduler`; only Android has an OS hand-off. And `:notification-service`
  stores *synced in-app rows*, not OS notifications — there is no **reusable presenter seam**, so
  every app that posts a real system notification hand-rolls it. Calendar is the app that forces
  both gaps. Sized in §4.3.
  ⚠ **v0.3 corrects v0.2 on *why* desktop is affected.** Control on desktop **is** a resident
  daemon (a LaunchAgent, autostarted hidden at login), so residency was never the problem — the
  problem is that `ScheduleDrainLoop` counts ticks rather than consulting a clock, so a pending
  one-shot loses to both machine sleep and any Control restart.
  ⭐⭐ **v0.4 then supersedes the fix**: macOS has an idiomatic mechanism and Control's tray agent
  is **already using it** — a working `UNUserNotificationCenter` path that needs only a trigger
  (§4.6). So the desktop gap is much shallower than v0.2/v0.3 implied, on both halves: the
  scheduler *and* the presenter.
- ⭐ **The most interesting permission finding.** `PunctualAlarmScheduler`'s own KDoc deliberately
  declines `USE_EXACT_ALARM` because Play restricts it "to apps whose *core* function is alarms
  or calendars". **Calendar is literally that app** — but ⚠ the alarm is scheduled by *Control*
  (the proxy process), which is not a calendar app. The permission is in the wrong process. Q6.
- **UI: less than it looks, but not free.** Roughly **70 % of the event-editing surface is
  free** from Material 3 (`DatePicker`/`TimePicker` are already used in `:fit-shared-ui`), and
  the agenda list is a `LazyColumn`. The month grid and the week/day time-grid have **no
  component on any platform** and get hand-rolled — twice, because no CMP UI ships on iOS.
  ⭐ Mitigation: the expensive part of a week grid is the **overlap-layout algorithm, which is
  pure math**, so it goes in Compose-free `:calendar-shared-logic` and is written once
  (the `:basemap-core` D7 precedent — "the shareable half was never UI").
- **Size estimate: ~9 000–12 000 LOC**, ≈1.5× Contacts (6.6 k measured). §5.4.
- ⭐ **Mia integration needs no new primitive** (§6). `mia-query-layer.md` MQ-D2 already puts the
  query layer **proxy-side**, so Mia reaches Calendar exactly as it reaches file-service — the
  app-to-app invocation gap `contacts-app.md` Q1 found deferred to Rei v2 does **not** block this.
  ⚠ But `CreateEvent` is a *write*, and MQ-D6's capability model is explicitly **read**-scoped,
  MQ-D4's intent table is closed with no calendar entry, and MQ-D1's "Mia cannot hallucinate"
  property is a read property that a write intent breaks. All four are §6.2.
- ⭐⭐ **The Mia hook is `CA-1`, not `CA-8`** — ordinary CRUD ops over a native schema *are* the
  integration. Which also promotes §3 D1 (native schema, not iCal blobs) from tidiness to a
  hard requirement: a raw-blob store is unreadable to the planner.

---

## 1. Goal & scope

Ship the milestone Calendar app: create events, navigate by day / week / month, recurring events,
reminders, an Android home-screen widget — on Android, desktop and iOS, over a new
`:calendar-service`, at **parity with the stock Android/Apple Calendar apps as far as it makes
sense** for a platform with no email plane and no account federation.

**Why it is worth doing now.** It is cheap relative to Drive/Timeline/Fit, it gates nothing else
in the milestone sequence, and it is the clearest "Tenshu replaces Google's free tier" signal
after Drive. It is also the first app whose value is *daily and involuntary* — a file manager is
opened on purpose; a calendar reminder arrives whether you opened the app or not. That makes it
the app most likely to be used every day, and the one least forgiving of a reminder that does
not fire.

**Resolved in v0.2: the second consumer is the Mia assistant app**, and specifically its *write*
path — "add a meeting tomorrow at 3" is the motivating gesture, not "what's on today". That is a
sharper requirement than a read integration and it lands in three places:

- **§3 D1 becomes load-bearing.** The event store must be **chain-queryable**, because MQ-D3
  lowers every plan to an ordinary chain-DSL callstack. A raw-iCal-blob store would be opaque to
  the planner — so the native-schema decision stops being a tidiness argument and becomes the
  thing that makes assistant integration possible at all.
- **The service layer must be complete without the UI** (§6.4) — an op invoked headlessly cannot
  lean on an editor screen to resolve a default calendar, duration or reminder.
- **§6** works out the rest, including what Calendar forces open in the Mia design.

⚠ Everything in §2–§5 stands unchanged; the assistant reading adds requirements, it does not
retract any.

---

## 2. What "80 % parity" actually contains

The stock apps' feature surface, scored by *how often a normal person touches it* rather than by
how much space it takes in a feature matrix. "80 % parity" is a claim about the daily loop, not
about the settings screen.

### 2.1 In scope — this is the 80 %

| # | Capability | Why it is in the 80 % | Cost |
|---|---|---|---|
| 1 | **Month / week / day / agenda views** with swipe-through navigation | The whole app is navigation; a calendar you cannot scan is not a calendar | ⚠⚠ High (§5) |
| 2 | **Create / edit / delete an event** — title, time, all-day, location text, notes | The other half of the whole app | Low — M3 pickers |
| 3 | **All-day and multi-day events** | Holidays, trips, birthdays; ~a third of most people's rows | Medium (a date, not an instant — §3 D3) |
| 4 | **Recurrence** — daily / weekly-by-weekday / monthly / yearly, with an end date or count | "Every Tuesday" is the single most-used advanced feature | ⚠⚠ High (§3 D2) |
| 5 | **Recurrence exceptions** — edit or delete *this* occurrence vs *all* | Falls out of (4) immediately in real use, and is where naive models break | Medium, if (4) is modelled right |
| 6 | **Reminders** — n minutes/hours/days before, that genuinely fire | The one feature whose failure is *noticed*, and the reason a calendar is trusted | ⚠⚠ High (§4) |
| 7 | **Multiple local calendars** — name + colour, per-calendar visibility toggle | "Work / Personal / Family" is the universal organising gesture | Low |
| 8 | **Timezone correctness** — an event is a local time in a zone, not an instant | Invisible when right, catastrophic when wrong (§3 D3) | Medium |
| 9 | **Search events** | Cheap over the chain DSL; Drive's `Contains` precedent | Low |
| 10 | **ICS import / export** (a `.ics` file in, a `.ics` file out) | The migration story — "can I bring my calendar over" | Medium |
| 11 | **Android home-screen widget** (agenda) | Named in the milestone; the widget *is* how most people read a calendar | Low — Glance precedent (§5.3) |
| 12 | **Sync across the user's own devices** | Table stakes, and free — it is what the platform is | ⭐ ~Zero (§4.5) |

### 2.2 Deliberately out of v1 — and this is where the missing 20 % lives

| Capability | Why out |
|---|---|
| **CalDAV two-way sync** | An entire protocol + auth + sync-token track. The milestone names *interop*, not *federation* — and §3 D2 keeps the door open by never losing an unknown property |
| **Attendees / invitations / RSVP** | ⚠⚠ **Structurally blocked, not deferred**: stock calendars invite by *email address*, and Tenshu has no email plane. See §2.3 — the Tenshu-shaped answer is different, not smaller |
| **Account calendars** (Google/Exchange/iCloud sign-in) | Same track as CalDAV; also the exact thing users are leaving |
| **Free/busy, scheduling assistant, room booking** | Organisational features; not the daily loop for an individual |
| **Auto-extraction from mail** (flights, bookings) | Needs an inbox we do not have |
| **Weather in the calendar, travel-time alerts, "time to leave"** | ⭐ Genuinely *cheaper here than anywhere else* later — Timeline already has movement + places — but not v1 |
| **Tasks / reminders as a separate entity type** | A different app (stock Android splits them too). ⚠ Do not let it leak into the event schema |
| **Attachments on events** | ⭐ Should ride Drive rather than being rebuilt; naturally a later slice |

⭐ **Revisited 2026-10-13 (maintainer):** `.ics` files and URL feeds turned out not to be a migration
story most people will complete, so the *account calendars* row is now a planned **one-way** track:
OS calendar store first (which on Android already includes Google), a direct Google sign-in where
there is no OS store, Microsoft later. Design in
[`calendar-external-sources.md`](calendar-external-sources.md). **Two-way CalDAV stays out.**

### 2.3 The interesting scope decision: sharing without email

Stock calendars have exactly two sharing gestures, and Tenshu's position on them is *inverted*:

- **"Invite bob@example.com to this event"** — impossible without email/iMIP. Out, and honestly
  out; it is not a v2 either without an email or messaging plane.
- **"Share this calendar with a person"** — ⭐ **nearly free**, and better than the stock version.
  The contact handshake (CI-1–4) and shared-realm membership grant (GR-1–7) are *both already
  shipped*. A shared calendar is a shared realm containing `Event` rows; Drive already does
  exactly this shape for folders. It is not v1 (v1 should be a personal calendar that works), but
  it is the natural v2 and it costs a fraction of what CalDAV would.

⚠ Worth saying plainly in any user-facing copy: Tenshu Calendar shares with *people you have
paired with*, not with *email addresses*. That is a real product difference, not a gap to
apologise for — but it must be stated, because "share" means the email thing everywhere else.

### 2.4 Subscribe-to-URL (`webcal:` / public `.ics`) — the one borderline call

Holiday calendars, sports fixtures, school terms. Read-only, high perceived value, and it is how
most people get their *second* calendar. Technically it needs proxy-mediated network egress,
for which there is now a precedent — `FetchPlaceImage` on `:map-region-service`
([`place-imagery.md`](place-imagery.md)'s P3 pattern), where Control does the fetch and the app
stays INTERNET-free.

⚠ But it carries a privacy note that the rest of the app does not: fetching a third-party URL
discloses the user's IP and their interest in that calendar **to that third party**, on a
schedule. It is not an HR#1 problem (our relay never sees it), but it is the first time Calendar
reaches outside the realm, and it needs the same consent framing as
`PlaceGeocodingControl`. **Recommendation: in scope, but as its own phase (CA-7), behind an
explicit per-subscription opt-in**, never in the v1 core.

---

## 3. Data shape — the milestone's named design question

The milestone asks: *Tenshu-native schema with iCal as interop, or store raw iCal blobs?* It also
states the preference. That preference is correct, and there is now a **precedent that settles
it** rather than merely arguing it.

### D1 — Native schema, with a lossless source sidecar (the `ContactSource` shape)

`:contact-service` already faced this exact fork and resolved it: **`Person` is deliberately not
vCard-shaped; the raw vCard 3.0 lives on a separate `ContactSource` record**
([`contact-service-identity-model.md`](contact-service-identity-model.md)). Calendar should copy
it verbatim:

- **`Event`** — a native composite. Queryable through the chain DSL, mergeable field-by-field,
  covered by the schema-evolution machinery, readable by Mia's query layer.
- **`EventSource`** — the raw `VEVENT` text an event was imported from, kept **losslessly** and
  untouched.

⭐ The sidecar is what buys back everything the "raw blobs" option was for. Round-tripping an
imported calendar preserves the exotic properties we do not model (`X-` extensions, `ATTACH`,
`ORGANIZER`, alarms we do not support) — so an export is not lossy, and a future CalDAV phase
is not blocked by v1 having thrown information away. It costs one composite and a merge rule
("native fields win; unknown properties survive").

⚠ A raw-blob-only store fails three ways here specifically: the chain DSL cannot filter it
(so no queries, no Mia, no widget without full deserialisation), positional composites give us no
per-field merge (so two devices editing different fields of one event conflict at the blob level),
and every read pays a parse.

### D2 — Recurrence: store the rule, expand on read. Never materialise occurrences.

- Store a **master `Event`** carrying its recurrence rule + a set of **override rows** keyed by
  recurrence-id (a modified single occurrence) and **exception dates** (a deleted one). This is
  the RFC 5545 model, and it is the right one — it is what makes "edit this occurrence" and
  "edit all future" representable at all.
- ⚠⚠ **Do not store expanded occurrences as rows.** "Every Monday, forever" is unbounded; a
  materialising store either invents a horizon (and silently stops showing events past it) or
  writes thousands of sync rows per event. Both failure modes are the kind users never report
  properly — they just stop trusting the app.
- **Expansion is a pure function** `(rule, window) → List<Occurrence>` living in
  `:calendar-service-common`, exhaustively unit-testable, with no clock and no device timezone as
  an implicit input. ⭐ The routines engine already learned this lesson the hard way and encoded
  it in the type system: `FiringCalculator.nextFiring(trigger, locale, after)` takes a
  `RoutineLocale` explicitly, with a KDoc calling it "RE-D3's sharpest trap — a laptop travelling
  in another timezone computing a different instant". **Calendar has the identical trap and
  should copy the identical remedy.**
- ⚠ There is **no RRULE or iCal library anywhere in the repo** (verified) and Kotlin
  Multiplatform has no obvious one. We write the expander. Scoped to the common subset
  (`FREQ` daily/weekly/monthly/yearly, `INTERVAL`, `BYDAY`, `BYMONTHDAY`, `COUNT`, `UNTIL`,
  `WKST`) this is a well-bounded, well-specified, highly testable ~400–600 LOC job — the single
  most test-dense component in the app, and the one to write first.
- **Open:** whether the canonical stored form is the RRULE *string* (stable wire shape forever,
  opaque to queries — but nobody queries by rule) or a structured composite (introspectable,
  but every RRULE feature we later add is a schema reshape under HR#9). **Recommendation: the
  string**, parsed by the expander, with the structured form as an in-memory type only. Q2.

### D3 — Time model: a local time in a zone, plus a *date* type we do not have

⚠⚠ This is the highest-risk part of the schema and it is easy to get wrong in a way that only
shows up in April.

Three distinct kinds of "when", which stock calendars all support and which collapse into
nonsense if modelled as one `timestamp`:

1. **Zoned** — "09:00 Europe/Berlin". Canonical form is `(LocalDateTime, tzid)`. It stays 09:00
   Berlin if you fly to Tokyo, and its *instant* changes at a DST boundary and can change again
   when the IANA tz database updates.
2. **Floating** — "09:00 wherever I am". No zone. Rare but real (a daily habit).
3. **All-day / multi-day** — a **date range**, not an instant range. "1 Aug" is not
   "1 Aug 00:00 UTC"; rendering it as an instant is exactly how all-day events drift a day for
   users west of Greenwich.

⚠ The schema DSL's `timestamp()` field is a `UnixInstant`, which is the *wrong* canonical type for
all three. The stored shape needs local-date-time + tzid components (string/int fields), with any
instant treated as a **derived read-model value, never stored as truth** — because a stored
instant silently becomes wrong when the tz database changes, and nothing will tell us.

⚠ The corollary is a real cost: **sorting and windowing by "when" is no longer a plain field
sort.** The predicate-based `filter`/`search` chain ops are interpreted per row, so a window
query is a scan — fine at calendar scale (thousands of rows, not the 107k-row Drive case), and
the `sortBy(key).take(n)` fusion (`core-ordered-window-fusion.md` F1) already covers the "next N
events" read the widget and Mia both want. But it should be a deliberate decision, not a
discovery. Q3.

### D4 — Calendars are rows, not realms (in v1)

A `Calendar` composite (name, colour, visibility) with `Event.calendarId` pointing at it. Cheap,
and it keeps §2.3's v2 open: sharing a calendar later means moving its rows into a shared realm,
which is a migration of the reference, not of the model — and `DataIdMigrator`
(`file-content-addressing.md` Phase 2) is precisely the generic primitive for that, already
built.

### D5 — House-rule bookkeeping

A new service starts at `service(id, version = 1)` with no migration chain, and — per **HR#9 as
amended by E2j-2** — a service-owned type **must not** bump `TENSHU_STORAGE_FORMAT_VERSION`
(that constant is `:core`'s alone, and moving it wipes every store). `SchemaShapeBaseline` will
need regenerating once the composites land. No relay redeploy is owed by any of this
(E2e — the relay gates on `RELAY_PROTOCOL_VERSION` and never reads the payload version).

---

## 4. Reminders — what we reuse, and the four gaps

The premise in the brief is right, with one correction: **the machinery is not the Hub Service's.**
It lives in `:sync-service-proxy` and is platform-generic; the routines engine (RE-D5) is what
*caused* it to exist, and the hub is merely the platform where it works best.

### 4.1 What is already built and directly reusable

- **`ScheduleItem` + `ScheduleService`** — a persisted registry of scheduled work, drained by two
  drivers against one store.
- **The `punctual` class** — `PlatformScheduler.register(item, punctual: Boolean)`, derived from a
  `Punctual` op trait (`core/.../trait/Punctual.kt`), read at registration in
  `ScheduleImplementation`. ⚠ Its op must also carry `HeadlessSafe` or registration fails
  closed — a confusing runtime refusal if discovered late.
- **Android's fork** — `WorkManagerScheduler` sends punctual one-shots to `PunctualAlarmScheduler`
  (`AlarmManager.setExactAndAllowWhileIdle` when `SCHEDULE_EXACT_ALARM` is granted, else
  `setAndAllowWhileIdle`), because "WorkManager batches by design… for 'at sunset' it is the
  wrong tool". ⭐ This is exactly a reminder's requirement, already solved, already commented.
- **The catch-up discipline (RE-D6)** — fire if late by ≤ 15 min, else record `Missed` rather
  than fire at 3 a.m. Calendar wants the same three-way answer with different constants (a
  reminder 40 minutes late is worse than useless; a *missed* marker in the UI is honest).
- **Dismissal as a synced row** — `:notification-service` already has `DismissedRequest` /
  `DismissPendingRequest`. Dismissing a reminder on the phone clearing it on the desktop is that
  pattern, not new work.

### 4.2 ⚠⚠ What must NOT be reused

The routines engine's `ExecutorClaim` + `PresenceResolver` exist to make a routine fire
**exactly once across the household** — one device wins a claim, ordered by capability tier
(hub > resident desktop > phone). That is correct for "turn on the porch light" and **exactly
wrong for a reminder**, which must fire on *every* device the user is carrying. Reusing the
election here would produce the worst possible bug class: reminders that fire on the laptop
in the office while the phone in your pocket stays silent, non-deterministically.

Take the `punctual` scheduling class and the `FiringCalculator` *discipline*. Leave the election.

### 4.3 The three gaps Calendar forces open

**Gap 1 — desktop and iOS have no punctual driver.** `NoOpPlatformScheduler` is bound on desktop,
iOS and the hub, deliberately: those hosts are "already resident while scheduling matters", so
the in-process `ScheduleDrainLoop` drains the registry. For a reminder that means:

- *Desktop*: ⚠⚠ **the residency premise holds; the mechanism is still wrong.** v0.2 of this doc
  said reminders "fire only while Control is running" and implied that was the problem — that
  was **incorrect and is corrected here**: `control-desktop` installs a **LaunchAgent** and
  autostarts hidden at login, explicitly on the OneDrive/Dropbox model
  (`control-desktop/.../Main.kt`, `LaunchAgentManager.forDaemon()`). Control *is* resident.

  The real defect is that `ScheduleDrainLoop` is a **relative-delay mechanism, not an alarm**.
  `tick()` does `accumulatedMs[key] += tickIntervalMs` — it adds the *nominal* tick, never
  consulting a clock. Two consequences, and the second is worse than the first:
  - ⚠ **Suspend does not count.** `delay()` does not run while the machine sleeps, so a
    reminder armed for 09:00 with the lid shut from 23:00 fires late by the whole sleep
    duration.
  - ⚠⚠ **`accumulatedMs` and `firstFired` are in-memory maps.** A *pending* one-shot's progress
    is not persisted, so any Control restart — logout, app update, crash — resets it to zero and
    it re-waits its **full** `initialDelayMs` from scratch. Arm a reminder six hours out, restart
    at hour five, and it fires six hours after the restart.

  ⚖️ This is deliberate, not a bug in the loop: its KDoc explains that tick-accumulation exists
  so tests run under `kotlinx-coroutines-test` virtual time, where "a monotonic/epoch clock would
  not advance with `delay`". It is correct for the offline keeper (periodic maintenance, drift
  harmless) and **exactly the category error WorkManager makes on Android** — which is why
  `PunctualAlarmScheduler` exists there.

  ⭐⭐ **v0.4 supersedes v0.3's answer here — macOS has an idiomatic mechanism and the repo is
  already half-way into it.** v0.3 proposed hand-rolling a desktop punctual path (`dueAtEpochMs`
  on the persisted item + an injectable clock). That is no longer the plan for *reminders*; see
  §4.6, which also settles what the hand-roll is still for.
- *iOS*: ⚠⚠ **the model does not transfer at all.** There is no reliable "wake me at 09:00" on
  iOS — `BGTaskScheduler` is best-effort and `ios-drive-parity.md` P6a already found live wake to
  be hardware-gated. **The iOS answer is a different mechanism entirely: pre-arm
  `UNUserNotificationCenter` local notifications** for upcoming occurrences (the OS holds the
  schedule, the app need not wake), re-armed whenever events change or the app foregrounds.
  ⚠ It has a hard cap (64 pending notifications per app) which the expansion window must respect
  — a real constraint on the design, not an implementation detail.

  ⭐ This fork is worth stating as a principle: **on Android we wake and then decide; on Apple
  platforms we decide in advance and let the OS hold it.** Two mechanisms, one `Reminder` model
  above them. ⚠ v0.2 said "on iOS"; **§4.6 widened it to macOS**, where the same
  `UNUserNotificationCenter` API applies and is already in use — so this is one design across
  both Apple targets, not an iOS special case.

**Gap 2 — there is no OS-notification presenter seam.** `:notification-service` is a store of
*synced in-app rows* (`Notification` is a `Syncable` composite; `SendNotification` writes one).
Everything that posts a real system notification today hand-rolls it against
`NotificationCompat` — `AndroidPendingRequestNotifier` in Control, the Timeline capture
foreground service, `TenshuOperationWorker`. **Nothing exists that an app can call to say "show
this to the user, now, on this device's OS".**

Calendar cannot ship without it, so Calendar is the app that pays for it. ⭐ It is a genuinely
reusable asset once built — Routines wants it (a routine that fired), Fit wants it, Drive wants
it for share notifications — so it should be scoped as a **platform slice, not a Calendar
internal**. Roughly: a `NotificationPresenter` expect/actual (Android `NotificationCompat` +
channels, iOS `UNUserNotificationCenter`, desktop tray/native toast), plus tap-to-open routing.

⭐ **v0.4 softens this: "a blank page" overstated it on macOS.** Control's tray agent already
runs a real `UNUserNotificationCenter` path — authorization, stable ids, add/remove reconcile
(§4.6). What is missing is the *seam*, not the mechanism, so the desktop actual is a
generalisation of working Swift. ⚠ Android and iOS are still genuinely unbuilt.

⚠ And it carries the first threat-model question Calendar raises: **a reminder puts event text on
a lock screen.** That is a deliberate plaintext disclosure to whoever can see the device — the
same class as `A5`'s transient decrypted previews. It needs a per-calendar or global "hide
details on the lock screen" control, and an amendment noting it (HR#2).

**Gap 3 — snooze.** Not built anywhere, and users expect it. It is a reschedule of a `punctual`
item plus a synced dismissal row; small, but it is *not* free and it is not optional.

### 4.4 ⭐ Gap 4 — the exact-alarm permission is in the wrong process

`PunctualAlarmScheduler`'s KDoc is explicit:

> ⚠⚠ `USE_EXACT_ALARM` is deliberately NOT declared. It grants exactness without asking, but Play
> policy restricts it to apps whose *core* function is alarms or calendars; claiming it for a
> smart-home app is a review risk…

**Calendar is that app** — it is precisely the category Play carves the permission out for. But
the alarm is scheduled by **Control**, which owns the proxy and therefore `AlarmManager`, and
Control is a sync/permissions app, not a calendar. So:

- Declaring `USE_EXACT_ALARM` in Control to serve Calendar is the review risk the KDoc warns
  about, only worse — the claim would be made by the app that *cannot* justify it.
- Declaring it in `:calendar-android` means the calendar app schedules its own alarms, which
  breaks the "Control is the proxy" architecture for one feature.
- The existing path — Control requests `SCHEDULE_EXACT_ALARM` from the user, contextually — works
  today and needs no policy argument, at the cost of a permission prompt and a settings round
  trip on Android 12+.

**Recommendation: keep `SCHEDULE_EXACT_ALARM`, asked contextually at the moment the user sets
their first reminder**, and revisit only if the prompt measurably hurts. But this is a
product/policy call with a Play-review dimension, not an engineering one. **Q6.**

### 4.5 ⭐ What is genuinely free

Multi-device sync of events, calendars and reminder settings: **zero Calendar-side work**. Mark
the composites `Syncable`, pick a merge strategy, and the existing machinery carries them —
including the offline case, the conflict case, and the new-device case. This is the single
biggest reason a calendar is cheap *here* and expensive elsewhere, and it is worth stating in
any user-facing pitch: the calendar syncs across your devices and **nobody can read it**, which
no free-tier calendar can say.

---

### 4.6 ⭐⭐ macOS: the idiomatic mechanisms, and one of them is already running

Added v0.4 (2026-08-05), after asking whether macOS has an official way to do this. It does —
**two**, and they split along exactly the line §4.3 already drew for iOS. ⚠ First, a scoping
fact that makes this simpler than expected: `control-desktop` builds
`targetFormats(TargetFormat.Dmg, TargetFormat.Pkg)` only. **The desktop answer *is* the macOS
answer** — there is no Windows/Linux packaging to fork for.

**(a) For the notification — `UNUserNotificationCenter` + `UNCalendarNotificationTrigger`.**
A reminder *is* a notification, so the thing to schedule is the notification, not a wake-up.

⭐⭐ **This is already running in the repo.** `control-desktop/macos/TrayAgent/PendingRequestNotifier.swift`
imports `UserNotifications`, sets a delegate, calls `requestAuthorization`, and posts via
`UNUserNotificationCenter.current().add(request)` — today with `trigger: nil`, i.e. immediate.
A scheduled reminder is **the same call with a trigger**. Everything around it already exists:

- ⭐ The **bundle-identity trap is already known and guarded**: the file's own comment explains
  that `UNUserNotificationCenter` needs a bundle identifier, which a bare `swiftc` binary lacks,
  so `notificationsAvailable = Bundle.main.bundleIdentifier != nil` no-ops the dev binary and
  the path "lights up in the signed, packaged `.app`". That is precisely the caveat this
  proposal would otherwise have had to discover the hard way.
- ⭐ The **reconcile shape is already the right one.** `reconcile(_ current:)` diffs a current
  set against a known set, posting the newly-arrived and removing the resolved by stable id.
  "Here are the next N reminders" is exactly that diff — and re-arming after an event edit is
  the same operation, not a new one.
- The daemon↔tray channel exists (`BridgeClient.swift`), and the tray runs on its own
  `KeepAlive=true` LaunchAgent, so it is resident independently of the Control daemon.

⭐ **What this buys over the v0.3 hand-roll, on every axis:** the OS holds the schedule, so the
reminder fires even if Control is not running at all; sleep and restart are the OS's problem,
not ours; no injectable clock, no persisted deadline, no accumulator; and it lands in
Notification Center natively, with grouping and actions for free.

⭐⭐ **So §4.3's principle generalises**: not "Android wakes and decides; *iOS* decides in
advance", but **"Android wakes; *Apple platforms* pre-arm"** — one design across iOS and macOS,
with the macOS half already built to the point where only the trigger is missing.

⚠ Two caveats, both honest: the pending-request cap is documented at 64 on iOS and should be
**assumed to apply on macOS until checked** — the expansion window must respect it either way,
so nothing changes structurally. And cancellation becomes a real duty: if the OS holds the
schedule, editing or deleting an event **must** reach the OS to re-arm, which the `reconcile`
diff covers but which nothing will remind us about at compile time.

**(b) For punctual *code execution* — launchd `StartCalendarInterval`.**
⚠⚠ **`UNUserNotificationCenter` schedules a notification, not code.** It is the right answer for
reminders and the wrong one for the generic `PlatformScheduler` punctual class, where something
must actually *run* at an instant (a routine's `EvaluateDueRoutines`, say).

For that, launchd's `StartCalendarInterval` is the idiomatic mechanism, and it is documented to
do the two things the drain loop cannot: it **coalesces missed intervals and runs the job at the
next wake** rather than skipping them the way `cron` does, and the schedule is owned by launchd,
so it survives a restart by construction. ⭐ `LaunchAgentManager` already generates plists
(`RunAtLoad`, `KeepAlive`) and shells out to `launchctl load`/`unload`, so this is an extension
of working code, not new machinery. ⚠ It is calendar-*interval*-shaped rather than
fire-once-at-instant, so a one-shot needs the job to unload itself after firing — manageable,
but it is why (a) is the better fit for reminders specifically.

**⇒ What this means for Calendar.** CA-5's desktop half is **(a)**, extending a Swift file that
already works, rather than the v0.3 hand-roll. ⚠ The `dueAtEpochMs`-on-the-item change is **not
discarded** — it remains the right fix for the generic in-process punctual class on any host with
no OS mechanism (and is strictly better than the tick accumulator regardless), but it stops being
Calendar's problem and stops gating desktop shipping alongside Android.

## 5. UI — how much do we actually write?

Short answer: **the event editor is mostly free; the calendar *views* are entirely ours; and the
grid gets written twice** because no Compose UI ships on iOS at all.

### 5.1 What the existing stacks give us for free

**Compose (Android + desktop), CMP 1.11.1 with Material 3 1.9.0:**

| Need | Available? |
|---|---|
| Date picking | ⭐ `DatePicker` / `DatePickerDialog` / `rememberDatePickerState` — **already used in `:fit-shared-ui`**, so it is proven on this stack |
| Time picking | ⭐ `TimePicker` — same package |
| Date *range* picking | `DateRangePicker` — covers multi-day/all-day |
| Adaptive phone/tablet/desktop layout | `AdaptiveNavScaffold` + `WindowWidthSizeClass` + `ContentWidth` in `:ui-core` |
| Search field, text fields, keyboard shortcuts | `AppSearchBar`, `AppTextField`, `AppKeyboardShortcuts` in `:ui-core` |
| Theme, chrome, navigation, feedback | `:ui-core` — Drive's chrome, which Contacts Ph10 already adopted |
| Agenda list with sticky day headers | Plain `LazyColumn`; Timeline's `homeDays()` feed is the working precedent |

⇒ **The create/edit-event screen is close to free** — pickers, fields, chips, dialogs, all
stock. That is the highest-traffic screen in the app and it is largely assembly.

**SwiftUI (iOS):** `DatePicker` (wheel/compact/graphical) covers date + time entry natively; the
list and detail screens are ordinary SwiftUI, and four first-party iOS shells already exist to
copy from (`contacts-ios` 758 LOC, `maps-ios` 1.3 k, `timeline-ios` 2.1 k, `fit-ios` 3.2 k).

### 5.2 ⚠⚠ What has no component anywhere, and must be built

1. **Month grid with per-day event indicators.** A 6×7 grid, paged by month, with dots/chips per
   day and a selected-day detail. No M3 component. Moderate: `HorizontalPager` + grid + the
   fiddly bits (leading/trailing days from adjacent months, week-start honouring locale, today
   marker). ~400–600 LOC Compose.
   - ⭐ **iOS has a real shortcut**: UIKit's `UICalendarView` (iOS 16+) is an actual month grid
     with per-date decorations, wrappable in `UIViewRepresentable` (~120 LOC). ✅ **Available —
     verified** (Q7): all eight iOS shells pin `deploymentTarget: iOS "16.0"` in their
     `project.yml`, so no bump is needed and this can be counted on.
2. **Week / day time-grid.** ⚠⚠ **The expensive one, and the reason a calendar is not a CRUD
   app.** Hour rulers, a scrolling time axis, all-day header band, the current-time line,
   tap-empty-space-to-create, drag-to-resize — and above all **overlapping-event layout**, which
   is a real algorithm (interval graph → column assignment → width/offset per event). ~800–1200
   LOC Compose, plus the same again in SwiftUI if done naively.
   - ⭐⭐ **The mitigation that halves this.** The overlap layout is **pure math over
     `(start, end)` pairs → `(column, columnCount)`** — no Compose, no SwiftUI, no platform. It
     belongs in Compose-free **`:calendar-shared-logic`**, written once, unit-tested exhaustively,
     and consumed by both renderers, which are then reduced to "draw a rectangle at the position
     you were given". This is precisely the `:basemap-core` **D7** finding from
     `ios-fit-timeline-maps-parity.md` — a 598-line file with *zero* `@Composable` in it was
     never UI, and splitting it let iOS ship natively without pulling Compose in. Same shape,
     same win, and it is the single most important structural decision in the UI layer.
3. **Widget.** Glance 1.1.1 is already in the version catalogue and `mia-android` ships a 60-line
   Glance widget — so the plumbing (manifest, receiver, provider info XML, binding into the
   proxy) has a working precedent. An agenda widget: ~200–300 LOC.

### 5.3 ⚠ Why the grid is written twice, and why that is right

`ios-fit-timeline-maps-parity.md` **B-1** established it: the `TenshuSdk` umbrella exports only
Compose-free modules, and **no CMP UI ships on iOS at all** — pulling `:ui-map` in would have
dragged Compose + Skia into every iOS app. The resolution (D7) was to split the module, not the
framework. Calendar inherits that verdict: **shared VMs + shared layout math in Kotlin; two
renderers** (Compose for Android/desktop, SwiftUI for iOS).

⭐ Given §5.2's split this is a smaller penalty than it sounds — the duplicated part is drawing,
not deciding — and it is what buys native scroll physics, native pickers and native
accessibility on iOS. It is also House Rule #5's shape, one platform over.

### 5.4 Size estimate

Grounded in measured siblings (`wc -l`, tests excluded): **Contacts** 6.6 k total
(3.1 k shared-ui + 2.1 k shared-logic + 0.7 k shared-data + 0.8 k iOS), **Maps** 5.1 k,
**Timeline** 19 k (dominated by 10 k of ingestion in `-shared-data`).

| Module | Estimate | Notes |
|---|---|---|
| `:calendar-service` (+ `-common`/`-client`/`-proxy`) | 2 500–3 500 | ⭐ RRULE expander + its tests dominate |
| `:calendar-shared-logic` | 1 500–2 000 | VMs, repositories, **the overlap-layout math**, ICS parse/serialise |
| `:calendar-shared-ui` (Compose) | 3 000–4 000 | Month + week/day grids are ~half of it |
| `:calendar-android` / `-desktop` | 300 + 300 | Shell + Glance widget |
| `:calendar-ios` (SwiftUI) | 1 500–2 500 | Lower if `UICalendarView` lands the month view |
| **Total** | **~9 000–12 000** | **≈1.5× Contacts**, ≈2× Maps |

Plus two **platform** slices that are not Calendar code but which Calendar forces:
the notification presenter (§4.3 Gap 2) and the iOS pre-armed-reminder path (§4.3 Gap 1).

---

## 6. Mia — capturing an event by utterance

The driving second use case (§1): a spoken or typed *"add lunch with Anna next Tuesday at one"*
becomes a real event. This is a **write** path into first-party realm data, which is a different
thing from the read integration Calendar would otherwise be, and it is worth being precise about
because it is the first one of its kind.

### 6.1 ⭐ What is already decided for us, and it is good news

[`mia-query-layer.md`](mia-query-layer.md) (Draft v0.1, no code) has settled the hard parts:

- **MQ-D2 puts the query layer proxy-side** (`:mia-service` + `-common`/`-client`/`-proxy`),
  explicitly "because only the proxy can read across every service". ⭐⭐ **So Mia → Calendar
  needs no cross-app invocation primitive.** The app-to-app gap that `contacts-app.md` Q1 traced
  to `tenshu-rei.md` Q12/Q16 and found deferred to v2 — the same primitive
  `cross-app-file-access.md` D-A/D-B wants — **does not block this**. Mia reaches Calendar the
  way it reaches file-service: through the proxy, over ordinary ops.
- **MQ-D3 lowers plans to ordinary chain-DSL callstacks**, so Calendar earns its integration
  simply by having a native schema and ordinary ops. There is no Mia-facing adapter to build.
- **MQ-D7 already covers mutating plans**: they render as a confirmation cell showing exactly
  what will happen and run only on tap. `CreateEvent` is precisely that class, and the rule
  exists before we need it.

⇒ On the Calendar side, integration cost is **≈ zero beyond doing §3 D1 and CA-1 properly**.
The work is all on the Mia side, and most of it is work MQ has already scoped.

### 6.2 ⚠⚠ Four things Calendar forces open in the Mia design

**(1) The intent set is closed, and has no calendar entry.** MQ-D4's table is `FindFiles` /
`FindPlaces` / `RecallMoment` / `QueryFitness` / `DeviceState` / `DeviceAction`. Calendar adds at
least `QueryCalendar` (read) and `CreateEvent` (write). Extending the table *is* the intended
mechanism — but it should land as an explicit amendment to `mia-query-layer.md`, not as a
Calendar-side fait accompli, because the table is also the permission surface and the "what can
Mia do" copy.

✅ **Done (Q11 resolved, 2026-08-05):** `mia-query-layer.md` is at **v0.2** with **MQ-D4a**
carrying both intents, and the three amendments below landed in the same pass — MQ-D6's write
tier, MQ-D1's read-property caveat, MQ-D7's resolved-artifact rule, plus the MQ-2/MQ-3 phasing
hooks and a new threat-model duty. ⚠ The items below are retained as the *rationale* for what
that amendment says; `mia-query-layer.md` is now the source of truth for the Mia side, and this
section should not diverge from it.

**(2) ⚠⚠ `CreateEvent` is the intent most exposed to MQ-D4's own warning.** That doc already
flags time expressions as "the fiddly part, and pure code, not ML", and — more expensively —
as **localisation-bearing** in a way string resources are not: an intent grammar is *per
language*, "a real cost to size before promising Mia in a second locale". A calendar-recording
intent is **almost entirely** time expressions: *tomorrow at 3*, *next Tuesday*, *in an hour*,
*every second Monday*, *the 15th*, *this weekend*. Calendar is therefore the intent that pays
MQ-D4's flagged cost **in full**, and it pays it first.

⭐ The counterpart is the best structural news in this document. §3 D2 and §3 D3 already force a
rigorous `(LocalDateTime, tzid)` + recurrence-rule model into `:calendar-service-common`,
complete with an expander. **So the NL grammar has a typed target that already exists** — it
resolves an utterance to *types*, not to strings, which is exactly the shape an intent grammar
needs and exactly what stops "next Tuesday" being re-interpreted differently in three places.
And *"every Tuesday at 9"* is **the** recurrence use case, so the expander earns its keep twice.

⚖️ **Seam recommendation: the grammar lives in `:mia-service`; the types it produces live in
`:calendar-service-common`.** The grammar is per-language and Mia's; the temporal model is the
calendar's. Neither module learns the other's job, and a second locale costs grammar only.

**(3) ⚠ MQ-D6 is read-scoped and needs a write dimension.** It is titled "Capability-scoped
**read** access from day one", and its revoke copy is *"Mia may read Timeline: off"*. Creating an
event is the first time Mia **authors a row** in first-party realm data — `DeviceAction` commands
a device, it does not write user data. *"Mia may read Calendar"* and *"Mia may add events to
Calendar"* are two different grants and a user will reasonably want the first without the second.
The finer grant should exist **before** the write intent ships, not after. ⭐ This is MQ-D6's own
argument applied one level further: it exists so that Skills arrive into a permission model that
already works, and the first-party write case is the cheapest place to discover that the model
needs a verb as well as a noun.

**(4) ⚠ MQ-D1's "cannot hallucinate" property is a *read* property.** "Mia's answers are
citations, never prose" holds because Mia can only show you rows you already have. A write intent
breaks the symmetry: a misheard slot does not produce a wrong *answer* you can ignore — it
produces a wrong *row*, which persists, syncs to every device, and **fires a reminder at 07:00
three weeks later**. MQ-D7's confirmation cell is the mitigation and for calendar writes it must
be **non-negotiable**, especially in a voice turn, where MQ-D7 already notes there is no hover
and no undo.

⭐ Mitigating factor worth stating plainly, because it makes this a *good* first write intent
rather than a scary one: **an event is reversible.** A wrong one is annoying, not destructive —
unlike deleting a file or unlocking a door. That is precisely why creating events is the right
place to exercise the write path. ⚠ The converse: **`DeleteEvent` should not be an intent in
v1.** Deletion by utterance has all of the risk and none of the "I would have typed it anyway"
payoff.

### 6.3 Sequencing — the two tracks are genuinely independent

- **Calendar's hook is `CA-1`** (ops + repository), not `CA-8`. Once ordinary CRUD ops exist over
  a native schema, Calendar *is* a Mia source. Nothing in the UI track (CA-2…CA-4) gates it.
- **Nothing about Calendar is gated on Mia.** `:mia-service` does not exist — MQ is a draft with
  no code, and Mia's modules "do not depend on `core-client` at all", so the app cannot read a
  byte of realm data today. Calendar should not wait for it.
- The tracks meet at a service boundary: **read slots into MQ-2** ("widen the sources"),
  **create into MQ-3** ("actions"). Both need MQ-1 to exist first, and both need CA-1.

⚠ Practical consequence for **Q8** (which platform first): **Android**, and now for a second
reason. Mia's assistant integration is Android-only today — `MiaVoiceInteractionService`,
`MiaRecognitionService`, `AndroidAssistantRoleLauncher` and the Glance widget all live in
`:mia-android` — so the utterance surface, the working punctual driver and the widget are all on
the same platform.

### 6.4 ⚠ The one requirement this adds to Calendar itself

**The ops must be complete headlessly.** A UI-only calendar can let the editor screen resolve
what the user did not say — which calendar, how long, whether to remind. An op invoked by an
assistant has no editor, so the *service* must own those defaults: a **default calendar**, a
**default duration** for an event given only a start, and a **default reminder policy**. These
are ordinary `:preference-service` rows (`RealmSetting`, the shipped Background-Sync precedent),
but they must be designed in at **CA-1**, not discovered at MQ-3 — retrofitting a default into an
op that already shipped means changing its behaviour for existing callers.

⚖️ Recommendation, deliberately conservative: **Mia fills only the slots it heard.** Everything
else comes from the defaults, and the confirmation cell shows the *resolved* event — the actual
calendar, the actual end time, the actual reminder — never the partial utterance. The user
confirms what will exist, not what was understood.

---

## 7. Dependencies

**Needs to exist / be built:**

- `:calendar-service` + `-common`/`-client`/`-proxy` — the standard four-module split.
- ⚠ A **notification presenter seam** (§4.3 Gap 2) — platform work, reusable, not Calendar's.
- ⚠ An **iOS pre-armed local-notification path** (§4.3 Gap 1) — platform work, reusable.
- An **RRULE expander** — no library exists; ours, pure, in `-common`.
- An **ICS parser/serialiser** — ours; bounded, and testable against real-world exports.

**Already there and load-bearing:** the `punctual` `ScheduleItem` class + Android
`PunctualAlarmScheduler`; `ScheduleDrainLoop` for resident hosts; the sync plane (free
multi-device); `:ui-core` chrome + M3 pickers; Glance (widget precedent); `DataIdMigrator` (the
v2 move-a-calendar-into-a-shared-realm path); proxy-mediated egress (`FetchPlaceImage` pattern,
for CA-7 subscriptions).

**Explicitly NOT depended on:** the Hub (a hub *improves* punctuality, it is not required —
`routines-engine.md`'s framing, "a hub buys punctuality", applies verbatim); Tenshu Rei; the
commerce plane; Photos; Timeline; ⭐ **`:mia-service`** — unbuilt, and the two tracks meet at a
service boundary (§6.3), so neither waits for the other; ⭐ **the app-to-app invocation
primitive** (`tenshu-rei.md` Q12/Q16), which §6.1 shows is not on this path at all.

**Gates nothing.** Consistent with the milestone's parallel-lane placement. ⚠ It does, however,
**unblock** two Mia slices (MQ-2's calendar source, MQ-3's first first-party write intent), which
is a reason to sequence it earlier rather than later.

---

## 8. Decisions — settled 2026-08-05

✅ **Q1–Q14 are all answered.** Maintainer session of 2026-08-05; Q11–Q13 were resolved earlier
the same day by the `mia-query-layer.md` v0.2 amendment. Nothing below is open.

| # | Question | **Decision** |
|---|---|---|
| **Q1** | Native schema + lossless `EventSource` sidecar, per §3 D1? | ✅ **Yes.** The `Person`/`ContactSource` precedent settles it, and §6.1 makes it load-bearing a second time — a blob store is unreadable to Mia's planner |
| **Q2** | Recurrence stored as an RRULE **string** or a structured composite? | ✅ **String.** Stable wire shape forever, and nothing queries *by rule* — you query expanded occurrences. The structured form is an in-memory type produced by the expander. ⚠ Consequence: adding RRULE features later costs expander code, never a schema reshape under HR#9 |
| **Q3** | Time model per §3 D3 — `(LocalDateTime, tzid)` canonical, instants derived, never stored as truth? | ✅ **Yes.** ⚠ Accepted with eyes open: windowing becomes a predicate scan rather than a field sort. Fine at calendar scale (thousands of rows, not Drive's 107k), and F1 fusion already covers the "next N events" read |
| **Q4** | Are reminders **per-device** (§4.2) — i.e. `ExecutorClaim` explicitly not reused? | ✅ **Yes, per-device.** ⚠⚠ To be stated in the reminder scheduler's own KDoc, not only here — the next person to see `ExecutorClaim` will reasonably assume it applies |
| **Q5** | Is the notification presenter scoped as a **platform** slice or a Calendar internal? | ✅ **Platform slice.** Routines, Fit and Drive all want it; Calendar merely forces it |
| **Q6** | ⭐ Exact alarms: `SCHEDULE_EXACT_ALARM` in Control vs `USE_EXACT_ALARM` in Calendar? | ✅ **`SCHEDULE_EXACT_ALARM` in Control**, asked contextually at the first reminder with copy saying why. Keeps "Control is the proxy" intact and makes no Play-policy claim we would have to defend. ⚠ `USE_EXACT_ALARM` stays undeclared **everywhere**, `PunctualAlarmScheduler`'s KDoc unchanged |
| **Q7** | Does `UICalendarView` (iOS 16+) meet our deployment target? | ✅ **Yes — verified**, not assumed: all eight iOS shells pin `deploymentTarget: iOS "16.0"` in their `project.yml`. No bump needed; the month view rides `UIViewRepresentable` |
| **Q8** | Which platform ships first? | ✅ **Android + desktop together**, the house default — they share `:calendar-shared-ui`. ⭐ **v0.4 removed the condition**: v0.3 made this contingent on hand-rolling a desktop punctual path, but §4.6 found macOS's idiomatic mechanism already running in Control's tray agent, so desktop's reminder half is an extension of working Swift rather than new scheduler machinery |
| **Q9** | Is ICS *subscription* (§2.4) in the roadmap, given it is the app's first outbound network reach? | ✅ **Yes, at CA-7, per-subscription opt-in.** Rides Control's proxy-mediated egress; each subscription opted into individually, with copy naming what the publisher learns (IP + refresh cadence) |
| **Q10** | Lock-screen event text (§4.3) — show details or hide them? | ✅ **Show details by default, with a per-calendar `sensitive` flag** that reduces its reminders to "Calendar reminder". Matches the stock behaviour users expect; honours the OS's own sensitive-notification setting where one exists. ⚠ Threat-model amendment lands with CA-5 either way |
| **Q11** | Do the calendar intents land as an amendment to `mia-query-layer.md`? | ✅ **Yes — done.** `MQ-D4a` in that doc's v0.2; the table is also the permission surface and the user-facing "what can Mia do" copy |
| **Q12** | Does MQ-D6 grow a **write** dimension before the create-event intent ships (§6.2 (3))? | ✅ **Yes — done.** A grant is `(service, read \| read + write)`, `read` the default, write opt-in asked at the first `CreateEvent`. ⚠ Still owes a maintainer's eye on the *copy* |
| **Q13** | Where does the natural-language time grammar live? | ✅ **`:mia-service`** (per-language, Mia's), resolving to types in `:calendar-service-common` (§6.2 (2)) |
| **Q14** | Are the headless defaults (default calendar / duration / reminder, §6.4) designed into **CA-1**? | ✅ **Yes.** Retrofitting a default changes behaviour for callers that already shipped |

⚠ **What these decisions add to the build beyond v0.2's estimate:** the per-calendar `sensitive`
flag (Q10, one field plus its presenter branch) and the desktop reminder path — which ⭐ **v0.4
made cheaper, not dearer**: §4.6 replaced v0.3's hand-rolled punctual scheduler with a trigger on
an already-working `UNUserNotificationCenter` call. Both land in CA-5; neither moves the §5.4
total meaningfully.

⚖️ **Still owed a human, and deliberately not decided here:** the MQ-D6 write-grant **copy**
(Q12), and the Q6 permission-request copy — both are one-shot prompts the user reads once and
which determine whether they say yes, so they want a person rather than a doc.

---

## 9. Proposed phasing

Each phase independently shippable; the order front-loads the parts that are hardest to change
later (the schema, the expander, the time model) and the part most likely to be wrong (reminders
actually firing on a real device).

- **CA-0 — schema + expander.** `:calendar-service` four-module split; `Calendar` / `Event` /
  `EventSource` composites; the RRULE expander and its test suite; the time model. ⭐ No UI.
  This is where the irreversible decisions live, and it is verifiable entirely by unit tests.
- **CA-1 — ops + repository.** CRUD ops, window queries, `:calendar-shared-logic` VMs. Still no UI.
  ⭐ **This is the Mia hook** (§6.3) — after CA-1 Calendar is a query-layer source and an action
  target, with nothing further owed from the UI track. ⚠ Includes §6.4's headless defaults
  (default calendar / duration / reminder as `:preference-service` rows), which must be designed
  in here rather than retrofitted.
- **CA-2 — agenda + month (Compose).** The cheap views, **Android + desktop together** (Q8) —
  one `:calendar-shared-ui` serves both. First usable app.
- **CA-3 — event editor.** Mostly M3 assembly; recurrence UI; this-vs-all-occurrences editing.
- **CA-4 — week/day grid.** ⚠⚠ The expensive slice. Layout math into `-shared-logic` **first**,
  then the Compose renderer over it.
- **CA-5 — reminders.** The notification presenter seam (Q5, a platform slice), the Android
  punctual path end to end with Control's contextual `SCHEDULE_EXACT_ALARM` request (Q6), snooze,
  dismissal-syncs, and the per-calendar `sensitive` flag (Q10). ⭐ **Desktop rides §4.6(a)** —
  a `UNCalendarNotificationTrigger` on the tray agent's existing `UNUserNotificationCenter` path,
  plus the `reconcile` diff extended to re-arm on event edits. ⚠ The one phase with a mandatory
  on-device pass (Doze, exact-alarm grant, reboot, and a lid-close/resume cycle on the desktop) —
  no test can prove an OS wake, exactly as `routines-engine.md` §RE-3 found. ⚠ Verify the
  64-pending cap on macOS here (§4.6).
- **CA-6 — ICS import/export.** The migration story; validate the sidecar round-trips.
- **CA-7 — widget** (Glance) **and ICS subscription** (proxy-mediated egress, opt-in).
- **CA-8 — iOS shell.** SwiftUI over the shared VMs + layout math; the pre-armed
  `UNUserNotificationCenter` reminder path; the month view over `UICalendarView` (Q7 ✅).
- **Later, not v1:** share-a-calendar-with-a-contact over shared realms (§2.3); attachments via
  Drive; travel-time alerts over Timeline.
- **On Mia's track, not Calendar's:** `QueryCalendar` at **MQ-2**, `CreateEvent` at **MQ-3** —
  both gated on MQ-1 existing, neither gating anything here (§6.3).

⭐ A reasonable "demo it" cut is **CA-0…CA-3** — a syncing, recurring, editable calendar on
Android and desktop with no reminders — and that is roughly half the total estimate. Reminders
(CA-5) are what make it *trusted*, and they are where the platform gaps are, so they should not
be deferred far past that.

---

## 10. Threat-model & house-rule notes

- **HR#1** intact — the relay stores ciphertext; calendars are ordinary realm data. ⚠ The one
  exception is CA-7's ICS subscription, which reaches a *third party* (not our relay) and needs
  its own consent surface and a `PlaceGeocodingControl`-style control.
- **HR#2** — two amendments are foreseeable and should land with their phases: **CA-5**, event
  text on a lock screen (a deliberate plaintext disclosure, the `A5` class) — ⚠ Q10 settled the
  default as *show*, so the amendment must state that plainly and name the per-calendar
  `sensitive` flag as the control; **CA-7**, outbound fetch disclosing IP + interest to a
  calendar publisher.
- **HR#9** — new service at `version = 1`; ⚠ do **not** bump `TENSHU_STORAGE_FORMAT_VERSION`
  for service-owned types (E2j-2); regenerate `SchemaShapeBaseline` when the composites land;
  no relay redeploy owed (E2e).
- **HR#3** — reminder scheduling is an `IoOperation` + `TaskInteractor`; ⚠ the punctual op must
  carry both `Punctual` and `HeadlessSafe` or registration fails closed.
- **HR#7** — the RRULE expander, the ICS round-trip and the overlap-layout math are all pure and
  must be test-covered before any UI consumes them. **HR#8** — every Android-touching phase runs
  `scripts/android-smoke.sh`.
- ⚠ **Mia's write path (§6) carries duties on `mia-query-layer.md`'s side, not Calendar's** —
  MQ §4 already owns Mia's threat-model duties. Two land with the calendar intents: the MQ-D6
  **write grant** (§6.2 (3)), and the note that an assistant-authored row is the first realm data
  Mia produces rather than cites (§6.2 (4)). Recording them here so whoever amends MQ finds them.

---

## 11. As built

Per-phase record of what landed and where it deviated from §§1–10. Written here rather than in
commit messages, per the repo's "decisions persist in docs" rule.

### CA-0 — schema + recurrence engine

Landed as designed: the four-module `:calendar-service` split, `Calendar` / `Event` /
`EventOverride` / `EventSource` composites, `RecurrenceRule` + `RecurrenceExpander` +
`OccurrenceResolver`, `EventTime`, `service(id, version = 1)`, no `TENSHU_STORAGE_FORMAT_VERSION`
bump (E2j-2).

⭐ **A mutation check found a real bug that 45 green tests missed.** Deleting the month-length
clamp changed nothing, which meant nothing exercised it: a bare `FREQ=MONTHLY` anchored on
31 January was emitting 28 February. RFC 5545 takes the day-of-month from `DTSTART` when
`BYMONTHDAY` is absent, so the correct output is *no* February occurrence at all. Fixed by
`matchesImplicitAnchorParts`, and `ImplicitAnchorPartsTest` now kills the mutation.

⚠ `Math.floorDiv` is JVM-only and compiled fine until the JS target saw it — the multiplatform
compile, not the test run, is what catches that class.

⚠ **The `js` target is gone (2026-08-12), and this paragraph is why it lasted.** It was the module's
only non-JVM canary when CA-1 declared it, and it earned its keep exactly once, here. It stopped
being load-bearing when the module gained `iosArm64`/`iosSimulatorArm64`: Kotlin/Native rejects
every `java.*` API too, so `Math.floorDiv` fails on iOS today with or without JS. Nothing else ever
consumed it — `:calendar-shared-ui` is android+desktop, `tenshu-sdk-ios` is iOS, no `web/` project
references Calendar, `:tenshu-sdk-js` does not export it, and `compileTestKotlinJs` was disabled
from birth so it was never even test-compiled. What it *did* cost was a `CalendarDateFormat` actual
written only to satisfy the compiler, and a block on `:core-paging` (no `paging-common-js`) — the
same wall Fit hit and cleared in `fit-app.md` §9.10a. ⚠ `:calendar-service*` keeps its JS target,
correctly: that is the protocol layer, which House Rule #5 *does* share to JS.

### CA-1 — operations + repository, and CA-1b

⚠ **A runtime failure the schema could not have caught:** `Calendar.create` passed a bare `Int`
colour into a `TInteger`, which is `Long`-backed — *"Value 4359668 is not a long"*. Latent since
CA-0, invisible until something actually wrote a row.

Also settled here:

- **`Calendar.visible` was mis-modelled and moved.** It is not a property of the calendar, it is
  a property of *this device's view* of it, so it became a **DEVICE-scoped**
  `CalendarPreferences.HiddenCalendars` preference. ⚠ The set stores the **hidden** ids, not the
  visible ones — polarity is load-bearing: a calendar that arrives from another device is visible
  by construction, with nothing to write.
- **`ensureDefaultCalendar()` uses a fixed id**, so two devices' first launches converge on one
  calendar rather than minting one each. Idempotent, because every launch calls it.
- **`CalendarDeviceIdentity` is a resolver, not a string** — Drive's `DeviceIdentity` incident:
  reading a blank id once strands every per-device preference for the whole session.

### CA-2 — month + agenda

`:calendar-shared-ui` (Android + desktop, Q8), `:calendar-android`, `:calendar-desktop`.
`MonthGrid` (pure grid math) lives in `-shared-logic` so the CA-8 SwiftUI view shares it.

⚠ `loaded` is tracked separately from `isLoading` — Drive's eternal-spinner bug
(`ios-drive-parity.md` P1a) was exactly this conflation, where an empty folder never stopped
loading.

⚠ Build-task trap worth knowing: `:calendar-shared-ui:compileDesktopKotlin` **succeeds** by
Gradle name abbreviation without compiling anything you meant. The real task is
`compileKotlinDesktop`.

### CA-2c — the desktop month grid, and a window that fits it

Opened by a maintainer's report that Calendar on macOS "does not scale to the window". It did
not, and the cause was structural: **CA-2 shipped a phone layout and Calendar never read
`LocalWindowWidthSizeClass` at all** — even though §5.1 lists the `:ui-core` adaptive kit as
available and `TenshuTheme` has been providing the size class to every app for free since the
adaptive-navigation work.

⚠⚠ **The month grid could not fit any Mac display, and nothing said so.** `aspectRatio(1f)` on a
`weight(1f)` cell makes the grid's height a function of the window's *width*: six rows of `W/7`,
≈ `0.857·W`, against `H` minus ~169 dp of chrome. It fits only when `H ≥ 0.857·W + 169` — 1403 dp
tall at a 1440 dp-wide window. **Widening the window made it worse**, which is the opposite of
what a resize should do. Compose neither scrolls nor clips that overflow: `Column` measures
children against the space *remaining*, so the last week rows are handed `maxHeight = 0` and the
day list below them with it. Measured in the test harness, the sixth row's day number lands at
y = 908 dp in a 900 dp window. `MonthGridFillsWindowTest` (`:calendar-shared-ui` `desktopTest`,
the `runDesktopComposeUiTest` harness `ContentWidthOrderTest` established) pins it, and was
confirmed to fail on the pre-fix layout before being kept.

What landed:

- **Cells fill their row from `Medium` up; phones keep the squares.** ⚠ The weighted rows have no
  minimum height, deliberately: a weight in a fixed-height `Column` measures its child with
  `min == max`, so a `heightIn(min = …)` inside one is a silent no-op. Six weeks always show, and
  a short window squeezes them — which is what a grid that must show six weeks has to do.
- **The selected-day list moves beside the grid** (300 dp, fixed) instead of under it. Not
  cosmetic: a fill-height grid and a stacked list compete for the same axis, so the list a
  desktop window would have left is none.
- **Rules between the cells**, once they are rectangles. At phone size the squares read as a grid
  on their own.
- **One toolbar row from `Expanded` up** — paging + *Today* leading, title, switcher trailing.
  ⚠⚠ `Expanded`, not `Medium` like the rest: this constraint is *horizontal*, and 600–839 dp does
  not have the room — measured at 700 dp the title wraps to "May / 2027" and the toolbar grows a
  second line, worse than the phone layout it replaced. The switcher wrapping its content rather
  than `fillMaxWidth()` is the single biggest un-native tell removed.
- **FAB → a toolbar `+` from `Medium` up**, `appWindowMargin()` on the body, `appContentWidth()`
  on the agenda (⚠ *before* the fill, per `ContentWidthOrderTest`).
- **`rememberPersistedWindowState("calendar")`.** ⚠ `:calendar-desktop` was calling bare
  `rememberWindowState()` — CMP's own 800×600 default, forgotten on quit — while Drive, Control,
  Fit, Mia and Timeline all open at 1024×768 and remember. ⚠ `:contacts-desktop` and
  `:maps-desktop` still have the bare call; `:home-desktop`'s 450×920 is deliberate.

⚠ **Compact is untouched by construction** — every branch is `>= Medium`, and the phone
screenshot is unchanged — so no `androidMain` delta and no HR#8 trigger, though the smoke gate was
run anyway because `:calendar-android` composes this code.

**Deferred when CA-2c landed, all three since done:** ~~event titles *inside* the month cells~~
(**CA-2e**); ~~a window-derived `HOUR_HEIGHT` in `TimeGridView`~~ (**CA-2d**); ~~a persistent
calendars sidebar at `Large`~~ (**CA-2f**). ⚠ The desktop list is empty — what is left for the
desktop is the maintainer's own click-through, not a phase.

### CA-2d — an hour is a fraction of the window, not a constant

CA-2c's first deferred item, and the other half of "Calendar does not scale to the window": the
month grid was fixed there, the **time grid** still drew a phone.

⚠ **The defect is the scale, not the range** — worth stating precisely, because the obvious
description is wrong. `HOUR_HEIGHT = 56.dp` is a *constant*, so a taller window did not show the
same twelve hours; it showed **more** hours at phone size — ~15½ of them in a 900 dp window, with
a 30-minute event 28 dp tall. The window grew and the content merely un-cropped, which is exactly
what "a stretched phone app" looks like.

⭐ **The fix was already written down, in the constant's own comment.** `HOUR_HEIGHT`'s KDoc read
"about twelve hours of a working day fit a phone at this" — i.e. 56 dp was itself derived from a
viewport, one nobody could re-derive because only the answer was kept. `TimeGridMetrics` promotes
that to the input: `hourHeight = max(56.dp, viewport / VISIBLE_HOURS)`, `VISIBLE_HOURS = 12` — the
same "Show N hours at a time" macOS Calendar exposes as a preference. A 900 dp window gets 72 dp
hours; a phone gets 63 dp, marginally roomier than the 56 dp its own assumption produced.

- ⚠ The old constant survives as a **floor**, so the scale can only grow. Below ~700 dp of
  viewport, twelve hours would mean twelve unreadable ones.
- ⚠ No ceiling, deliberately — on a tall display an hour genuinely should be tall, and capping it
  puts back the wasted space this removes.
- ⚠ Measured on what is left *after* the day-header band, not the window: the band grows with a
  day's all-day events, and scaling to height the grid does not have shows eleven hours on
  precisely the days somebody has holidays booked.
- ⚠ The scroll-to-08:00 effect stays keyed on the **date**, not the metrics: re-running it on a
  resize would yank the grid back to the morning every time a window edge moved.

⚠⚠ **The file-level `HOUR_HEIGHT` / `MINUTE_HEIGHT` / `GRID_HEIGHT` vals are deliberately gone**,
not left alongside. One scale draws the ruler and another reads a tap, and a drift between them is
silent in both directions — each half looks right alone. `TimeGridScaleTest` measures the drawn
gap between the "09:00" and "10:00" ruler labels (the artefact a user actually reads a time off),
then taps the column a quarter into that row and asserts the event lands at 10:00. Injecting the
old constant into the tap arithmetic alone makes that tap create a **14:30** event — which is the
whole argument for threading one metrics object rather than re-deriving per composable.

⚠ Android moves too, by ~7 dp per hour, and that is intended rather than incidental: the phone was
never the exception, it was the one viewport the constant happened to be tuned for. Smoke-gated.

### CA-2e — the month cell says what is on the day, not how many things are

The last of CA-2c's list, and the biggest single step towards looking like the stock app: a
desktop cell now lists its events by title. Dots answered "something is on"; a month view is
supposed to answer "*what*".

⭐ **The decisions went to `-shared-logic`, the drawing stayed in Compose** — §5.2's split, third
time. `CalendarMonthCells` (`byDate` + `fit`) owns ordering, the colour join and the overflow
arithmetic; `MonthGridView` is left drawing a list it was handed.

⚠⚠ **Not a widening of `CalendarMonthDots`, and that is deliberate.** That model exists for a
`UICalendarView` delegate, which gets exactly *one* decoration per cell and therefore throws away
everything but a count and a colour. A Compose cell has room for titles. Two consumers, two
projections of the same data, rather than one model that serves neither — the alternative was a
`CalendarDayDot` carrying titles that iOS would never read.

What the model decides, and why each is a test rather than a comment:

- **All-day first, then start time, then title.** The title tiebreak is not tidiness: two events
  at the same minute must not swap places between two loads of identical data, and a cell that
  reshuffles looks like a sync bug rather than a sort bug. Start-time-then-title is the same total
  order `CalendarMonthDots` and `CalendarAgenda` use, so a cell and the day pane beside it cannot
  disagree about which event is "first".
- **⚠⚠ The "+N more" row costs a slot.** With room for three and four events the answer is *two*
  events and "+2 more" — not three plus a fourth that overflows, and not three with the fourth
  silently dropped. The silent drop is the worse failure: nobody notices what a calendar never
  mentioned.
- **An unknown calendar colours `NEUTRAL` rather than dropping the event.** A hidden calendar or a
  load race must not make an event disappear from the grid.

On the rendering side:

- **One measurement for the whole grid, not one per cell.** Every row is an equal weight of the
  same box, so capacity is a property of the *grid*; 42 `BoxWithConstraints` subcompositions would
  also let two cells disagree, which is the one thing a table must never do.
- **⚠ Below one title's worth of room the cell falls back to the dots** — and that path is now
  reachable on a *desktop* window, not only on a phone. Showing nothing would make a busy day and
  an empty one identical.
- **Chips are clickable and open the event.** A title a user can see but not click reads as broken.
  The inner `clickable` consumes the tap, so opening an event does not also re-select the day under
  it.
- ⚠ Compact is untouched: `>= Medium` gates the titles, and the phone's dot count now comes from
  the same grouped map rather than `countsByDate` — the same number by a shorter route.

⚠ `CalendarUiState.countsByDate` consequently has **no production consumer left** (iOS reads
`CalendarMonthDots`, Compose now reads `CalendarMonthCells`). Kept rather than deleted: it is
tested public API of a module three shells consume, and removing it belongs in a deliberate pass,
not as a side effect of a UI change.

### CA-2f — the calendars sidebar, and the one control worth a permanent 220 dp

The last of CA-2c's list. A wide window now keeps the calendar list on screen, with the
show-on-this-device checkboxes that were previously two navigations away.

⭐ **No new state, and that is the finding.** Everything this needed already existed and was
already tested: `CalendarUiState.calendars` carries every calendar with its `visible` flag,
`CalendarRepository.occurrences` already excludes hidden calendars by default, and
`CalendarViewModel.setCalendarVisible` already writes the preference *and* reloads the window.
The sidebar is a projection of state the grid was already holding — which is why it is UI-only,
with nothing added to `-shared-logic`.

⚠⚠ **It routes through `CalendarViewModel`, not `CalendarManagementViewModel` — and both have a
`setVisible`.** The management one updates its own list optimistically and refreshes only itself,
which is right for the screen it belongs to and wrong here: the checkbox would move and the grid
beside it would carry on drawing the calendar just hidden. Two view models offering the same verb
with different blast radii is a trap worth naming, because the wrong one compiles.

⚠⚠ **`Large` (1200 dp), not `Expanded`.** From `Medium` up the month view already spends 300 dp on
the selected-day pane; another 220 at 840 dp leaves the grid — the thing the window is for — with
roughly 45 dp columns. At 1200 dp it still has ~90 dp columns, at 1440 dp ~124 dp. A sidebar is
worth having only once there is width nothing else is asking for.

⚠ **It deliberately does not duplicate `CalendarsScreen`.** Rename, recolour, delete, subscribe and
`.ics` import stay there, reached from one "Manage calendars" button pinned at the sidebar's foot;
the sidebar owns exactly the control worth keeping permanently visible. Two places to rename a
calendar is two places for the wording, the validation and the error handling to drift.

⚠ The top bar's *Calendars* action is **dropped** once the sidebar is up — the same destination
behind two controls in one window teaches a user to distrust both. Below `Large` it is unchanged.

⚠ The row **is** the checkbox (`toggleable` + `Checkbox(onCheckedChange = null)`), not a clickable
row beside an independently clickable box: one semantics node with a checked state, and a 220 dp
pointer target instead of a 20 dp one. `CalendarSidebarTest` asserts the *row's* checked state, so
splitting the two stops the assertion resolving rather than silently shrinking the target.

⚠ Visibility is device-local — derived from a device-scoped preference, not a field on the row — so
unchecking dims a name and says nothing else. On another device the calendar is untouched.

### CA-2g — ⚠⚠ the sidebar toggle did nothing, and Calendar has no reactive reads at all

Reported the day CA-2f shipped: unchecking a calendar left the grid unchanged. Two findings, one
small and one that outlives this fix.

**The bug.** `CalendarViewModel.setCalendarVisible` wrote the preference and then called
`refresh()`. The write landed — verified by reading Control's desktop store directly, which held
the `calendar.view.hiddenCalendars` device row containing the toggled calendar the whole time —
but the `refresh()` that followed re-read the preference through
`deviceSettings.getAll()` and got the **pre-write** value back. So the grid redrew identically and
the checkbox sprang back. Nothing failed, nothing logged, and the user's click *had* been saved:
restarting the app showed the calendar correctly hidden.

⚠ This is the failure mode `:preference-service-client` documents on `readForDevice` /
`readOnceForDevice` and that Drive hit twice on iOS in 2026-07 — **a read taken after a write
observes the value from before it**. Calendar reached it by a new route: not a view model
polling a preference, but a *repository* read (`hiddenCalendarIds()`) sitting underneath an
innocuous-looking `refresh()`. The rule is the same and now has a home in Calendar's own seam:
`CalendarRepository.observeHiddenCalendars()`, whose KDoc carries the incident.

The fix follows the established pattern exactly — **collect the flow as the source of truth,
write fire-and-forget, update optimistically**:

- `CalendarViewModel` loads its window with `includeHidden = true` and applies visibility itself,
  so hiding and un-hiding are a filter over data already in hand. The toggle is instant, and there
  is **no second read left to be early**.
- The `visible` flags on `CalendarUiState.calendars` are recomputed from the same set rather than
  taken from `repository.calendars()`, so the checkbox and the grid are two renderings of one
  value and cannot disagree.
- ⚠⚠ A test asserted the bug. `toggling_a_calendar_writes_through_and_reloads` required the toggle
  to reload the window — the reload that *is* the defect — and was green throughout, because the
  fake applied writes synchronously and the store does not. Its replacement asserts the opposite
  (no reload, events gone at once), and the fake now models the store: a write does **not** become
  visible to reads until the test pushes the emission. Re-run against the old code, it fails with
  "the events are still on the grid" — the reported symptom, in a unit test.

**The finding that outlives it: Calendar has no reactive reads whatsoever.** Grepped across
`:calendar-shared-logic`, `:calendar-shared-ui` and `:calendar-service-client` — **zero**
occurrences of `.flow()`, `.changes()`, `pagingSource` or `PagingData`. Every read is a one-shot
`getAll()` behind a suspend repository function, and freshness comes entirely from something
calling `CalendarViewModel.refresh()` by hand. Drive is the contrast: reactive IPC reads plus a
paging source.

⚠ Paging is **not** the gap — a calendar holds thousands of rows, not Drive's 107 k, and §3's
windowed reads are the right shape. *Reactivity* is: an event created in Mia, an `.ics` import, a
subscription refresh, or any change synced from another device does not appear until the user
pages a month or reopens the app. This visibility toggle is simply the first place it was noticed,
and it is now the only surface in Calendar with a live read. Sizing a `flow()`-based repository
seam (and what it means for the `CalendarUiState.refresh()` contract) is worth its own slice
before the next surface needs one — recorded here rather than smuggled into a UI fix.

### CA-2h — the window is observed, not fetched

CA-2g's recorded gap, closed for the surface that shows it: `CalendarViewModel` now **subscribes**
to its window instead of loading it. An event created by Mia, an `.ics` import, a subscription
refresh or a block synced from another device lands on the grid on its own — where before it
waited for a page step or a relaunch.

Two new reads on the seam, both `Flow`:

- **`observeOccurrences(from, to)`** — `combine(events, overrides)` folded through the same
  `OccurrenceResolver` the one-shot read uses. ⚠ `combine`, not events alone: an override edited
  without its series being touched must move the occurrence it overrides.
- **`observeCalendars()`** — rows joined with `observeHiddenCalendars()`, so a rename, recolour or
  new calendar arrives by itself.

⚠⚠ **`observeOccurrences` includes hidden calendars, deliberately.** Joining visibility in would
put a preference read back on the write's own code path — the CA-2g bug, one week old — and would
pay for that read on every unrelated event change. The view model collects the hidden set
separately and filters what it already holds, which is also what keeps the visibility toggle
instant.

⚠ **`refresh()` kept its name and became a resubscription.** Every window change still calls it
(paging, view switch, jump to today), and so does iOS — `.refreshable` and the editor's
`onDismiss`. Both meanings collapse to "show me the current state of this window". It **cancels
the previous subscription first**: a left-running one keeps a stale window's reads alive for the
life of the screen, and every page step would add another, all emitting into the same state.

⚠ The projections moved into `calendarSelection()` / `eventSelection()` / `overrideSelection()`
and the fold into `resolveOccurrences(...)`, so the one-shot and live reads cannot drift. A live
read selecting a field the one-shot did not would differ from it only under recurrence — which is
the kind of difference neither call site can show you. Each selection is a **function**: chains
mutate as they are lowered, so a shared `val` with two terminals hanging off it is not the saving
it looks like.

**Verified where it counts.** `TenshuCalendarRepositoryTest` subscribes *first* and then writes,
against a real proxy — the property a screen depends on, and one that cannot be faked at the view
model layer, because the emission has to come out of the store. Both new tests fail *fast* (not by
hanging) against a single-emission implementation. The view-model tests push rows into the fake to
stand for a sync and assert the grid follows with nobody calling `refresh()`.

**Still one-shot, deliberately:** the editor, the calendars screen and the interop sheet. Each is a
write-then-navigate surface that re-reads on entry, and none of them is on screen while somebody
else changes the data. `CalendarUiState.countsByDate` remains the dead projection CA-2e left.

### CA-2i — the calendars screen goes live, and the editor deliberately does not

CA-2h left two surfaces one-shot and called it deliberate. Asked to do them too — and the answer
is different for each, which is the interesting part.

**The calendars screen: fully live.** All three of its lists are now collected — `observeCalendars`,
`observeSubscriptions` (new) and `observeDefaultCalendar` (new). A calendar renamed on the phone, a
feed whose background fetch just landed, a default changed elsewhere: all arrive on their own. The
old "re-read on entry" was *nearly* right and stopped being right the moment anything else could
write while the screen was open — which, since the sidebar shipped, is routine.

- ⚠ The three flows are `combine`d rather than collected separately: a row shows its calendar, its
  feed and whether it is the default *together*, and three collectors would paint two thirds of a
  change and then the rest.
- ⚠ `.catch` turns a failing read into state, exactly as the one-shot `runCatching` did. A flow
  collected for the life of a screen must not throw out of it.
- ⚠ A subscription's *last fetched* stamp is the clearest case for this: refreshing is a background
  op, and a screen showing "never" until you left and came back would report the opposite of what
  had just happened.

**⚠⚠ The editor: its chooser is live, its form is not — and that boundary is the point.** A list
can be replaced under the user; a field they are typing into cannot. Re-reading `title`, the times
or the reminders from the store mid-edit would overwrite what they had typed, and *"the app ate my
edit"* is a far worse failure than *"the app showed me a stale field"*. So:

- The calendar **chooser's options** are collected — a calendar created in another window appears.
  ⚠ The preselect stays `ifBlank` on *every* emission, so a later one cannot silently re-file an
  event either.
- The **form** is seeded once, from the occurrence the caller handed over (§CA-3's "the occurrence
  travels whole"), and only the user moves it after that. `EventEditorViewModelTest` pins it: an
  emission arriving mid-edit updates the chooser and touches neither the title nor the chosen
  calendar.
- The default-reminder read stays a one-shot **seed**. `reminderChoiceIsUsers` exists precisely so
  a default can be replaced silently while an explicit choice never is; a live default would have
  to re-decide that on every emission, for a preference that changes about once a year.

⚠ **What this does not do:** notice that the event you are editing was changed or deleted on
another device. Not clobbering is the safe half and is what shipped; *telling* you is new UI and a
decision about what to offer (reload, keep mine, both), and that belongs in its own slice rather
than smuggled into a read change.

⚠ Fakes had to be made honest again, twice, and both would otherwise have been silently green: the
editor's fake returned an empty flow where the code now collects its chooser (every save would have
failed with "no calendar"), and the management fake threw `failWith` only from the suspend twin, so
the error path would have compiled, passed and never run.

### CA-2j — two mergers, and the case against a third

Calendar had no `SemanticMerger` at all: every row resolved last-writer-wins. Asked for one — and
most of the work was working out **which** conflicts a merger can honestly fix, because the
platform's merge seam is narrower than it looks.

**⚠⚠ Two constraints rule out field-level merging, and both are worth stating before the code.**

1. **There is nothing to merge *on*.** A `SemanticMerger` receives two whole states and nothing
   that says which field moved when. `FolderMerger` can tie-break per child because a `File`
   carries `LastModifiedAt` as a value property; an `Event` carries no such stamp, so "keep the
   newer title" is not a question this layer can answer.
2. **A merger must converge, or devices disagree for good.** `existing` is whatever *this* device
   holds, so the two devices call the merger with the arguments swapped. Any rule that is not
   commutative — "prefer the incoming title", "prefer the non-blank one" — leaves them holding two
   different answers permanently, with no later sync to reconcile them. Every rule below is a set
   union, a boolean OR or a max.

And a third, specific to events: **start, end, all-day and timezone are one fact, not four.**
Merging them field-wise can produce an event that ends before it begins — a state neither device
ever held. Losing one device's edit is recoverable; inventing a third version nobody wrote is not.

So `Event`'s schema comment — "an event is edited as a whole by one person at a time, so a semantic
merge has nothing useful to contribute" — is right about the scalar block, and it stays. What it
did not consider is the one field that is *not* edited as a whole:

**`EventMerger` — the union of deleted occurrences.** `DeleteOccurrence` is a read-modify-write: it
reads `exceptionDates`, appends one date, writes the delimited set back. Two devices deleting
*different* occurrences of one series each write a set missing the other's date, and last-writer-wins
**resurrects** one of the deleted occurrences. That is data loss, not a preference, and set union is
the standard answer. Everything else is taken from `incoming` — precisely today's behaviour.

**`ReminderStateMerger` — a dismissal a snooze cannot undo.** `ReminderState`'s KDoc already
records the precedence its *readers* apply ("a dismissal always wins, or 'I am done with this'
would lose to 'remind me again'") — but the row itself did not obey it. Dismiss on the phone, snooze
on the laptop, and whichever block landed second was the whole truth, so the user who said *done*
got alerted again. `dismissed` is now one-way (OR) and `snoozedUntilEpochMs` monotone (max). ⚠ The
snooze is *kept*, not discarded: precedence is the reader's job, and dropping the fact would lose it
if the dismissal were ever revised.

⚠ Both schema comments were amended in the same change. A KDoc that says a merger has nothing to
contribute, sitting above a type that now has one, is worse than no comment.

⚠ Every test asserts **both argument orders**. A one-way assertion passes for a merger that
diverges, which is the failure this design is most exposed to.

⚠ Registered in all four DI sites that carry `FolderMerger` (Android, desktop, iOS, system-test).
The iOS one is unverified beyond the dependency check — nothing compiles Kotlin/Native here — but
`:calendar-service-proxy` is already an `iosMain` dependency, which is the failure CA-5c hit and
left a comment about.

**Not done:** the conflict *UI* ("this event changed elsewhere — keep mine, theirs, both"). These
mergers make concurrent edits lose less; they do not make the loss visible. That remains what
CA-2i left open, and it is a UI slice, not a sync one.

### CA-2k — the conflict the mergers cannot fix, made visible

CA-2j made concurrent edits *lose less*; it could not make the loss **visible**, and the loss that
remains is the one users notice: two people editing the same event, one save silently winning.

⚠⚠ **Detected at the write, not while typing.** The obvious design — watch the event and warn as
soon as it changes — was rejected for the same reason CA-2i kept the form a snapshot: an editor
that interrupts mid-sentence, or worse replaces the field being typed into, trades a rare loss for
a constant irritation. The check runs where it can still change the outcome and where the user is
already thinking about this event: the moment before the write. Classic optimistic concurrency.

`commit()` re-reads the occurrence it was seeded from and compares the fields this editor can
change. Three outcomes:

- **unchanged** → writes, exactly as before;
- **changed** → writes *nothing* and offers **Keep mine** / **Use theirs** / **Keep both**;
- **no longer there** → offers **Save mine anyway**, and deliberately not "use theirs".

⚠ **"Gone" is not called "deleted".** The occurrence may have been moved to another day by the
other device, and this layer cannot tell the two apart without an event-by-id read the repository
does not have. The copy says *"no longer where you opened it — another device deleted or moved
it"*, which is what is actually known. Naming the wrong one would be a confident lie in the one
dialog whose whole job is to be trusted.

- ⚠ **Keep both** blanks the id, so it is a *create*: the other device's version is left untouched
  and this form becomes a new event. Scope is forced to `ALL_OCCURRENCES` — a new event has no
  series to except an occurrence from — and `loadedFrom` is cleared, because a create cannot
  conflict with anything.
- ⚠⚠ **Use theirs re-seeds the baseline, not just the fields.** Without that the next save would
  conflict against a version the user has already accepted — a prompt with no way past it.
- ⚠⚠ **A failed read is not a conflict.** If the proxy is unreachable the save fails on its own and
  says so; refusing to write on a read failure would turn every outage into a prompt about a change
  nobody made.
- ⚠ Their version is *named* in the dialog ("Theirs: Sprint sync · 09:00"). "Something changed"
  leaves the user choosing between two things they cannot see.

⚠ The fake had to be made honest for the third time in this run: `EventEditorViewModelTest`'s
repository returned an empty occurrence list forever, which the editor never asked about until this
change — and then *every* save read as "the event is gone". A fake that accepts writes must be able
to answer for them.

**Still open:** the same treatment for delete (deleting an event someone else has just changed
asks nothing), and reminders — the conflict comparison covers what the form edits, not the reminder
rows, which are their own write.

### CA-2l — deleting over someone else's change

CA-2k covered the save; the delete still went through silently. Same detection, a **different set of
ways out** — because the two actions are not symmetric.

- **Changed elsewhere** → *Delete anyway* / *Keep theirs*, and the copy says what a delete costs
  here: "deleting now throws that change away too". ⚠ Keeping theirs is what **cancels** the
  delete — the event stays and the form shows what the other device made of it, which is the only
  way to decide whether you still want it gone.
- ⚠ **No "Keep both".** Both of a deletion and an edit is just the edit, which is exactly what
  keeping theirs already does. An option that duplicates another is worse than one option.
- ⭐⭐ **Already deleted elsewhere is a *success*, not a conflict.** The user's intent is already
  the world's state, so a prompt would be asking permission for something nobody disagrees with and
  nobody can undo. It closes and reports `deleted`.
- ⚠ That path reports `deleted` **without calling the op**: whether deleting a missing row throws
  is the op's business, and the honest outcome here does not depend on the answer.

⚠ The three prompts now share `EditorConflict`, discriminated by `ConflictIntent`. One dialog with
one place for the copy — not two dialogs that drift apart the first time someone rewords a button.

**Verification note.** Both gate failures in this slice were investigated rather than re-run away:
`:hub:test`'s `HomeDeviceControlSystemTest` timed out (passes standalone twice, touches no calendar
code, `hub/src` unchanged since the durable-`FileDatabase` change — the `TestSystem` flake population that `tech-debt.md`'s
"full gate is flaky under parallel load" entry names), and `:drive-android:mergeExtDexDebug` failed
once under the smoke gate's parallel build and then succeeded, up-to-date, on its own.

### CA-3 — event editor

The form is Material 3 assembly, as §5.1 predicted. Four things were not:

- ⚠⚠ **The this-vs-all prompt is the phase's actual content.** Both answers are destructive in
  opposite directions, so `EventEditorViewModel` raises it *only when the question is real* — an
  existing, recurring event. Not for a create, and not for a one-off: a dialog with one meaningful
  answer trains people to dismiss dialogs, which is how the recurring case then gets answered
  wrongly. Neither answer sits in the dialog's "dismiss" slot for the same reason.
- ⚠⚠ **A series edit must carry the event's existing `exceptionDates`.** `SaveEvent` treats an
  omitted optional as absent, so renaming a series would otherwise silently **resurrect every
  occurrence the user had skipped**. `TenshuCalendarRepository.saveEvent` reads them back and
  passes them through; the mutation that removes it kills exactly one test.
- ⭐ **`ResolvedOccurrence` now carries the series' raw `RRULE`** instead of an `isRecurring`
  flag (which became a derived getter). The editor has to *classify* the rule, not merely know
  one exists, and the alternative was a second read of a row the list had already handed it.
  ⚠⚠ The rule is classified against the occurrence's **`recurrenceId`** — its original start —
  not its effective one: an override that moved this Monday standup to a Wednesday leaves the
  series weekly-on-Monday, and classifying `BYDAY=MO` against the Wednesday would call it
  `CUSTOM`, so the editor would refuse to edit a rule it wrote itself.
- **`RecurrencePreset` is conservative by design.** Only the exact shapes the editor *emits* map
  back to a named choice; `FREQ=WEEKLY;INTERVAL=2` is weekly-ish and still classifies as
  `CUSTOM`. That is what stops a save touching only the title from quietly dropping an interval
  or an end date. `CUSTOM` renders read-only with an explanation — §3 D2's "refuse to edit rather
  than silently rewrite" — and `toRrule` returns `null` for it so the enum cannot be asked for a
  rule it does not have.

Supporting pieces:

- `RecurrenceRule.format()` lives beside `parse`, because CA-6's ICS export needs the same
  renderer and two would drift. ⚠ Deliberately **lossy** for a rule carrying
  `unsupportedParts` — re-emitting a part we do not expand by would claim an understanding we do
  not have, which is precisely why `CUSTOM` is read-only. Tested as `parse ∘ format == identity`.
- **ADR 0016 gap closed** (CA-2 flagged it and named CA-3): the hard-coded English weekday and
  month names are gone, replaced by a `CalendarDateFormat` expect/actual. ⚠⚠ **Deliberately not
  a string catalogue** — these are *locale data*, not copy, and translating them by hand means
  re-deriving, badly and per language, a table every platform ships (plus the 12-vs-24-hour
  convention and the day/month field order). Actuals: `java.text` for Android **and** desktop —
  ⚠ **not `java.time`**, which needs API 26 or desugaring that this repo does not enable, so
  using only the fallback path gives one shared actual with no version branch that can rot —
  `NSDateFormatter` on iOS, `Intl` on JS. ⚠ Formatters are built per call: they are not
  thread-safe and the default locale can change under a running app.
- Navigation was a `remember`ed sealed route, not a nav library — *"Calendar has one branch and no
  deep links"*. ⚠⚠ **Superseded (2026-08-31): it is `:ui-core`'s `KmpNavHost` over a
  `mutableStateListOf` back stack**, and the *no deep links* half of that reasoning was already
  false when it was written — CA-5f's reminder taps and TT-1c's cross-app opens are both deep
  links, resolved a few lines above the routes. What a nav host buys is a saveable-state slot and a
  `ViewModelStore` **per entry**; without one, leaving a `when` tears the screen out of composition
  and takes every `rememberSaveable` with it, which is a bug with three separate histories in this
  repo (`tech-debt.md`, "scroll a list, open a detail, come back"). ⚠ Routes carry **ids**: the
  editor's used to carry a whole `ResolvedOccurrence`, and the resolved value now lives in a
  shell-held slot instead — `ui/navigation/Destination.kt` carries the reasoning.
  ⚠ Still **no `AdaptiveNavScaffold`**, deliberately: one top-level surface, and a rail would take
  width CA-2f already ruled the grid cannot spare. CA-4's week grid remains a *view* within the
  calendar destination rather than a destination of its own.
- ⚠ The editor's `koinViewModel` takes a **`key`** — without one Compose hands back the previous
  event's view model and the form opens pre-filled with somebody else's data
  (`FilesScreenIntegrationTest` documents the same trap). It is now doubly satisfied, since each
  route is also its own `ViewModelStore`; it stays because it is what makes the composable correct
  wherever it is called from.
- ⚠ The editor closes on the **commit**, never on the tap — a refused save would otherwise look
  exactly like a successful one.

⚠ **Not verified in this environment:** the iOS `CalendarDateFormat` actual (Kotlin/Native
targets are disabled on Linux) and the HR#8 Android smoke gate (no emulator). Both are owed on a
machine that has them, along with a maintainer click-through of the editor.

### CA-4 — week / day time grid

§9 called this "the expensive slice" and told it to put the layout math into
`-shared-logic` **first**. That ordering was right, and it is most of the story: the pure half is
~230 lines with 34 tests, and the Compose renderer over it is ~330 lines that do nothing harder
than multiply a `dp`.

**`DayLayout` — the algorithm §5.2 called out.** Cluster into maximal runs of transitively
overlapping events → greedy column assignment within a cluster → expand each event rightwards
over columns nothing else occupies during its span.

- ⚠⚠ **Clustering is what keeps the column count local.** Without it one three-way conflict at
  15:00 shrinks the 09:00 standup to a third of the screen.
- ⚠⚠ **The cluster boundary is `>=`, not `>`**, because intervals are half-open. ⭐ **Found by
  mutation**: flipping it left all 17 tests green, because greedy assignment still puts the event
  in column 0 — only the **column count** changes, so a full-width event silently renders at half
  width with empty space beside it. A test now pins it.
- ⭐ **The expansion pass** is not cosmetic: without it a day with one long meeting forces every
  event in its cluster to a third of the width even where nothing is beside them.
- ⚠⚠ **A zero-length event is given a minimum height**, and that is load-bearing rather than
  aesthetic: an empty half-open interval overlaps *nothing*, so such an event would stack
  invisibly under its neighbour and be impossible to tap. RFC 5545 permits them and imports
  produce them.
- ⚠ Layout is **deterministic** (input order breaks ties). Two identical intervals that swapped
  columns between runs would make the grid flicker on every recomposition.
- ⚠ Expansion only ever grows **rightwards**. Growing both ways would let a meeting jump across
  the column as its neighbours change, which reads as a bug even when the geometry is defensible.

**`daySchedule` — clipping and the band.** An all-day event goes to a header band, never the
column: it has no position on an hour ruler, and drawn as midnight-to-midnight it would bury
every real appointment. ⚠ A **timed** event spanning midnight is clipped into *both* days instead
— "22:00 until 02:00" is a time, and banding it would lose the only thing the grid is for.

⚠⚠ **The WEEK/DAY window reaches one day further back than it draws.** The repository answers by
an occurrence's *start* date, so a shift running 22:00 Sunday → 02:00 Monday is not in a
Monday-first window at all, and Monday's column would show a clear morning that is not clear.
Nothing needs widening forward: an event starting on the last visible day is already returned and
gets clipped.

**View-model shape.** `stepMonth` became **`step`**, which pages the current view by one of *its
own* periods, so the header arrows stay view-agnostic. `selectedDate` now serves as both the
month view's detail day and the grid's anchor — deliberately one field, which is what makes "tap
the 20th, switch to Day" land on the 20th; two fields kept in step by hand drift silently. ⚠
Paging weeks carries the month along, so switching back to MONTH lands where the user actually
is.

**Renderer notes.** One shared vertical scroll for the ruler and every column, so hour labels
cannot drift out of step with the events beside them. ⚠ `BoxWithConstraints`, because the layout
layer reports **fractions** rather than pixels — it has no idea how wide a phone is, and a
renderer that had to tell it would not be the one iOS reuses. ⚠ The opening scroll waits for a
real `maxValue` (`snapshotFlow { … }.first { it > 0 }`): `scrollTo` clamps, so firing before
measurement would silently scroll to 0 and look like the feature was never written. ⚠ Tap-to-
create snaps to the half hour — an unsnapped tap yields "10:37", which nobody means.
`rememberCurrentMinute()` is the **only ticking clock in Calendar** and is deliberately in the UI
layer: it is an animation, not a decision.

⚠⚠ **Deliberately not shipped: drag-to-resize**, which §5.2 lists in this slice's scope. Two
reasons, both about honesty rather than effort. It cannot be verified at all in this environment
— a gesture is exactly the thing no unit test reaches and no emulator run here can exercise. And
it re-opens the CA-3 scope question on a surface with no room for it: a drag on a recurring event
has to ask this-vs-all, and a modal dialog interrupting a drag is a UX decision the proposal
never settled. It wants its own slice, with the question answered first.

⚠ **Also owed:** the HR#8 Android smoke gate (no emulator here) and a maintainer click-through —
the grid's proportions, scroll feel and tap targets are precisely what tests cannot judge.

### CA-5a — the reminder model and the plan

CA-5 is the largest phase in the plan and it splits cleanly. **CA-5a is the half with the
irreversible decisions and no platform in it** — schema, ops, and the pure "what fires when"
calculation — following the discipline that has now paid off three times (CA-0's expander, CA-4's
layout): put the part that is expensive to change where a unit test can reach it, first.

**Schema.** `Reminder` (event + signed offset) and `ReminderState` (per-occurrence dismissal and
snooze), plus §6.4's third default, which CA-1 deliberately deferred here because there was no
`Reminder` to attach it to.

- ⚠⚠ **A reminder belongs to the series, not the occurrence.** A row per occurrence would be the
  unbounded expansion of a rule that this schema refuses everywhere else. `ReminderState` exists
  only where the user has actually *acted* — the common case (fires, ignored) writes nothing.
- ⚠⚠ **`minutesBefore` is signed, and the negative half is not an edge case.** An all-day event
  starts at 00:00, so "on the day at 9 a.m." — what every stock calendar offers — is **minus
  540**. Modelling only "before" would put the alert at 23:50 the night before, or make it
  unrepresentable. Hence two default preferences, not one: the timed default and the all-day
  default are not the same quantity.
- ⭐ Both keys are **content-derived** (`"$eventId|$minutesBefore"`,
  `"$reminderId|$recurrenceId"`), so two devices adding the same reminder converge on one row.
  ⚠ That is also why `DeleteReminder` **must** cascade to state rows: re-adding the same offset
  mints the same id, and an orphaned dismissal would silently suppress a reminder the user had
  just asked for.
- **HR#9:** `CalendarService` → **version 2** with an **empty** `SchemaMigration`. Nothing was
  reshaped — the baseline diff shows every existing field list unchanged and four new rows — but
  a bumped service with no registered chain sits below its derived floor and the store-open gate
  **wipes** rather than migrates. The empty step is what makes the bump mean "old peers stop
  pulling my new types" instead of "delete every calendar on this device". No
  `TENSHU_STORAGE_FORMAT_VERSION` bump, no relay redeploy.

**`ReminderPlanner`** — pure, clockless, platform-free. Occurrences × reminders × state → what to
arm, in a window, soonest first.

- ⚠⚠ **Redaction happens at compose time, and that is the phase's sharpest finding.** Q10's
  `sensitive` flag would naturally be applied where the notification is *drawn* — and on Apple
  platforms that is **impossible**, because the OS is handed finished text days in advance and we
  never see it again (§4.6). So the planner redacts, and a presenter cannot show details it was
  never given. ⚠ The **location** is redacted with the title: "Oncology, 3rd floor" says
  everything the title would.
- ⚠ The event's **own** zone decides the instant (the `RoutineLocale` discipline), with the
  device zone used only for a genuinely floating event.
- ⚠ `limit` defaults to **64**, the documented iOS pending cap, and takes the **soonest** N — the
  wrong end would lose tomorrow's reminders and keep next month's.
- ⚠ It deliberately does not know whether a reminder was already *delivered*. Nothing is written
  when one fires and is ignored, so not re-delivering is the presenter's duty — which the stable
  `DueReminder.key` makes cheap, since it replaces rather than stacks on every platform.

#### ⚠⚠ Four survey findings that change CA-5's remaining slices

Established by reading the code rather than the docs, and each one invalidates something §4
assumed:

1. **`ScheduleItem` carries no parameters — `opId` only, by design.** So a reminder's identity
   *cannot* ride in the scheduled item. The shape is `RoutineScheduleArmer`'s: arm **one**
   `ONE_SHOT` at the earliest upcoming firing under a single stable `dedupKey`, and have the op
   re-derive what is due when it wakes. That is exactly what `ReminderPlanner` is for, and it is
   why the planner takes a window rather than a single event.
2. **`DismissedRequest` is not reusable.** §4.1 said dismissal-sync was "that pattern, not new
   work" — but the type is keyed to inbound *actionable requests* (membership grants, contact
   invites) and carries no per-occurrence identity, so reuse would have meant inventing a fake
   request id per reminder. `ReminderState` is the right row; the *pattern* was reused, the type
   was not.
3. **The macOS tray bridge is poll-only** — a 10-second timer, no server push, and nothing at all
   while the Control daemon is quit. So §4.6(a)'s `UNCalendarNotificationTrigger` pre-arm is not
   merely the nicer option, it is the only one that works; extending the poll was never viable.
4. **Nothing in the repo requests `SCHEDULE_EXACT_ALARM`.** `canScheduleExactAlarms()` is only
   ever *read*. Q6's contextual request is entirely unbuilt, and `calendar-android`'s manifest
   already carries a comment saying so.

**Remaining CA-5 slices**, in the order their dependencies allow:

- **CA-5b — the `NotificationPresenter` platform seam** (Gap 2, and scoped as a platform slice
  because Routines, Fit and Drive all want it). The template is `NewFolderAnnouncer`: a narrow
  `commonMain` handle with a `Noop` default binding, not a general notification framework.
- **CA-5c — Android**: the armer over `ScheduleItem` + a `Punctual`+`HeadlessSafe` op, plus Q6's
  contextual `SCHEDULE_EXACT_ALARM` request.
- **CA-5d — macOS**: `UNCalendarNotificationTrigger` on the existing tray path, with `reconcile`
  extended to re-arm on edits. ⚠ Verify the 64-pending cap here.
- **CA-5e — the UI**: reminder rows in the editor, snooze and dismiss actions, and the
  per-calendar *sensitive* toggle.
- **HR#2** — the lock-screen amendment lands with the first slice that actually posts something
  (CA-5b), not before: until then no plaintext reaches a lock screen.

### CA-5b — the OS-notification seam

Gap 2, built as **`:app-notify`** — a platform module beside `:app-liveness` / `:app-outbox` /
`:app-reachability`, not a Calendar internal. §4.3 said to scope it that way and the survey proved
the point: **ten call sites hand-roll `NotificationCompat` today**, with no shared builder and no
shared channel registry. Calendar is the eleventh, and pays for the seam.

⚠ Narrow by construction, following `NewFolderAnnouncer` rather than growing a framework: post,
cancel, a capability question, and a `Noop` default so a platform with no surface needs no branch
at the call site.

**⭐⭐ The scheduling split is the architecture of reminders, made into types.** §4.6's finding —
*Android wakes and decides; Apple platforms pre-arm* — becomes `NotificationPresenter` and
`SchedulingNotificationPresenter`. ⚠⚠ Modelled as a **sub-interface, not a `deliverAt` field**,
because the field version's failure mode is an alert that fires **immediately instead of on
Tuesday**: silent, and exactly wrong. With a sub-interface, "schedule this" against a presenter
that cannot is unrepresentable, and a caller does `as? SchedulingNotificationPresenter` and falls
back to arming its own wake.

**`AlertReconciler`** is the diff, lifted from where it already worked. Control's macOS tray agent
has had `reconcile(_ current:)` for a long time — the only problem is that it lives in Swift, so
Android would have written a second one. ⚠⚠ It compares **content**, not just ids: an alert whose
text or time changed is re-scheduled and one that did not is left alone, because re-scheduling an
unchanged alert can re-deliver one the user has already seen on Apple platforms and wastes an
alarm on Android. Both failure modes were mutation-checked. ⚠ A second entry point exists for a
platform that can only report **ids**, deliberately as a separate function rather than a lossy
adapter — given ids alone an alert must be re-scheduled regardless, and a caller should know it is
paying that.

**Android actual** ships; macOS and iOS are `Noop` for now and land with CA-5d, where they can be
verified against the trigger work rather than written blind. ⚠ Two Android details that are easy
to get wrong: `PendingIntent.FLAG_UPDATE_CURRENT` (without it a re-armed reminder deep-links to
the *previous* occurrence, because a matching PendingIntent keeps its old extras), and setting
`PRIORITY_*` as well as channel importance (below API 26 `NotificationCompat` reads priority, and
`minSdk` is 24).

**`CalendarAlerts`** maps a `DueReminder` to a `ScheduledAlert` — one place, so CA-5c and CA-5d
cannot disagree about what a reminder looks like once it leaves the app. Two decisions there are
security-relevant rather than cosmetic:

- ⚠⚠ **The tap payload carries ids, never the title.** A payload rides in a `PendingIntent` extra,
  so putting event text there would smuggle a *redacted* reminder's title onto the device in the
  clear — defeating Q10 through the back door.
- ⚠⚠ **One channel for all calendars, not one per calendar.** Per-calendar channels sound like a
  feature (silence "Health" in OS settings) until you notice the channel *name* is then the
  calendar name, listed in the OS UI — the same disclosure by another route.

**HR#2 — the amendment landed** (threat model A5). An event reminder is the first Tenshu surface
that discloses a fragment of A5 to someone with **only line of sight to a locked screen** — no
possession, no unlock, no malware. AD1 already names the shoulder-surfer but assumed they held the
device; AD2 (proximity: an ex-partner, a stalker) gains most, since repeated glances are a
realistic collection method. Mitigation is the Q10 flag, plus the two back doors closed above,
plus `VISIBILITY_PRIVATE` set **explicitly** rather than inherited so a future platform default
cannot silently un-redact every alert. ⚠ Still open: a *global* "hide all details" control — the
per-calendar flag is the only granularity today.

⚠ **A build trap worth recording:** a KMP module that targets fewer platforms than its consumer
fails dependency resolution for the missing one **while still printing `BUILD SUCCESSFUL`** — the
only signal is an `e:` line in the log. `:app-liveness` omits `iosX64`; copying its build file
meant `:calendar-shared-logic` silently could not resolve `:app-notify` on that target.

⚠ **Nothing calls `post` yet.** No plaintext reaches a lock screen until CA-5c wires the armer,
which is why the amendment says what it *will* disclose rather than what it does. On-device
verification of the Android presenter is owed there too — a notification is not something a unit
test can see.

### CA-5c-1 — ⚠⚠ Calendar was never wired into Control, and the wake decision

**The headline is a defect, not a feature.** Tracing where the reminder op would have to run turned
up something worse: **`:calendar-service-proxy` had no production wiring site at all.**

- `:control-shared-proxy` did not depend on it.
- `calendarServiceCommonModule` / `calendarServiceClientModule` were absent from the proxy's
  registered schema configs, so Control could not materialise an `Event`.
- `calendarServiceProxyImplementationFactory` was absent from every platform's factory chain, so
  **every calendar operation would have failed on a device** — `SaveEvent`, `DeleteEvent`, all of
  them.
- `initCalendarService()` was never called, so `installRelationMaintainer()` never ran and a
  calendar row **arriving via sync would have been invisible** — stored but unfindable.

⚠⚠ **This spans CA-1 → CA-4: the Calendar app has never worked on a real device.** Every phase
was green because every calendar test builds its own `TestProxy` with its own factory override, and
that harness is the *only* place the ops were ever registered. The one check that would have caught
it — HR#8's Android smoke gate — was owed and unrun at every phase (no emulator in the build
environment), and the smoke script installs Control + Drive + Maps, not Calendar. The
"maintainer click-through pending" caveat carried on each PR was pointing straight at this.

**Fixed** by registering the schema modules in `ProxyModule`, adding the factory to the Android,
desktop and iOS chains, and calling `initCalendarService()` in each platform's init hook —
following the shape every other service already uses.

**Guarded** by `CalendarOperationCoverageTest`, which reads the op ids out of the *client module*
rather than a hand-written list and asserts each has a proxy implementation. ⚠ It cannot re-catch
the exact hole (a module absent from Control is not visible from inside the module), but it closes
the realistic next one — "declared an operation, never gave it an implementation" — which fails at
the first call as an opaque `error/internal`, never at startup.

⚠ **Still unverified.** The wiring compiles and every test passes, but nothing here proves Calendar
works on a device; only the smoke gate and a click-through can, and the smoke script should be
extended to install Calendar.

**`ReminderDrainCalculator`** is the other half of this slice: the wake-up decision, pure, because
everything around it — `AlarmManager` firing, an OS throttling us, a notification appearing — is
unreachable from a test. One pass answers all three questions (deliver / missed / next wake), and it
has to: planning only the future would deliver nothing and re-arm forever, while planning only the
past would never wake again.

⚠⚠ Its catch-up window is **5 minutes, not RE-D6's 15**. A routine that runs the heating 12 minutes
late still heats the house; a reminder for a meeting that started 12 minutes ago is worse than
useless — it reports something already missed and teaches you to distrust the ones that arrive on
time.

**Deferred to CA-5c-2** (and this is a scope reduction, stated rather than hidden): the `Punctual` +
`HeadlessSafe` op, the `RoutineScheduleArmer`-shaped armer, and Q6's contextual
`SCHEDULE_EXACT_ALARM` request. Two reasons. The wiring fix changes the foundation those sit on and
deserves to land reviewable on its own; and all three are **entirely unverifiable in this
environment**, so stacking them on freshly-wired, never-device-tested plumbing compounds risk that a
device pass would resolve in minutes.

⭐ The survey that found the wiring gap also settled *how* the armer will reach the presenter: the
`PlatformScheduler` shape — a constructor parameter on
`calendarServiceProxyImplementationFactory`, defaulting to `NoopNotificationPresenter`, with
`AndroidNotificationPresenter(androidContext())` bound per platform. ⚠ **Not** `TContext.components`:
`docs/agent.md` §"Job B" rules against growing that map, and the factory closure already has Koin
reach.

### CA-5c-2 — the wake, the armer, and Q6

The three pieces CA-5c-1 deferred, over the now-wired proxy.

**`DeliverDueReminders`** — `Punctual` + `HeadlessSafe` + `IoOperation` + `TerminalOperation`,
**parameter-less** because a `ScheduleItem` carries an `opId` and nothing else. The op finds its own
work when it lands, which is why `ReminderPlanner` takes a *window*. ⚠ `HeadlessSafe` is not
decoration: `ScheduleImplementation` rejects a punctual op without it at **registration**, so
omitting it fails loudly at arming rather than quietly at wake.

⚠⚠ **Delivering and re-arming are one call, and they are not independent.** Delivering without
re-arming means exactly one reminder ever fires; re-arming without delivering means none do. That
is why `ReminderDrainCalculator` answers both from one pass. ⚠ The plan window opens *before* now by
the catch-up allowance so a late wake still finds what it was armed for, and closes a week out —
far enough to reliably find a next wake, near enough that re-planning on every edit does not walk a
year of recurrences.

⚠⚠ **The occurrence window is wider than the alert window, by the largest offset any reminder
uses.** An alert due today can belong to an event next week, so resolving occurrences only over the
alert window would miss it — invisibly, as a reminder that simply never arrives. A fixed margin
breaks the moment someone sets "1 week before"; deriving it from the data cannot.

**`ReminderArmer`** is the `RoutineScheduleArmer` shape — one `ONE_SHOT` at the earliest upcoming
reminder under a stable `dedupKey`, through the `Schedule` op so it inherits that op's fail-closed
check. ⚠⚠ Empty `ConstraintSpec`: idle+charging is the *compaction* class, and a reminder that only
fires on the charger is not a reminder.

**⭐⭐ `ReminderRearmObserver` is the piece worth arguing about.** It is a `DataObserver`, not a hook
on the write ops — because **a reminder created on the laptop must arm an alarm on the phone**, and
that row arrives by *sync*, not by a local call. Hooking the seven write ops would have covered
precisely the case that needs no help and missed the one that does. ⚠⚠ It is also **coalesced**:
every re-arm re-plans the whole calendar, so re-arming per row would make a first sync of N events
O(N²). A conflated channel plus a short debounce turns any burst into one plan.

**Q6 — `ExactAlarmPermission`** (in `:app-notify`, since it is a platform fact rather than a
Calendar one). ⚠⚠ Nothing in the repo asked for this before: `PunctualAlarmScheduler` only ever
*read* `canScheduleExactAlarms()` and silently degraded to an inexact alarm — correct as a fallback,
invisible as an experience. ⚠ `isRequestable()` is deliberately distinct from `isGranted()`
returning true: below Android 12 the permission does not exist, and those two look identical at the
API while meaning opposite things in a UI. Per §4.4 the request stays `SCHEDULE_EXACT_ALARM` asked
**contextually** — Control cannot claim `USE_EXACT_ALARM`, whose Play carve-out belongs to the
calendar app, not to the proxy that happens to own `AlarmManager`.

**Two corrections to CA-5b, made here:**

1. ⚠ **`CalendarAlerts` was in the wrong module.** CA-5b put it in `:calendar-shared-logic`; the
   **proxy** is what posts a reminder, and it cannot depend on an app module. Moved beside
   `ReminderPlanner`, for the same reason the planner is there.
2. ⚠⚠ **`NotificationPresenter.post`/`cancel` are no longer `suspend`.** Handing a notification to
   the OS is synchronous on every platform, and nothing reads a result. The suspending version
   forced the op to post from a launched scope, which made delivery unordered and **unobservable to
   the op that asked for it** — caught because the end-to-end test saw an empty presenter.

⚠ **Where the notification comes from, and the UX debt it creates.** Control owns `AlarmManager`,
so **Control** posts — the Gap-4 consequence of "Control is the proxy". The alert therefore carries
Control's icon, and a tap opens Control. The payload already addresses the occurrence
(`CalendarAlerts.tapPayload`), so **CA-5e owes the routing** that lands the user in Calendar
instead.

⚠ **Owed on hardware, and unusually large here:** the alarm firing, an OS throttling it, the
notification appearing, the permission screen, and Doze. The tests reach exactly as far as "real
rows in a real store, read by the real op, reaching the presenter with the right text" — including
that a **sensitive** calendar's reminder arrives with no event text and no title in its tap payload.
Everything past the presenter is the on-device pass §9 always said this phase needed.

### CA-5d — macOS pre-arms, and the OS holds the schedule

§4.6(a)'s half: the reminder becomes a `UNCalendarNotificationTrigger` the OS owns, so it **fires
whether or not Control is running at all**. Sleep, logout, an app update and a crash all become the
OS's problem, and none of them can make a reminder late — which is exactly what the v0.3 hand-rolled
desktop alarm could not promise.

**The chain is daemon → tray → OS**, and the middle hop is forced: a JVM daemon cannot post a macOS
notification (`UNUserNotificationCenter` needs a bundle identifier and authorization, which only the
signed Swift agent has). So the proxy *plans* into `PendingAlertSchedule`, a new
`calendarReminders` bridge method serves it, and `ReminderNotifier.swift` reconciles it into
triggers. ⚠ Polling remains the transport — it limits how fast a *change* reaches the OS, **not**
when a reminder fires: an armed trigger is unaffected by the poll interval, and by the daemon being
quit.

**⚠⚠ The op now returns early on a pre-arming platform.** Handing the OS the forward set *and*
arming our own alarm would be two copies of one schedule, drifting apart the moment either was
throttled. `SchedulingNotificationPresenter` is what makes that a branch on a type rather than a
platform check.

**⚠ `UNCalendarNotificationTrigger`, not `UNTimeIntervalNotificationTrigger`.** An interval trigger
counts from *now*, so it drifts across sleep and is wrong the moment the machine suspends. A
calendar trigger is anchored to a wall-clock moment, which is what a reminder means.

**⚠ Cancellation is `removePendingNotificationRequests` only.** A reminder already *delivered* stays
in Notification Center on purpose: the user may not have read it, and silently retracting an alert
they were shown is worse than leaving one for an event that has since moved.

**⚠ An unreachable daemon reconciles nothing.** `nil` from the bridge is distinct from a
successfully empty calendar — treating them alike would cancel every armed reminder each time
Control restarted, which is the one failure this whole design exists to avoid.

#### ⚠⚠ A second correction to the CA-5b seam

`SchedulingNotificationPresenter` was `schedule` + `cancelScheduled` + `scheduledIds`; it is now a
single `replaceScheduled(alerts)`. Two things were wrong with the first shape, and building the
consumer is what exposed them: `scheduledIds()` had to be `suspend` (Apple's pending-request query
is asynchronous) and a **synchronous op cannot call it**; and it put the diff in the *caller*, which
is the one place that does not know what the OS is actually holding — that set outlives our process.
Handing over the desired set puts `AlertReconciler` where the truth is, in the implementation.
⚠ That is twice this interface has moved in two days; it has no other consumers yet, which is
precisely when it is cheap.

#### ⚠ Two tests that passed for the wrong reason

Worth recording because the mutation check is what caught it, not review. Both pre-arm tests were
green *and* survived their mutations:

- "not handed reminders in the past" used an event 2 minutes out with a 15-minute reminder — due 13
  minutes ago, which is **outside the planned window entirely**, so the filter under test never
  ran. Now 12 minutes out (due 3 minutes ago, inside the catch-up window), so the reminder is really
  in the plan and really has to be filtered.
- The fake scheduling presenter returned `canPost() == false`, which made the pre-arm branch and the
  post-and-arm branch indistinguishable — both post nothing. It now returns `true`, so deleting the
  early return fails.

⚠ **Owed on hardware, and this phase's list is the longest yet:** the trigger actually firing with
Control quit; whether the **64-pending cap applies on macOS** (§4.6 said assume it does until
checked — the planner's limit already respects it, so nothing changes structurally either way); the
signed-`.app` path lighting up (`notificationsAvailable` is false for a bare `swiftc` binary by
design); and a lid-close/resume cycle. ⚠ None of the Swift compiles in this environment, so
`ReminderNotifier.swift` is **unbuilt** — the same standing debt as the iOS actuals.

⚠ **iOS is still `Noop`.** The same `SchedulingNotificationPresenter` shape fits it exactly — the
API is identical — but it belongs with an iOS Calendar shell, which does not exist (CA-8).

### CA-5e — reminders in the editor, and the default that was never read

The editor half of CA-5e, plus the first consumer of the two reminder preferences CA-5a declared.
**Scope reduced, and the reduction is stated below rather than hidden** — §9 listed three things
here and this slice ships one of them well.

**The editor.** `EventEditorUiState.reminderMinutes` holds the list, `toggleReminder` edits it, and
`ReminderField` renders it as a `FlowRow` of `FilterChip`s from `ReminderPresets`.

⚠⚠ **Two preset lists, not one with a flag.** A timed event alerts *before it starts*; an all-day
event starts at 00:00, so the same "10 minutes before" fires at 23:50 the night before. That is why
every stock calendar switches to a *time of day* the moment you tick all-day, and it is why
`ReminderPresets.TIMED` and `ALL_DAY` are separate lists with separate labellers — rendering `540`
as "540 minutes before" would be true and unreadable.

⚠ **The chip row shows anything already chosen, even off-list.** An offset set on another device or
by Mia has to be visible and removable here; an offered-only list would silently hide a reminder the
user could then neither see nor delete.

⚠ **Reminders are written *after* the event, with the id the save returned.** A create has no id
until then, so a reminder filed against a blank one is orphaned by construction. ⚠ And a
**`THIS_OCCURRENCE` edit does not touch them at all** — a reminder belongs to the series, so writing
them from a screen that promised to change one occurrence would change every one.

**`setReminders` diffs; it does not clear and rewrite.** ⚠⚠ Deleting a reminder cascades to its
`ReminderState` rows and the key is content-derived, so a clear-and-rewrite would bring an
*identical* reminder back with the user's dismissal silently discarded. The test
`rewriting_an_unchanged_reminder_does_not_discard_its_dismissal` is the one that fails if this is
ever simplified.

#### ⚠⚠ The CA-5a defaults were declared and read by nothing

`DefaultReminderMinutes` (10) and `DefaultAllDayReminderMinutes` (-540) had no reader anywhere in
the tree, so every new event was created with **no reminder at all** — the preferences existed and
did nothing. `CalendarRepository.defaultReminderMinutes(allDay)` is now that reader, and the editor
pre-selects it for a create.

⚠ **The sentinel is asymmetric, and that is a decision.** For a *timed* event any negative offset is
meaningless ("10 minutes after it started" is not a warning), so anything negative reads as off. For
an *all-day* event negative **is** the normal case — minutes after midnight — so only the explicit
`-1` is off. That costs the all-day value 00:01, which no picker offers and is the cheapest thing to
spend. ⚠ Zero could never be the sentinel: "when it starts" is a real choice.

⚠⚠ **Ticking all-day re-reads the default, and `reminderChoiceIsUsers` is what makes that safe.** A
default may be replaced silently; a choice may not. Without the flag the swap would either strand a
23:50 alert on a midnight event or throw away reminders the user had just picked, and there is no
third behaviour that is right for both. The flag is deliberately **not** in the UI state — it is not
something a screen renders, and exposing it would invite a screen to set it. `load()` claims it
*before* its own read starts, so an `init` default already in flight cannot land on an edit.

⚠ **One guard, checked twice, and no decorative second one.** An `isEditing` check inside
`applyDefaultReminder` would read as a safeguard and be unreachable — worse than none, because the
next person would trust it. `reminderChoiceIsUsers` covers every case; the re-check *inside* the
`update` is the round-trip race.

#### ⚠ Four mutations survived the first pass

Recorded because review would not have caught any of them:

- **The staleness re-check was untested.** The race test held one gate for *both* reads, so the
  fresh answer landed last regardless and the test passed with the check deleted. Per-kind gates
  fixed it: only the timed read is held, so the stale answer resumes **after** the fresh one.
- **The early `reminderChoiceIsUsers` return was untested** — the in-`update` re-check catches the
  same case, which makes the early return a pure optimisation (it skips an IPC round trip whose
  answer is guaranteed to be discarded). Pinned rather than deleted, since that round trip would
  otherwise be issued on every all-day tap for the rest of the session.
- **`defaultReminderMinutes` had no repository test at all**, so ignoring `allDay`, dropping the
  negative rule and ignoring the stored value all survived. Four tests now cover it.

All nine mutations are killed as of this slice.

#### ⚠⚠ Deferred to CA-5f, with reasons

§9's CA-5e listed "reminder rows in the editor, snooze and dismiss actions, and the per-calendar
*sensitive* toggle", and CA-5c-2 added the tap routing. Two of those four ship here (the rows, and
the defaults that turned out to be missing). The rest, and why:

- **The per-calendar `sensitive` toggle** needs a **calendar-management screen that does not
  exist** — nothing in Calendar lets the user add, rename, recolour or delete a calendar, and
  `setCalendarVisible` has been on the view model since CA-2 with no UI either. ⚠ Everything *below*
  the toggle is built and correct: the field is on the row, `SaveCalendar` accepts it, and
  `planReminders` already passes `sensitiveCalendarIds` into the planner, which redacts. What is
  missing is one screen, and it is a screen — not a checkbox to be smuggled into the editor.
- **Snooze and dismiss notification actions.** `dismissReminder`/`snoozeReminder` land on the
  repository in this slice, but the *actions* are Android notification actions handled by a
  `BroadcastReceiver` **in Control** — Control posts, because Control owns `AlarmManager` — and that
  receiver reaches the proxy directly rather than through this client seam. Different path, Android
  only, and unverifiable here.
- **Tap routing into Calendar.** Same reason: the tap currently opens Control, because Control is
  what posted. Routing it needs a cross-app intent carrying `CalendarAlerts.tapPayload` plus
  handling on Calendar's side.

⚠ **A reminder you cannot dismiss from the notification is half a feature**, so CA-5f is not
optional polish — it is the rest of CA-5. It is a coherent slice on its own: *the notification
becomes interactive*, plus the calendars screen that the `sensitive` toggle and the CA-2 visibility
toggle both need.

⚠ **Nothing here changes a stored shape** — `Reminder`, `ReminderState` and `Calendar` are untouched
— so no service version moves, no migration is owed and HR#9 is not triggered. No relay redeploy, no
threat-model delta (the redaction path CA-5b amended A5 for is unchanged; this slice only leaves its
switch un-exposed).

⚠ **Owed on hardware:** the chip row's wrap behaviour on a narrow phone, and whether the all-day
labels read correctly in a real editor. Neither is testable here.

### CA-5f — the calendars screen, and a notification you can act on

The rest of CA-5. Two independent halves, and they are worth reading separately: the calendars
screen is fully covered by tests here, and the interactive notification is almost entirely not
(it is `AlarmManager`, `BroadcastReceiver` and cross-app `PendingIntent`s).

#### The calendars screen — where two orphaned toggles finally live

⚠⚠ **Both of this screen's switches were built and unreachable.** The per-calendar `sensitive`
flag (Q10) has been on the row, accepted by `SaveCalendar` and honoured by `ReminderPlanner`
since CA-5a, with no way for a user to set it. `setCalendarVisible` has been on
`CalendarViewModel` since **CA-2** with no UI at all. Neither needed new plumbing; both needed a
place to be, and that place is a calendar list with add, rename, recolour and delete on it.

⚠ **The two look alike and are not.** Visibility is device-local view state; sensitivity is a
synced property of the data. The row says *"Show on this device"*, the sheet says *"Hide details
on the lock screen"* and explains what happens — naming the scope is the only thing keeping them
apart at a glance.

⚠⚠ **The last calendar cannot be deleted**, and the affordance is *absent* rather than disabled.
`DeleteCalendarImplementation` cascades to every event, so deleting the only one destroys the
user's whole calendar and leaves `ensureDefaultCalendar` to re-seed an empty "Personal" on the
next launch — data loss dressed up as a fresh start.

⚠⚠ **Deleting the default re-points it**, in the repository rather than the screen. Left
dangling, every editor-less create — Mia's path — fails with "no default calendar", raised from a
screen that never mentioned defaults. ⚠ Deleting the *last* calendar leaves it blank on purpose:
`ensureDefaultCalendar` re-seeds on the next launch and inventing one here would race it.

⚠⚠ **A duplicate name is refused, not merged.** A new calendar's id is derived from its name (the
`newEventId` precedent), so two devices creating "Travel" offline converge on one row — right for
that case, and silently destructive for someone deliberately making a second "Work", where the
save would replace the first. The refusal is in the view model, where there is a name to complain
about; the convergence is in the repository, where it belongs.

⚠ **A fixed palette, not a colour picker.** A calendar colour's whole job is to be
distinguishable at a glance in a dense grid, and an arbitrary picker lets a user choose two
near-identical shades. A new calendar takes the first unused colour; an exhausted palette falls
back rather than refusing a ninth calendar.

⚠ **"Make default" is on this screen** because deleting a calendar can move the default and the
user has to be able to move it back. It is the first UI for `DefaultCalendarId` (§6.4/Q14), which
until now only `ensureDefaultCalendar` ever wrote.

#### The notification's buttons, and where the tap lands

⚠⚠ **Snooze and Dismiss close a loop open since CA-5a.** `ReminderPlanner` has honoured
`ReminderState` from the first slice and **nothing anywhere could write one** — a reminder swiped
away wrote nothing and came back on the next re-arm.

**The seam.** `AppAlert` gains `actions: List<AlertAction>` and `targetApp`, `:app-notify` gains a
generic `AlertActionHandler` + `CompositeAlertActionHandler` + an app-agnostic
`AlertActionReceiver` (the `PunctualAlarmReceiver` shape: a receiver in a platform module,
declared in whichever app's manifest hosts the process). Calendar's handler is
`ReminderActionHandler`, reached through a `CalendarReminderActionHolder` that
`initCalendarService` fills — the `ScheduledOpDispatcherHolder` pattern, for the same reason: the
handler needs an `ExecutionContext` that does not exist when Koin is assembled, and the consumer
is framework-instantiated.

⚠⚠ **The write runs in Control, not in Calendar.** An action's whole value is that it resolves
without opening anything, and Control is both the process that posted and the one hosting the
proxy — so the handler calls `SetReminderState`'s implementation directly rather than going out to
a client and back through a cold app.

⚠⚠ **No re-arm call anywhere in the action path.** Writing a `ReminderState` is exactly what
`ReminderRearmObserver` already watches, so the next wake — including a snooze's own re-alert — is
recomputed by machinery that predates this slice. Arming here as well would double-plan on every
press.

⚠⚠ **Every reminder on the occurrence is written, not just the one that rang.** An event can
carry "10 minutes before" *and* "1 hour before"; the notification names the occurrence, not which
fired. Dismissing means "I have seen this" — writing one would let the other arrive minutes later.

⚠ **Snooze is ten minutes, fixed.** Android shows at most three actions and routinely collapses
the third, so a duration picker would cost the *Dismiss* button — and a snooze you cannot dismiss
is worse than a snooze you cannot tune.

⚠ **The alert is cancelled *after* the handler succeeds.** Dismissing first would make a failed
write look like it worked, and "snooze" would silently become "the reminder is gone".

⚠ `goAsync()`, with an 8 s bound inside the receiver's ~10 s budget: without it the process can be
killed the moment `onReceive` returns and the write never lands; without the bound, a hung handler
is an ANR.

**The tap.** ⚠⚠ `AndroidNotificationPresenter` resolved the launcher of `context.packageName`, so
every Calendar reminder tapped through to **Control**, which has no idea what an occurrence is.
`AppAlert.targetApp` names the app; blank keeps the old behaviour, and platforms with no separate
app processes ignore it. ⚠⚠ This needs `<queries>` in **Control's** manifest — without it
`getLaunchIntentForPackage` returns null on API 30+ and the tap *silently does nothing*, with no
error. ⚠ An unresolvable target yields **no tap intent at all** rather than falling back to our own
launcher: landing the user in the wrong app with no explanation is worse than a dead tap.

⚠ The payload still carries **ids and no text** (CA-5b's Q10 defence), so the app has to resolve
them — `ReminderDeepLink` + `resolveReminderLink`. ⚠ It searches a **window** around the anchor
rather than that one day, because a `recurrenceId` names the occurrence's *original* slot and an
override can have moved it. ⚠⚠ And it passes `includeHidden = true`: a reminder was armed, so the
user asked to be told, and refusing to open it because the calendar is hidden on *this* device
would make the notification unactionable.

⚠ The intent extra key moved to `AlertIntents` in **commonMain**. It is contract, not
implementation: the process that writes it and the process that reads it are deliberately
different apps, so a private constant on the Android presenter was a string duplicated across an
app boundary with nothing to catch a typo — and the symptom of a typo is a tap that does nothing.

⚠ `CalendarAlerts.ANDROID_PACKAGE` is an Android application id in a platform-neutral module,
deliberately. Every other platform ignores the string, and the alternative — a
payload-prefix→package map in Control's DI — hides Calendar's own identity in an app that should
not need to know it. Nothing enforces the match but the smoke gate, where a tap landing in Control
is the visible symptom.

#### ⚠⚠ Calendar joins the Android smoke gate

`scripts/android-smoke.sh` now builds, installs and launches Calendar alongside Control, Drive,
Maps and Home. **Its absence is precisely why CA-5c-1 found four phases of Calendar code that had
never been wired into Control** — no schema modules, no implementation factory, no
`initCalendarService`. Unit-green throughout, and it could not have worked on a device.

#### Verification, and what is not verified

Covered here: the management view model (21), the repository's calendar management (7), the deep
link (8), the action handler and its wiring (8), the composite handler (4). Mutation-checked: 9
mutations on the action handler and the holder, all killed — including "unknown action falls
through", "only the first reminder is written" and "an unfilled holder claims the press".

⚠ **Not covered, and structurally so:** the buttons appearing on a real notification; the
`PendingIntent` distinctness that keeps Snooze and Dismiss from collapsing into one intent (the
data-URI trick, `PunctualAlarmScheduler`'s trap verbatim); `goAsync()` surviving a process kill;
the cross-package launch actually resolving; and the tap landing in Calendar rather than Control.
Every one of those is framework behaviour on a device. The manifest merge is checked (the receiver
and the `<queries>` entry both survive into Control's merged manifest) and both APKs assemble, but
**no emulator is reachable from this environment**, so HR#8 remains owed on this PR as on the
five before it.

⚠ **No stored shape changes** — `Calendar`, `Reminder` and `ReminderState` are untouched, and
`AppAlert`/`AlertAction` are plain data classes that never cross the wire — so no service version
moves, no migration is owed and HR#9 is not triggered. No relay redeploy. ⚠ No threat-model delta:
CA-5b's A5 amendment already covers reminder text on a lock screen, and this slice only makes its
`sensitive` switch reachable. The tap payload's ids-not-text rule is unchanged and is now
load-bearing in a second place.

⚠ **Owed on hardware:** the whole notification half above; the calendars screen's sheet and
palette on a narrow phone; and a delete actually cascading across two devices.

## CA-6 — ICS import and export

### CA-6a — the format, and the sidecar's missing writer

The migration story's engine: a bounded iCalendar codec, `EventSource`'s first writer, and
import/export through storage. **The file picker and the UI are CA-6b** — stated here rather than
discovered later; the format carries the risk and is fully testable, the picker is two platform
actuals and a sheet.

#### ⚠⚠ `EventSource` had a reader, a cascade-delete and no writer

CA-0 shipped the type, its `DataIdProvider`, its DTO, its Ro and its service relation;
`DeleteEventImplementation` has cascaded to it since CA-1. **Nothing anywhere could create a
row.** That is the third piece of Calendar found built-and-unreachable — after Q10's `sensitive`
flag and CA-2's `setCalendarVisible`, both closed in CA-5f — and it is the one whose absence
would have made the whole D1 decision decorative. `SaveEventSource` lands here rather than in
CA-0 because a sidecar with no importer has nothing to hold. Its `Entity<out EventSource>`
accessors were missing for the same reason: nothing had cause to read a row nothing could write.

#### The codec

⭐ `IcsContentLine.kt` is a near-twin of `:contact-service-common`'s `VCard.kt`, and deliberately
so — vCard and iCalendar share the content-line layer exactly (`NAME;PARAM=v:value`, folding with
a leading space, the same backslash escapes). Written rather than depended on for the same reason
the maintainer settled the vCard question on 2026-06-11: every library here is JVM-only, and
platform import code is needed regardless.

⚠⚠ **`IcsProperty.value` holds the value still escaped, exactly as it arrived.** That is what
makes a round trip byte-exact for every property nobody edited. Decoding happens only when a value
becomes a native field.

⚠ **No `QUOTED-PRINTABLE`** — that is vCard 2.1's, and iCalendar has never had it. Carrying the
decoder over would be dead code that looks load-bearing.

⚠ Components **nest**: a flat property list would fold a `VALARM`'s `TRIGGER` in beside the
event's own properties, where it would look like the event had one.

⚠ Folding counts **characters, not octets**, against the letter of RFC 5545. Splitting on an octet
boundary can cut a UTF-8 sequence in half and corrupt the value; folding a little late is
something every parser copes with. The vCard codec made the same call.

⚠ Tolerant throughout, because real exports are: an unterminated component closes at end of input,
a stray `END` never pops the root, a line with no colon is skipped, and a payload with no
`BEGIN:VCALENDAR` still yields its events. A single bad line must cost only itself.

#### Import decisions

⚠⚠ **All-day is `VALUE=DATE`, never a guess from a midnight time.** A timed event that genuinely
starts at 00:00 is a real thing.

⚠⚠ **The alarm sign flips.** iCalendar writes "10 minutes before" as `-PT10M`; `minutesBefore` is
positive for before. Reading it backwards fires every reminder *after* the event, which a user
reports as "reminders don't work" rather than as a sign bug.

⚠⚠ **A series and its exceptions share one `UID`** and differ only by `RECURRENCE-ID`. Treating
them all as series makes the last one win and silently discards the recurrence.

⚠ `EXDATE` appears both repeated *and* comma-separated in the wild; handling one form drops
exceptions silently, which surfaces as deleted occurrences coming back.

⚠ A blank `endLocal` is left blank rather than invented — `EventTime.parse` already implies the
right end per kind, and guessing here would put a second, disagreeing rule in the codebase.

⚠⚠ **A `VEVENT` with no usable `DTSTART` is counted, not dropped silently.** An import that says
"42 events" when the file held 44 is how a user loses two appointments and never finds out.

⚠ A trailing `Z` is read as the civil value and stored as **floating**, not converted. A real
conversion needs a zone database this layer does not have; the raw sidecar is what makes it
recoverable.

#### Export — where D1's promise is kept or broken

⚠⚠ **The export re-emits the stored `VEVENT` with our fields written over it**, rather than
regenerating from scratch. `ATTACH`, `ORGANIZER`, `ATTENDEE`, `X-` extensions and alarm shapes we
never modelled come back out. Regenerating would be simpler, would pass every test that checks
only our own fields, and would quietly make the sidecar pointless — which is why the round-trip
tests are the ones that matter.

⚠ **Overwrite in place, keep position, append only what is new.** Rebuilding the property list
would drop everything not named, which is the failure the merge exists to avoid. ⚠ A field
*cleared* in the app is removed from the export too — the other half of "native fields win".

⚠⚠ **`DURATION` is owned only when we write a `DTEND`.** RFC 5545 forbids both on one event, so it
must be stripped when we emit an end — but stripping it when we *cannot* would export an event
with no length at all.

⚠ Floating means **no `TZID` and no trailing `Z`**; stamping `Z` on it would move the event by the
reader's offset. ⚠ An `RRULE` value is never escaped — escaping its `;` produces a rule no parser
can read. ⚠ A `VALARM` we could not map rides through unchanged; our own offsets replace only the
relative ones.

#### Import through storage

⚠⚠ **Idempotent by `UID`**, which is what makes "import it again after I fixed something" safe
rather than a way to end up with two of everything. A block with no `UID` falls back to a content
hash for the same reason — a random id would duplicate on every import.

⚠⚠ **Series first, overrides second, in two passes.** A file routinely lists an exception *before*
the series it belongs to, and an override written first would leave `saveEvent`'s
`existingExceptionDates` read seeing no series — silently dropping the file's `EXDATE`s.

⚠ Reminders are **set**, not added: a re-import converges on what the file says rather than
accumulating the union of every import.

⚠ Overrides export as their own `VEVENT`s carrying the series' `UID` and a `RECURRENCE-ID`.
Omitting them would be lossy in a way the sidecar cannot rescue — an override is *our* row and has
no raw of its own. ⚠ Its absent fields inherit from the series: absent means "unchanged", and
writing `""` would export a blank title for an occurrence that merely moved.

#### ⚠⚠ HR#9 — `CalendarService` 2 → 3, for an added *operation*

No composite was reshaped; `SchemaShapeBaseline` confirms it (one added line, every other line
identical once the version is normalised away). The bump is still owed: `SaveEventSource` is a new
op id, and an old Control that does not know it would fail a user's first import with a cryptic
`Operation not found` — **exactly the HR#9 failure class**. Bumping routes it through the announce
gate instead, as a typed *update Control* (`core-schema-evolution.md` §6.8).

⚠ And the **empty `fromVersion = 2` step is mandatory**: a bumped service with no registered chain
sits below its own derived floor and the store-open gate *wipes* rather than migrates. The CA-5
v1→v2 precedent, for the same reason. ⚠ No `TENSHU_STORAGE_FORMAT_VERSION` change, so no store
wipe and no relay redeploy.

#### Verification

Codec 40, round trip through storage 19. **Mutation-checked: 22, all killed** — the sign flip,
all-day-from-`VALUE`, `DURATION` both ways, repeated `EXDATE`, `RECURRENCE-ID`, regenerate-instead-
of-merge, stale-field, dropped unmappable alarms, floating-as-UTC, override-before-series,
UID-keyed ids, accumulating reminders, dropped overrides, the calendar filter, and the sidecar
write.

⚠ **One mutation survived the first pass and the fix was a test, not the code.** Deleting the
import's `VALUE=DATE-TIME` guard changed nothing, because the fixture's absolute value
(`20260803T080000Z`) is rejected by `parseIcsDuration` anyway — so the guard looked decorative. It
is not: the `VALUE` parameter is the *declaration* of what the value is and wins over the value's
shape, and a file whose two contradict is exactly where trusting the shape fires a reminder a day
late. A fixture with `TRIGGER;VALUE=DATE-TIME:-P1D` now pins it, and a second pass found the
export side's `RELATED=END` branch unexercised for the same reason.

⚠ **Not verified:** nothing has read a real `.ics` from a real calendar app. The fixtures are
hand-written to the spec and to the shapes Google/Apple/Outlook are documented to emit; a genuine
export from each is a CA-6b task, once there is a picker to feed one in.

⚠ **Deferred to CA-6b:** the file picker and saver (the `ContactImporter` seam in
`:contacts-shared-ui` is the template — an `expect`/`actual` obtained `@Composable` so the Android
actual can hold `ActivityResult` launchers), the import sheet that reports `skipped`, and the
export share/save. No threat-model delta here; CA-6b's is worth a look, since an `.ics` written to
shared storage is plaintext calendar data leaving the app (the A5 shape `drive-content-preview`
D3 already amended for).

### CA-6b — the file in and out

The other half of CA-6: a picker, a saver, and the flow between them. **CA-6 is complete.**

#### The seam

`CalendarFileAccess` is modelled on `:contacts-shared-ui`'s `ContactImporter` — a
`@Composable`-obtained `expect`/`actual`, because the Android actual has to hold `ActivityResult`
launchers and those may only be registered during composition.

⚠⚠ **It has a *save* half, which `ContactImporter` does not.** Contacts only ever imports; a
calendar is something people take *out*, and that is half of what "the migration story" means. It
is also the direction that puts plaintext calendar data outside the app — see the threat-model
amendment below.

⚠ **The Storage Access Framework, not a storage permission.** `OpenDocument` and `CreateDocument`
hand back a URI the user chose, with a grant scoped to that one file, so Calendar declares **no**
storage permission at all and never asks for one. Reading a `.ics` the other way would need
`READ_EXTERNAL_STORAGE` and would put every file on the device in reach for the sake of one.

⚠ `"wt"`, not `"w"`, on the Android write. Without the truncate flag, overwriting a longer file
leaves the old tail behind — the export ends mid-`VEVENT` and reads as a *corrupt* calendar rather
than a shorter one.

⚠ The desktop dialog is owner-less, so it is not modal to the app window. The `ContactImporter`
desktop actual made the same call and for the same reason: the seam is obtained from an arbitrary
composable and has no `FrameWindowScope` to read a window from.

⚠ **No iOS actual.** `:calendar-shared-ui` is android + desktop only; an iOS Calendar shell does
not exist (CA-8), and its picker would be `UIDocumentPicker` rather than either of these.

#### The flow

`CalendarInteropViewModel` **knows nothing about pickers** — the platform hands it `.ics` *text*
and takes text back, and everything either side is CA-6a's repository seam. That split is what
makes CA-6b testable at all: the parts that can only run on a device contain no logic beyond moving
bytes.

⚠⚠ **Export produces the text *before* the save dialog opens.** A failure to read the calendar has
to be an error message, not a picker that appears and then writes nothing. The callback shape
(rather than a return value) is what lets the caller be a `@Composable` holding a launcher.

⚠⚠ **Zero events out of a non-empty file is a failure, not a quiet success** — the file had content
and none of it landed. ⚠ But *skipped* entries are **not** a failure: some landed, and the count
rides along in the message. That is the whole reason `IcsImportResult.skipped` exists, and the
sheet is where it finally becomes visible.

⚠ Cancelling is a decision, not a failure: no message, back to the two choices.

#### Where it lives

Reached from the **calendars screen**, per row, rather than from a settings screen where a stock app
puts it. Calendar has no settings screen — and more to the point, *which calendar* is the question
both actions need answered, and opening from a row answers it before the sheet appears.

⚠ Delete moved into the same per-row overflow when import/export arrived. Three actions is where a
row of icon buttons starts competing with the row's own tap target.

#### ⚠ A bug the tests found, and one they nearly missed

`suggestedFileName` mapped every non-alphanumeric to `-` one for one, so "Work & Life" became
`work---life.ics` — in the file name of every export a user would ever make. Runs collapse now.

⚠⚠ And one mutation survived the first pass: `dismiss()` could have kept the whole last result and
the test would still have passed, because it dismissed and then called `open`, which resets
everything itself. The assertion now looks at the state **after `dismiss()`**. Same lesson as
CA-6a's `VALUE=DATE-TIME` guard and CA-5d's two pre-arm tests — a green test through a path that
never exercises the thing under test.

#### ⚠⚠ Threat model: A5 amended (HR#2)

An export writes a **plaintext `.ics`** — every title, location, note, time and recurrence, plus
whatever the `EventSource` sidecar preserved — to a destination outside the app's storage and
outside Tenshu's control. Same class as `drive-content-preview` D3's *open in another app*, scoped
the same way (user-initiated, one file, never automatic).

**Two deltas beyond D3, and they are why this needed its own amendment:** an export discloses a
**whole calendar at once** rather than one file already open, and it is **durable** — there is no
`onCleared` that deletes it. It is also the first surface where `Calendar.sensitive` does **not**
apply: the flag redacts *reminders*, and an export is the user deliberately asking for the full
text. `sensitive` must therefore not be read as "this calendar never leaves in the clear".

Relay exposure unchanged (HR#1): nothing reaches the relay, no key material is involved, the rows
were already decrypted client-side. **Not yet closed:** no warning before exporting a calendar
marked sensitive, and no export-to-encrypted-archive option — both UI, not protocol.

#### Verification

14 view-model tests, **14 mutations all killed** (zero-import-as-success, skipped-as-failure, both
sheet-shut guards, export-saved-before-the-picker, a failing read still opening the picker, cancel
as failure, dismiss retaining state, acknowledge closing the sheet, the two slug bugs, the busy
state). Both Calendar shells build; the full gate is green.

⚠ **Not verified, and structurally so:** the pickers themselves. `OpenDocument`, `CreateDocument`,
the `ContentResolver` streams and the AWT `FileDialog` are framework behaviour on a device — which
is precisely why the seam was drawn to leave them logic-free. And **still nothing has read a real
`.ics` from a real calendar app**: the picker now exists to feed one in, but doing so is a
maintainer action, not something this environment can perform. That is the single highest-value
manual check outstanding on the whole of CA-6.

⚠ No stored shape changes and no new ops ⇒ **HR#9 untriggered**, no service version moves, no relay
redeploy. ⚠ `androidMain` changed, so **HR#8 is owed** — still no emulator here.

### CA-6c — the file the OS hands over, and `.ics` on iOS

Two ends of the same gap CA-6b left: the desktop app was not something macOS would *offer* for a
`.ics`, and iOS had no picker at all. **Both shipped together** because they share the one piece of
new shared state — the sheet learning to ask "which calendar?" *last*.

#### The two entry orders

⚠⚠ **This is the whole design.** Every in-app path answers "which calendar?" first, by being
reached from that calendar's row. A file from outside the app answers it **last**, because the OS
knows nothing about calendars. `CalendarInteropUiState` grew `pendingImportText` +
`pendingImportOrigin` and the derived `choosingCalendar` so that one flow serves both, rather than a
second sheet existing for the OS-delivered case.

⚠⚠ **Nothing is written until the user names a calendar.** A file arriving from outside is not
consent to put its contents somewhere in particular, and there is no defensible guess: the default
calendar is where *this device's* new events go, which says nothing about where a stranger's file
belongs. It is also the unrecoverable direction — the wrong calendar means hand-deleting every
event in the file. `chooseCalendar` clears the pending text *as it starts the import*, so a
double-tap cannot import the same file into two calendars (a test pins this).

⚠ No "create a new calendar" row in the chooser, even though `X-WR-CALNAME` is already parsed and
would name one. Creating a calendar is a synced write to every device; making it a side effect of
opening a file is not what the user asked for, and it would be the easiest row to hit by accident.

⚠⚠ **Subscribed calendars are filtered out of the chooser, and only there.** A CA-7 refresh replaces
a feed-backed calendar's whole contents, so importing into one loses the events at the next fetch —
a trap worth removing from a list the user picks a destination from blind. The row's own *Import or
export…* still offers them, because there the user named that calendar and export is the point.

⚠ A file the host could not read still **opens the sheet** (`openFailed`). The alternative is a
double-click that launches the app and then appears to do nothing, which reads as "this app cannot
open .ics files" — the opposite of what the file association just claimed.

#### macOS: the association, and the two delivery routes

`:calendar-desktop`'s `nativeDistributions` declares
`fileAssociation("text/calendar", "ics", "iCalendar file")`. jpackage turns that into a
`CFBundleDocumentTypes` entry in the bundled `Info.plist`; Launch Services reads it when the `.app`
is first seen, and only then does *Open With* list Calendar or let a user make it the default.

⚠⚠ **It therefore does nothing for `:calendar-desktop:run`**, which starts a bare JVM and never
builds a bundle. Exercising this means `packageDmg` (or `createDistributable`) and launching the
built `.app` at least once.

⚠⚠ **macOS uses neither obvious delivery route.** Windows and Linux launchers pass the document as
a command-line argument; macOS passes *nothing* on the command line and sends an `odoc` Apple Event,
which the JVM surfaces as `Desktop.setOpenFileHandler`. An implementation reading only `args` would
work everywhere except the platform this was built for. `IcsDocumentOpens` wires both.

⚠⚠ **The handler is registered before `application { }`.** A double-click both launches the app and
fires the event; a handler installed once the window is composing arrives after that event has been
dropped, so the association would work only for a *second* file opened while the app was running.
The `replay = 1` buffer on the flow is the other half — it holds that first file until the
composition is up to collect it.

⚠ `App(openedIcsFiles = …)` takes a `Flow`, not a nullable value, for the same reason: a `String?`
loses the pre-composition file and swallows the same file opened twice as "no change". Arrival also
routes to the calendars screen, because that is where the sheet's chooser gets a list to offer.

#### iOS: no `CalendarFileAccess` actual, deliberately

⚠⚠ The `expect`/`actual` exists because Android's `ActivityResult` launchers may only be registered
during composition. SwiftUI has `.fileImporter` / `.fileExporter` as plain view modifiers and needs
no seam — and `:calendar-shared-ui` does not build for iOS in the first place. The shared half of
CA-6b is `CalendarInteropViewModel`, which knows nothing about pickers and takes `.ics` *text*; the
Swift shell hands it exactly that. `CalendarSurface.calendarInteropViewModel()` is the only new
Kotlin.

⚠⚠ **Nothing is marked busy before `.fileImporter` opens.** SwiftUI is not reliably documented to
report a cancellation on every iOS version this app supports, and a spinner shown on a callback that
may never arrive is a sheet the user cannot leave. The export path is made safe the same way: the
text is produced first (the CA-6b ordering rule), and then the busy flag is cleared *before* the
save dialog is presented, so a dialog that dismisses without reporting anything leaves the sheet on
its choices.

⚠ `com.apple.ical.ics`, not `public.calendar-event`, as the exporter's content type: the abstract
type has no filename extension for SwiftUI to append. `.data` is in the *import* list as the
backstop, for the reason `ICS_MIME_TYPES` gives on Android.

⚠ The security-scoped resource is not optional — a picker URL points outside the app container, and
reading it without the scope open fails on a device while working in a simulator run from Xcode.

#### Verification

11 new view-model tests (24 total), covering both entry orders: a handed-over file writes nothing
until a calendar is chosen, choosing twice imports once, the chooser closes while the write is still
in flight, an empty file says so instead of asking, and an unreadable one still opens the sheet.
`:calendar-shared-logic:jvmTest`, `:calendar-shared-ui:desktopTest` and `:calendar-desktop` are
green.

⚠⚠ **Not verified, and structurally so:** the association itself. Nothing in this environment can
run jpackage for macOS, launch a `.app`, or compile Swift — so *Finder offers Calendar for a `.ics`*
and *the iOS picker returns readable text* are both maintainer checks on hardware.
`:tenshu-sdk-ios` did not compile here either (Kotlin/Native for iOS needs macOS).

⚠ No stored shape changes and no new ops ⇒ **HR#9 untriggered**; no service version moves, no relay
redeploy. ⚠ No `androidMain` file changed, but `:calendar-shared-ui/commonMain` did and Android
renders it, so **HR#8 is owed on the sheet's new chooser branch** — still no emulator here.

### CA-6d — Android's turn: *Open with* and *Share*

The third and last host that can hand Calendar a `.ics`. Nothing new in the flow — CA-6c's
"which calendar?"-last entry order is what receives it — so this is a manifest, an intent read, and
one refactor.

#### The filters, and the wildcard that is not there

`MainActivity` declares two filters: `ACTION_VIEW` (`content:` / `file:`) for *Open with*, and
`ACTION_SEND` for *Share*. Two, because they are two different gestures — declaring only the first
leaves Calendar out of the share sheet, which is where a `.ics` attachment usually is.

⚠⚠ **No `*/*` mimeType, and this is the opposite call to the one the picker makes.** `ICS_MIME_TYPES`
carries a wildcard because a provider that mislabels a `.ics` as `application/octet-stream` would
otherwise make the file unselectable — there, a wildcard only widens what the *user* may pick. In an
intent filter it widens what the OS offers *this app* for, so Calendar would appear in the "open
with" list for every PDF, video and zip on the device. Missing the occasional mislabelling provider
is the better failure: that user still has *Share*, and the in-app picker, both of which accept
anything.

⚠ `text/x-vcalendar` is declared alongside: it is vCalendar 1.0, still emitted by older exporters,
and `IcsImport` reads those blocks fine — refusing the type would reject files the parser handles.
⚠ No `BROWSABLE` category: this handles files, not links, and adding it would let a web page hand
this activity a URI.

⚠⚠ **`onNewIntent` reads it too, not just `onCreate`.** A `.ics` opened while Calendar is already
running reuses the existing instance, so reading only in `onCreate` would make the filter work
exactly once per app launch — the same trap `tapPayload` documents two lines above it.

⚠⚠ **And `onCreate` reads it only when `savedInstanceState == null`.** The mirror-image bug: a
rotation recreates the activity with the *same* launch intent, so an unguarded read re-delivers a
`.ics` the user has already imported, putting the chooser back up over whatever they were doing and
inviting a second import into a second calendar. `setIntent` does not survive recreation, so the
saved-state bundle is the only thing that tells a launch from a rebuild.

⚠ The file name comes from an `OpenableColumns.DISPLAY_NAME` query, not the URI's last path segment:
a `content://` path is routinely an opaque row id, so the obvious spelling would show a number where
the sheet shows a file name and store that number as `EventSource.origin`.

#### `IcsOpenRelay`, and where it lives

The replay buffer CA-6c put in `IcsDocumentOpens` is now a shared `IcsOpenRelay`, because Android
has the identical problem: `onCreate` reads the launch intent *before* `setContent` runs, so there is
no collector yet. The buffering decisions — replay one, drop the oldest, never suspend — are the
part that must not drift between shells; each platform still owns its delivery route and its file
reading, which genuinely differ.

⚠⚠ It sits in **`:calendar-shared-logic`**, not beside `CalendarFileAccess`, and the second reason is
the load-bearing one: **`:calendar-shared-ui` has no test source set at all.** A
`:calendar-shared-ui:desktopTest` invocation succeeds there by running nothing — worth knowing before
citing it as a gate. `:calendar-shared-logic` has `commonTest`, and is also the module iOS consumes,
which is where an *Open in* handler would reach for the same relay.

#### Verification

4 new relay tests (28 in the interop area), all about timing, since timing is all this type does:
a file delivered before anything collects is still received, twenty deliveries neither suspend nor
fail, and the same file opened twice arrives twice — the case a `StateFlow` would swallow as "no
change". `:calendar-shared-logic:jvmTest`, `:calendar-android:assembleDebug` (which is what proves
the manifest merges) and `:calendar-desktop` are green.

⚠⚠ **Not verified:** that Android actually offers Calendar in a chooser, and that a real provider's
`content://` URI reads. Both need a device. This container has an `/opt/android-sdk` with no
emulator, no system image and no `adb`, so **HR#8 is owed** — and CA-6d is exactly the shape of
change the smoke gate exists for, since a manifest filter that never matches fails silently.

⚠ No stored shape changes and no new ops ⇒ **HR#9 untriggered**. No threat-model delta: an
`ACTION_VIEW` grant is scoped to the one URI the user chose, no storage permission is declared, and
the import writes through the ops every other platform uses.

### CA-6e — a file you open should not move you to another screen

Reported on macOS: double-clicking a `.ics` (or *Open With*) switched the app from the calendar to
the **Calendars** screen before showing the import sheet. Confusing, and unnecessary — the sheet
already asks which calendar to import into, which is the only thing that screen would have told
you.

⚠⚠ **The navigation was not decoration, which is why removing it is a refactor and not a deletion.**
`CalendarsScreen` *composed* the interop sheet, so a file arriving over the month grid would have
opened a sheet nothing was drawing. The route change existed to put the sheet's host on screen.

So the sheet moved instead: `App` hosts `CalendarInteropSheet` above the routes, as a modal over
whatever the user is looking at, and the route change is gone. The calendars screen still *starts*
an import or export from a row — it just no longer draws the sheet.

- ⚠ The chooser's list moved with it, from a parameter into `CalendarInteropUiState.importTargets`,
  collected by the view model. A host above the routes has no calendar list to pass, and the sheet
  can now be opened from anywhere without one.
- ⭐ **The subscribed-calendar filter became testable in the move.** It was a `.filter {}` inside a
  Compose file with a paragraph explaining it; importing into a feed-backed calendar loses the
  events at the next refresh, which replaces the calendar's whole contents. It is now a rule in the
  view model with a test on it.
- ⚠⚠ `freshState(...)` replaced four wholesale `CalendarInteropUiState(...)` resets. Resetting is
  right — reopening must not show the last import's result — but `importTargets` is a *live* list
  arriving on its own schedule, and dropping it would empty the chooser for the one flow that needs
  it, until an emission that (for a `StateFlow`-backed read) may never come.
- ⚠ Android gets the same fix for free: CA-6d's *Open with* / *Share* path feeds the same
  `openedIcsFiles` flow, and it navigated too.

## CA-7 — the widget and ICS subscription

### CA-7a — subscription: the engine, the egress, and the consent

Q9's *subscribe to a URL*, end to end through storage — schema, ops, the fetch seam, refresh
semantics and the threat-model amendment. **The UI is CA-7b, alongside the widget** — stated up
front, as in CA-5 and CA-6: the decisions and the risk are all here, and the surface is a dialog
and a menu item.

#### ⚠⚠ A refresh is a *sync*, not an import

This is the decision the whole slice turns on. CA-6's import is **additive** — every `saveEvent`
is an upsert, so re-importing converges but importing a *shorter* file leaves the extra events
behind. A feed is **authoritative**: a fixture cancelled upstream has to disappear. So a refresh
sweeps — anything in the subscribed calendar the fetch did not name is deleted.

⚠ That is only safe because **a subscribed calendar is read-only**: every event in it came from
the feed and none is the user's. The same sweep over an ordinary calendar would destroy their
work, which is exactly why `importIcs` does not do it and `SubscriptionRefresher` does.

⚠ **Read-only is *derived*, not stored.** `Calendar` gains no field — "some subscription points at
this id" is the whole test, and reshaping `Calendar` for a computable flag would cost a real
migration for nothing.

⚠⚠ **A 304 must touch nothing but the timestamp.** Treating *not modified* as an empty calendar
would delete every event the feed holds — the single most destructive way to get this wrong, and
the reason `IcsFetchOutcome.NotModified` is its own case rather than an empty `Fetched`.

⚠ A **failure** leaves `lastFetchedEpochMs` alone, so the UI can say how stale a calendar really
is rather than claiming it just refreshed. ⚠ And one publisher being down must not stop the
others — a feed that throws is caught per-subscription.

#### The egress

`IcsSubscriptionFetcher` is behind an interface for the same reason `RegionDownloader` is: every
decision worth testing lives above the network line. `KtorIcsSubscriptionFetcher` is the real one,
and it **runs in Control** — Calendar's manifest strips `INTERNET` and keeps it stripped.

⚠ **HTTPS only.** `webcal://` is accepted and rewritten (it is what every publisher's *Subscribe*
button emits, and is not a distinct protocol), but plain `http://` is **refused, not upgraded**: a
silent upgrade claims a guarantee we cannot make, and a calendar fetched in the clear discloses
its *contents* to every hop — far stronger than the IP disclosure the user agreed to.

⚠ An `ETag` rides as `If-None-Match` when known. Not an optimisation — a **politeness and privacy**
measure: the common case becomes a 304 that transfers no calendar and tells the publisher strictly
less than a full `GET`. ⚠ No credentials, no cookies. ⚠ 8 MiB cap, so one absurd feed cannot
exhaust the proxy's heap.

#### Consent, and why there is no global toggle

**Creating a subscription is the consent.** There is no ambient behaviour to gate: a user with no
subscriptions makes no requests. Contrast `PlaceGeocodingControl`, which *is* a global switch —
correct there because enrichment happens without a per-item act.

⚠ **Pausing is a distinct affordance from deleting**: `enabled = false` stops the egress and keeps
the events, which is what someone who wants the holidays but not the traffic means. Deleting takes
the calendar with it, deliberately — leaving a frozen copy behind would be a calendar that looks
live and silently never updates again.

⚠⚠ **The row syncs; the fetch does not.** A feed added on the phone appears on the laptop, and each
device fetches for itself — so the publisher sees one IP *per device*. Nominating a single fetcher
would need an election this design does not have. CA-7b's copy owes the user this fact.

#### ⚠ `RefreshSubscriptions` is `HeadlessSafe` but deliberately not `Punctual`

A holiday feed that refreshes an hour late has not failed, and an exact alarm for it would spend
the `SCHEDULE_EXACT_ALARM` grant that *reminders* actually need on something that does not.
Batched maintenance is the honest class. ⚠ It is also `Async`, unlike every other calendar op — it
waits on the network, and a synchronous implementation would block the proxy's dispatch for as
long as a slow publisher takes.

#### ⚠⚠ HR#9 — `CalendarService` 3 → 4, and HR#2

A new composite (`CalendarSubscription`) plus its relation and two new ops. Nothing existing was
reshaped — both baselines confirm it (four added shape lines, one added property line, every other
line identical once the version is normalised away) — and the **empty `fromVersion = 3` step is
still mandatory**, or the bump puts the service below its own derived floor and the store-open gate
wipes instead of migrating.

**Threat model A5 amended.** ⚠⚠ The disclosure runs the *other way* from every prior A5 amendment:
those disclose the user's data outward, this discloses **who and where the user is** to a publisher
who was not previously in the picture — IP, approximate location, that they hold this calendar,
and from the cadence roughly when their devices are awake. Nothing of their own calendar is sent.
HR#1 intact: our relay never sees the fetch. **Not yet closed:** there is no way to subscribe
without revealing the IP (a relay-side fetch would fix it and break HR#1 doing so — it would put a
user's calendar interests on our server), and nothing bounds how many feeds a user may add.

#### Verification

17 tests through real storage; **14 mutations, all killed** — no-sweep, unscoped sweep, 304-as-empty,
304-blanks-etag, failure-stamps-time, paused-fetched-anyway, one-failure-aborts, etag-never-sent,
raw-url-unnormalised, http-upgraded, webcal-not-rewritten, edit-blanks-fetch-state, and both delete
cascades. Control builds on all three platforms; both Android apps assemble; full gate green.

⚠ **Not verified:** no real feed has been fetched. `KtorIcsSubscriptionFetcher` is the one piece
behind the seam and it is untested here by construction — the mock-engine test would prove Ktor
works, not that a publisher answers. ⚠ Nothing schedules a refresh yet either: the op exists and
is `HeadlessSafe`, but registering it with `PlatformScheduler` lands with the UI in CA-7b, so today
a refresh only happens when something calls it.

⚠ **Deferred to CA-7b:** the add/pause/remove UI and its copy (which owes the per-device-IP fact),
the periodic refresh registration, and the Glance **widget** — the other half of CA-7, Android-only
and device-verified.

### CA-7b — subscriptions you can see, and a schedule behind them

The surface over CA-7a's engine: add, pause, refresh, unsubscribe, and the six-hourly registration
that makes a feed stay current without anyone pressing anything.

⚠ **The widget is CA-7c** — stated rather than quietly dropped. It shares nothing with this slice
(Glance, Android-only, a separate module) and is entirely device-verified, so bundling it would
mix a fully-tested unit with an unverifiable one.

#### ⚠⚠ The copy is the consent

The subscribe dialog names three things *before* the button, and each is there because it is not
obvious:

1. **The publisher learns something** — the device's IP, and that the user follows this calendar.
2. **Each device asks for itself.** The subscription row syncs, the fetch does not; a user who
   assumes one device fetches on behalf of the rest has assumed wrong (CA-7a's design note, and
   the threat model's A5 amendment turns on it).
3. **The calendar is read-only** — an update replaces whatever is in it, so anything added by hand
   will not survive.

⚠ Without (1) this would be a network reach with no disclosure; without (3) the read-only sweep
CA-7a built would look like data loss.

#### The schedule

⚠⚠ **Six hours, and the number is a privacy decision as much as a freshness one.** Every fetch
tells the publisher a device is awake and roughly where — so the cadence *is* the disclosure rate.
Holiday feeds change a handful of times a year; hourly polling would multiply the disclosure
twenty-four-fold to learn nothing.

⚠ **Unmetered only** (the `OfflineKeeper` precedent): a holiday feed is not worth cellular data,
and a user who wants it now has *Refresh now*, which calls the op directly and bypasses the
constraint. ⚠ Registration is best-effort with a named log line, because the call site is a bare
`sessionScope.launch` with no exception handler and a maintenance tick must not be able to take
Control down.

⚠⚠ **Registering costs nothing when there are no subscriptions.** The op finds its own work and
does nothing with an empty list — which is what lets "creating a subscription is the consent" stay
true with an always-registered schedule behind it.

⚠ A new subscription **fetches immediately** rather than waiting up to six hours: a calendar that
stays empty after you add it reads as broken.

#### Where it lives

The calendars screen again, and the row is where the subscription surfaces: *Subscribed* or
*Subscription paused* under the name, and *Refresh now* / *Pause updates* / *Unsubscribe* in the
overflow.

⚠ **A subscribed calendar has no plain *Delete*.** Unsubscribing is what removes it, and offering
both would leave a user who picked the wrong one with a live subscription to a calendar that no
longer exists.

⚠ A small secondary FAB rather than a speed dial: subscribing is the rarer action, and a speed dial
would hide the common one behind a tap.

#### ⚠ A duplicated rule, deliberately

`TenshuCalendarRepository.normalizeSubscriptionUrl` restates the proxy's `normalizeIcsUrl` so a
pasted `http://` link is refused **while the user is typing** rather than becoming a subscription
that silently never updates. `:calendar-shared-logic` must not depend on `:calendar-service-proxy`,
and a shared eleven-line constant in `-common` would be worse than the duplication. ⚠ If the two
drift, the symptom is a URL accepted here and refused there — visible, not silent.

⚠⚠ **Mutation found this one undefended**: the repository's copy of the rule had no test at all,
so it could have started accepting `http://` with nothing failing. Six repository tests now cover
it, including the refusals.

#### Verification

11 new view-model tests and 6 repository tests; **9 mutations, all killed** — refused-url-closes-
anyway, no-immediate-fetch, blank-url-reaches-repo, typing-does-not-clear, subscriptions-unkeyed,
pause-deletes-instead, silent-unsubscribe-failure, repo-accepts-http, refresh-now-does-nothing.
Control compiles on all three platforms, both Calendar shells build, gate green.

⚠ One gate run failed on `HomeDeviceControlSystemTest` (a 30 s timeout in `:hub:test`) and passed
on the module alone and on the next full run — the known parallel-run flake, unrelated to Calendar.

⚠ **Still not verified:** no real feed has been fetched, and no schedule has actually fired. Both
need a device. ⚠ `androidMain` changed (Control's DI) ⇒ **HR#8 owed**.

⚠ No schema, wire or version change in this slice ⇒ **HR#9 untriggered**; the threat-model
amendment landed with CA-7a, and the dialog's copy is what that amendment assumed would exist.

### CA-7c — the widget, and the refresh it cannot have

The last of CA-7: a "what's next" home-screen widget over Glance. **CA-7 is complete.**

#### ⚠⚠ `Empty` and `NotConnected` are different states

A widget reads over IPC to Control, and a failed read yields the same empty list a free day
does. Rendering both as *Nothing today* tells a user their calendar is empty when the app simply
cannot see it — and that is the worse of the two lies, because it sends them looking for events
that are not lost. `WidgetAgenda` is a sealed type for exactly this reason, and the widget says
*Can't reach Tenshu Control*.

⚠ This is the third time the same conflation has come up in this codebase: `ios-drive-parity.md`
P1a found it in Drive's file list (empty folder ⇒ eternal spinner), `timeline-ios` P3 found it in
the Timeline shell (mock data shown as real), and here it would have been an empty agenda.

#### The seam

⚠⚠ **Every decision lives in `WidgetAgendaBuilder`, not in the composable.** A Glance widget
cannot be rendered in this environment, so what appears, in what order, what is dimmed and when
the list is empty are all computed on the testable side; the widget draws an answer it did not
compute. The same discipline as CA-6b's file pickers, and for the same reason.

⚠⚠ **An event under way still shows, dimmed.** "Standup, started 10 minutes ago" is the most
useful thing a calendar widget can say at 09:10; filtering on *start* rather than *end* would
blank the glanceable surface during exactly the meeting the user is in.

⚠ An all-day event on today is never "ended" — treating its 00:00 start as past would drop every
holiday from the widget by 00:01. ⚠ It also shows no time: midnight is how it is stored, not when
it happens, so the column carries *all day* instead.

⚠ The list looks ahead a week once today is done (a blank widget is a wasted one) and marks those
rows so the widget can distinguish them from today's. ⚠ Ties break on title, so the order does not
shuffle between redraws.

#### ⚠⚠ The refresh is the honest limitation of this slice

The widget redraws on the platform's `updatePeriodMillis` — **30 minutes is the floor**, anything
smaller is silently clamped — and on `MainActivity.onStop`, which is the moment the user's calendar
is most likely to have just changed.

That is the ceiling of what Calendar can do alone, and it is structural rather than a shortcut:
**Calendar owns no background scheduler — Control does** — and Control cannot wake a widget living
in Calendar's process. Closing it properly would mean either a scheduler in Calendar (which the
whole architecture exists to avoid) or a Control→app push that wakes a widget, which does not
exist. ⚠ `updatePeriodMillis` also does not wake the device, so a widget on a sleeping phone
redraws when the phone wakes, not on the half hour.

⚠ `onStop`, not `onPause`: a permission dialog or the notification shade is not the user leaving,
and redrawing on each would cross IPC for nothing.

⚠ Koin is read through `GlobalContext.getOrNull()` rather than injected — a widget is
framework-instantiated and the launcher can reach it on a cold start before
`CalendarApplication.onCreate` has run. Absent graph ⇒ `NotConnected`, which is true.

#### Verification

13 tests on the builder; **13 mutations, all killed** — started-dropped-instead-of-dimmed,
ended-kept, all-day-ended-at-midnight, all-day-gets-a-time, past-days-included, no-horizon,
limit-ignored, unstable-order, empty-as-Items, untitled-blank, tomorrow-flag-false, and the
non-positive limit. The APK assembles.

⚠ **One mutation survived first and the fix was the test.** The `limit <= 0` guard looked
decorative because `take(0)` already yields empty — but `take(-1)` *throws*, and a widget that
crashes the launcher's host process is a worse failure than one that shows nothing. The test now
passes `-1`. Fourth time this session that a green test ran through a path that never exercised
the thing under test.

⚠ **Not verified, structurally:** the widget rendering at all. Glance, `GlanceAppWidgetReceiver`,
`updateAll`, the launcher placing it, `updatePeriodMillis` firing — every one is framework
behaviour on a device. The manifest merges and the APK builds; that is the whole of what this
environment can say. ⚠ `androidMain` changed ⇒ **HR#8 owed**, and this is the slice where the
smoke gate would earn the most — a widget receiver that crashes on placement is exactly what it
catches.

⚠ No schema, wire or service-version change ⇒ **HR#9 untriggered**. No threat-model delta: the
widget shows event text on a home screen, which is the *unlocked* surface — CA-5b's A5 amendment
covers the lock screen, and a home screen is behind the same unlock as the app itself.

---

### CA-8a — the iOS shell exists, and everything that can be wrong is on the Kotlin side

The first of three CA-8 slices. Calendar now has an iOS app: a route-1 runtime, the umbrella
export it needs, and an agenda over the shared view model. **CA-8b** brings the month grid over
`UICalendarView` (Q7), the event editor and calendar management; **CA-8c** brings the pre-armed
`UNUserNotificationCenter` reminder path (§4.3 Gap 1). The split is stated here rather than
discovered from a half-finished editor.

⚠⚠ **Nothing in this slice's iOS code was compiled.** Not the Swift — there is no Xcode here —
and not the Kotlin/Native either: iOS targets are *disabled on this host* (`:core-common` carries
a `libsodium` cinterop that cannot be processed on `linux_x64`), so `compileIosMainKotlinMetadata`
fails on `master` before any of this existed. That is a bigger hole than the usual "no emulator",
and it is what shaped the slice.

**⭐⭐ The shape that follows from it: if a decision could be wrong, it is not in Swift.** Three
pieces were pulled back across the line into `commonMain`, where the compiler and the test suite
can reach them:

- **`CalendarAgenda`** — which days appear, in what order, with what heading, what the time column
  says, what an untitled event shows. The SwiftUI view groups nothing and formats nothing; it
  draws the answer. Same discipline as CA-7c's `WidgetAgendaBuilder`.
- **`CalendarHostInput`** — `today(year, month, day)` and `weekStart(isoDayNumber)`. The surface
  takes **three integers, not a bridged `LocalDate`**, so the Swift shell never names a
  kotlinx-datetime constructor — a spelling that would otherwise be verified for the first time on
  somebody's Mac.
- **`NotConnectedCalendarRepository`** — the honest empty surface, in `commonMain` rather than
  private to the iOS file (where Contacts keeps its twin), precisely so its 25 methods are pinned
  by tests.

⭐ **It paid immediately.** `CalendarAgendaSection.epochDay` was declared `Int`; `toEpochDays()`
returns `Long`, and the Kotlin compiler said so. In Swift that would have been an `Int32`/`Int64`
mismatch found by whoever next opened Xcode.

**⚠⚠ Writes throw, and that is the design.** `CalendarRepository` has no result type — a write
returns `Unit` or an id — so a disconnected implementation can only return normally or throw, and
returning normally is indistinguishable from having saved. Every view model already folds a throw
into `errorMessage`, and `EventEditorScreen` already closes on the *commit* rather than the tap
for exactly this reason (the `RenameFileDialog` precedent).

⚠ **One deliberate exception, and it exposed a live bug.** `ensureDefaultCalendar` no-ops instead
of throwing, because it runs from the shell's launch effect. Writing that down led to
`App.kt:65` — `LaunchedEffect(Unit) { repository.ensureDefaultCalendar() }`, **unguarded**, on
Android and desktop. An uncaught throw there takes the app down, and the seeding write is
precisely the call that fails when the proxy is unreachable: Calendar would crash on launch in the
one state the shell exists to explain. Now wrapped in `runCatching`. Found by writing the
disconnected twin, not by running anything.

**⚠ Reachability sits on `CalendarSurface`, not on `CalendarRepository`.** Contacts put its signal
on the repository because its *Compose* shell reads it; Calendar's does not, and widening a
25-method interface to give Android and desktop a method nothing calls is the wrong trade. If a
Compose banner lands, it moves down. (Calendar's desktop `PlatformClientModule` already refers to
"the reachability banner" — a fourth instance of this app naming something that was never built.)

**⚠⚠ The banner, not the wall** (ADR 0022 §Decision 4/5). Calendar is a closer call than Contacts
and a clearer one than Maps: with no realm it has no events at all, which sounds like Drive's "no
realm, no files" — but a calendar's *dates* exist without any data. The grid pages, today is still
today, and the empty state is a true statement about a real surface rather than a dead end. The
reasoning is recorded on `CalendarRootView` so the next reader sees the question was asked.

**⚠⚠ D12 — and this export is the dangerous direction.** `app.tenshu.calendar` sorts **before**
every other app package on the umbrella, and K/N resolves an Obj-C name clash by appending `_` to
whichever fq name sorts *later* — so a shared type name here would rename the **incumbent's**
Swift symbol, breaking an app this change never touched (the way the Maps export silently renamed
Timeline's `Place`). Every public top-level name in `:calendar-shared-logic` and
`:calendar-service-common` was checked against every currently exported module: no collisions, so
no `@ObjCName` is owed. ⚠ Re-run that check whenever either module gains a type — it is silent
here and loud only in a sibling app's Xcode build. `:calendar-service` and `-client` stay
unexported: Swift never names `Calendar` (which would also shadow Foundation's) or `SaveEvent`.

⚠ **`:calendar-service-common` is exported explicitly**, not inherited: `ResolvedOccurrence` is a
field on `CalendarUiState`, so Swift reads the type by name, and `export(...)` is not transitive.

⚠ **No new service wiring.** `initCalendarService` already runs in the iOS `PlatformProxyModule`,
and `:preference-service` is already in the graph — which is why `CalendarLocalRuntime` is thin.
It is the fifth `FirstPartyLocalRuntime` consumer and adds nothing to the substrate.

⚠ **Drive-by: `contacts-ios` was missing from `.githooks/regenerate-xcode-projects.sh`.** It
shipped without being added, which is exactly the failure that script exists to prevent — a branch
switch leaves its generated project listing files that no longer exist, and the build then fails
somewhere unrelated. Both it and `calendar-ios` are in the list now.

**Verified:** the full gate (`desktopTest jvmTest test`, 1775 actionable tasks) green. 22 new
tests. Mutation testing on the three pure pieces: **21 of 22 killed**; the survivor —
reversing the input list before a total sort — is an *equivalent* mutant, and the mutation that
actually matters (dropping the within-day sort and trusting the repository's order) **is** killed.

**⚠ Not verified — and here the list is longer than usual:**

- ~~Every line of Swift, and every line of Kotlin/Native (`CalendarSurface`, `CalendarLocalRuntime`).
  Neither compiles on this host. The XcodeGen spec, the entitlements and the SKIE call shapes are
  written from the sibling shells and are unverified against a compiler.~~
  ✅ **Closed 2026-08-12** — and the prediction was exact: it was the **SKIE call shapes**.
  `calendar-ios` was missing from `scripts/ios-smoke.sh`, so nothing compiled it; adding it there
  found two defects in four lines, both of the contacts-ios family that script's own header
  documents:
  - `EventEditorScreen.swift` called `RecurrencePresetKt.label(x)` — the plain Kotlin/Native
    file-facade spelling. SKIE bridges a Kotlin extension function as a **Swift extension**, so it
    is `x.label()`. ⚠ This was the only `…Kt.` call in any Swift file in the repo; there was no
    sibling to copy it from, which is exactly how it was written wrong.
  - `RemindersPermissionSection.swift` used `granted?.boolValue` on a callback parameter that
    bridges as a **non-optional** `KotlinBoolean`.

  ⚠⚠ The lesson is the gate, not the four lines. CA-8a shipped, then CA-2a…CA-2k and CA-6c…CA-6e
  shipped on top of it, all while nothing on any machine compiled this app — the same shape as
  contacts-ios (Contacts Ph10, never compiled once) that `--build-only` was built to prevent. The script's
  app list is not documentation of what exists, it is what gets built; an app absent from it is
  invisible to every gate in the repo.
- The route-1 gate actually seeing Control's provisioning; the shared-replica read; the banner's
  deep link. All of it needs a Control-genesis A/B pass on a simulator.
- ⚠⚠ **Reminders do not fire on iOS.** `initCalendarService` binds a `Noop` presenter there, so a
  reminder is stored and synced and nothing on the device delivers it. Named in
  `CalendarLocalRuntime`'s KDoc and in `calendar-ios/README.md` rather than left to be found by
  missing an appointment. CA-8c closes it.

⚠ No schema, wire or service-version change ⇒ **HR#9 untriggered**. No `androidMain` change ⇒ no
HR#8 owed by this slice. No threat-model delta: route-1 key custody is unchanged (whole-blob,
migration proposal §4 → C), and Calendar reads the same realm every other first-party iOS app
already does.

---

### CA-8b — the month grid, for free, and a screen for the calendars

The second CA-8 slice: the month view over `UICalendarView` (Q7 ✅) and the calendars screen. The
same rule as CA-8a — no iOS code compiles here, so anything that could be wrong is in tested
`commonMain`.

**⭐⭐ Q7 pays off, and it is the largest single saving on this app.** §5.4 sized the month grid as
a component that has to be hand-rolled *on every platform*, twice, because no Compose UI ships on
iOS. `UICalendarView` is a real month grid — locale-correct, first-weekday from the system,
VoiceOver, Dynamic Type, its own paging — and what CA-8b writes is the *binding*, not the grid.
⚠ It has to arrive through `UIViewRepresentable`: SwiftUI's `DatePicker(.graphical)` looks similar
and has no per-day decorations, which is the entire point of a calendar's month view.

**⚠⚠ Two new methods on `CalendarViewModel`, both for the same structural reason.** `step(delta)`
moves by one of the current view's periods, which is right for a header with arrows — but a native
grid **pages itself and then reports where it landed**, and "the user flicked forward three months"
is not a sequence of steps we saw. So `showMonth(year, month)` and its twin `selectDay(year,
month, day)`, which takes a tapped cell's *components* rather than a date object. Both are shared,
not iOS-only: a Compose pager would be the second caller.

⚠⚠ **`showMonth` no-ops when the month has not changed, and that guard is load-bearing.** The
control reports its visible month on every settle — *including* settles our own assignment caused —
so an unconditional reload is a feedback loop. Its mirror lives in the Swift coordinator, which
guards the selection assignment for exactly the same reason. Two guards, one hazard, on opposite
sides of the bridge.

⚠ `showMonth` deliberately does **not** move `selectedDate`: scrolling the grid is not selecting a
day, and moving the selection with the scroll silently changes which day's events the detail list
shows, from a gesture that never touched it.

**New tested Kotlin, all of it consumed by a UIKit delegate:**

- **`CalendarMonthDots`** — which days carry a marker, what colour, and the redraw diff.
  ⚠⚠ **One dot per day, coloured by that day's *earliest* event's calendar.** `UICalendarView`
  gives a cell exactly one decoration; several dots need a custom view and at month scale three
  4-point dots are not something anyone reads. A day mixing calendars therefore shows one colour —
  a real loss, recorded rather than left to be noticed, with `count` carrying "there is more than
  one".
- **`changedKeys`** — ⚠ `reloadDecorations` re-asks the delegate for every date handed to it, so
  passing the whole visible range on each emission makes a scroll stutter. ⚠⚠ A **removal** counts
  as a change: a day whose last event was deleted keeps its dot until something asks again.
- **The ARGB channels on `CalendarDayDot`.** The stored colour is Android-packed; UIKit wants the
  alpha last. Doing that shift in Swift would put untestable bit arithmetic in a file nothing here
  compiles, and a swapped red and blue is invisible until someone looks at a screen.

**⚠⚠ Mutation testing found three real gaps, and one of them was a fixture lying.** The
mixed-calendar test used the titles "Early" (08:00) and "Late" (17:00) — alphabetical order agreed
with time order, so the test passed even with the comparator's time key deleted. The fixture is now
"Zebra" at 08:00 and "Afternoon" at 17:00, where the two disagree. The other two: the redraw
list's ordering had no test that could distinguish it from insertion order, and `selectDay` had no
nonsense-input test at all. **18 of 18 killed** after.

⚠ **Week and day views are deliberately absent.** `CalendarViewModel` supports them and the layout
maths (`DayLayout`, `WeekGrid`) is shared and tested — but the renderer is CA-4's Compose canvas,
and a native time grid is a slice of its own, not a tab entry. Recorded so the absence reads as a
decision.

⚠ **Three tabs rather than a nav-bar view switcher** (which is what the Compose shell has). The
shared view model reloads on every `show(...)` precisely so a view never renders the previous
view's window, so a switcher and separate tabs cost the same reload — and tabs keep each screen's
scroll position, which is most of what an agenda is for. The cost is two live `CalendarViewModel`s
loading the same rows; named in `CalendarScreenModel`.

⚠ The calendars screen re-implements none of the CA-5f/CA-7b rules — last calendar undeletable,
duplicate name refused, visibility optimistic and device-scoped, no plain *Delete* on a subscribed
calendar. Its sheets and alerts are presented off the **shared state** rather than local `@State`
flags: the draft *is* the condition, and a second boolean beside it is one more thing that can
disagree with the view model about whether a sheet is open.

**Verified:** the full gate (1775 actionable tasks) green; 18 new tests; **18 of 18 mutations
killed**. The D12 name scan re-run with the two new types — still no collisions.

**⚠ Not verified:** every line of Swift, again — `UICalendarView`'s delegate signatures, the
decoration API, the `UIViewRepresentable` update cycle, the two re-entrancy guards actually not
looping, and whether 380 pt is the right grid height. All of it needs Xcode and then a simulator.

⚠ No schema, wire or service-version change ⇒ **HR#9 untriggered**. No `androidMain` change ⇒ no
HR#8. No threat-model delta.

---

### CA-8c — reminders fire on iOS, and only from one app

The third CA-8 slice, and it closes **§4.3 Gap 1** — the last *platform* gap in the whole Calendar
plan. Before this, an iOS device synced every event and every reminder and delivered none of them.

**⭐⭐ Almost all of it already existed, which is the point.** §4.6's design — *Android wakes; Apple
platforms pre-arm* — was built out end to end at CA-5: `ReminderPlanner` composes and redacts the
alerts, `DeliverDueReminders` hands the whole forward set to a `SchedulingNotificationPresenter`
and arms nothing of its own, `AlertReconciler` diffs desired against known. What was missing was
one implementation. `IosNotificationPresenter` is that: `UNCalendarNotificationTrigger`, the
pending query, the two remove calls.

⚠ The redaction being *upstream* matters here specifically rather than generally: on Apple
platforms the OS is handed this text days in advance and the app never sees it again, so a
presenter that enriched a title would be enriching something already out of its hands.

**⚠⚠ The hazard this slice is really about: Calendar posts, Control must not.**
`UNUserNotificationCenter` is per *app*, and on route 1 **both** Control and Calendar run the same
proxy graph over the same shared replica. A presenter bound in the platform module would therefore
be bound in both — and every reminder would arrive **twice**, once from each process, with nothing
in either app's logs to suggest why. So the iOS `PlatformProxyModule` binds
`NoopNotificationPresenter` and `CalendarLocalRuntime` overrides it through the last-wins
`appOverrides` slot. The mirror of `ios-drive-parity.md` P6a, where Drive rather than Control is
route 1's background syncer — and the second time route 1's "two processes, one graph" shape has
had to be resolved by naming an owner.

⚠ The presenter is constructed **before** the graph and handed in, not resolved from it afterwards:
the ops factory closes over whatever Koin holds at construction, so a later override would be read
by nothing.

**New tested Kotlin — `AppleAlertSchedule`, the two decisions that are not API calls:**

- ⚠⚠ **The 64 pending-request cap is applied here, over a sort.** `ReminderPlanner` already caps
  and orders, but the OS's pending pool belongs to the whole *app* — anything else it ever
  schedules competes for the same 64, and the OS drops the overflow silently. Taking a prefix of an
  unsorted list would quietly discard tomorrow morning's alarm in favour of one next week.
- ⚠⚠ **The trigger components carry seconds.** `UNCalendarNotificationTrigger` matches
  *components*; omit the second and it fires at the top of the minute, which turns "five minutes
  before" into up to fifty-nine seconds late.
- ⚠ A **calendar** trigger, not a time-interval one: an interval is fixed at scheduling time, so a
  device that sleeps across a DST change fires at the wrong wall-clock minute — and the wall clock
  is what a reminder means.

**⚠⚠ Two defects were found in this slice's own Swift, by re-reading it rather than by running
anything** (nothing here compiles Swift):

1. The permission row hung its `scenePhase` refresh off the row *itself*, which is rendered only
   when permission is missing — so a revocation in Settings could never be noticed and the row
   could never come back. The hooks now hang off an enclosing `Group`.
2. `refresh()` called the OS settings query and then read the cache in the same turn, which answers
   with the **previous** value — showing "turn on reminders" to someone who had just turned them
   on. `refreshAuthorization` now reports through a callback.

**⚠ Asking for permission is the shell's job, and this is the moment.** `NotificationPresenter`'s
KDoc already said so; iOS sharpens it, because the prompt is once per install and unrepeatable —
firing it at proxy init would spend it on a launch the user had no reason to connect with
reminders. It sits on the Calendars screen under a heading that says what it is for, and vanishes
once reminders work. ⚠ A refused request routes to **Settings**, because the second tap does
nothing: once denied, `requestAuthorization` returns `false` immediately without showing anything,
so a button that only re-requests silently fails forever.

**⚠⚠ No *Snooze* / *Dismiss* on an iOS reminder.** CA-5f's actions ride on every `AppAlert` and
this presenter drops them: an Apple action needs a registered `UNNotificationCategory` **plus** a
`UNUserNotificationCenterDelegate` in the Swift shell to receive the response and call back into
Kotlin — a second path, not a parameter. Named in the presenter's KDoc and the app README rather
than left to be found by pressing a button that is not there.

⚠ Drive-by: `:app-notify`'s `iosX64()` comment justified the target by *"`:calendar-shared-logic`
declares it"* — which dropping that target from `:calendar-shared-logic` made false. The comment now says the target's reason is gone and why it
is still there; the hazard it named (a dependency targeting fewer platforms than its consumer fails
resolution while still printing `BUILD SUCCESSFUL`) is restated, because that is the part worth
keeping.

**Verified:** the full gate (1774 actionable tasks) green; 12 new tests; **9 of 9 mutations killed**
on `AppleAlertSchedule`. D12 name scan re-run, now including `:app-notify`'s types (they ride the
umbrella through `:calendar-shared-logic`'s `api`) — no collisions.

**⚠ Not verified:** the whole of it, at the level that matters. `UNUserNotificationCenter`'s
Kotlin/Native bindings, whether a trigger actually fires, whether the interruption level pierces
Focus, the permission prompt, and the Settings deep link are all device behaviour. ⚠⚠ **And the
duplicate-notification hazard above is exactly the kind of thing that only shows on a device with
both apps installed** — the code says Control binds the Noop, but nothing here can prove the
override lands.

⚠ No schema, wire or service-version change ⇒ **HR#9 untriggered**. No `androidMain` change ⇒ no
HR#8. **No threat-model delta:** reminder content on a lock screen is already covered by CA-5b's A5
amendment, and the per-calendar `sensitive` redaction happens upstream in `ReminderPlanner` — this
presenter receives text that is already safe to hand to the OS, which is the property that made
pre-arming acceptable in the first place.

---

### CA-8d — the editor, and the one place a `Date` is allowed to exist

The last CA-8 slice: iOS can create and edit events. **CA-8 is complete, and with it the phase map
CA-0 … CA-8.**

**⚠⚠ The adapter is why this was its own slice.** SwiftUI's `DatePicker` binds a `Date`; the shared
view model speaks civil `LocalDate`/`LocalTime` (§3 D3), and the shell must never name a
kotlinx-datetime constructor — not for tidiness but because nothing in this environment compiles
Swift *or* Kotlin/Native, so a constructor spelling would be verified for the first time on
somebody's Mac. Three additions, all tested:

- **`EventEditorViewModel.setStartDateFromParts` / `setStartTimeFromParts` / `setEndTimeFromParts`**
  — the `showMonth`/`selectDay` precedent, third and fourth time. ⚠⚠ Each **delegates** rather than
  duplicating: `setStartTime` drags the end along to preserve the duration, and a second copy of
  that arithmetic is a second place for it to be wrong. The test that pins it is the one worth
  having.
- **`CalendarHostInput.dateParts` / `timeParts` / `time`** — the *read* direction, so the editor
  never reaches into a bridged `LocalDate` for `year` / `monthNumber` / `day`. ⚠ Those accessor
  spellings kotlinx-datetime has already deprecated once, in this very repo (`monthNumber` warns on
  every build).
- ⚠ A null date answers with the fallback's parts rather than a null of its own: the caller is
  filling a picker, which must show *something*, and an obviously wrong date is easier to notice
  than an empty control.

**⚠ `RecurrencePreset.label()` and `OFFERABLE` moved out of the Compose editor.** Both were private
there; the SwiftUI one needed the same list and the same words, and two copies of user-visible copy
is how "Yearly" becomes "Annually" on one platform. ⚠⚠ `CUSTOM` stays out of `OFFERABLE` and the
test says why: it is a state an event can already be in, never a choice a user can make — offering
it would let someone turn a rule we understand into one we deliberately refuse to touch (§3 D2).
It still has a *label*, because the editor must be able to **show** an imported rule.

⚠ **One `Date` in the whole screen, in one binding, and it goes no further.** Foundation's picker
speaks `Date`, the shared layer speaks civil time, and the conversion happens at exactly that seam
so the two never meet anywhere else. The time pickers use a **fixed anchor date** (2000-01-01)
rather than today: only the hour and minute are displayed, and anchoring on today would make the
control jump if the picker were open across midnight.

⚠ **The editor closes on the COMMIT, never on the tap** — the same guard the Compose editor
carries, and for the same reason: a refused save (no calendar, proxy unreachable) would otherwise
look exactly like a successful one. That path is live on iOS specifically because
`NotConnectedCalendarRepository` *throws* on a write (CA-8a) rather than quietly returning.

⚠ **`+` creates on the selected day on the month tab, on today in the agenda.** Tapping the 20th
and then `+` means "something on the 20th"; opening on today would silently discard the one thing
the user had already told us. The agenda has no selection to honour — it is a forward list whose
scroll position is its navigation.

⚠ **No `initialTime` on the surface factory.** Kotlin default arguments do not survive to Swift, so
an unused parameter would have to be spelled at every call site — and the only thing that ever
supplies a time is CA-4's time-grid tap, which iOS does not have. It gains the parameter when a
native time grid lands, rather than the shell inventing one now.

**Verified:** the full gate (1774 actionable tasks) green; 13 new tests; **13 of 13 mutations
killed**. D12 name scan re-run with `CalendarDateParts` / `CalendarTimeParts` — no collisions.

⚠ **A gate false alarm worth recording:** the first run failed in `:commerce-service` with two
unresolved test-helper references, in a module CA-8d does not touch. It compiles clean on `master`,
and the same log carried the known `InvalidPathException: Malformed input` on a test-class file
name containing an em dash — corrupted incremental state, not a regression. A clean re-run is green.
The em-dash-in-a-backtick-test-name hazard is worth a real fix at some point; `LANG=C.UTF-8` only
hides it.

**⚠ Not verified:** every line of Swift. In this slice that includes the `DatePicker` bindings, the
`confirmationDialog` scope prompts, whether `Form` renders the sections in a usable order, and
whether a `.sheet` over `NavigationStack` dismisses cleanly. All of it needs Xcode and a simulator.

⚠ No schema, wire or service-version change ⇒ **HR#9 untriggered**. No `androidMain` change ⇒ no
HR#8. No threat-model delta — the editor writes through the same ops every other platform uses.

**Left for later, and none of it CA-8:** *Snooze* / *Dismiss* buttons on an iOS reminder (CA-8c),
and week/day time grids on any native shell.

⚠ ~~`.ics` import/export on iOS~~ — **shipped in CA-6c**, and the guess recorded here was wrong in
an instructive way: it does *not* need a `CalendarFileAccess` actual. The seam exists for Android's
composition-scoped `ActivityResult` launchers; SwiftUI's `.fileImporter` / `.fileExporter` are plain
modifiers, and `:calendar-shared-ui` does not build for iOS anyway. See CA-6c.

---

### Deletes reach every device — tombstones (2026-11-04)

⚠⚠ **The defect.** `SyncObserver.onDataDeleted` is a deliberate no-op: a delete leaves the device
it was made on only if the op emits its own tombstone (`DeleteWorkout` and `DeleteFile` are the
precedents). No Calendar op did, until *Stop syncing* in P2-a (`calendar-external-sources.md` §11).
So deleting an event, a calendar, a reminder or a subscription, skipping a moved occurrence, and
everything a refresh removed stayed on the device that did it. The row lived on everywhere else,
and the next write of it from there brought it back. Since CA-1.

**As built.** One helper, `deleteRow(type, key, tombstoneEmitter)` (`CalendarDeletes.kt`), and
**no other `deleteData` call in `calendar-service-proxy`**. It runs once per row, and cascades are
rows too: an event's overrides and sidecar, a reminder's states, a calendar's events, a mirror's
stamps. The emitter comes from `calendarServiceProxyImplementationFactory` (wired by P2-a on
Android, desktop, iOS, web and the system-test harness) and is threaded into every delete op,
`ApplyExternalEvents` (the overrides and reminders a batch *sets* away) and `SubscriptionRefresher`
(the feed sweep).

- ⚠ **A tombstone for a row this device does not hold is emitted anyway** (an event's sidecar, a
  skipped occurrence's override). A peer may hold one this device has not pulled yet, and the cost
  is one relay write.
- ⚠ **The mirror sweep tombstones only what it deletes.** Its windowed rules
  (`TenshuCalendarRepository.syncMirrorLocked`, CX-2b) keep rows outside the read window by
  *not deleting* them, so they get no tombstone either. The sweep's deletes go through `DeleteEvent`,
  and the reminder switch's through `DeleteReminder`.
- ⚠ **A subscription sweep now reaches every device.** Each device fetches a feed for itself, so an
  event one device swept and another still sees in its (older) copy of the feed is re-saved there
  with a newer clock, and comes back until that device's next fetch sweeps it too. It converges on
  the feed, as it did before, only now the sweep is shared rather than per device.

**Verified:** `CalendarDeleteSyncSystemTest` (new), two real devices through the relay, one test per
path: event with override and sidecar, skipped occurrence, calendar with its events, reminder with
its state, subscription with its calendar, and a mirror refresh (sweep, moved-back override,
reminder switch). Each test waits until the peer holds every row, deletes on the other device, and
asserts each row is gone on the peer and stays gone. All six failed before the fix, each naming the
rows still on the peer. **10 mutations, all killed**, each by the test for its path: no tombstone
from `DeleteEvent`, for an event's overrides, for its sidecar, for a skipped occurrence's override,
for a calendar row, for a calendar's events, for a reminder's states, from `DeleteReminder`, for a
subscription row, and for an override `ApplyExternalEvents` sets away.

## Web scope

*Question PR for [`web-app-suite.md`](web-app-suite.md) Wave 2 (§8.1), opened 2026-10-23 alongside
Calendar run A (the SDK boundary + bridge). Every item below is a decision the maintainer makes;
the answers get merged here, and run B cites this section.* Run A builds only what nothing here
disputes: calendars, windowed occurrences (one-shot and live), visibility, the default calendar,
calendar/event/occurrence writes, `.ics` text import/export, the reminder *data*
(`remindersFor`/`setReminders`/default minutes) and the subscription *rows*. It wires **no timer
and no fetch**: `DeliverDueReminders` and `RefreshSubscriptions` are not in the browser's
operation factory at all, so they fail as "no implementation" rather than run.

### W1 — Reminders

- **The browser can:** show a notification with the Notification API
  (`Notification.requestPermission()`, `new Notification(…)` or
  `ServiceWorkerRegistration.showNotification(…)`, which also carries action buttons, i.e. *Snooze*
  / *Dismiss*), but only while a tab of the origin is open: a page `setTimeout` firing at the
  reminder's instant. The Page Visibility API tells the page it is in the background, where
  browsers throttle timers to about once a minute (Chrome's intensive throttling, after 5 minutes
  hidden), so a reminder in a background tab can arrive up to about a minute late.
- **The browser cannot:** fire anything with every tab closed. The Notification Triggers API
  (`showTrigger` / `TimestampTrigger`) was a Chrome origin trial and was abandoned. Push API
  delivery needs a push server that knows *when* the user's events are, which breaks House Rule #1
  (the relay sees ciphertext only). Periodic Background Sync is Chromium-only, installed PWAs only,
  at intervals the browser picks (hours, not minutes), so it cannot carry a punctual alert.
- **Already true without any web reminder code:** the user's phone and desktop already fire every
  reminder. Reminders are **per-device** (Q4), so a browser that also fired them would give a second
  alert on any machine where the desktop app runs as well.
- **Options:** (a) **none**: the web edits the reminder offsets (pure data, run A builds it), and
  firing stays with the native devices; (b) **in-tab only**: the web arms `setTimeout`s for the
  next N hours from the live occurrence window while a tab is open, behind an explicit per-browser
  opt-in and a "only while this tab is open" note.
- **Recommendation: (a) none for v1**, with a note in the editor that reminders fire on the user's
  phone and desktop. (b) is cheap to add later. It would be the first background-ish work in a tab,
  though, and it duplicates alerts on desktops. If (b) is wanted, the dismiss/snooze state rows
  (`SetReminderState`) are already pure data writes, so an in-tab alert could record the user's
  dismissal and the phone would not fire it again.

### W2 — ICS subscriptions (remote feeds)

- **Already true:** *the subscription row syncs; the fetch does not* (threat model A5, CA-7
  amendment). A feed added on the phone appears in the browser, and its **events reach the browser
  through ordinary sync**, because the phone fetches them and they sync as normal events. So the
  browser *shows* subscribed calendars without fetching anything.
- **The browser can:** `fetch()` a feed only if the publisher sends
  `Access-Control-Allow-Origin` for our origin, or `*`. Most don't: Google Calendar's public
  `.ics`, most sports and school feeds, and iCloud's `webcal://` links send no CORS headers. A
  `mode: 'no-cors'` fetch gets an opaque body that cannot be read.
- **The browser cannot:** refresh on a schedule with the tab closed (W1's limits again), or read a
  CORS-less feed at all without a server in between.
- **Options:** (a) **no fetch on web**: the browser lists the subscriptions, and can add, pause and
  remove them as rows (pure data, run A exposes them), while native devices do all the fetching. An
  added feed starts filling once a phone or desktop next runs its schedule. (b) **a relay
  passthrough** (`GET /sync/ics?url=…`): the relay would see every subscription URL, which tells it
  which calendars the user holds. That is new plaintext metadata on our server (a House Rule #1
  tension, and a new threat-model asset), and it turns the relay into an open fetch proxy that
  needs abuse limits. (c) **direct `fetch()` only, CORS permitting**: it works for the few feeds
  that allow it and fails confusingly for the rest.
- **Recommendation: (a)**, with the subscriptions screen saying "fetched by your phone and desktop".
  Drop (b): a relay that learns subscription URLs is exactly what House Rule #1 is meant to stop.
  ⚠ Even under (a), *adding* a feed from the browser is consent to **other devices'** egress. The
  add-subscription copy has to say that the user's phone will fetch it, not the browser.

### W3 — The first-run seed (`ensureDefaultCalendar`)

- **The issue:** native apps call `ensureDefaultCalendar()` on every launch. It seeds a "Personal"
  calendar and the `DefaultCalendarId` preference when the store holds no calendar and no default.
  A browser is the clearest case of the known race (`tech-debt.md`, "Calendar seeds its default
  calendar before the first sync"). A freshly paired browser holds **nothing** until its first
  pull, so a seed that ran at load would write a Personal the user may already have under another
  name.
- **Options:** (a) the web never seeds, and an empty book shows "create a calendar" (run A exposes
  `saveCalendar` + `setDefaultCalendar` for that); (b) seed only after the first completed sync
  pull (`SyncHealth` can tell), using the same rules as native.
- **Recommendation: (a)** for v1. Run A therefore does **not** export `ensureDefaultCalendar`.

### W4 — Things with no browser equivalent: drop

- **Copying from the device calendar store** (CX-0/CX-1, Android `CalendarContract`): no browser
  API exists. `.ics` text import covers it, because Google and Apple Calendar both export `.ics`.
- **"Open with Calendar" for an `.ics` file** (CA-6c/CA-6d): the File Handling API
  (`launchQueue`) is Chromium-only and needs an installed PWA. Recommendation: a file input plus
  drag-and-drop (`<input type=file accept=".ics,text/calendar">`, `File.text()`), and
  `Blob` + `<a download>` for export.
- **The home-screen widget** (CA-7c): no web equivalent. Drop.

### W5 — Time zones in the browser (finding, no decision needed)

kotlinx-datetime on Kotlin/JS goes through js-joda, whose core knows only UTC and fixed offsets:
`TimeZone.of("Europe/Berlin")` throws (`app-notify/build.gradle.kts` records the same finding for
tests). Recurrence expansion is zone-free civil time and still works, but nothing could turn an
event's `(startLocal, timeZoneId)` into an instant for the grid. Run A therefore bundles the IANA
database (`@js-joda/timezone`) into the SDK's Kotlin chunk, adding about 180 KB to the lazy Kotlin
chunk and nothing to the public pages. It also gives every occurrence epoch-ms bounds, resolved in
the event's own zone (or the browser's zone, `Intl.DateTimeFormat().resolvedOptions().timeZone`,
for floating and all-day events). The only question is whether to ship the 10-year-range variant
of the database instead (smaller, but wrong for events far in the past or future). The
recommendation is the full database.

### Decided (maintainer, 2026-10-24)

All recommendations accepted:
- **W1 Reminders: none on web.** The web edits reminder offsets (data only); firing stays with the
  native devices, and the editor says so.
- **W2 ICS subscriptions: no fetch on web, and no relay passthrough.** The browser lists, adds,
  pauses and removes subscription rows; native devices fetch. The add-subscription copy says that
  the user's phone/desktop will fetch the feed.
- **W3 No first-run seed on web.** An empty book shows "create a calendar"; `ensureDefaultCalendar`
  is not exported.
- **W4** Device calendar copy, "open with", and the widget are dropped; `.ics` via file input,
  drag-and-drop, and Blob download.
- **W5** Ship the full IANA time-zone database (`@js-joda/timezone`) in the lazy Kotlin chunk.

### As built — run B, the `web/calendar` app (2026-10-24)

`web/calendar` on the app-kit, at `calendar.<apex>` (dev port 5176), over run A's `calendar.*`
gateway. Screens: month / week / day / agenda, the event editor, calendars, subscriptions, and
import & export. The logic it needs from `calendar-shared-logic` is ported to TS, not compiled:
`MonthGrid`/`WeekGrid` (`lib/dates.ts`), `DayLayout` (`lib/dayLayout.ts`), `RecurrencePreset`
(`lib/recurrence.ts`), `ReminderPresets` (`lib/reminders.ts`), `CalendarPalette` (`lib/palette.ts`).
Decisions made in the build, for the next person here:

- **Placement is by instant, the window by civil date.** The bridge intersects `[from, to]` in each
  event's own zone, while the grid places timed occurrences by `startEpochMs`/`endEpochMs` on the
  browser's clock (all-day ones by their civil dates). The app therefore reads one day of padding
  each side and keeps only the slices that land on a visible day (`lib/placement.ts`).
- **Allowlist: exact names, and not `calendar.reminder.dismiss`/`.snooze`.** Nothing in a tab fires
  a reminder (W1), so nothing dismisses one.
- **Creates are floating (`timeZoneId: ''`), as the native editor creates them; edits keep the
  event's zone** and show its civil times with a "times are in <zone>" note.
- **A series edit from a later occurrence re-anchors the series on that occurrence's date** — the
  native editor's behaviour (it has no series start to keep either). The scope prompt says so
  ("The series will start on …"). ⚠ Earlier occurrences drop out; the fix belongs in both editors
  and needs the series' first start on `Occurrence`.
- **A new calendar whose derived id already exists is refused in the dialog.** `saveCalendar('')`
  derives `cal-<hash(name)>`, so a second "Work" would upsert (rename, recolour) the first; the app
  computes the same Java `hashCode` (`lib/calendarId.ts`, pinned against JVM vectors).
- **Unread is never defaulted.** A failed reminders read leaves the stored offsets untouched on
  save; a failed hidden-set read shows every calendar and says so; a failed read renders by `kind`.
- **`zoneUnresolved`** occurrences open a refusal (why, and "edit it on your phone or desktop");
  delete stays available because it writes no time.
