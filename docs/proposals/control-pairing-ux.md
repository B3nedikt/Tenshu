# Control pairing UX — unified "Add a device" + global approval

**Status:** Draft v0.1 (2026-06-24). No code yet. Phase 1 handed over for
implementation.

This proposal reshapes device-pairing in Control so that (a) adding a device has a
single, role-explicit entry point, and (b) the host-side approval prompt is an
app-global, interrupt-level surface rather than a dialog that only exists on the Sync
screen. Phase 1 also closes the open tech-debt item *"Control host-side pairing poll
stops mid-session"* (`docs/tech-debt.md` → Sync, Pairing & Recovery) — the poll
relocation is both the UX mechanism and the bug fix.

This is a UI + proxy-poll-lifetime change. It touches no sync protocol, no relay data
shape, and no A1–A4 / A9 asset, so **no threat-model amendment is required** (House
Rule #2). The pairing SAS ceremony itself — the only security-bearing part — is
unchanged; we only move *where* the SAS comparison is presented. §6 records the audit.

---

## 1. The problem

The **Realm devices** screen
(`control-shared/.../ui/screens/sync/SyncDashboardScreen.kt`) exposes two "add a
device" affordances that are actually the **two opposite roles of one pairing**,
presented as if unrelated:

- **QR `IconButton`** in the top bar (`SyncDashboardScreen.kt:288` →
  `SyncDashboardViewModel.startPairing()`): this device becomes the **origin** — it
  shows a code and waits to approve a joiner.
- **"+" FAB** (`SyncDashboardScreen.kt:304` → `Destination.PairingWizard`): this device
  becomes the **joiner** — it scans a code and asks to be let in.

To add device B you must know that on the existing device A you press the QR button,
and on the new device B you press "+". Nothing on screen says which is which. That
split is the core unintuitiveness.

Compounding it, the host-side SAS approval only ever appears as an auto-dialog **on the
Sync screen** (`SyncDashboardScreen.kt:125` — shown when `activePairingRequest != null`),
and the poll that drives it lives on `viewModelScope`, so it dies whenever the VM
churns (the tech-debt item). When the poll lapses there is **no other surface** the
request can appear on — the user perceives "nothing happened." On desktop this is worst:
the maintainer's instinct is that approval should feel system-global, the way Apple's
iCloud sign-in SAS sheet does.

## 2. Goals / non-goals

**Goals**

1. One entry point for "add a device", with the user's *role* made explicit by copy
   rather than inferred from which button they pressed.
2. Host-side approval that survives leaving the Sync screen and surfaces app-globally
   (and, when the app isn't foreground, as a system notification — gated by priority so
   not every Tenshu notification escalates).
3. Close the host-poll tech-debt by moving the poll off `viewModelScope` onto a
   process-lifetime scope.

**Non-goals**

- **Joining a shared realm stays separate.** Shared-realm membership (a contact grants
  you access; you accept via central notifications and it lands in Drive's *Shared with
  me*) is a different concept from adding your own device. Folding it into this screen
  would reintroduce role confusion one level up. (Optional: a one-line pointer link.)
- No change to the pairing crypto, the relay mailbox, the SAS derivation, or the
  `tenshu://pair?…` string format.
- EPC **app** pairing (`Destination.EpcPairing`, Apps → "Connect on another device") is
  out of scope — it's a distinct ceremony.

## 3. Design

### 3.1 Single entry point (Phase 2)

Remove the top-bar QR `IconButton` (`SyncDashboardScreen.kt:288`). Keep Refresh and the
"+" FAB. "Add a device" is reached only via the FAB (plus the existing Home /
onboarding entry points that already route to `PairingWizard`).

### 3.2 Unified screen — segmented Scan / Show (Phase 2)

The unified screen leads with the **role**, via a segmented control, defaulting to
**Scan** (the common case: you're holding the new device).

```
┌─────────────────────────────────────┐
│  ← Add a device                      │
├─────────────────────────────────────┤
│   ┌─────────────┐  ┌──────────────┐  │
│   │ ▸ Scan a code│  │ Show a code  │  │
│   └─────────────┘  └──────────────┘  │
│                                       │
│   On the NEW device, scan the code    │
│   shown on a device already signed in.│
│                                       │
│        [   camera viewfinder   ]      │
│                                       │
│        Enter a pairing code instead   │
└─────────────────────────────────────┘
```

- **Scan a code** ("this is the new device"): reuses today's
  `ScanQr` → `ManualCode` → `NameDevice` → `Approval` → `Success` wizard steps
  unchanged (`PairingWizardScreen.kt`). The current standalone `PickMethod` step is
  subsumed: "Scan" is the default; "enter a pairing code instead" is the secondary link
  already present on `ScanQrStep`.
- **Show a code** ("this device invites a new one"): hosts the origin QR + copyable
  pairing code (today's `startPairing()` content, currently inside the dashboard
  dialog), **minus** the inline approval — approval moves to the global surface (§3.3).

The microcopy ("On the NEW device…" / "On a device already signed in, show this
code…") is what removes the guesswork: the user reads which device they're holding
rather than decoding origin-vs-joiner.

### 3.3 Global, interrupt-level approval (Phase 1 + Phase 3)

When a joiner request arrives, the host shows a **high-priority interrupt**, not a
Sync-screen-local dialog:

| Host app state | Surface | Mechanism |
|---|---|---|
| **Foreground** (any platform) | In-app modal at the App root — SAS digits + Approve/Deny | Compose, hosted in `App.kt`; works everywhere incl. the unbundled dev binary. **Phase 1.** |
| **Backgrounded / closed, Android** | HIGH-importance notification; lock-screen → full-screen intent (closest to an overlay); tap → opens the modal | Reuses the `PendingRequestAlertHandle` substrate (central notifications P3a). **Phase 3.** |
| **Backgrounded / closed, macOS** | Tray-posted `UNUserNotificationCenter` banner → tap opens the modal | Reuses the desktop tray bridge (central notifications P3b). ⚠ no-ops in the unbundled dev binary — needs a signed/bundled build. **Phase 3.** |
| **iOS / Windows / Linux** | Deferred (foreground modal only) | — |

**Why not the iCloud system overlay verbatim.** Apple's iCloud SAS sheet that floats
over every app is drawn by a *privileged Apple daemon* (`akd`/AppleIDAuthAgent), not by
the app you're looking at. A third-party app **cannot** render UI over another app's
context on macOS or iOS — only the foreground app draws its own UI. The sanctioned
equivalent for the not-foreground case is a **notification**. So the cross-platform
shape is necessarily *in-app modal when foreground + system notification otherwise* —
which is exactly the target.

**SAS security constraint — load-bearing.** Pairing requires comparing SAS digits on
*both* devices, so Approve **must not** be a one-tap notification action. The
notification's job is "a device wants to join — tap to review," which opens the in-app
modal where the digits are shown and compared. Approve/Deny live only behind the SAS
comparison. (This is also why pairing approval is *not* folded into the passive
`PendingRequest` notification-center feed: it's an interrupt demanding a comparison, not
a glanceable, dismissible card. It rides the same *OS-elevation transport* as
`PendingRequest` but is its own surface.)

**Priority gating.** Only HIGH-priority security prompts (pairing approval today;
perhaps contact-SAS later) ride the system-notification channel. The existing
`PendingRequest`/bell entries are unchanged.

### 3.4 Proxy-scoped poll = the tech-debt fix (Phase 1)

The host pairing poll
(`SyncDashboardViewModel.startPairingObserver` / `resumeActivePairingSession`) moves off
`viewModelScope` onto a **process-lifetime** owner — a new `HostPairingMonitor` Koin
`single`, mirroring `TenshuPendingRequestsRepository` (process-lifetime
`SupervisorJob` scope). The host pairing **session** already lives durably on the proxy
(`SyncManager.activePairingContext`); the monitor just observes its liveness (via
`getSyncStatus().pairingString`) and polls `getPendingPairingRequests()` while it's
live, exposing the result as a `StateFlow<PendingPairingRequest?>`. Because the monitor
outlives any screen VM, the request can't be missed when the user navigates away — and
the global modal (§3.3) has a stable source to render from.

⚠⚠ **A poll's answer belongs to the session it was asked under** (2026-10-02). The proxy reads
`activePairingContext` when a poll *starts* and answers a relay round-trip later, so a poll in
flight while the session ends — approve, deny, or the §3.5 expiry — still returns the request.
The monitor therefore publishes an answer only if that session is still live (`sessionEpoch`,
checked and written under `sessionLock`; every teardown moves it). Without the gate the answer
landed *after* the teardown and `activeRequest` went non-null again: the §3.3 modal reopened on
a device that had just been approved until the next 2 s tick withdrew it, and across a
stop → start the previous session's request — with the previous session's SAS — sat in the new
session, where an empty poll never clears it. After a completed approval the proxy is its own
backstop — approve clears the context and zeroes the ephemeral key, so a second approve is
refused with *"No active pairing context"*. ⚠ The stop → start case has no such backstop (the
new session has a live context), so there the gate is the only thing keeping a previous
session's request from being offered for approval. Pinned by `HostPairingMonitorTest` (the
*poll outliving its session* group) and by `PairingHandshakeIntegrationTest`, which holds a
real poll across the approval.

### 3.5 Android process lifecycle — process-lifetime is necessary but not sufficient

Moving the poll to a process-lifetime `single` (§3.4) fixes the *in-process* failure
mode (navigation / VM churn) that the tech-debt item describes, on every platform. But
on **Android** it does not survive **process death**, and the maintainer correctly
flagged this. The relevant facts (verified 2026-06-24):

- Control's Android process is **bound-only by default** — it is a foreground service
  *only* while a Layer-2 task is in flight (`ForegroundTaskLiveness` /
  `control/.../server/ProxyService.kt`). With Drive unbound and no task running, the
  process is **OS-reclaimable**.
- The host pairing context — `SyncManagerImpl.activePairingContext`, the **ephemeral
  X25519 key + secret** used to compute the SAS and approve the joiner — is held
  **in memory only** (`sync-service-proxy/.../SyncManagerImpl.kt`). It is *not*
  persisted (and shouldn't be — ephemeral keys persisting to disk would be their own
  problem). So if the process dies mid-pairing, the key is gone, the relay-queued
  joiner request becomes unanswerable, and the joiner times out. **This is pre-existing
  and orthogonal to where the poll lives** — `HostPairingMonitor` neither causes nor
  could fully fix it.

So during a *user-attended* pairing session (Control foreground or Drive bound) the
process is alive and the monitor works strictly better than the old `viewModelScope`.
The gap is: user backgrounds Control mid-pairing → process reclaimed → poll *and*
context lost. The pre-existing relay-mailbox retention lets re-opening "Add device"
re-arm *only if the process survived* — process death loses the ephemeral context, so
re-arming can't recover that session.

**Fix (Phase 1.5, on-device):** a **bounded, visible, self-destructing** host pairing
session. Concrete design (decided 2026-06-24):

- **Bounded window — 2 minutes.** Starting a host session arms a 2-minute countdown
  (the draft spec's 5-min §9 value is superseded here; 30s was rejected as too tight for
  the human flow — the joiner must pick up the other device, open the app, and scan
  before the host even compares the SAS). At expiry the session self-destructs: clear
  `activePairingContext` (and, finally, zero the ephemeral key — the §5 zeroing, now
  implemented in `SyncManagerImpl.stopPairing()`; see §7), drop the "in Pairing" row,
  cancel the notification. A
  timed-out session *failed* anyway, so tearing it all down is correct.
- **Pause/extend on request arrival.** The 2-minute timer governs only the
  *waiting-for-joiner* phase. The moment a request arrives (`activeRequest != null`) the
  countdown **pauses** — the SAS approval is a human comparison step that must not be
  guillotined mid-approval. The row flips from "Waiting… 1:43" to "Approve this device?"
- **Visible "in Pairing" placeholder.** A pending row in the Realm-devices list for the
  whole session — a stable home + re-entry point (tapping it, or the notification,
  returns to the pairing surface), better than a modal QR that must stay open. (This is
  also a Phase-2 win on the unified screen.)
- **Cross-platform core + platform liveness handle (per §4).** The countdown,
  expiry/teardown, and the "in Pairing" row live in `HostPairingMonitor` (a
  `remainingSeconds`/expiry state) — useful on **desktop** too (clarity), and
  unit-testable. The **liveness effect is a thin platform handle**, mirroring the
  `PendingRequestAlerts` (pure) → `PendingRequestAlertHandle` (platform) split:
  - **Android** — a `dataSync` FGS driven off `HostPairingMonitor.isPairing`, reusing
    Drive's `FolderUploadForegroundService` + `FolderUploadLiveness` pattern (which
    mirrors Control's own `ForegroundTaskLiveness`): promote on session start, demote on
    approve/deny/expiry. The countdown notification keeps the process — and the ephemeral
    context — resident exactly while pairing is underway.
  - **Desktop** — inert handle; the tray/daemon process is already long-lived.
  - **iOS** — inert handle, **foreground-only** (see below).

**iOS — the FGS does not port, and need not.** iOS has no foreground-service equivalent
for arbitrary work: `beginBackgroundTask` grants only a finite (~30s) grace, Background
App Refresh is opportunistic, Background URLSession is for handed-off transfers, and the
native "wake on event" answer (APNs push) needs push infrastructure the zero-trust relay
deliberately lacks. But pairing is a **user-attended foreground ceremony** (you watch the
screen while the other device joins), and iOS keeps a foreground app fully alive — so the
poll + countdown + approval modal all work in the common case. Backgrounding mid-pairing
suspends the app within ~30s and the session dies, which simply collapses into the
spec's existing "invalidate on background" (§5) behaviour; the 2-minute window makes a
restart cheap. This matches every native iOS pairing/SAS flow (iCloud sign-in, AirDrop).
Because the liveness effect is a platform handle, iOS supplies a near-no-op one. (The
deeper constraint — iOS won't let the Control *proxy* run persistently in the background
at all — is a Control-on-iOS concern broader than pairing; pairing is one of the *easier*
instances precisely because it's attended.)

**Security note (records a deviation from §5).** `sync-pairing-flow-improvements.md` §5
invalidates a host context after 60 s of *backgrounding*. The Android FGS intentionally
keeps it alive past that — but not silently: the countdown **notification** keeps the
user informed it's running and for how long, and the 2-minute window hard-bounds it
regardless. We consciously trade "invalidate on 60 s background" for "keep alive while
*visibly* counting down, hard-capped at 2 min." No asset/threat-model change (the
ephemeral key still never persists; if anything, the on-expiry zeroing is a net
improvement); this is a §5 design-value update to fold in when §5 is next touched.

Requires an Android device to verify, so Phase 1.5 is tracked separately and does not
block Phase 1.

### 3.6 Phase 3 (backgrounded/closed delivery) — decomposed, mostly device-gated

Studying the existing OS-elevation substrate (central notifications P3a/P3b) surfaced two realizations that
reshape Phase 3 from the single row in §5 into a small CI-able core + two device-gated
platform halves:

1. **Pairing approval is *process-alive-only*; there is no `SyncWorker`-wake path.** The
   central-notifications feed (`PendingRequest`) survives a closed Android process because
   it's durable relay state re-surfaced on the next `SyncWorker` drain (the
   `PendingRequestPostDrainHook`). A host pairing context is the opposite: the ephemeral
   X25519 key lives only in `SyncManagerImpl.activePairingContext` (§3.5), so once the
   process is reclaimed there is *nothing to approve* — waking it later can't help. So,
   unlike `PendingRequest`, pairing adds **no** `PostDrainHook`.
2. **The Android "backgrounded" surface *is* the Phase 1.5 FGS notification.** The only way
   a backgrounded Android pairing session stays alive is the Phase 1.5 `dataSync` FGS — and
   that FGS already shows an ongoing notification ("Pairing in progress — 1:43"). When a
   request arrives it simply updates to "A device wants to join — tap to review." A
   *separate* `AndroidPendingRequestNotifier`-style alert would be redundant and could fire
   with no live context. **So Phase 3-Android folds into Phase 1.5** (do them together,
   on-device).

That leaves three pieces:

- **3-core (CI-able, ships now).** A cross-platform JVM seam mirroring the existing
  tray-consumed `registerPendingRequestMethods` bridge: a `pairingApproval` native-bridge
  method serving `HostPairingMonitor.activeRequest` as a **minimal, PII-free, SAS-free**
  payload — `{ pending: Boolean, id: String? }`, where `id` is the opaque `deviceId` used
  only as the notification identifier (never displayed; no device name, no SAS — the SAS
  comparison happens only in the in-app modal per the §3.3 constraint). Pure shaper +
  desktop unit test. This is the reusable primitive the macOS tray-agent will poll.
- **3-desktop/macOS (bundled-build-gated).** The embedded Swift tray-agent (in
  `Contents/Helpers/tray-agent` of a bundled `.app`) polls `pairingApproval`, posts a
  `UNUserNotificationCenter` banner when one arrives (it's needed for the close-to-tray
  case — a hidden window's Compose modal can't raise the OS window), and on tap focuses
  Control so the global modal shows. No-ops in the unbundled dev binary, so it can
  only be verified in a signed/bundled build.
- **3-Android (folds into Phase 1.5, on-device).** The FGS notification content update
  described above.

## 4. Surfaces & ownership

- `HostPairingMonitor` (new, `commonMain`, Koin `single`) — owns the poll, the
  `activeRequest` / `pairingString` / `isPairing` state, and `start/stop/approve/deny`.
  This is the single source of truth for host-side pairing; the re-registration
  recovery (`maybeReregisterDevice`) moves here too.
- `SyncDashboardViewModel` — stops running its own poll loop; observes the monitor for
  the "Show a code" tab (pairing string) and delegates start/stop. Device-list logic
  (`refresh`/`revokeDevice`/`createRealm`) is untouched.
- `App.kt` — mounts a global `HostPairingApprovalDialog` driven by
  `monitor.activeRequest`, above the `KmpNavHost` (sibling to the existing
  `PinnedRequests`), so it overlays any destination.
- Phase 3 (§3.6) reuses the desktop tray bridge pattern (`registerPendingRequestMethods`)
  as the JVM seam — a `pairingApproval` method over `HostPairingMonitor.activeRequest`.
  The Android background surface is the Phase 1.5 FGS notification (not a separate
  notifier); the macOS banner is the Swift tray-agent (bundled-build-gated).

## 5. Phasing

| Phase | Scope | Ships value | Notes |
|---|---|---|---|
| **1** | `HostPairingMonitor` (process-lifetime poll) + global foreground approval modal in `App.kt`; `SyncDashboardViewModel` consumes the monitor | Approval survives leaving the Sync screen; **closes the host-poll tech-debt**; foreground iCloud-feel on every platform incl. dev binary | The handover below |
| **1.5** | Bounded/visible/self-destructing session (§3.5): 2-min countdown (pauses on request arrival) + auto-teardown (incl. ephemeral-key zeroing) + "in Pairing" placeholder row, all in `HostPairingMonitor`; Android `dataSync` FGS liveness handle (reuse `FolderUploadForegroundService`/`FolderUploadLiveness`) | Session has a visible home + hard time-bound; poll + ephemeral context survive backgrounding mid-pairing on Android; desktop gets the clarity, iOS the inert foreground-only handle | **CI-able parts LANDED + GREEN** (countdown + zeroing + liveness edge logic + "in Pairing" row); Android FGS wired + compiles; **on-device round-trip pending** (can't be CI'd). Handover + as-built in §7. Doesn't block Phase 1 |
| **2** | Remove top-bar QR button; unified Scan/Show segmented "Add a device" screen; fold origin QR into the "Show" tab | Single, role-explicit entry point | Pure UI; depends on Phase 1 having moved approval out of the dashboard dialog |
| **3-core** | JVM `pairingApproval` native-bridge method over `HostPairingMonitor.activeRequest` (PII-free/SAS-free `{pending,id}`) + pure shaper + desktop test (§3.6) | The reusable seam the macOS tray-agent polls | CI-able; ships now |
| **3-desktop** | Swift tray-agent polls `pairingApproval`, posts a `UNUserNotificationCenter` banner (close-to-tray case), tap → focus Control → modal | Approval reaches the user with the Control window hidden/backgrounded on desktop | Bundled-build-gated; no-ops in dev binary |
| **3-Android** | **Folded into Phase 1.5** — the FGS ongoing notification updates to "a device wants to join — tap to review" (no separate notifier; no `SyncWorker`-wake path — §3.6) | Approval reaches the user with Control backgrounded on Android | On-device; do with Phase 1.5 |
| ~~3 (iOS/Win/Linux)~~ | Deferred | — | iOS = foreground-only (§3.5) |

## 5b. The offerer's poll — extracted, bounded, and audible (2026-08-30)

Phase 1 hoisted the **host** poll out of the VM into [`HostPairingMonitor`]. The **offerer** poll
stayed in `PairingWizardViewModel.startOfferSession`, and carried two defects the host side had
already fixed:

- ⚠⚠ **It swallowed every failure.** `getOfferStatus(hash).getOrNull() ?: continue` — no error, no
  end, no signal. An offerer whose status calls all failed waited forever with nothing on screen:
  the *ownerless eternal spinner* §3 says the offer role exists to avoid.
- ⚠⚠ **Nothing bounded the wait.** The session had no deadline at all.

`OfferPairingMonitor` extracts the poll and closes both.

**⚠ The lifecycle deliberately does NOT follow the host.** The obvious move — hoist it to process
lifetime too — is wrong here. The host's session is durable by design; the offer session is
deliberately ephemeral (`sync-pairing-inverted-direction.md` §5.1): *"an abandoned offer has no
approval to surface globally, so leaving the screen tears it down."* Surviving navigation would
leave a pairing QR valid after the user walked away with nothing on screen to report the outcome —
a product decision with a security flavour, not a refactor. **Teardown-on-leave is unchanged**; the
VM starts and cancels the monitor.

**⭐ The bound is a deadline, not a failure count.** A count has to answer "how many is too many",
and every answer is wrong in one direction — too low kills a live pairing on one blip, too high is
arbitrary. `OFFER_WINDOW_SECONDS = 300` mirrors the host's countdown, and the relay's
`PAIRING_OFFER_RESPONSE_TTL_MS` (7 min) is deliberately **that window plus grace** — so the client
stops while the code is still servable rather than after it has silently died, and neither side has
to know the other's constant. On expiry the offer is cancelled proxy-side and `failed` is raised,
which the user resolves by showing a new code.

Poll failures raise a transient `connectionLost`, cleared by the next clean poll — the shape
`HostPairingMonitor` already uses. ⚠ Deliberately **not** terminal: a blip must not cost a pairing.

⚠ `PairingWizardUiState` is **unchanged**. `connectionLost` is exposed on the monitor but not
mirrored into the UI state, because iOS mirrors that state in `PairingView.swift` and a field
nothing reads yet is speculative. Surfacing it as a banner is a follow-up.

⚠⚠ **Two scopes, split by lifetime — not one.** `pollScope` is the **VM's**: the poll and countdown
must end when the screen does, and a scope ending does that *structurally*, where a single
long-lived scope left them running until someone remembered to call `cancel()`. `teardownScope`
outlives the VM and runs only the proxy-side cancel, because that fires from `onCleared` when
`viewModelScope` is already dead — launch it there and the offer is orphaned on the relay until its
TTL. ⚠ The first draft of this class took one scope for both, which compiled, passed every test, and
silently moved the poll off Main onto `Dispatchers.Default` into the bargain.
`cancellingThePollScope_endsTheWork_withoutAnyoneCallingCancel` is the guard: it calls no `cancel()`
at all, and fails if the poll is moved back onto the long-lived scope.

⭐ **Why this shape matters beyond the defects:** the poll is now obtained in exactly one place,
which is what makes `sync-relay-api.md` **Phase 3 (long-poll on pairing)** a drop-in — the interval
becomes a held-open request and nothing outside `OfferPairingMonitor` moves. That was the point of
doing it this way rather than patching the loop in place.

## 6. Threat-model audit

No amendment required. The pairing protocol, relay mailbox, `tenshu://pair?…` string,
and SAS derivation are unchanged. Moving the poll from `viewModelScope` to a
process-lifetime scope does not change what is sent, stored, or trusted — the relay
mailbox already retains the request (that's why the human workaround works today). The
global modal presents the SAS the user compares; the **Phase 3 notification deliberately
does *not*** carry the SAS (its payload is `{pending, id}` only — §3.6), so the §3.3
constraint holds by construction: a notification can never approve, it only routes the
user to the in-app modal where the comparison happens. The notification copy stays
generic/PII-free — no device name, no fingerprint, no SAS — the same proxy-isolation
constraint `AndroidPendingRequestNotifier` and the desktop tray bridge already honour (§6
of `control-central-notifications.md`).

## 7. Implementation Notes for Claude Code

Phase 1 file-level handover (the canonical starting point):

**New**
- `control-shared/src/commonMain/kotlin/app/tenshu/control/sync/HostPairingMonitor.kt`
  — process-lifetime Koin `single`. Mirror `TenshuPendingRequestsRepository`'s shape:
  a `SupervisorJob + Dispatchers.Default` scope, a `MutableStateFlow` for
  `activeRequest`, `pairingString`, `isPairing`, `connectionLost`. Hoist verbatim from
  `SyncDashboardViewModel`: the `startPairingObserver` 2 s loop (lines 64–109), the
  `resumeActivePairingSession` liveness adoption (54–62), `startPairing` (162–175),
  `stopPairing` (223–235), `approveActiveRequest` (121–138), `denyActiveRequest`
  (148–160), and `maybeReregisterDevice` + `REREGISTER_BACKOFF_MS` (177–221, 299–302).
  Keep the existing comments — they encode hard-won behaviour (dialog-vs-pairing
  distinction, 401 re-register rationale).
- `control-shared/.../ui/sync/HostPairingApprovalDialog.kt` — the global modal
  (extract the approval-mode UI from `SyncDashboardScreen.kt:195–234`: device-name
  message + SAS 3-3 split + Approve / Deny). SAS-bearing; not dismissible to a passive
  card.
- `control-shared/src/desktopTest/.../sync/HostPairingMonitorTest.kt` — pure unit test
  of the arrival/clear transitions (mirror `PendingRequestAlertsTest`), with a fake
  `SyncRepository`. House Rule #7: this is the verification, not a screenshot.

**Edit**
- `SyncDashboardViewModel.kt` — delete the poll loop + pairing state; inject
  `HostPairingMonitor`; expose its `pairingString`/`isPairing` for the (still
  dialog-based, until Phase 2) "show QR" path and delegate `startPairing`/`stopPairing`.
  `refresh`/`revokeDevice`/`createRealm`/`approveDevice(deviceId)` (the device-list
  approve) stay.
- `control-shared/.../di/ProxyModule.kt` — register `HostPairingMonitor` as a `single`
  (it's process-lifetime, like `PendingRequestsRepository`). Pass it to the VM.
- `App.kt` — observe `monitor.activeRequest` and render `HostPairingApprovalDialog`
  above `KmpNavHost` (sibling of `PinnedRequests`, ~`App.kt:174–196`). On
  Approve/Deny call the monitor; the modal hides when `activeRequest` clears.

**Don't** touch the relay, the sync protocol, `SyncManager.activePairingContext`, or the
`PendingRequest` feed in Phase 1. Don't add `suspend` to any `Operation` interface
(House Rule #3) — the monitor orchestrates existing `SyncRepository` calls.

### Phase 1.5 — as-built (file-level)

Two layers: a CI-able cross-platform core, then the device-gated Android FGS handle. No
relay / sync-protocol / threat-model change.

**Core (commonMain + tests, CI-able — landed green):**
- `HostPairingMonitor.kt` — added `remainingSeconds: StateFlow<Int?>` + a `countdownJob`. On
  `startPairing()` (and on `resumeActivePairingSession()` adoption) it arms a
  `PAIRING_WINDOW_SECONDS = 120` countdown that ticks once/second **while waiting** and
  **pauses** when `activeRequest != null` (SAS approval must not be guillotined). On expiry it
  self-destructs (`syncRepository.stopPairing()` + reset flows). `approve`/`deny`/`stopPairing`
  cancel the countdown via the shared `cancelCountdown()` + `clearSessionFlows()` helpers.
  Tests in `HostPairingMonitorTest` (countdown ticks / pause-while-pending / expiry teardown /
  approve-cancels) drive it over `backgroundScope` virtual time.
- `SyncManagerImpl.stopPairing()` (`:sync-service-proxy`) — now zeroes the ephemeral X25519
  private key (`activePairingContext?.privateKey?.fill(0)`) before nulling. This is the **§5
  on-teardown zeroing** flagged as not-yet-implemented; `stopPairing()` is the single teardown
  chokepoint (approve's step 6, deny, expiry all route through it). `PairingContextIdempotenceTest
  .stopPairing_zeroesTheEphemeralPrivateKey` pins it. **Records a §5 design-value update**:
  §5's "invalidate after 60 s background" is consciously traded for "keep alive while *visibly*
  counting down, hard-capped at 2 min" (§3.5 security note) — fold into §5 when next touched.
  Net security improvement, no asset/threat-model change (the key still never persists).
- `HostPairingLiveness.kt` + `HostPairingNotificationHandle` / `HostPairingNotificationContent`
  (commonMain, Android-free) — the idle ⇄ pairing edge mapper, mirroring `ForegroundTaskLiveness`.
  Content is **PII-free**: `requestPending` + `remainingSeconds` only (no device name, no SAS —
  §6). `HostPairingLivenessTest` locks the promote/refresh/demote contract.
- "in Pairing" row — `SyncDashboardViewModel` re-injects `HostPairingMonitor` (DI
  `viewModel { SyncDashboardViewModel(get(), get()) }`) and mirrors its session state into
  `SyncDashboardUiState` (`isPairing`/`pairingRemainingSeconds`/`pairingRequestPending`);
  `SyncDashboardScreen.PairingStatusRow` renders "Pairing in progress — m:ss" → flips to
  "A device wants to join — tap to review", tap → `onAddDevice` (re-entry). CMP strings
  `sync_dashboard_pairing_*`; `SyncDashboardScreenTest` covers both states.

**Android FGS handle (`:control`, device-gated — wired + compiles, on-device pending):**
- `server/HostPairingForegroundService.kt` — a `dataSync` FGS (structural copy of Drive's
  `FolderUploadForegroundService`) that observes the monitor via `HostPairingLiveness` and keeps
  the Control process — hence the in-memory ephemeral key (§3.5) — resident while pairing. Renders
  the countdown / "wants to join" notification (PII-free); tap → `MainActivity` with the new
  `DEEPLINK_SYNC` → `Destination.Sync` so the app-global approval modal shows. **Folds in
  Phase 3-Android** (§3.6: no separate notifier, no `SyncWorker`-wake path).
- `server/HostPairingServiceLauncher.kt` — observes `monitor.isPairing`, `startForegroundService`
  on the rising edge; created in `ControlApplication.onCreate()`. The service self-demotes/stops
  on idle (mirrors `FolderUploadServiceLauncher`).
- `AndroidManifest.xml` — new `<service .HostPairingForegroundService dataSync>` (FGS perms
  already declared by `ProxyService`). `ProxyService` has no `android:process`, so Control's UI +
  proxy + monitor share one process — a single FGS keeps the key alive, no cross-process plumbing.

**Verification:** `:control-shared:desktopTest` + `:sync-service-proxy:jvmTest` green. On-device
(can't be CI'd): start pairing → background Control → countdown FGS persists + process survives;
join from a 2nd device → notification flips to "wants to join" → tap → modal → approve; confirm
expiry tears down at 0:00.

Phases 2 and 3 each landed with their own handover (see git history); this completes the phase map.

### A start that mints no code is not a session (2026-10-13)

⚠ `HostPairingMonitor.startPairing()` raises `isPairing` and arms the §3.5 window **only** when the
start returned a pairing string. A `Failure`, or a `Success` with no string, sets
`startError: StateFlow<PairingError?>` instead (the generic `UNEXPECTED` for the latter) and leaves
every session flow alone — so no "in Pairing" row, no 2 s poll, no Android FGS promotion, and no
live → ended edge to bounce the wizard to Scan two minutes later. A restart that fails while a
session is already live keeps that session's code and reports nothing. The wizard mirrors
`startError` onto `PairingWizardUiState.error` for the Show tab's host role only, and re-reads it
after the start's `Job` joins — the same failure twice leaves the `StateFlow` value unchanged, so a
collector alone can miss it. Retry is `onSelectShow()`. ⚠ `startError` is cleared by the next start
(at its beginning, and again when it succeeds) and **never** by a session ending: stop and start are
independent coroutines, so a stop issued earlier can land after a failed start and would erase it.

---

## 9. Auto-continue on the success terminals (2026-10-26)

(Numbered §9, not §8, because code comments cite it by that number; §7 stays the handoff section.)

A terminal success screen used to sit on a lone Continue button. It now **auto-continues**: the
screen looks the same, but Continue carries a progress ring and fires by itself after
**3000 ms**; "Stay" — or a tap anywhere on the screen — cancels for good and leaves a plain
Continue. One component per platform, one constant each, all three equal:
`AppAutoContinueButton` + `AUTO_CONTINUE_DURATION_MS` (`:ui-core`), `AutoContinueButton.swift` +
`autoContinueDurationMs` (control-ios), `AutoContinueButton.tsx` / `useAutoContinue.ts` +
`AUTO_CONTINUE_DURATION_MS` (web/apex). The timer lives in the UI; the VM only says whether the
screen is *armed*.

| Terminal | Rule |
|---|---|
| Host admitted a joiner (`hostPairingComplete`; iOS `hostSuccessContent`; web `PairedStep`) | auto-continue, armed once the funding check settles — run with **no** settle delay |
| Joiner admitted (`PairingStep.Success`) | auto-continue, armed after the 6 s join-race settle |
| Offerer paired (`offerPaired`) | auto-continue **only if** `offerSasShownBeforeApproval` — else manual |
| Responder admitted an offer (`ResponderSuccess`) | **manual** with a SAS; the hub flow (no SAS) may auto-continue |

The rule is one derived property, `PairingWizardUiState.autoContinueArmed`, read by Compose and
SwiftUI alike — never re-derived in a renderer.

⚠⚠ **Arm only on settled state.** `watchForUnfundedRealm` can flip `realmUnfunded` and swap the
body to "Nothing will sync yet" + "Set up storage"; a countdown that started before that would
leave the screen before the warning could be read. So `fundingCheckSettled` (reset per terminal,
set when the check finishes by any route — refused, not refused, timeout, a throwing read) gates
arming, and an unfunded terminal never counts down. The host (and the offer-flow responder, the
same role) runs the read with no settle delay: its pushes were refused long before this pairing,
so there is no join race, and a needless 6 s would stall the most common countdown.

⚠⚠ **The SAS screens are a check, not a receipt (T-P-07).** The responder's terminal keeps the code
up for the side-by-side, post-hoc comparison (`sync-pairing-scanned-announce.md` §5.3) and cannot
know whether the offerer saw the code *before* approving — learning that would reshape a wire
composite (House Rule 9), so it stays manual. The offerer *can* know: `OfferPairingMonitor` sets
`sasShownBeforeApproval` in the same poll step that surfaces a `scanned` SAS, so a SAS that only
arrived with `approved` never counts. The scanned announce is best-effort; without it the paired
screen's code is the only comparison there was, and the screen waits for the user.

⚠ **Accessibility.** With a screen reader on (Android touch exploration, iOS VoiceOver) nothing
auto-continues — a spoken UI cannot be raced. Desktop and web have no reliable signal and treat it
as off. Titles announce politely (Compose `liveRegion = Polite`, web `aria-live="polite"`, iOS
header trait). Reduced motion drops the ring, never the timer. Backgrounding (lifecycle below
RESUMED / `visibilityState === 'hidden'` / `scenePhase != .active`) pauses the countdown, and the
return restarts it from the full 3 s.

No wire, composite or schema change: the two VM fields (`fundingCheckSettled`,
`offerSasShownBeforeApproval`) are `UiState`-only.
