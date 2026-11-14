# Web apex Home — an overview first, Control behind the gear

**Status:** **v1.0 — H1–H6 and Pause/Resume (H5c) built (2026-11-13).** §2 is verified
fact; §3 onward is the design, accepted by the maintainer; §4.1–§4.8 are as-built notes; §7 records
the decisions.

**Owner:** Benedikt

**Relationship:** supersedes parts of [`web-control-plane.md`](web-control-plane.md) (§2's
landing route, §3's single shell, §3.1's launcher contents — listed in §5).
Builds on [`web-app-suite.md`](web-app-suite.md) F4 (one registry file per app) and F5 (per-origin
allowlists). Reads [`control-app-registration.md`](control-app-registration.md) and
[`core-preference-service.md`](core-preference-service.md) for the two data sources it needs.

---

## 1. The problem

A signed-in user lands on `/control/home`: Control's notification feed, inside Control's chrome
(side rail with Home / Apps / Data / Services / Settings). The apps are reached through a small
nine-dot popover in the top bar. So the first thing the web shows is the *settings* app, and the
thing people came for — their files, their photos — is one hidden click away.

Three further problems:

- **Every app wears Control's chrome.** Drive, Photos and the rest render under Control's side
  rail and bottom nav, losing width to navigation that is about something else.
- **All nine apps are always listed.** Most users will use two or three. There is no way to
  pick, and the launcher's "Install another app" entry is a disabled placeholder.
- **Nothing shows what is in an app without opening it.** The desktop tray already answers
  "anything need me? is sync fine? open an app" in one glance; the web has no equivalent.

The model is iCloud.com's home: a short status area, the apps, and one preview card per app
that opens the app when clicked — without iCloud's large profile card.

## 2. Ground facts (verified 2026-11-13)

- **One shell wraps both Control and the apps.** In `web/apex/src/RuntimeApp.tsx` every
  `HOSTED_APPS` route is a child of the same `<ControlShell>` route as `/control/*`, so an
  embedded app renders inside Control's `SideRail` and `BottomNav`.
- **The launcher lists everything.** `control/AppLauncher.tsx` builds its entries from
  `Tenshu` + every `HOSTED_APPS` entry, unconditionally.
- ⚠⚠ **"Installed" was not a real fact on the web** when this was written: the Apps section read
  `MockAppRegistry`, seeded from the bundled manifests, with a placeholder install date and an
  in-memory uninstall. **Resolved in H6** (§4.7): the web now reads the realm's synced
  `AppRegistration` rows.
- **The preference plane exists on the web.** `tenshu-sdk-js`'s `WebPreferencePlaneTest` round-
  trips a setting through the real JS runtime, so a synced setting is available to the apex.
- **The apex holds the runtime in-process; apps reach it through the gateway.** Each app origin
  may call only its registry entry's `methods` (F5, enforced in `lib/proxyGateway.ts`); the
  handlers are composed from `lib/gateway/*` (`GATEWAY_MODULES`).
- **The data for a preview card already exists, inside each app's own allowlist:**

  | App | Methods already on its allowlist |
  |---|---|
  | Drive | `fs.recentFiles`, `storage.status` |
  | Photos | `photo.feedPage`, `fs.getPreview` |
  | Calendar | `calendar.occurrences` |
  | Fit | `fit.dailyAggregates`, `fit.workoutsPage` |
  | Timeline | `timeline.feed` |

- **An embedded app costs a cold load.** `web-embedded-app-loading.md` §1 measured ~180 ms of
  blank surface before Drive's UI appears in its iframe — per app, per load.
- **What the desktop tray shows** (`control-desktop/macos/TrayAgent/main.swift`, `PopoverView`):
  sync state + one line of detail, *Needs action* (pending requests), *Recent* notifications,
  the app list, Pause/Resume sync, Open Control.

## 3. Design

### 3.1 Routes

| Route | Today | Proposed |
|---|---|---|
| `/overview` | — | **New.** The signed-in landing page (§3.3). A runtime route: inside `RequiresRuntime`. |
| `/control/home` | Notification feed | **Unchanged page**, still Control's first section; its rail label becomes *Notifications* so there are not two "Home"s (§3.4). |
| `/control` | → `/control/home` | Unchanged. The gear opens it (§3.4). |
| `/control/{apps,data,services,settings}/…` | Unchanged | Unchanged. |
| App routes (`drive/files/*`, `photos/*`, …) | Inside `ControlShell` | Unchanged paths, inside the new app shell (§3.2). |

⚠⚠ **Not `/home`, and never labelled "Home" in the UI.** `/home` is the **Home app's** route
(`apps/registry/apps/home.ts`, `routePath: 'home/*'`), and "Home" is that app's name. Found while
building H1. The overview is labelled with the brand, **"Tenshu"** — the launcher entry, and the
"← Tenshu" link back from inside an app; the path constant is `OVERVIEW_PATH`
(`web/apex/src/lib/overviewPath.ts`). This doc still says "Home" for the *concept* (the page
iCloud calls home); the product never does.

After onboarding and after unlock, the destination becomes `/overview`. The brand in the top bar
links to `/overview` from everywhere.

### 3.2 Two shells, one session guard

Split today's `ControlShell` in two:

- **`AppShell`** — `/overview` and every hosted app. A slim top bar only: on `/overview` the brand; in
  an app `← Tenshu` plus the app's icon and name. Right side on both: sync dot/pill, launcher,
  bell, gear, device menu. No side rail, no bottom nav — apps get the full width.
- **`ControlShell`** — `/control/*` only. Keeps its side rail and bottom nav; the first entry
  is relabelled *Notifications* (same page, same route).

The session work `ControlShell` does today — `Session.detect()` → `/` or `/lock?next=…`, and
`ensurePersistence()` — moves to one shared parent layout route above both shells, so it runs
once and is not duplicated.
⚠ Keep the `ensurePersistence` effect **above** any early return in that layout; below it, it
becomes a conditional hook and takes the shell down (the comment in `ControlShell.tsx` records
three tests catching exactly this).

### 3.3 The Home page

Top to bottom:

1. **Needs action** — at most two rows, each with one button: pending device-join requests,
   recovery phrase unverified/stale (today's `RecoveryBanner`), a schema-skew update notice. More
   than two collapses to "+N more" in the bell. The band is absent when nothing needs action.
2. **Your apps** — the shown apps (§3.5) as large icons in a row, ending with a dashed
   **+ Add app** tile. This is the most prominent element.
3. **Preview cards ("glances")** — one per shown app that defines one (§3.6), in dock order,
   on a 12-column grid: wide cards (Drive, Photos) span 7/5, narrow ones span 4. A card's header
   (icon, name, subtitle, *Open*) opens the app.
4. **Customize** — a single button at the bottom of the page that enters Customize mode
   (§3.5.1).

On a phone the dock becomes a 4-column grid and the cards stack in one column.

### 3.4 Where Control goes — **decision: split, not a tile**

Control is not a peer of Drive; it is what administers the others. Making it one more tile
would repeat today's mistake from the other side. So its content splits by *urgency*:

- **What needs attention now** moves out to where the tray puts it: the **sync pill** (state +
  the one-line divergence detail), the **bell**, and Home's *Needs action* band.
- **Everything else** stays where it is, under `/control/*`, reached by a **gear** in every top
  bar, and as the last entry of the launcher ("Control — devices, data, storage"). The gear opens
  `/control`, i.e. Control's notification feed, exactly where Control opens today.

**The bell panel is a short list, like the tray's** — not a second feed:

- *Needs action* rows first (each with its one button), then the **last four** notifications.
- A *All notifications* link to `/control/home`. The full history stays that existing page; the
  web gets **no new history page** (a dedicated one may come later, as its own proposal).

| Tray item | Web place |
|---|---|
| Sync state + detail | Top-bar sync pill (Home), sync dot (in an app) |
| Needs action | Home band + bell badge |
| Recent | Bell panel, last four; full history at `/control/home` |
| Apps | Home dock + launcher |
| Pause/Resume sync | Sync pill menu |
| Open Control | Gear |

### 3.5 Choosing apps — the *shown* set

**What it is.** An **ordered** list of app ids the user wants on Home and in the launcher. Its
order is the dock order, the card order and the launcher order — one list, so the three never
disagree. It is a **presentation choice only**:

- ⚠⚠ **Removing an app hides it; it never uninstalls, deregisters or deletes anything.** Its
  data stays in the realm and on every other device, and its permissions are untouched. It must
  **not** be wired to `AppRegistry.removeFromAllDevices` (the Control › Apps action), and the dialog says
  so in plain words ("its data stays in your realm and on your other devices").
- ⚠ **Hidden is not revoked.** A deep link to a hidden app's route still opens it (and offers
  *Add to Home*); the gateway's allowlists do not consult this set.
- ⚠ **Drive is always shown, enforced where the set is *read*.** Other apps keep their files in
  Drive. A stored set without `drive` (an old row, a bug, another client) still renders Drive;
  the dialog shows it as *Always on* with no Remove button. Enforcing only in the writer would
  let one bad row hide it.
- Apps that depend on Drive say *Uses Drive* in the dialog. As built (H2) this is **derived**, not
  declared: an app uses Drive when its allowlist (`methods`) holds any `fs.*` method
  (`overview/shownApps.ts` `usesDrive`). A declared `requires` field could drift from what the app
  can actually call; the allowlist cannot.

**Where it is stored.** **Decided: one synced realm-wide preference, shared by every browser**
— not per device, and not `localStorage` (per browser, lost on site-data clears). Two browsers
editing at once: last write wins on the whole list; an app id the reader does not know (a newer
client added it) is kept in the list and skipped when rendering, so an older browser never drops
it. As built: `OverviewPreferences.ShownApps` (`apex.overview.shownApps`, REALM,
`StringListAdapter`) in `tenshu-sdk-js/…/Overview.kt`, exposed as `@tenshu/sdk/overview`.
⭐ **A new preference KEY is not a reshape** — it is a new `RealmSetting` row (`preferenceId` +
`genericData` value), so House Rule 9 owes no bump and `SchemaShapeBaseline` does not move. ⚠ An
empty stored list means "never customised"; the apex never writes one (Drive is always in it).

**The first time.** **Decided: a realm with no stored list shows Drive, then Photos.** Nothing is
written until the user customizes, so the default can change later without migrating rows.

**"You use it on Pixel 9."** The dialog's *More apps* rows say which of the user's devices
already run the app, from the realm's `AppRegistration` rows (H6, §4.7). ⚠⚠ **Never a guessed
line:** loading, a failed read, an app no device registered, and a device the roster cannot
name (revoked, or its row not synced yet) all show **no** line — not "on no devices", not an id.
⚠ Every row is another device's: the browser writes no registration of its own (it has no native
IPC handshake), so a missing row never means "not used in this browser". The line never reorders
the list — rows jumping when the read lands would move one out from under the pointer.

#### 3.5.1 Customize mode — the only place the layout changes

Reordering and removing happen **only in an explicit mode**, so a stray drag on the everyday
page never rearranges it (the iCloud.com pattern).

- **Enter:** the *Customize* button at the bottom of Home. Leave: *Done* in a bar that replaces
  the top bar while the mode is on (Escape also leaves).
- **In the mode:**
  - Each removable dock icon and card shows a **−** badge; it hides that app (with an
    *Undo* toast, no confirm dialog — nothing is lost). Drive shows no badge.
  - Dock icons and cards are **draggable**; dragging either reorders the one list (§3.5), so
    the dock and the cards move together.
  - The **+ Add app** tile opens the *Your apps* dialog (removed apps reappear there under *More
    apps*). As built the bar holds only *Done* and a hint — one way out while editing.
  - Card bodies stop being links, so a click never navigates mid-edit.
- ⚠ **Keyboard and screen-reader reorder is required, not a follow-up.** Drag alone fails
  WCAG 2.1.1. Each item gets *Move earlier* / *Move later* buttons (visible on focus) and an
  `aria-live` announcement of its new position.
- **Saving:** each change writes the list at once (§3.5); *Done* only leaves the mode. A failed
  write says so and reverts that change — it never leaves the screen showing an order that was
  not saved.
- Not in v1: background colour, card sizes, per-card content options (iCloud's chevron menu).

### 3.6 Glances — **decision: declared in the registry, drawn by the apex, scoped to the app's allowlist**

Three ways to draw a preview card:

| | How | Verdict |
|---|---|---|
| **A** | Each app serves a `/glance` page, framed in the card. | Rejected for v1: up to nine cross-origin cold loads (~180 ms each, §2) on the page meant to be fastest. Kept as the later route for third-party apps, which cannot ship code into the apex. |
| **B** | The apex draws app-specific cards with its full runtime access. | Rejected: the apex can read everything, so a card would see more than its own app may — it erases F5's boundary. |
| **C** | The app's registry file declares the card: a fixed layout plus a loader that may call **only that app's `methods`**. The apex draws it. | **Chosen.** One load, no iframes, and Photos' card can read nothing the Photos app cannot. Still one file per app (F4). |

Shape, on `HostedApp` (`apps/registry/types.ts`):

```ts
readonly glance?: {
  readonly layout: 'list' | 'grid' | 'stat' | 'agenda'
  readonly size: 'wide' | 'narrow'
  // `call` rejects any method not in this app's `methods` — the SAME check the gateway applies
  // to the app's origin, not a second implementation of it.
  load(call: ScopedCall, signal: AbortSignal): Promise<GlanceData>
}
```

Rules:

- **Apps contribute data, never markup.** `GlanceData` is plain values the apex's four layouts
  render; no HTML strings. Realm data is user-controlled, so this keeps the card's XSS surface
  at React's normal escaping.
- ⚠⚠ **Thumbnails come from the `fs.getPreview` op**, never by projecting `File.Preview` — on the
  web only the dispatcher path awaits (`drive-web-parity.md` §1.2i).
- ⚠⚠ **A failed load is a failure, never an empty card.** The card keeps its header and says it
  couldn't load, with a retry. "No files yet" is a separate, real empty state. (The same rule as
  Control-on-iOS: not-connected is a failure, never an empty success.)
- Load after first paint, each card on its own, with a skeleton; abort on unmount. v1 is
  one-shot plus a refresh on window focus; switching to the `*.observe` subscriptions is later.
- Clicking the header opens `manifest.launchPath`. A row opens its item only where the app
  already has a deep-link route; otherwise rows are not links.

v1 glances (verify each method's parameters when building):

| App | Layout | Loader |
|---|---|---|
| Drive | list, wide | `fs.recentFiles` (4–6 rows) + `storage.status` for the usage bar |
| Photos | grid, wide | `photo.feedPage` (`pageSize: 6`) + `fs.getPreview` per tile |
| Calendar | agenda, narrow | `calendar.occurrences` over the next 7 days, first 3 |
| Fit | stat, narrow | `fit.dailyAggregates` for today + `fit.workoutsPage` (1) |
| Timeline | agenda, narrow | `timeline.feed` for today |

Contacts, Maps, Home and Mia get no glance in v1: icon only.

### 3.7 Inside an app

The top bar's launcher lists **Tenshu** (the overview) + the **shown** apps, then *Add or remove apps* and
*Control*. The disabled "Install another app" placeholder goes.

## 4. Phases

| Phase | Scope |
|---|---|
| **H1** | Routes + shell split (§3.1, §3.2); `/overview` with the Needs-action band and the dock showing every app; gear; no glances. |
| **H2** | The shown set (§3.5): synced ordered setting, Drive enforced at read, *Your apps* dialog, launcher filtered. |
| **H3** | Customize mode (§3.5.1): − badges, drag + keyboard reorder, Undo. |
| **H4** | Glance contract + scoped `call` (§3.6); Drive and Photos glances. |
| **H5a** | Calendar, Fit, Timeline glances (`agenda` + `stat` layouts). |
| **H5b** | Bell panel (needs action + last four notifications, readable text); sync pill (state + *Sync now*). |
| **H5c** | Pause/Resume in the sync pill (§4.8): a device-local switch per browser, a pause gate in the web runtime. |
| **H6** | The web reads the realm's `AppRegistration` rows (Control › Apps shows real devices and install dates); "You use it on …" lines in the dialog's *More apps*. |

### 4.1 As built — H1 (2026-11-13)

- `shell/SessionLayout` (session guard + `ensurePersistence`, once) → `shell/AppShell` (`/overview`
  + hosted apps) | `control/ControlShell` (`/control/*`, first section relabelled *Notifications*).
- `/overview` = `OverviewPage`: `RecoveryBanner` as the Needs-action band + `AppDock` of **every**
  registered app (the shown set is H2). Dock: 4-across icons on phones, bordered tiles from `sm`.
- Landing destinations (onboarding, unlock, recovery, landing CTA and signed-in banner) →
  `OVERVIEW_PATH`. ⭐ Unlock now honours `?next=` through `postUnlockPath`, which accepts only a
  same-origin path — it was built but never read before.
- Launcher: the overview entry ("Tenshu"), app icons (`apps/AppIcon.tsx`; per-app colours since the
  visual pass, §4.9), 3-across, and a Control link in the arrow-key cycle.
- Not in H1, as planned: bell, sync pill, device-join requests in the band (the web has no
  pairing-approval surface yet), shown set, Customize, glances.

### 4.2 As built — H2 (2026-11-13)

- **Storage:** the REALM preference above, through `Overview.observeShownApps` / `setShownApps`
  (`tenshu-sdk-js`), pinned by `OverviewWebTest` (a `jsBrowserTest` — ⚠ not in `run-gate.sh`).
- **Seam:** `overview/shownAppsContext.ts` + `ShownAppsProvider`, mounted by `SessionLayout` only
  for an ACTIVE session (a locked or first-time browser never subscribes); `RuntimeApp` injects
  `realShownApps`, tests inject `test/fakeShownApps.ts`.
- **Reads:** loading → the dock holds its height and lists nothing (a default could be wrong for a
  customised realm). ⚠⚠ Unreadable → **every** app, a status line saying so, and no editing — never
  a guess that hides apps.
- **Dialog:** a route, `/overview/apps` (`YOUR_APPS_PATH`), so the launcher's *Your apps…* opens it
  from inside any app. Each Add/Remove writes the whole list at once; a failed write shows an alert
  and nothing moves, because the screen only follows the store's emissions.
- The launcher lists the overview + the shown apps, then *Your apps…* and *Control*.
- ⚠⚠ **A write resolves before the store re-emits.** For that window `stored` is stale, so the dialog
  builds the next change from the list it just wrote until the store emits anything newer — else a
  second quick click drops the first change. `test/fakeShownApps.ts` can hold emits open
  (`holdEmits`) because a fake that emits inside `write` hid exactly this race.
- `Overview.kt` forwards only a CHANGED list (the realm relation re-emits for every realm setting)
  and skips a write of an unchanged list (a no-op still burns a sync block).

### 4.3 As built — H3 (2026-11-13)

- **Route:** `/overview/customize` (`CUSTOMIZE_PATH`); `AppShell` swaps its whole top bar for the
  Customize bar (*Done* + a hint, the hint hidden on phones where drag does nothing). Escape leaves
  unless a dialog is open. The everyday dock has nothing draggable and no badges.
- **`overview/CustomizeDock.tsx`:** − badge (none on Drive) → write + Undo toast (10 s); Undo
  re-inserts at the old index of the list as it is **now** (`withRestored`), not the old list.
  Move earlier/later buttons on every tile — always visible on phones, on hover/focus from `sm` —
  plus HTML drag-and-drop for pointers. Every change is announced in an `aria-live` region.
- **Reorder rules** (`withMovedBy` / `withMovedTo` / `withVisibleOrder`): only the KNOWN apps'
  slots are refilled, so an unknown id keeps its place.
- ⚠⚠ **One write overlay, in `ShownAppsProvider`, for every consumer.** Per-editor overlays let
  the dialog opened from Customize build from a list missing Customize's last change (and the
  reverse). The overlay holds while the store still shows the list it was written against and goes
  when the store shows the written list or anything else. Editors build from it and refuse new
  changes while a write is in flight; the three race tests fail without it (checked).
- Refusal is `aria-disabled`, never `disabled`: disabling the pressed button dropped keyboard focus.
  Focus returns to the pressed button after a move and to the neighbour after a hide. The move
  buttons hide behind hover only under `(hover:hover) and (pointer:fine)` — a tablet is wider than
  `sm` and has neither hover nor HTML drag. A failed Undo keeps its toast.
- ⚠ Not hand-verified: a real pointer drag. The browser pane's synthetic drag does not start
  native HTML drag-and-drop; the handlers were checked with dispatched `DragEvent`s in the page.
- No cards yet (H4), so the −/drag/move apply to the dock only; cards join the same list.

### 4.4 As built — H4 (2026-11-13)

- **Contract:** `HostedApp.glance?: AppGlance` (`apps/registry/types.ts`) — `size`, a `subtitle` key,
  and `load(call)` returning `GlanceData` (`list` rows + optional usage bar, or `grid` tiles). Apps
  give data; the apex draws (`overview/glance/GlanceCard.tsx`, `GridBody.tsx`).
- ⚠⚠ **Scope:** `scopedCall(app, gateway)` (`overview/glance/glanceGateway.ts`) refuses any method
  outside `app.methods` with the gateway's own `isMethodPermitted` / `not permitted`. The unscoped
  `callInProcess` (`lib/inProcessGateway.ts`, the same handler modules the iframe gateway serves) is
  injected at the runtime root and handed only to `scopedCall`. A test runs **every** registered
  loader through its own scope, so a loader reaching outside its allowlist fails CI, not production.
- **v1 cards:** Drive (`fs.recentFiles` + `storage.status`; the bar is dropped, not the card, when
  storage fails) and Photos (`photo.feedPage`, tiles via the card's own `fs.getPreview` — ⚠ never a
  projected `File.Preview`). `{ ok: false }` from the feed is a throw.
- **States:** loading (holds height) · failed (header + "couldn't load" + retry — ⚠⚠ never the empty
  state) · empty (per-app copy) · data. Reloads on window focus, keeping what it shows.
- Cards follow the shown list's order and are not drawn while customizing or while the list is
  unknown. Wide = half the row from `lg`, narrow = a third.
- Checked against the real runtime: empty states on a fresh realm, then two uploaded files listed
  and a PNG tile drawn through Photos' scope.
- ⚠⚠ **`FileSystem.recentFiles` used to answer `[]` on a failed read** (Kotlin `fold(onFailure =
  { emptyArray() })`), so the Drive card — and Drive's own Home — said "no files yet" on a broken
  read. It now **rejects**; both callers render that as an error. ⚠ `FileSystem.list` still folds
  failure to `[]` (out of this track's scope).
- A card reloads on focus at most every 30 s (a load can walk the whole drive). A failed *refresh*
  keeps the last good data without a sign; only a failed first load or retry shows the failure.
- Known, not fixed: the Drive card sorts by `File.LastModifiedAt` but shows the content-modified
  date (as Drive's Home does), and it can show fewer than six rows when the newest entries are
  folders. `callInProcess` does not unwrap `transferring(…)` answers — no card may load through one.

### 4.5 As built — H5a (2026-11-13)

- **Layouts:** `agenda` (`GlanceAgendaRow`: title, local wall time, all-day, a detail line) and
  `stat` (a number, a unit key, optional goal progress, one footnote row) beside `list`/`grid`. The
  apex words the time ("Today · 14:00", "Tomorrow", "Fri 14 Nov") in `AgendaStatBodies.tsx`.
- **Loaders** (registry files, shared `apps/registry/glanceTime.ts` — browser zone, DST-safe days):
  - Calendar: `calendar.occurrences` for the next 7 days, `includeHidden: false`, the next three
    still to END, placed by `startEpochMs` on the reader's clock.
  - Fit: `fit.dailyAggregates` for today is the card; `fit.goals` (bar) and `fit.workoutsPage`
    (footnote) are garnish. **0 steps is a value**, never the empty state.
  - Timeline: ⚠ the whole `timeline.feed` (no day-scoped read on web — the read the app makes on
    open), keeping today's three latest moments.
  - Every `{ ok: false }` is a throw; the every-loader scope test now covers all five cards.
  - ⚠ **Never an ingest-time English word as a title.** `GlanceAgendaRow.titleKey` names a catalogue
    key the apex words (a movement mode, a home/work role, an activity type); only user data (an
    event title, a place name) travels as `title` — the Timeline app's own `momentTitle` rule.
  - An all-day event already under way is labelled by today, not the day it began.
- **Row packing:** cards keep the user's order on a 6-column grid; a row that ends short (a third
  beside a half) widens its last card (`glance/packSpans.ts`) instead of leaving a hole.
- Checked against the real runtime: all five cards load; Calendar/Timeline show their empty states.

### 4.6 As built — H5b (2026-11-13)

- **SDK:** `Notifications.observeRecent(limit, …)` (`tenshu-sdk-js`) — the web twin of the desktop
  tray's `recentNotifications`: `{ ok, events: [{ id, timestamp, kind, name, otherCount }] }`, newest
  first. ⚠⚠ It is a **port** of `NotificationMapper.mapToEventOrNull` (`:control-shared-logic`, not
  an SDK dependency): a kind added there owes one here. Unknown subtypes are skipped; a failed read
  is `ok: false`, never `[]`.
- **Seam:** `shell/statusContext.ts` (recent events, sync row, *Sync now*), real source
  `shell/realStatus.ts` injected at the runtime root; nothing subscribes until a bell or pill mounts.
- **Bell** (`shell/Bell.tsx`): the badge counts what **needs action** (today the recovery phrase —
  there is no read state on the web to count); the panel lists needs-action rows, the last four
  events in the tray's words, and *All notifications* → `/control/home`. A failed read says so.
- **Sync pill** (`shell/SyncPill.tsx`, wording `syncView.ts`): a labelled pill on the overview, a dot
  inside an app; "Checking" until the runtime has a row, never a premature "Synced". The panel
  shows last synced, what is waiting, *Devices* and *Sync now* (and, since H5c, Pause/Resume — §4.7).
- ⚠ `SyncHealth.observe` had no end path, so a dead stream left the pill on its last row ("Synced"
  forever). The pill uses the new `SyncHealth.observeUntilEnd` and reads **Unknown** once the stream
  ends; `observe` keeps its shape for the `sync.health.observe` bridge. *Sync now* sends one kick per
  press and its "requested" note resets on reopen. Popovers move focus inside on open.
- Checked against the real runtime: the bell showed "Added notes.txt and 1 more" (the producer's
  coalesced row for two uploads) and the pill reported the browser's real `device_unrecognized`.

### 4.7 As built — H6 (2026-11-13)

- **SDK:** `Apps.observeRegistrations` / `Apps.removeApp` (`tenshu-sdk-js/…/Apps.kt`, `@tenshu/sdk/apps`)
  over `AppService.appRegistrations` combined with `IdentityService.devices`, both seeded with a
  one-shot read (an empty relation emits nothing). Emits `{ ok, apps: [{ appId, installedAtMs,
  verified, devices: [{ deviceId, name }] }] }` for **Active** rows; `name` is `null` unless the
  device is on the **authorised** roster. Pinned by `AppsWebTest` (`jsBrowserTest` — ⚠ not in
  `run-gate.sh`), through the live observer: the guarded failure is the relation never being
  maintained in a browser, which a join-only test would pass against. The relation needs no new
  plugin: the generic `RelationMaintainer` the Home/Health/Place plugins install serves every
  declared maintained relation, and `AppRegistration`/`RemoveApp` are in `:core-common`'s default
  registry and op factory.
- ⚠⚠ **Two id spaces.** Rows carry the native catalogue id (`app.tenshu.photos`,
  `BundledAppManifestSource`); the web registry keys by `photos`. `control/apps/appIds.ts` derives
  one from the other and `appIds.test.ts` pins all nine. A third-party id is dropped on the web
  (the apex hosts nothing to show it against) — native's "unrecognised app" card (catalogue C3) has
  no web twin yet.
- **Seam:** `AppRegistry` gained `registrations()` (`loading | unavailable | ready`); the hosted-app
  half (`listInstalled`) is unchanged and still means "openable here". Production injects
  `realAppRegistry` at the runtime root; it subscribes on the first listener and stops (deferred a
  tick, so a remount does not restart it) on the last, so sitting above the public routes starts no
  runtime. ⚠ The context default is `UnconfiguredAppRegistry` (registrations `unavailable`), **not**
  the mock — a missing provider must not show invented devices. `MockAppRegistry` is tests-only.
- **Control › Apps:** cards say "On N devices" / "Not on your devices yet" (nothing while loading or
  failed); detail shows the real install date (earliest `createdAt`) and a *Devices* list.
  ⚠⚠ **Removal is all devices or nothing** (`RemoveApp(appId, null)`: tombstones + grant purge) and
  is offered only when some device holds a row: the browser has no row, so "this device" would be a
  silent no-op. The page stays put afterwards — the web still hosts the app. The fake
  `installedAtMs` / `V1_INSTALL_AT_MS` placeholder is gone from the registry files.
- ⚠ **Grants are still display state on the web.** What an app may call is its gateway allowlist
  (F5); the realm's `AppGrant` rows are keyed by native `(serviceId, operationId)`, which the web
  manifests' permission ids (`photos.read`) do not carry. Wiring them is its own slice.

### 4.8 As built — H5c, Pause/Resume (2026-11-13)

- **The switch is the browser's, never the realm's.** `SyncPause` (`tenshu-sdk-js` internal) persists
  it in `localStorage` (`tenshu.sync.paused`) — per origin, so per browser profile, the same scope as
  the browser's device identity. Pausing one browser pauses no other device; the panel says so. Tabs
  of one origin follow each other through the `storage` event, so a second tab's runtime cannot keep
  pushing. Readable before the runtime starts; a reload comes back paused.
- **SDK:** `SyncStatus.isPaused()`, `setPaused(Boolean)`, `observePaused(cb)` (current value first);
  TS in `web/sdk/src/native/sync.d.ts`. Seam: `StatusSource.observePaused` / `setPaused`.
- ⚠⚠ **What a pause gates is a new `pauseGate`, not the desktop tray's route.** Desktop composes its
  `SyncPauseState` into the *policy* `syncGate`, and since `sync-metered-plane-split.md` that gate is
  read-only to the scheduler and holds **file content only** — metadata push and pull keep running
  under a tray that says *Paused* (`tech-debt.md` *"The desktop tray's Pause holds file content
  only"*). `initSyncService(pauseGate = …)` instead (a) makes `SyncScheduler.tickOnce` skip the whole
  cycle — push, pull, shared realms, the deferred `onEnabled` replay — and (b) composes into the
  observer's `contentGate`, so uploads park. The policy `syncGate` stays separate, so a paused tick
  never reports *deferred* ("Waiting for Wi-Fi").
- ⚠ **Not every relay call is sync.** Paused, the browser still answers the storage-usage poll
  (`GET /sync/usage`) and any read the user asks for (opening a file's bytes). The promise is *no
  push, no pull, no upload* — which is what the copy says ("Changes stay here until you resume").
- **Pill:** `syncView(row, lost, paused)` — ⚠⚠ `paused` outranks every row, so a paused browser
  never reads *Synced*. The panel swaps *Sync now* for *Resume*: a kick cannot get past a pause
  (the scheduler holds even a pending `forceDrainOnce` until resume). Resume wakes the loop
  (`notifyForegrounded`, the gate-respecting wake).
- Checked against a local relay (`:sync-relay-server:run`) with the apex dev server: pulls every
  ~1.5 s → pause → no `/sync/log` or `/sync/push` from the browser for ~55 s, while a Customize
  change queued locally → Resume → that write pushed within 5 ms and pulls resumed. Reloaded while
  paused: the new runtime started paused and pulled nothing.

### 4.9 Visual pass (2026-11-14)

The look, not the layout: [ADR 0027](../adr/0027-web-overview-glass-and-app-colours.md) has the
decision and the contrast rules. The maintainer picked *Twilight glass* from three mocked looks.

- Wallpaper + glass top bar while no app is open (`AppShell`); a hosted app keeps `surface`.
- Glass cards (`GlanceCard`), dock icons straight on the wallpaper, glass tiles in Customize.
- Per-app colour pairs on every `AppIcon`; each card's accents (storage meter, agenda bars, Fit's
  goal ring — now a ring, still a `meter`) take its app's pair via `apps/appColor.ts`.
- Rose `--attention` for the recovery band, the try-mode strip and the bell badge; no more
  `warning` brown there.
- ⚠ Text on glass is `text-2` at the lightest — `text-3` is under AA on it.

## 5. What this supersedes in `web-control-plane.md`

- §2: `/control/home` is no longer the landing destination; `/overview` is.
- §3: one shell for Control and the apps → two shells over one session layout.
- §3.1: the launcher lists the shown apps, not every app, and drops the install placeholder.
- §4.1 stands: the feed page is unchanged; only its rail label becomes *Notifications*.

## 6. Tests the build owes

- Drive renders on Home and in the launcher when the stored set omits it.
- The scoped `call` rejects a method outside the app's `methods` (and shares the gateway's check).
- A hidden app's deep link still opens the app.
- A glance whose loader throws renders the error state, not the empty state.
- Hiding an app never calls `AppRegistry.removeFromAllDevices`.
- Outside Customize mode nothing is draggable and no − badge renders.
- Keyboard reorder moves an item and announces its position; dock and card order stay equal.
- An unknown app id in the stored list survives a reorder written by an older client.
- With no stored list, Home shows Drive then Photos and writes nothing.
- The bell panel shows at most four notifications.
- `/overview` sits inside `RequiresRuntime` (`RuntimeLaziness.test.tsx`, `BundleSplit.test.ts`).
- "You use it on …" shows no line while loading, on a failed read, or for a device the roster cannot name (`OverviewPage.test.tsx`).
- New strings in every locale (`i18nParity.test.ts`).

## 7. Decisions (maintainer, 2026-11-13)

| # | Question (v0.1) | Decision |
|---|---|---|
| D1 | Default shown set for a new realm | **Drive, then Photos.** |
| D2 | Realm-wide or per-device shown set | **Realm-wide** — shared by every browser. |
| D3 | A notification history page | **None new.** History stays the existing Control feed (`/control/home`); the bell holds a short list, **last four**, like the tray. A dedicated page may come later. |
| D4 | Reordering | **Yes**, via an explicit **Customize mode** (§3.5.1) so nobody reorders by accident. |
