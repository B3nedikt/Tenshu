# Control central notifications & pinned requests

**Status: Phases 1–4 shipped (2026-06-22 → 06-23).** Gives Control a
**central, elevated surface for inbound actionable requests** — pairing approvals,
membership grants, contact invites, and (later) app-reconnect wakes — including
**pinned/sticky** notifications that stay at the top until the user resolves or dismisses
them. Motivated by a session question on the EPC pairing flow ([§10](ios-proxy-architecture.md)):
without elevation, a user must keep the right section open to notice an inbound request,
which is unintuitive. Owned by the Control IA work ([`control-ia.md`](control-ia.md)).
**Shipped:** P1 aggregation core · P2 in-app surface · P2b actions + pinned
cards · P3a Android OS elevation · P3b desktop tray · **P4 synced
dismissal**. ✅ **The in-app surface now ships on Control-on-iOS too** (NT-1) — the
center, the cards, the pinned slot and a Home bell. ⚠⚠ **P3a has no iOS twin, and that is now a
recorded decision rather than pending work** — see
[`ios-notification-elevation.md`](ios-notification-elevation.md) (D1). It is not a missing
presenter: `IosNotificationPresenter` exists and iOS binds `NoopNotificationPresenter` *on purpose*
(per-app `UNUserNotificationCenter`, one proxy graph per route-1 app). The stronger reason is that
Apple platforms **pre-arm** rather than wake, an inbound request cannot be pre-armed, and
`control-ios` declares no background modes at all — so both delivery mechanisms are closed to this
event class. See [`ios-control-notifications-and-shares.md`](ios-control-notifications-and-shares.md) §3
before touching that binding. Outstanding: **P5 6e app-reconnect hook — BLOCKED on Control-on-iOS**
(no producer exists on any platform today; see §8 Phase 5 for the prerequisite list).
**Phase-4 decisions (maintainer, 2026-06-23):** dismiss = **mark-seen** (clears the badge +
pinned card + OS notification on every device, but the request stays actionable in the
center until resolved — supersedes O3's "snooze re-surfaces", which doesn't model as a
synced fact); the seen marker is a **dedicated synced `DismissedRequest` record** keyed per
id (so concurrent multi-device dismissals union-merge) rather than a list preference (which
would last-writer-wins clobber).

---

## 1. Problem

Control already has a notification substrate, but it only does **ambient** notifications well:

- `:notification-service` carries a **synced** `Notification` stream (`Syncable`,
  `SyncStrategy.Eager`); Control maps it to `Event`s and renders a paged **event feed** on
  Home (`TenshuEventRepository` → `HomeScreenViewModel`). These are *passive* ("a file was
  added", "an app was registered") — fine as a scrollable history.

The **actionable, inbound** requests — the ones a user must *act on* — are by contrast
**scattered, poll-based, and buried**:

| Inbound request | Discovered today | Surfaced today |
|---|---|---|
| **Device-pairing approval** (a joiner Control wants into the realm; host approves w/ SAS) | 2 s **poll** while a pairing session is live (`SyncDashboardViewModel.startPairingObserver`) | a dialog **only on the Sync dashboard** |
| **Incoming membership grant** (GR-6 `PendingGrant`) | **on-demand** poll on visiting the screen (`pollPendingGrants`) | a card **only on Drive's *Shared with me*** |
| **Contact invite** redeemed (CI-4) | synced `ContactInvite` state | a badge **only on Drive's Shared tab** |

Consequences: there is **no global bell / badge / unread count** in Control; **no priority**
distinction between ambient events and actionable requests; and **no "pinned"/sticky** concept
— every notification is a transient list row. So an inbound request can sit unnoticed unless
the user happens to have the exact screen open (and, for the polled ones, *keeps* it open).

## 2. Scope — what is inbound vs ambient (and the EPC-pairing clarification)

This mechanism is for **inbound actionable requests**, not the ambient feed (which already
works). One clarification that bounds the scope:

> **EPC *app* pairing (§10) is Control-proactive, not inbound.** In the §10 flow Control
> **shows** the QR and the app **scans** it — the user opens *"Pair a new app"*, so there is
> no async "app → Control" request to be notified about. The reverse only appears in two
> *later* cases: (a) a **remembered app reconnecting** to a **suspended Control-on-iOS**
> (§10.4 / Phase 6e) — the app posts to the rendezvous mailbox and Control must *wake* and
> notice; (b) any future **third-party app-initiated** pairing. Both are out of v1 scope but
> are exactly why this surface should exist before 6e.

So the v1 inbound set is the three rows in §1 (pairing approvals, grants, invites). The
mechanism is designed to absorb the 6e app-reconnect wake when it lands.

## 3. Existing substrate (reused, not reinvented)

- **`Notification` / `SendNotification`** (`:notification-service`) — synced stream, the
  ambient feed's source. *Could* carry actionable notifications too, but that means a wire/
  schema change (a priority/pinned/resolution field) and a threat-model touch — see §4.5.
- **`Event` mapping + 60 s aggregation** (`NotificationMapper`) — the presentation pipeline
  pattern to mirror.
- **`SyncBanner.evaluate(...)`** — a pure `state → banner?` decision function; the model for
  deriving pinned items from observable inputs without mutation.
- **`InviteBadgeViewModel.showBadge: StateFlow<Boolean>`** — the existing (Drive-side) badge
  pattern to lift into Control's shell.
- **Control shell** (`App.kt` `AdaptiveNavScaffold`, `Destination`) — where a global bell/
  badge + a notification-center destination attach.

## 4. Proposed design

### 4.1 Two tiers: ambient events vs actionable requests

Keep the existing **event feed** as the ambient tier. Add an **actionable-requests** tier:
items that demand a decision (Approve/Deny, Accept/Decline) and **persist until resolved or
explicitly dismissed**. The two tiers share a surface (a notification center) but render
differently — requests are pinned and carry actions; events scroll and don't.

### 4.2 v1 = aggregation over Control's own client (no wire change)

v1 **aggregates the existing inbound sources** rather than inventing a new synced type:

- A Control-side `PendingRequestsRepository` exposes `StateFlow<List<PendingRequest>>`.
  `PendingRequest` is a sealed type (`MembershipGrant`, `ContactInviteRedeemed`, + future
  `PairingApproval`/`AppReconnect`) with a stable id, a display summary, and the actions.
- This mirrors the `Event`/`SyncBanner` pure-mapping precedent: **no schema/wire/threat-model
  change** for v1. Cross-device convergence still works because the *underlying* sources are
  already synced or relay-backed; only the *aggregation* is local.

**Phase 1 as-built (2026-06-22).** The cold-inbound v1 sources are **incoming membership
grants + redeemed contact invites**, which Control owns as the realm/identity plane but were
only read by Drive. The chosen direction (over a Drive-surfaced or presentation-only hack) is
that **Control consumes the service clients directly via its own in-process `TenshuClient`** —
`client.contactService.contactInvites` (reactive, chain-DSL `.select{}.flow()`, filtered to
`REDEEMED`) + `client.syncService.pollPendingGrants()` (poll-only → polled on the repo's
process-lifetime scope). `TenshuPendingRequestsRepository` combines them; the entity→model
mapping mirrors Drive's `ContactsRepository`/`MembershipRepository`. **Pairing approvals are
*not* a v1 source** — both pairing flows are host-initiated (the user already started pairing;
§2), so they are not cold-inbound; `getPendingPairingRequests` also only returns anything while
a pairing session is live. The pure mapping/aggregation is unit-tested; the client wiring
compiles against the real APIs. The §4.6 reactive-pairing fix is therefore deferred with the
pairing source.

### 4.3 Surfaces

1. **A bell + badge in Control's top bar** (`App.kt` scaffold) — badge count = unresolved
   `PendingRequest`s; tapping opens the notification center.
2. **A notification center** — a `Destination.Notifications` screen (or a sheet) listing
   actionable requests (pinned, with actions) above the ambient event feed.
3. **Pinned cards on Home** — the highest-urgency unresolved requests render as sticky cards
   *above* the event feed (the slot already used for recovery prompts), so a user lands on
   them without navigating.
4. **OS-level elevation when Control is backgrounded** — extend the Android foreground-service
   notification (today task-count only, `ProxyService`) to post a **system notification** for
   a new actionable request, and the **desktop tray** equivalent, so the user is alerted
   without Control in the foreground. (This is the part that actually solves "don't make me
   keep a section open.")

### 4.4 Pinned / sticky semantics

A request is **pinned** while unresolved: it stays at the top of the center (and the Home
sticky slot) regardless of newer ambient events, and **survives navigation**. It leaves when
either (a) the user **acts** (approve/deny/accept/decline → the underlying request resolves)
or (b) the user **explicitly dismisses** it (snooze/hide — distinct from resolving). Ambient
events are never pinned.

### 4.5 Decision: local dismissal in v1, synced dismissal later

Whether "dismissed/seen" state **syncs across devices** (so dismissing a pairing request on
the desktop clears the badge on the phone) is the load-bearing follow-up. v1 keeps
**dismissal local** (per-device UI state) because:

- *Resolution* already converges device-wide (approving a pairing request, accepting a grant
  changes synced state → the request disappears everywhere). Only *snooze/dismiss-without-
  acting* would diverge.
- Synced dismissal needs a durable per-request "seen by device" marker — a schema/wire
  addition and a threat-model touch (House Rule #2). Defer until the local version proves the
  UX.

### 4.6 One substrate fix worth doing: make pairing-request discovery reactive

Device-pairing approval is the worst case today — a **2 s poll that stops when the user
navigates away**. For a global surface it must be **reactive** (or at least poll on an
app-scoped owner that outlives the screen, like the GR-6 `FolderUploadController` pattern). This
is the one non-presentation change v1 needs, and it also de-risks the 6e app-reconnect wake.

## 5. Relation to EPC pairing (§10) and Control-on-iOS (6e)

- **Slice 2b ("Pair a new app", §10.6) does NOT depend on this** — it's the proactive QR
  shower; it proceeds independently.
- **6e app-reconnect** *does* — when a remembered app reaches a suspended Control-on-iOS, the
  wake → "an app wants to reconnect" is an actionable request that should ride this surface +
  the OS-level elevation (§4.3.4). Building the surface now means 6e plugs into it.

## 6. Cross-cutting

- **Proxy-isolation (the "Control renders only proxy-authoritative generic data" rule).**
  Honoured: every `PendingRequest` is a **realm-level** request Control itself mediates
  (pairing, grant, invite) — generic, proxy-authoritative data, **not** client-supplied UI.
  No app gets to inject a notification's content.
- **Threat-model.** v1 (presentation aggregation + local dismissal) touches no synced shape →
  no amendment. Synced dismissal (§4.5) and any OS-notification payload (must stay
  PII-free / generic, like the FGS) get a note when built (HR #2).

## 7. Open questions

- **O1 — Notification center as a destination vs a sheet/overlay?** A `Destination` is
  discoverable + deep-linkable; a sheet is lighter. Lean destination + a bell badge.
- **O2 — Home sticky slot vs center-only?** Do pinned requests *also* render on Home, or only
  in the center (badge → center)? Proposed: top 1–2 on Home, rest in the center.
- **O3 — Snooze vs hide.** Is dismiss-without-acting permanent or a snooze? Proposed: snooze
  (re-surfaces next launch) — a request you didn't act on shouldn't vanish.
- **O4 — Drive parity.** Drive has its own inbound badges (Shared tab). Do we unify Drive's
  into the same per-app model, or is this Control-only? Proposed: Control-only v1; Drive keeps
  its tab badges (they're app-scoped lenses, `control-ia.md` §5).
- **O5 — Should SAS confirmation be its own Operation? Which service owns it?** Raised
  while building Phase 2b's invite-confirm card. **Decision: there is no single "SAS
  service" — each handshake owns its own SAS confirmation**, because the SAS is derived
  from *that* handshake's keys/transcript and means nothing outside it:
  - **Contact handshake** → **`contact-service`**. The §5.3 SAS (`ContactInvite.claimedSas`)
    is proxy-derived and already a field on the synced invite row; `CompleteContactHandshake`
    already carries `sasConfirmed` (it upgrades the new identity to `VERIFIED`/`QR_SAS` vs
    `UNVERIFIED`/`INVITE_LINK` — it does *not* gate acceptance).
  - **EPC app pairing** (§10) → the **EPC / pairing layer**, whose SAS binds the X25519
    channel — a different transcript, so a different op.

  The reusable seam is therefore a **common SAS-compare UI** (a `:ui-core` composable),
  *not* a common service. For the "confirm an unverified contact later, from `Control →
  Apps`" flow the user floated, the contact case needs `contact-service` to support a
  **two-phase** completion: accept-now-but-unconfirmed, then a separate
  `ConfirmContactSas(inviteId)` op that flips the identity's verification state. That is a
  contact-service protocol slice (and a threat-model touch — it changes *when* verification
  is recorded), so it is **out of scope for Phase 2b**, which uses the existing atomic
  `completeContactHandshake(sasConfirmed=…)`. Phase 2b keeps the action behind a
  repository/VM method (not inline in Compose) so a later dedicated-UI or `Apps`-section
  entry point can reuse it without a rewrite.

## 8. Phasing (each independently shippable)

1. **Aggregation core.** `PendingRequest` sealed type + `PendingRequestsRepository`
   (`control-shared`) merging the three sources; unit-tested pure mapping (mirror
   `NotificationMapper`/`SyncBanner`). Make pairing-request discovery reactive (§4.6).
2. **In-app surface.** Top-bar bell + badge + a notification center; pinned cards on Home.

   **Phase 2 as-built (2026-06-22).** Read-only: `NotificationsViewModel` (lens
   over the repo), `NotificationCenterScreen` at `Destination.Notifications`, `NotificationsBell`
   in Home's `AppTopAppBar` (the `AdaptiveNavScaffold` has no global actions slot, so the
   bell rides a new `topBarActions` slot on `HomeScreen`).

   **Phase 2b as-built (2026-06-22).** Per-request **actions** + **pinned Home cards**.
   - `PendingRequest.ContactInviteRedeemed` gained `claimedFingerprint` + `claimedSas`
     (the repo's invite `select` now also projects `CLAIMED_FINGERPRINT`/`CLAIMED_SAS`).
   - `PendingRequestsRepository` gained four suspend actions returning a `RequestActionResult`
     (`Success`/`Failure(message)`): `acceptGrant`/`declineGrant` (`syncService`
     `acceptSharedRealmGrant`/`declinePendingGrant`, then `refresh()` since grants are
     poll-sourced) and `confirmInvite(petname, sasConfirmed)`/`declineInvite`
     (`contactService.completeContactHandshake` ACCEPT/REJECT; invites are reactive so no
     refresh). `confirmInvite` sends no self-asserted own-name yet (Control has no own-name
     field — a follow-up; the peer just gets no name from this device).
   - `NotificationsViewModel` tracks per-request `actionState: Map<id, RequestActionUiState>`
     (`inProgress`/`error`); a resolved request drops out of `requests` as its synced state
     converges, and its action entry is cleared.
   - One shared **`PendingRequestCard`** (stateless; takes a `PendingRequestActions` callback
     bundle + the per-request `RequestActionUiState`) renders both in the center and as the
     pinned Home cards. Grant → Accept/Decline. Invite → the §5.3 verification code + a
     **toggleable "the codes match"** row + a petname field (prefilled with `claimedName`),
     then Confirm/Decline; the SAS check upgrades trust but does not gate Confirm (mirrors
     Drive's `ContactPromptDialog`). In-flight disables the buttons; a `Failure` renders inline.
   - **Pinned cards on Home** via a new `pinnedContent` slot on `HomeScreen`, rendered above
     the recovery affordance + event feed; `PinnedRequests` shows the top `maxVisible = 2`
     (O2), the rest stay in the center. Wired in `App.kt`'s Home entry off the same VM.
   - Tests: pure-mapper coverage updated for the new fields; `runComposeUiTest` covers the
     card's grant-accept callback, invite confirm (petname + codes-match flag), in-flight
     disable, inline failure, and the pinned top-2 cap. `:control-shared:desktopTest` green;
     Android on-device pending.
   - SAS confirmation **as its own operation** is deliberately *not* built here — see **O5**.
3. **OS-level elevation.** Android system notification for new actionable requests (extend
   `ProxyService`) + desktop tray; PII-free generic copy.

   **Phase 3a as-built — Android (2026-06-22).** Split from desktop (3b) because Control's
   desktop tray is a separate native macOS Swift process (pull-only bridge), so its
   notification needs Swift work + a signed Mac build to verify — its own PR.

   ⚠ **Liveness reality (the honest cadence).** Control's Android process is **not** kept
   alive: `ProxyService` is *bound-only* (started when Drive binds, or the UI is open, or a
   Layer-2 task is in flight) and is reaped when idle. There is **no FCM / server push** (the
   relay is zero-trust — HR #1 — and cannot wake the device). So when Control is **closed**,
   the *only* scheduled thing that wakes the process is the existing **`SyncWorker`**
   (WorkManager periodic, ~1 h interval; 15 min OS floor; Doze can stretch it to a few times a
   day). Delivery is therefore: **near-real-time while the process is alive**, and **bounded by
   the `SyncWorker` cadence (eventually, not instant) when closed** — never if the OS starves
   WorkManager. Truly instant closed-app delivery needs a push channel (separate design, §4.6
   reactivity / 6e wake; conflicts with the zero-trust relay).

   Two drivers, one notifier (so the stable per-request id keeps the shade idempotent):
   - **`PendingRequestAlerts`** (pure, in `control-shared` commonMain; mirrors
     `ForegroundTaskLiveness`): diffs the `PendingRequestsRepository.requests` stream into
     *arrived* (id new → notify) / *resolved* (id left the feed → cancel) transitions behind a
     `PendingRequestAlertHandle`. Unit-tested in `:control-shared:desktopTest`.
   - **`AndroidPendingRequestNotifier`** (`control-shared` androidMain — *not* the app module,
     so the `SyncWorker` hook can reach it): a **second, HIGH-importance** channel
     `tenshu_actionable_requests` (distinct from the LOW `tenshu_foreground_task_liveness` FGS
     channel); `notify`/`cancel` by a **stable id** (`requestId.hashCode()`); `setAutoCancel`,
     `PRIORITY_HIGH`; deep-links via Control's launcher intent + the deep-link extra (built
     from the package launcher, not a `MainActivity` class ref, to stay in `control-shared`;
     `MainActivity.DEEPLINK_NOTIFICATIONS` → `Destination.Notifications` reads it).
     - **`start(scope, requests)`** — the live observer, started from `ControlApplication.onCreate()`
       on a process-lifetime scope: near-real-time alerts *while alive*.
     - **`notifyCurrent(requests)`** — a one-shot pass invoked by the **`SyncWorker` post-drain
       hook**: this is what delivers the alert when **closed**, reliably *per wake* (it runs
       inside `doWork()` after the pull, before the process is reaped — no Application-scope
       race).
   - **New generic seam:** `PostDrainHook` (a `fun interface` in `:sync-service-proxy`
     androidMain) is invoked by `SyncWorker` after `drainNow()`, isolated by `runCatching` so a
     notify failure never turns a successful sync into a retry. Control binds it
     (`PendingRequestPostDrainHook`) in `PlatformProxyModule`; absent binding ⇒ no-op (keeps
     the sync layer Control-agnostic).
   - **Copy is generic / PII-free** — request *kind* only, never the grantor fingerprint or
     the peer's quarantined claimed name (§6 proxy isolation, same constraint as the FGS).
     Touches no synced shape / relay / sync protocol → **no threat-model amendment** (HR #2
     satisfied: the §6 "note when built" is this PII-free-by-construction statement).
   - v1 trait: a request still pending after a **process restart** re-alerts once (per-process
     known set); bounded + idempotent in the shade by the stable id. No backgrounded-gating —
     the in-app pinned card is the co-resident surface when Control is foregrounded.
   - `:control-shared:desktopTest` (pure diff) + `:control:testDebugUnitTest` green (whole
     Android chain — `control` + `control-shared` androidMain + `:sync-service-proxy` —
     compiles); on-device heads-up / deep-link / permission-denied / background-wake sweep
     pending.
   **Phase 3b as-built — desktop tray (2026-06-23).** The macOS counterpart to 3a,
   over Control's existing **native Swift tray agent + JSON bridge** (the
   tray/daemon split; `control-desktop/macos/TrayAgent`), not a Compose/AWT tray.

   - **Daemon side (one bridge method).** `registerPendingRequestMethods(koin)`
     (`control-shared` desktopMain, wired in `Main.kt`) exposes `pendingRequests` →
     `{ requests: [{ id, kind, timestampMillis }] }`, read from the in-process
     `PendingRequestsRepository.requests` (the same feed the center + pinned cards
     render). PII-free by construction: the row carries the **opaque stable id**
     (notification identifier + diff key, never displayed) + a generic **`kind`**
     code (`membership_grant`/`contact_invite`) + the timestamp — never the
     grantor fingerprint or the quarantined claimed name (§6, same constraint as
     the Android notification). Pure `pendingRequestsResult(...)` shaping,
     unit-tested in `:control-shared:desktopTest`.
   - **Tray side (the OS effect).** `PendingRequestNotifier.swift` polls
     `pendingRequests` on a **10 s background timer** (independent of the popover,
     so it fires while closed), owns the *arrived* / *resolved* diff (mirroring the
     Kotlin `PendingRequestAlerts`), and posts/cancels a `UNUserNotificationCenter`
     notification keyed by the stable id (shade replaces, not stacks). New **Needs
     action** popover section lists the same requests. The tray composes the
     **generic title from `kind` alone** (ADR 0016 — this surface owns its strings).
   - **Tap → Home, no separate deep-link.** A notification tap raises Control via
     the existing `openControlWindow`; Control opens to Home where Phase 2b's
     **pinned request cards** carry the Accept/Decline (or Confirm) actions — so the
     desktop needs no runtime-nav deep-link-to-center plumbing (the Android extra
     has no clean desktop analogue while the window is already composed).
   - **Poll, not push** (deviation, matches 3a's spirit): the bridge is
     request/response and the macOS notification can only be posted by the GUI
     agent that holds notification authorization, not the headless JVM daemon — so
     the daemon serves state and the tray owns the diff. **Liveness:** the resident
     daemon is normally alive whenever Control runs (unlike Android's reaped
     process), so there's something to poll; when the daemon is unreachable the
     poll is **skipped** (delivered alerts left intact, not spuriously cancelled).
   - **Signing gate (why its own PR).** `UNUserNotificationCenter` needs a bundle
     identifier, which a bare `swiftc` binary lacks; in dev (`./tray-agent`) the OS
     path no-ops (the **Needs action** popover section is the dev surface) and the
     banner/tap/authorization path **displays only from a packaged, code-signed
     `Control.app`** (Phase 4 embeds + signs the agent). Touches no synced shape /
     relay / sync protocol and ships only generic copy → **no threat-model
     amendment** (HR #2; §6 "note when built" = this PII-free-by-construction
     statement, same as 3a).
   - `:control-shared:desktopTest` (pure shaping + UDS round-trip) green;
     `swiftc` builds the 3-file agent; on signed-build heads-up / tap / authorization
     / arrived-resolved sweep pending. Windows/Linux have no tray today.
4. **Synced dismissal — SHIPPED (this PR).** Durable per-request **seen** marker, synced.
   **As-built:**
   - **Semantics = mark-seen** (not snooze/hide, superseding O3). Dismissing a request
     records that it has been *seen*; it leaves every **elevation** surface (the unread
     badge, the pinned Home slot, and — because they now diff `unread` — the Android system
     notification + desktop tray banner) on **every paired device**, but stays listed and
     actionable in the notification center until it actually resolves (accept/decline).
     "Seen" is a monotonic synced fact, so it has no re-surface ambiguity.
   - **Carrier = a dedicated synced record**, not a preference. New `DismissedRequest`
     `Syncable(Eager)` composite in `:notification-service` (single field: the request's
     content-derived id, keyed by it via a `DismissedRequestDataIdProvider`) + a
     `DismissPendingRequest(requestId)` op + a `dismissedRequests` service relation +
     `DismissedRequestRelationMaintainer` (mirrors `Notification` end-to-end across
     common/client/proxy). **Why a record, not a list-pref:** one row per dismissed id
     **union-merges** when two devices dismiss concurrently; a single synced "dismissed
     set" blob would last-writer-wins clobber. The proxy stamps the record (client passes
     only the id), so no client-side timestamp/clock.
   - **Repository/VM/UI:** `PendingRequestsRepository` gains `seenIds` (from the relation) +
     `unread` (`requests − seen`) + `dismiss(request)`. The bell badge + pinned slot + both
     OS notifiers read `unread`; the center reads the full `requests` and passes `seenIds`
     so a seen card drops its dismiss "×" (re-dismissing is a no-op) but still renders.
   - **Threat model (HR #2):** A24 *Amendment* — the marker is a personal-realm Syncable,
     ciphertext to the relay, no relay-shape/protocol change; **nil-new device exposure**
     (the id embeds a fingerprint/inviteId the device already holds in the referenced
     grant/invite row). §6 "note when built" satisfied.
   - Tests: `:notification-service-proxy:jvmTest` (`DismissPendingRequestUnitTest` — record +
     relation + idempotent union-merge) + `:control-shared:desktopTest` (dismiss "×" fires /
     hidden-when-seen / seen request stays listed) green. Android on-device sweep pending.
5. **6e hook — BLOCKED on Control-on-iOS (deferred, maintainer 2026-06-23).** The intended
   shape is unchanged: a new `PendingRequest.AppReconnect` variant (the sealed model + the
   center/card/OS-elevation rendering already absorb it generically) surfacing "an app wants
   to reconnect" when a remembered app reaches a suspended Control-on-iOS. **It is not built
   because the entire *producer* side is unbuilt and sequenced after Control-on-iOS** —
   wiring an `AppReconnect` variant now would connect a consumer (and an "approve reconnect"
   action) to a producer that exists on no platform, i.e. speculative scaffolding. The
   prerequisites, all deferred to the iOS track's Phase 6e (see
   [`ios-proxy-architecture.md`](ios-proxy-architecture.md) §10.4 / §9.4 O4):
   - **Control-on-iOS** — no `:control-ios` app target exists (only `:drive-ios` + the
     `:tenshu-sdk-ios` SDK); Control runs on Android/desktop only.
   - **Remembered-app persistence** — no paired-app / rendezvous-session registry. §10.4
     defers durable reconnection as "the 6e seam"; v1 pairing assumes a *live, serving*
     Control, so a desktop/Android app just reconnects silently to the always-on proxy —
     there is no "suspended Control must surface a reconnect" case on those platforms.
   - **Inbound wake path** — `EpcRendezvousAcceptor.serve()` is a passive poll loop that runs
     only once the host explicitly `provision()`s + `serve()`s; nothing wakes a *suspended*
     proxy when an app posts to the rendezvous mailbox. That wake fabric (iOS `BGTaskScheduler`,
     the §9.4/O4 wake-window problem) is unresolved.

   **Resume condition:** pick this up when Control-on-iOS (iOS-track 6c/6d) lands the
   remembered-app + on-demand provision-and-serve machinery; at that point `AppReconnect`
   is the surface-side plug-in, plus an "approve reconnect" action over the new producer and
   a threat-model touch on §8.16 (the EPC rendezvous surface currently says nothing about
   app-reconnect / remembered apps).

## 9. Implementation Notes for Claude Code

- `PendingRequestsRepository` is **Control-shared commonMain**, pure aggregation over existing
  repos — no new wire type in v1. Model the merge as a `combine(...)` of three flows → one
  `StateFlow<List<PendingRequest>>`; keep a pure `toPendingRequest(...)` mapper (testable like
  `NotificationMapper.mapToEventOrNull`).
- The bell/badge attaches in `App.kt`'s `AdaptiveNavScaffold` top bar; the center is a new
  `Destination`. Follow the existing screen+VM+`viewModelsModule` registration pattern.
- The pairing-request reactivity fix (§4.6) is in `SyncRepository` / `SyncManager` —
  prefer an app-scoped observer (outlives the screen) over a screen-scoped 2 s poll.
- OS elevation reuses `ProxyService`'s channel plumbing (Android) — **add a second channel**
  for actionable requests (importance HIGH) distinct from the LOW task-liveness channel; keep
  the copy generic/PII-free (same constraint as the FGS).
