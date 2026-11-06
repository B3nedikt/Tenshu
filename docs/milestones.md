# Tenshu Milestones

What's shipping now, what's next, and what the broader ecosystem looks like once the
foundations are in place. Each entry names the deliverable, why it earns its keep, and
the major dependencies that gate it. This document is intentionally lighter than a roadmap —
no dates, no commitments — and it gets revised as decisions land.

For the longer-form vision behind these milestones, see the
[Tenshu White Paper](whitepaper.adoc).

---

## Currently in Development

### Drive

The reference file-management client. Multiplatform (Android + Desktop in this repository
today; iOS planned via the External Proxy Connection pattern, see below). Drive consumes
`:file-service`, `:sync-service`, `:notification-service`, and `:identity-service` through the
proxy provided by Control on the same device. It is the first end-to-end demonstration of the
proxy / semantic-permission model on real user data.

Beyond personal file management, Drive now ships **folder sharing across people** — sharing a
folder into a shared realm (its own end-to-end-encrypted store) and browsing the folders others
share with you. This completes the Drive-side UI of the *shared-realm file model* (the inbound
*Shared with me* tab and a shared-realm browse screen; design + per-phase log in
[`proposals/drive-shared-realm-file-model.md`](proposals/drive-shared-realm-file-model.md)).
Two threads remain open beyond it: *filing* a received share into your own file tree at a chosen
location (needs a personal-FS placement-rendering design), and on-device verification of the full
share→receive round-trip.

### Control

The proxy / control plane that every Tenshu client app connects through. Owns the user's
realm membership (pairing, revocation), the recovery manifest, the permission grants, and the
binding between client apps and feature services. Control is where users meet the platform —
"Realm Devices," "Recovery Phrase," and the per-app permission surfaces all live here.

Control now also runs on **iOS** as a full, key-holding realm device (not a keyless EPC client):
genesis (mint the master key on the iPhone), recovery-from-phrase, and a real proxy runtime all
ship, and **device-pairing is complete** — a second Control joins over the existing cross-Control
ceremony through a native SwiftUI "Add a device" wizard (Scan/Show roles, camera-QR + 6-digit SAS),
reachable from both onboarding and Settings. Simulator-complete; the on-device, real-relay pairing
round-trip is gated on Apple signing and tracked alongside the rest of the iOS data-plane work. The
sliced plan and per-slice status live in
[`proposals/ios-control-handover.md`](proposals/ios-control-handover.md) (C6 = device-pairing).

⚖️ **Launch blocker — set `TENSHU_CONTROL_APP_STORE_ID` when Control reaches the App Store.**
Every other first-party iOS app forwards an unprovisioned device to Control through one shared
screen ([ADR 0022](adr/0022-ios-forward-to-control-surface.md)), which hands off to Control's
App Store page when no Control build is installed. That id is a build setting that is
deliberately **empty** today: unset, the screen says *"Tenshu Control isn't on the App Store
yet"* rather than opening a broken page. Correct now, wrong the day Control ships — and nothing
fails to build when it is missed, so it is listed here rather than left to a code comment.

---

## Platform Prerequisites

These are not apps. They are platform capabilities that gate multiple Future Apps below and
that don't have a natural home inside any single app's milestone. They are listed first because
their absence would force every dependent app to invent its own throwaway version.

### Background-service story

Tenshu needs to keep ingesting device data when no Tenshu app is in the foreground. Photos
can't sync new files the user shoots if it only runs while open; Timeline can't capture the
user's movement; Fit can't ingest vitals from the platform sources. Each platform requires
its own answer (Android foreground service or `WorkManager`; iOS background tasks /
`BGTaskScheduler`; desktop daemon / launch agent) but the Tenshu-shaped contract on top of
those needs to be one design.

⚠ **Android as built:** Drive's automatic photo backup is WorkManager jobs with no *Drive*
foreground service (Play's `dataSync` policy excludes automatic backup); Drive's service carries only
the user's *Back up now*. ⚠ The uploads still run under **Control's** `dataSync` service, which
promotes for any in-flight task — open, separate work —
[`drive-synced-folders-phase2.md`](proposals/drive-synced-folders-phase2.md) §4.1a.

**Decided architecture — capture is app-side on mobile, policy is Control-side (2026-06-08).**
On **mobile** the *ingesting app* owns the OS permission, the enumeration/capture, **and** its
own background trigger (Android `WorkManager` + content-URI trigger; iOS `BGTaskScheduler`)
— the way every comparable backup app works, because the OS wakes the process that holds the
permission. **Desktop is the principled exception (refined 2026-06-09):** there the resident
process is the *Control daemon*, not the app, and there is no per-app gallery grant (Linux/
Windows) forcing capture out, so the watcher is **hosted in Control in-process** — no app
trigger, no reverse trigger; Control enumerates and uploads directly. (On macOS this means
Control holds the folder/Photos TCC grant — least-privilege-per-capability given the resident
process, not the ambient-sensor blast radius this split guards against.) See
[`proposals/drive-synced-folders-phase2.md`](proposals/drive-synced-folders-phase2.md) §4.2.
**Control** owns the *generic* parts: the constraint/Wi-Fi/charging/storage
policy, the registry of which sources are enabled, the management/inspection surface, and —
unchanged — the crypto + relay data plane that everything after an opened FD already flows
through. This deliberately rejects the alternative where Control holds every sensitive grant
(gallery, background location, mic, Health Connect) and enumerates in-process:

- **Data plane vs permission plane.** Control is the legitimate *super-app on the data plane*
  — it receives and correlates every app's data (that's what makes Timeline and Mia possible).
  It should **not** become the *permission-plane* holder of every OS grant. Holding the grants
  is a strictly larger blast radius than seeing realm data: a compromised grant-holder can
  bug the mic live, pull live GPS, and read on-device data the user never put in a realm —
  ambient/live capability, not just realm contents. Keeping each grant in the app that owns it
  is the least-privilege posture the whole platform is built on.
- **Platform reality.** On iOS there is no local Control to delegate to at all (see below), so
  enumeration is in-app *by force*. On Android, Google Play reviews sensitive permissions
  (background location, Health Connect, mic) per app against a *visible feature in that app* —
  concentrating them in a control-plane app is a rejection risk. Per-app ownership is the
  approvable, cross-platform shape.
- **No reverse trigger.** Because each app registers its own OS trigger and wakes itself, there
  is **no** Control→app "ingest now" tick and no cross-app process-start — the new IPC
  direction that a Control-owned scheduler would have required is avoided entirely. Bytes still
  flow app→Control as today.
- **Fork-era note.** If Tenshu ever ships its own OS / a `Tenshu Hub`-as-trust-root where
  Control is preinstalled and privileged, the Play-review and prompt-attribution objections
  dissolve — but "preinstalled" ≠ "monolithic": the right shape there is preinstalled
  *per-domain capture services* (own sandbox, honest attribution) handing data to Control, which
  is this same topology with different *packaging*, not a collapse into one process. So this
  decision is forward-compatible with that vision and need not be revisited for it.

The first instance of this is **Synced Folders** auto-backup
([`proposals/drive-synced-folders.md`](proposals/drive-synced-folders.md)) — Phase 1 (the
in-process, app-alive tier on Android + Desktop) is implemented; the Control-owned policy
+ registry + management surface and the app-owned background trigger are sketched in
[`proposals/drive-synced-folders-phase2.md`](proposals/drive-synced-folders-phase2.md), which
operationalises the split above and supersedes that earlier proposal's §3.3 "Control owns the
scheduler" / reverse-trigger framing.

**iOS has no local proxy — it uses the External Proxy Connection.** iOS does not allow a
resident app to broker on-device IPC (hard sandbox + no persistent background daemons), so the
iOS client talks to the user's proxy on *another* device (Android phone / desktop / `Tenshu
Hub`) — or to a co-resident Control reached over the same EPC channel — gated by iOS App
Attestation + Tenshu Rei (see *iOS Drive Client* and
[`docs/proposals/ios-proxy-architecture.md`](proposals/ios-proxy-architecture.md)).
Consequently the on-device iOS app necessarily holds its own photo/location/etc. permission and
enumerates locally — it confirms the app-side-capture decision rather than complicating it.
The iOS **data plane** fork — remote/co-resident proxy EPC (master key stays on the proxy) vs
the web-style **in-process** model (app embeds the K/N core + holds the key locally) — is now
**decided in [`ios-proxy-architecture.md`](proposals/ios-proxy-architecture.md) §4**:
proxy-mediated EPC by default (third-party apps are untrusted), in-process reserved for
first-party. That choice is orthogonal to *where enumeration happens* and does not change this
prerequisite.

Major dependencies:

- A platform-delegate shape for "long-lived background ingest" **owned by each ingesting app**,
  with documented battery and privacy trade-offs per platform.
- A Control-side generic engine: the constraint/storage policy, the enabled-source registry, and
  the management/inspection surface — reading status from each app over the existing app→Control
  IPC (no reverse channel).
- Threat-model amendment covering background-clock activity (the relay sees the
  pattern-of-life implied by when ingest happens, even if every block is ciphertext — see
  §10 metadata caveats).

Gates: Photos, Timeline, Fit, Mia (always-on voice trigger).

### Cross-realm content distribution

Today's relay is a per-realm mailbox: it stores and forwards `SyncBlock`s between *one* user's
devices. Creator/Creations and Tenshu Store both need content to flow from one realm to
another — a creator's app or media artifact, an entitlement record proving the consumer has
the right to it, a revocation channel if either side withdraws. This is in the same conceptual
cluster as Tenshu Rei (which already covers the publication / attestation / evaluation
chain), but the *transport* layer of cross-realm flow is its own design.

Major dependencies:

- A relay protocol that distinguishes intra-realm (today's `SyncMailbox`) from cross-realm
  (signed-from-creator-realm, addressed-to-consumer-realm, with entitlement proof).
- Storage shape for the entitlement records so revocation is propagatable.
- Threat-model amendment: cross-realm distribution exposes the *fact* of distribution
  (creator → consumer pairing) to the relay even though payloads remain ciphertext. Document
  this as an A6/§10 metadata exposure with appropriate mitigations.

Gates: App Console & Tenshu Store, Creator, Creations.

### Desktop distribution channels

How users actually install the macOS desktop apps (Control + Drive). Two channels, very
different cost, and they are *not* the same build artifact.

**Homebrew (near-term, cheap).** A Cask just downloads and installs the *existing*
**Developer-ID-signed + notarized** `.app` (in a `.dmg`/`.zip`), which is already required for
direct download — Cask installs are quarantined, so Gatekeeper still applies. No new
entitlements, no sandbox, no separate build. The work is pure release plumbing: a versioned
artifact on a stable URL (e.g. GitHub Releases) + its SHA256, and a cask formula — ideally in
our own tap (`b3nedikt/homebrew-tenshu`) for full control rather than `homebrew/cask`'s
acceptance gate; optionally `livecheck`/`auto_updates` + a Sparkle appcast. This is the
cheapest real install channel and is orthogonal to everything below.

**Mac App Store (genuine target; one gating unknown).** A different build from the
Developer-ID one — App Sandbox (mandatory), Apple-Distribution signing via App Store Connect
(no notarization; review replaces it), and a **paid** developer program (the free Personal Team
can't ship to the store, and App Groups — which the shared-container architecture depends on —
is paid-gated anyway). The common shape is to maintain **two macOS variants**:
Developer-ID/notarized (direct + Homebrew) and a separately-built MAS-sandboxed one.

**Decided 2026-10-17: try it, spike first** (D11 in
[`desktop-macos-distribution.md`](proposals/desktop-macos-distribution.md)). Developer ID stays the
first route. The submission spike lived in `spikes/mas-sandbox/` (deleted 2026-11-05, see git history at `2e3783216`); its result and the maintainer's
certificate/App ID/ASC/profile checklist are §4.3.1 there. Nothing from item 1–2 below (or that
doc's §4.2) is built until the spike has passed TestFlight for Mac review. ⚠ *Overtaken: the spike
ran from TestFlight for Mac and was deleted with no beta-review verdict; the §4.2 work has started and
the production MAS builds carry the review question (that doc's §4.3.2).*

The remaining work sorts into three buckets, only one of which is genuinely uncertain:

1. **Login-item autostart → `SMAppService` (small, MAS-build-only).** The sandbox forbids
   writing `~/Library/LaunchAgents` / `launchctl`, which is how "Start at login" works today. MAS apps register via `SMAppService` instead — functionally identical (it registers
   with launchd through the sanctioned API). This was once "rejected, needs a JVM↔native
   bridge", but that premise is gone: the `keychain-helper` already
   established the bundled-signed-Swift-helper-spawned-by-the-JVM pattern, so an `SMAppService`
   registration is the same ~20-line helper. Developer-ID keeps the current `launchctl` path.
2. **Helper-spawn + UDS IPC under sandbox (works in principle; needs an audit pass).** Spawning
   the bundled, same-team-signed helpers (`keychain-helper`, `tray-agent`) and cold-launching
   Control via LaunchServices are all sandbox-legal; UDS IPC across Control ↔ tray ↔ the Drive
   File Provider works because `SocketPathResolver` already routes the sockets through the App
   Group container. This is an audit + entitlement-wiring pass, not a redesign. (If both Control
   and Drive are sandboxed they ship as a same-team suite, so the "Drive is useless without
   Control" dependency dissolves.)
3. **JVM hardened-runtime entitlements surviving App Review — THE gating unknown.** A bundled
   JVM needs `com.apple.security.cs.allow-jit` + `allow-unsigned-executable-memory` (+ likely
   `disable-library-validation` for the JRE/Skiko dylibs). These are routine for
   Developer-ID/notarization but are the entitlements MAS review scrutinizes hardest. Resolve
   by a **submission spike** (push a sandboxed hardened build to App Store Connect / TestFlight
   and see what review says) — *not* by rewriting to native (Compose Desktop is JVM-only; that
   would be a Kotlin/Native + SwiftUI re-platform — see the fallback below). Do this spike
   **first** — it's cheap and it's the only thing that can actually kill the MAS path.
   *Measured 2026-10-17 (§4.3.1):* sandboxed **without** the hardened runtime, the bundled JDK 25
   JITs with **no** code-generation entitlement; only the hardened runtime makes `allow-jit`
   necessary. Locally the JVM is therefore not the blocker; review is still unanswered.

**Fallback if the spike fails — go JVM-less (Kotlin/Native + native UI), converging with iOS.**
The JVM entitlements exist only because a JVM runs at all; they're about the *runtime*, not the
UI toolkit. Swapping Compose for SwiftUI changes nothing on its own — a JVM-over-IPC backend
still needs the entitlements. What removes them is having **no JVM in the process**: compile the
shared Kotlin to a Kotlin/Native `XCFramework` and drive it from a native UI. That is exactly the
**iOS app's architecture**, so this is best framed not as "rewrite the macOS UI" but as "make
macOS a second Apple-native target alongside iOS," keeping Compose Desktop for Windows/Linux.
Cost/shape:
- `commonMain` (domain, sync, crypto, schema, services) ports for free — it already has to
  compile to Kotlin/Native for iOS.
- The real work is a **`macosMain` platform layer** to replace the current `java.*`-based
  `desktopMain`: sockets (`UnixSocketServer`/`NativeBridgeServer` use `java.nio`), the SQLite
  object store, process spawning (`ProcessBuilder`), secure storage, file IO
  (`DesktopAppPaths`/`SocketPathResolver`), AWT image decode. Much of this is **shared with the
  iOS platform layer**, so the two efforts converge rather than duplicate.
- Sequencing: the **iOS Drive client is the forcing function** — today the repo declares iOS
  K/N targets but builds no frameworks and disables iOS tests, so full-stack
  K/N-viability is unproven. Building iOS proves the K/N core; macOS-native then reuses it
  instead of being a speculative bet. (No-JVM also yields a smaller, faster-launching binary.)
- Non-issue, for the record: **Koin is fine on Kotlin/Native** — it supplies constructor lambdas
  (no reflective instantiation) and supports K/N; it is *not* a blocker for this path.

**How much UI would the native move actually cost (SwiftUI-reuse reality check).** A tempting
hope is "iOS Control is already SwiftUI, so the macOS UI mostly falls out of it." Measured
against the two codebases as they stand, the reuse is **real but modest** — closer to "a new
macOS SwiftUI UI that cribs iOS leaf views" than to a port. Numbers:

- The genuinely reusable asset is **already extracted and already reused**: the Compose-free
  `:control-shared-logic` VM/repo layer (~9k LOC, `uiState: StateFlow<…>` + action callbacks)
  is bound by *both* the Compose desktop UI (`koinViewModel()`) and the iOS SwiftUI UI (the
  `ControlSurface` Kotlin/Native façade). A native macOS UI binds to the **same** layer — that
  part is **not** doubled. This is the whole point of the VM split, and iOS already proves it.
- The **View layer** is the open cost. The iOS Control UI is ~2.4k LOC of Swift across ~9
  screens, of which **~40% is iOS-only platform glue that won't compile / won't behave on
  macOS**: `UIViewControllerRepresentable` over `AVCaptureSession` (the QR scanner), `UIPasteboard`,
  `UIDevice`, `@Environment(\.scenePhase)`, and phone navigation idioms (`NavigationStack` +
  `TabView` + `.sheet`, `.navigationBarTitleDisplayMode`, iOS-only `.toolbar` placements). What
  ports cleanly is the **leaf content** (`VStack`/`List`/`Text`/`Button`, the `@Published`
  Model-bridge pattern, the `OnboardingKit` design vocabulary) — the easy ~half.
- **iOS is a feature subset.** Desktop Control has ~17 screens; iOS has ~9. The ~8 desktop-only
  screens (Shares, Services/entitlements, Notifications center, Errors, Ingest/synced-folders,
  Background Sync, per-app permission detail) have **no iOS counterpart** and are net-new SwiftUI.
- **Native look is the genuine win — but it comes from the parts iOS *doesn't* donate.** SwiftUI
  on macOS renders true AppKit (`NavigationSplitView` sidebars, native toolbars/menus,
  `MenuBarExtra` for the tray, a real `Settings` scene, vibrancy/focus) — a nativeness Compose
  Desktop (Skia-painted widgets) structurally can't match. But you *get* that look by rebuilding
  the **navigation shell** with macOS-shaped containers, which is exactly the part the iOS shell
  (phone idioms) can't supply. The shell is rebuilt regardless; the leaf views port.
- **Tree count:** the move adds a **third UI tree**, not a swap — Compose-Android,
  Compose-Desktop (Win/Linux stays Compose), and SwiftUI (iOS + macOS).
- **The unlock/re-auth sheet would move in-window for free.** A native app owns its `LAContext`,
  so `LAAuthenticationView` embeds directly; on the JVM it is a separate helper's sheet. Not a
  reason to move — `tech-debt.md` *"Desktop Control's unlock and re-auth prompt is a separate
  system sheet"* has the in-place routes.

Net: the SwiftUI reuse is a *tailwind*, not a *reason*. The decisive payoffs remain (1) genuinely
native macOS feel and (2) no JVM → no MAS-entitlement risk **and** no camera-TCC crash (a native
in-process app is its own responsible TCC subject, so the `desktop-qr-scanning.md` §9 first-grant
SIGKILL simply dissolves). The decisive cost is the third UI tree plus porting ~half the desktop
screens from scratch. That balance is why the move stays gated behind the two cheap probes first
(the MAS submission spike below, and the option-3 in-process FFM camera spike in
`desktop-qr-scanning.md` §10 — which can retire the QR pain with *no* re-platform at all): if both
come back favorable the reuse upside isn't enough to justify the move on its own; if the MAS spike
fails you re-platform anyway and the partial iOS reuse becomes the tailwind rather than the reason.

Already done toward the sandbox path: all desktop at-rest writes — object store, `secrets.enc`,
device-local settings, and now window geometry (`WindowStatePersistence` → `DesktopAppPaths`) —
route through the sandbox-reachable App Group container. See `docs/tech-debt.md` "Desktop
App-Data Paths".

Major dependencies:

- Homebrew: versioned signed+notarized DMG/zip on a stable URL + SHA256 + cask formula (own tap).
- MAS: a JVM-entitlement App-Review submission spike (the gating item, do first); paid Apple
  Developer Program; `SMAppService` login-item helper (small, reuse the keychain-helper
  pattern); App Sandbox audit + entitlement wiring of the multi-process IPC + helper-spawn
  model; Apple-Distribution signing + App Store Connect pipeline; a second sandboxed build
  variant. Revisit the data-protection-keychain / biometric path under sandbox rules.

Gates: broad desktop adoption (Homebrew); macOS App Store presence for parity with the planned
iOS App Store apps (MAS).

---

## Future Apps

The whitepaper sketches a much larger ecosystem. The list below pulls out the apps the project
intends to build out beyond Drive and Control, in roughly the order their dependencies tend to
resolve. Each will get its own design doc when work begins.

The dependency story at a glance: Photos comes first after Drive (introduces media indexing,
the *Places* service in lite form, and basic search). Calendar / Contacts / Alarm slot in as
parallel-lane productivity work that doesn't gate anything else. Timeline graduates Places to
its full strength and introduces the Location History service. A slim Maps — Places tab only —
ships next, riding on Places at full strength. Fit rides the same window: it shares Timeline's
`:health-service` substrate (Timeline's activity layer *is* Fit data), doesn't gate the
payload-flow milestones, and can ship any time after that substrate lands. Then App Console &
Tenshu Store land alongside
Creator and Creations as the joint payload-flow milestone validating Tenshu Rei. Mia follows
because its Skills system is the second consumer of Rei's attestation chain. Tenshu Hub
extends Mia into ambient hardware. The reach apps (iOS Drive, Web Baseline, Browser Extension,
PWAs) progress on their own thread orthogonal to this sequence.

### Photos

The reference photos client. Three tabs: a *Home* feed of photos by recency with a search bar,
an *Albums* tab supporting both manual albums and smart albums grouped by place / date / event,
and a *Settings* tab where the user picks which platform-source folders should be ingested into
the realm. Photos is the first app after Drive that goes beyond "files as files" — it asks
the platform to know that a `File` is a photo, that it has EXIF metadata, that it has a capture
location, and that those facts are searchable.

Photos is the first consumer of three new platform services. **Media metadata indexing** lives
inside `:file-service` as an extension that parses EXIF (and equivalents for other media types
later) into structured, sync-able fields. **Places** ships in lite form: GPS clusters with
human-readable labels, sufficient for "Photos taken in Paris." **Search** ships as a generic
full-text-and-metadata service, with Photos as its first consumer; later apps (Notes, etc.)
will extend it.

Because Photos presents the realm **flat** — by recency, ignoring paths — it is the first app
for which a duplicate is *visibly* wrong rather than merely redundant. Drive can show the same
photo under two folders and have that read as an explicable filing choice; a recency feed showing
it twice just looks broken. That makes photo identity a Photos concern in a way it never was for
Drive, and there are two distinct layers of it:

- **Device-local asset identity** (`MediaStore` id / `PHAsset` localIdentifier) already exists and
  is what the backup drain dedupes on today, so one device cannot upload the same asset twice even
  when its source buckets overlap (an iOS album is a view, not a directory).
- **Cross-device content identity** is the layer Photos actually needs — the same photo backed up
  from a phone and a tablet carries different asset ids but identical bytes. `File.ContentHash`
  (a plaintext whole-file SHA-256, `file-service/.../File.kt`) is **already computed on every
  upload, wire-registered, and currently read back only as the content manifest's integrity
  anchor** — no index, no lookup, no consumer. Photos is its natural first consumer, and grouping
  on it costs nothing new.

⚠ This grouping must stay **client-side**. The relay deliberately addresses blobs by *ciphertext*
hash, with a random `contentId` and a fresh CEK per emit, so that a blob ref can never become a
confirmation-of-file oracle (`file-content-addressing.md`; "no cross-user dedup, ever"). That
property constrains what the *relay* may address, not what a device that already holds the
plaintext may notice — so a Photos client may group by `ContentHash` freely, and must not push
that grouping down into the storage layer.

Major dependencies:

- Background-service story (above) — folder-watching for new photos must work without the
  user opening Photos.
- `:file-service` extension for media metadata; schema additions for EXIF-shaped fields.
- Places service (lite); will be deepened by Timeline and graduated to full strength by Maps.
- Search service shape, with Photos as the first consumer.
- Per-platform gallery integration (Android `MediaStore`, iOS `PHPhotoLibrary`, desktop folder
  watching) via the platform-delegate pattern.
- `File.ContentHash` as a client-side identity for cross-device de-duplication in the flat feed
  (already populated; needs a consumer, and an index if the feed is to group at scale).
- The source-folder picker for the *Settings* tab already exists in prototype as Drive's
  Synced-Folders screen (Android) and `BackupScopeView` (iOS, spiked). Drive carries it only
  because Photos does not exist yet — it should **move** here, not be rebuilt.

### Calendar

A simple calendar app: create events, navigate by day / week / month, support recurring events
and reminders, expose a home-screen widget on Android. Cheap relative to the bigger apps and
high-value for the "Tenshu replaces Google's free tier" perception. Doesn't gate anything else
in the sequence; ships in a parallel lane between bigger items.

The interesting design work is the calendar data shape — it has to interop with iCal /
CalDAV downstream so users can subscribe to and publish standard calendars. Decide the schema
to be a Tenshu-native one with iCal as an interop format (preferred) versus storing raw iCal
blobs.

Major dependencies:

- Calendar data service (events, recurrence rules, reminders).
- Per-platform widget surface for Android home screens; equivalent on desktop later.
- iCal interop (import / export / subscribe-to-URL).
- One-way import of the calendars a user already has — OS calendar store first, then a direct
  Google sign-in, Microsoft later
  ([`proposals/calendar-external-sources.md`](proposals/calendar-external-sources.md)).

### Contacts

A simple contacts app: a list of contacts, per-contact detail screen, manual add / edit, vCard
interop. Same parallel-lane positioning as Calendar — but the underlying **data service** is
pulled forward because Drive's *Manage access* (Mode-B named-recipient sharing) needs a store
of known identity references. The service design (thin `Person` aggregate + `TenshuIdentity`
keyring + lossless `ContactSource` vCard records; no relay directory — handshake-only identity
acquisition) lives in
[`proposals/contact-service-identity-model.md`](proposals/contact-service-identity-model.md).

**The app is scoped** in [`proposals/contacts-app.md`](proposals/contacts-app.md) (pre-design
decision record, 2026-08-02) — that doc is the v2 of the identity model and carries the locked
decisions (avatars, parity scope, re-verification, merge) plus the phasing. Note its premise
correction: the v1 handshake service and Drive's sharing flow are already shipped, so this app
is about *managing* contacts (rename / forget / verify after the fact), not about unblocking
sharing.

Major dependencies:

- Contacts data service — designed in
  [`proposals/contact-service-identity-model.md`](proposals/contact-service-identity-model.md)
  (`Person` is deliberately *not* vCard-shaped; raw vCard 3.0 lives on `ContactSource`);
  v1 handshake subset shipped, the CRUD + `ContactSource` half is `contacts-app.md` Ph4–Ph6.
- Per-platform contacts integration where the user opts in to sync from the system contacts
  store.

### Alarm

A simple clock app: alarms, world clocks, stopwatch, timer. Includes a home-screen widget.
Mostly local with per-device sync of alarm definitions through `:sync-service`. Of the three
productivity apps in this tier, Alarm is the smallest and the one with the least new platform
shape; it's listed for completeness.

Major dependencies:

- A small alarm-definition schema that syncs across realm devices (same alarm fires on phone
  and watch on the same morning).
- Platform alarm scheduling (Android `AlarmManager`, iOS local notifications).

### Timeline

A personal-history app that visualises events from the user's life — places visited, photos
taken, trips made — drawn from the User History Service. The intent is to replace
Google Maps Timeline / Google Takeout for users who already live in the Tenshu realm: data
stays end-to-end encrypted between realm devices, no central server holds plaintext, and
exporting or migrating to another Tenshu-enabled timeline app is a no-op (the data is already
in the user's realm in a standardised shape).

The location-history layer is the threat-model-sensitive piece — continuous capture of where
the user has been is at least as sensitive as the file contents in Drive, and the privacy
wedge versus Google Timeline is exactly the E2E-encrypted version of this story. The Timeline
design doc and a corresponding amendment to `docs/sync/threat-model.md` (new A-asset for the
location-history stream) need to land together.

Major dependencies:

- Background-service story (above) — continuous location capture must run without the
  Timeline app being open.
- User History Service interface stable and registered in the schema DSL.
- Location History service (the substream of User History dedicated to GPS / motion); graduates
  the Places service from Photos to its full strength.
- Trips service — derived from location history but stored separately so it's editable and
  curatable by the user.
- On-device location classification model (so place-of-interest detection does not require
  cloud inference).
- Photo / event linking through `:file-service` so timeline entries can reference media from
  Photos without duplicating it.
- Threat-model amendment for the location-history stream.

### Maps — Places (slim)

A maps app shipped first in *slim* form: only the **Places** tab from the long-term Maps
vision. That tab covers *Your places* (home, work, other locations of importance to the user),
*Saved places* (places the user bookmarked for later), *Recommended places* (places the system
proposes based on history, contacts, or curated lists), and *Contribute* (POI submissions and
map-mistake reports). No routing, no Explore / Navigate — those land later as a separate
"Maps full" milestone (see *Out of Scope*).

This slim version exists because the Places service that Photos and Timeline build at lite /
medium strength is genuinely most of the work needed for a useful maps experience. Routing and
turn-by-turn navigation can be deferred without starving the rest of the roadmap. (The 2026
basemap reassessment under *Out of Scope → Maps full* found that tile **rendering** is no
longer the hard part — a MapLibre + offline PMTiles region bundle is cheap — and slim now
carries one: `:ui-map` renders a read-only basemap with place, landmark and street labels,
fully offline and keyless, with no tile or glyph egress. Gestures stay disabled — it is a
static frame, not an interactive map. The harder, deferred work is POI/search depth and
Navigate UX.) The slim form ships meaningful Maps value for the cost of a few weeks of UI work
on top of services that already exist.

Major dependencies:

- Places service at full strength (graduated from Photos / Timeline).
- POI database — initially user-contributed, with moderation flow that mirrors the
  attestation shape of Tenshu Rei (signed contributions, evaluator confirmations later).
- Per-platform location source for the user's current position.
- Cross-link with Timeline (saved-from-trip places) and Contacts (a place can be a contact's
  home/work).

### Fit

A privacy-first health & fitness app — the Tenshu answer to Google Fit / Apple Health. Four
surfaces: *Home* (daily active-minutes ring + steps/distance/energy with trends), *History*
(chronological workout feed), *Browse* (Health-Connect-shaped categories: activity, body
measurements, vitals, sleep, nutrition, cycle tracking, plus read-only medical records), and
*Profile* (goals, personal data, units, data-source management) — plus a first-party workout
recorder. Formerly the "Health" entry under *Out of Scope*; promoted (and renamed per the
maintainer's `timeline-services.md` note) because Timeline's `Day.movement` /
`ActivityRollup` layers are `:health-service` data, putting the substrate on Timeline's
critical path.

Load-bearing product decisions (2026-07-04):

- **Works fully de-Googled.** Import from Health Connect *when present* (an AOSP platform
  module on Android 14+; a Play-Store app on 13−), but never require it or Play Services:
  Fit ships its own recorder (foreground while-in-use location + step-counter sensor + BLE
  heart rate — no GMS) and FIT/TCX/GPX file import (also the desktop/web story). Everything
  works seamlessly on stock AOSP. On iOS, HealthKit is part of the OS — no equivalent concern.
- **No background location — ever.** Workout GPS routes arrive via the health import or the
  explicit in-workout recorder (while-in-use). *Passive* movement capture belongs to Timeline
  per the app-side-capture decision (see *Background-service story*): concentrating the
  Health grant **and** background location in one app is exactly the blast-radius
  concentration that decision rejects, and Play reviews each sensitive grant against a
  visible feature in the holding app.
- **No Heart Points clone.** The metric is Google-proprietary and not exposed by Health
  Connect (Google Fit's APIs sunset end-2026); the headline is a WHO-derived **active
  minutes** score computed on-device.
- **FHIR-vs-Tenshu-native: both, split by domain.** Tenshu-native schema for fitness records;
  medical records (Health Connect Medical Records API / HealthKit clinical records, both FHIR
  R4) stored losslessly as raw FHIR on the content plane and rendered read-only in v1.
- **Lossless import by construction.** The original source record (Health Connect record
  JSON / HK sample / the FIT file itself) is retained as a content-plane blob next to the
  neutral schema — the `ContactSource`-vCard precedent.

Major dependencies:

- `:health-service` ([`proposals/timeline-services.md`](proposals/timeline-services.md) §4)
  beyond the P2 `Workout` slice: `DailyAggregate` (steps/distance/energy/active-minutes),
  point samples (HR, weight), sleep sessions, medical-record envelope.
- Import-seam `actual`s: Health Connect (Android), HealthKit (iOS), FIT/TCX/GPX parsers
  (desktop/web + universal fallback).
- First-party workout recorder (foreground service; GNSS + step counter + BLE HR; no GMS).
- Background-service story (above) for periodic aggregate ingestion (plain `WorkManager`).
- Goals via `:preference-service` (`RealmSetting`).
- Threat-model amendment: health asset class (GDPR Art. 9) — already demanded by
  timeline-services §4.3; lands with the first `:health-service` PR.

Design brief for the mockup pass: `docs/design/fit/fit-brief.md` (local-only); the durable
design doc will be `docs/proposals/fit-app.md` when work begins.

### iOS Drive Client

Drive on iOS. iOS does not allow proxies to broker on-device IPC the way Android does (App
Groups are single-Team-ID, so there is no cross-vendor on-device IPC), so the iOS client uses
the **External Proxy Connection** pattern: the user's Tenshu proxy (Android phone, desktop, a
Tenshu Hub, or a co-resident Control on the same iPhone) brokers all external calls over a
network channel. iOS app attestation and Tenshu Rei are both in the proxy's trust path. The
architecture — why on-device IPC is off the table, why Control still ships on iOS as a
co-resident EPC endpoint, the proxy-mediated data-plane decision, and the relay rendezvous
surface — is specified in
[`docs/proposals/ios-proxy-architecture.md`](proposals/ios-proxy-architecture.md).

Major dependencies:

- External Proxy Connection protocol finalised and documented — now drafted in
  [`ios-proxy-architecture.md`](proposals/ios-proxy-architecture.md) (was: whitepaper only).
- Relay extended with an app↔proxy rendezvous channel (distinct from realm-device sync
  mailboxes); attestation verification stays proxy-side.
- iOS App Attestation integration on the proxy side.
- Tenshu Rei publication layer operational so the proxy can verify the iOS app's permissions.

This is also the **forcing function for the Kotlin/Native core**: it builds the first real
`XCFramework` from the shared Kotlin and the first Apple-native platform layer. That investment
is what a JVM-less macOS app would later reuse — see *Desktop distribution channels* → Mac App
Store fallback.

### Web Baseline

`tenshu.app` as a hosted web experience: users visit the URL, log in, and get the same
proxy-mediated experience as the Control app on mobile/desktop, plus access to first-party
Tenshu web apps on per-app subdomains (`drive.tenshu.app`, eventually `mia.tenshu.app`,
`timeline.tenshu.app`, …). No install required.

Architecturally, Web Baseline is the *External Proxy Connection* pattern applied to the web —
the proxy splits between a browser-side service worker (permission evaluation, master-key
crypto) and a server-side ciphertext relay (the same code as the mobile sync relay, no
additional trust surface). First-party Tenshu apps are isolated from each other via the
browser's Same-Origin Policy on per-app subdomains. Daily login uses WebAuthn passkeys; the
recovery phrase remains the cold-storage fallback for total device loss.

Once shipped, Web Baseline answers *"I'm on someone else's computer and need to access my
files"* without compromising the proxy-mediated trust model. It is the foundation that the
Browser Extension and Tenshu PWAs milestones build on.

Major dependencies:

- KMP → JS toolchain: `js(IR)` targets on existing `:core` / `:core-common` / `:core-client` /
  feature-service modules, plus a new `:tenshu-sdk-js` module publishable to npm.
- `web/apex/` scaffolded per [`docs/web/architecture.md`](web/architecture.md) §12.8 — pnpm
  workspaces, Vite + React + TypeScript, Tailwind, TanStack Query.
- WebAuthn flow implementation for daily login.
- DNS and TLS provisioning for `tenshu.app` and per-app subdomains.

Design doc: [`docs/web/architecture.md`](web/architecture.md).

### Browser Extension

Cross-browser extension that adds the *Direct Proxy Connection* pattern on top of Web
Baseline. The extension intercepts requests from any origin and routes them through the user's
apex tab/PWA, which is what lets third-party Tenshu integrations work on arbitrary websites
(e.g. an `example.com` declaring a Tenshu manifest) without each website having to be a
`*.tenshu.app` subdomain.

The extension does **not** hold the master key. Per
[`docs/web/architecture.md`](web/architecture.md) §4.3, the master key always lives in
IndexedDB scoped to `tenshu.app`; the extension is a request interceptor that delegates crypto
operations to the apex tab/PWA. This means an extension exploit cannot extract the key — it
can only ask the apex to perform permitted operations.

Phased delivery:

- **v1 (first-party only)**: extension supports `*.tenshu.app` integrations. No third-party
  origin support. Useful for users who want the extension's better request-interception
  capabilities even while the third-party ecosystem is still gated on Rei.
- **v1.1 (third-party support)**: ships once Tenshu Rei's RPL / RIL / REL are operational. The
  extension verifies third-party origins' Rei publications, code-signing chains, and evaluator
  confirmations before granting any permissions. This is when the web ecosystem opens up
  beyond first-party Tenshu apps.

Major dependencies:

- Web Baseline shipping (the extension routes through the apex; needs a working apex first).
- Manifest V3 / WebExtensions API compatibility across Chrome, Firefox, Safari.
- For v1.1 only: Tenshu Rei (RPL / RIL / REL) operational — see *App Console & Tenshu Store*.

Design docs: [`docs/web/architecture.md`](web/architecture.md) §2 and §7; future
`docs/proposals/web-extension.md` for the extension manifest, content scripts, and permission model.

### Tenshu PWAs

Each Tenshu web app — the apex itself, plus per-app subdomains like `drive.tenshu.app` —
installable as a Progressive Web App. Installation gives users an app icon, a standalone
window without browser chrome, push notifications, and durable local storage that survives
the browser's normal storage-eviction policies.

PWA install is purely a UX upgrade. It does **not** change the security model, the connection
pattern, or where the master key lives. What it adds: Tenshu feels native on any platform
with a modern browser — ChromeOS, Linux desktops where the Compose Desktop target isn't
ideal, locked-down corporate environments where installing native apps is restricted, and
any mobile browser supporting PWA install.

For the apex specifically, the installed PWA also serves as the always-available proxy
endpoint for the Browser Extension's request routing. This is what makes the
"extension-as-pure-interceptor" design from `architecture.md` §4.3 painless in practice — the
apex PWA is the persistent tab the extension needs.

Major dependencies:

- Web Baseline shipping (PWAs are an installable form of the existing web apps).
- Per-app `manifest.json` files declaring installability and the Tenshu-specific extensions
  from `architecture.md` §6.
- Service workers handling offline capability and the persistence-of-storage hint.

Design doc: [`docs/web/architecture.md`](web/architecture.md) §2, §6, and §10.

### App Console & Tenshu Store

The closed-source web portal app developers use to publish, telemeter, and analyse their apps,
plus the open-ecosystem app store. Both interact with Tenshu Rei for publication, issue, and
evaluation flows. Tenshu Store is the alternative to Google Play and the App Store for Tenshu
Essential Apps and third-party Tenshu-enabled apps.

This milestone ships **alongside Creator and Creations** (below). All three rest on the same
machinery — signed artifacts, identity-bound entitlement records, cross-realm content
distribution, payment rails, evaluation/rating layer. Two products on top of Rei (apps for
the Store, content for Creations) shake out spec bugs that either alone would miss; doing
them concurrently is the right validator.

Major dependencies:

- Tenshu Rei layers RPL / RIL / REL operational.
- Cross-realm content distribution (see *Platform Prerequisites*) — Tenshu Store needs apps
  to flow from the developer's realm to the user's.
- Payment rails (shared with Creator / Creations).
- Developer onboarding flow.

### Creator

The companion app for people who publish content on Tenshu — the content equivalent of the
App Console. *Home* surfaces notifications (latest buys, comments, milestones); *Create*
captures or composes new content (photos, video, ebooks, arbitrary binary files) either by
upload from existing apps or directly from the camera; *Settings* manages payout configuration,
pricing, and creator-profile metadata.

Creator and Creations are paired apps. Creator publishes; Creations consumes. They reuse the
App Console's machinery: a creator's content artifact is signed, distributed via cross-realm
flow with an entitlement record proving the buyer has paid, and is revocable through the same
chain that powers app revocation.

Major dependencies:

- Cross-realm content distribution (see *Platform Prerequisites*).
- Payment rails (shared with App Console & Tenshu Store and Creations).
- Tenshu Rei publication / attestation chain (same as App Console).
- Creator-profile schema (shared with Creations' subscription model).
- Content-type adapters for the supported binary types (images, video, ebook formats, arbitrary
  files).

### Creations

The consumer side of the Creator / Creations pair. *Home* surfaces discovery (new content from
known creators, browse-by-creator, recommendations); *Collection* is the library of owned
content (images, videos, ebooks, binary files), with appropriate viewers per type; *Wishlist*
is content saved to buy later; *Settings* manages subscriptions and payment. Creations is to
content what the Tenshu Store is to apps.

DRM and licensing semantics are the open design question — owned content has to remain
viewable across the user's realm devices indefinitely, but the creator's revocation rights and
the consumer's "what does ownership mean here" expectations need a written-down position.
Threat-model implications: the relay sees the *fact* of distribution (creator-realm to
consumer-realm) even though payloads stay ciphertext.

Major dependencies:

- Cross-realm content distribution (see *Platform Prerequisites*).
- Payment rails (shared with App Console & Tenshu Store and Creator).
- Creator-profile schema (shared with Creator).
- Per-content-type viewers (image, video, ebook readers, plus a "raw binary download" fallback).
- Threat-model amendment for cross-realm payload-flow metadata.

### Mia

An intelligent personal assistant built around the **Voice App Service**. Voice apps are
Tenshu-enabled apps that perform actions from natural-language input, with semantic permissions
governing exactly what data they can see. The user interacts with Mia through a Tenshu
Essential App of the same name, or via a smart speaker connected to a Tenshu Hub. Mia's ML
features default to federated learning and on-device inference, in line with the
Minimal-Information design principle.

The Mia Essential App has five surfaces: an *Onboarding* flow (voice-ID setup, set-as-default
assistant); a *Home* screen to start Mia and adjust basic settings; a *Skills* tab listing
installed third-party voice skills and their settings; a *Routines* tab where the user wires
"if this then that"-style local automations across their realm; and *Settings* with an
Activity sub-screen showing recent voice interactions.

> **Update 2026-07-22:** the app surface is being built UI-first, reshaped into **two
> apps** — *Home* (smart-home dashboard, automations, activity, home-facing Hub surfaces)
> and *Mia* (standalone conversation-first assistant with a widget, useful without any
> smart-home devices). Skills stays gated on Rei as described below; everything else
> un-gates. Design + phasing: [`proposals/home-and-mia-apps.md`](proposals/home-and-mia-apps.md).

> **Update 2026-07-27:** two of the "Major dependencies" below are argued **off the
> critical path**. [`proposals/mia-query-layer.md`](proposals/mia-query-layer.md) (MQ-1..MQ-5)
> shows a *deterministic* planner over already-structured realm data makes Mia genuinely
> useful with **no LLM, no voice, and no third-party code** — the Voice App Service and the
> ML-services contract then *widen* a working app rather than gating a first one.
> **Update 2026-08-11 — MQ-1 SHIPPED.** Mia has a backend: a `:mia-service` module set on
> the proxy, a deterministic planner, and an `AskMia` op whose plan lowers to an ordinary
> chain-DSL callstack behind one validator. The chat surface answers from real Drive rows
> on both Mia and Home's embedded *Ask Mia*, with no LLM, no voice and no third-party code
> — which is the claim the doc made and this slice tests. As built:
> [`proposals/mia-query-layer.md`](proposals/mia-query-layer.md) §7. MQ-2 widens the
> sources (Timeline / Fit / Home / Calendar) and lands the per-service grants.
>
> [`proposals/inference-service.md`](proposals/inference-service.md) (IN-1..IN-5) settles the
> previously-unspecified half of "on-device inference": the execution seam, the
> per-platform runtime, model distribution + provenance, and per-capability opt-in — the
> phone/desktop case that `hub-hardware-and-management-service.md` §5 only ever addressed
> for the Hub. It is shared substrate with Drive's image features and `camera-service.md`
> CM-4.

Mia ships **after App Console & Tenshu Store** because its *Skills* tab is structurally
identical to third-party app distribution — third-party code asking for attestation-backed
permissions to access user data — and so depends on the same Rei machinery. Building Mia
first would mean either shipping without Skills or shipping a hand-rolled skills mechanism
that gets thrown away when Rei lands. Mia is the second consumer of Rei's attestation chain
(the App Store being the first), and that dual-validator role is part of why the App Store
milestone earns its keep.

Major dependencies:

- App Console & Tenshu Store / Tenshu Rei operational (Skills attestation and revocation).
- Voice App Service shape and the ML-services contract that follow Minimal Information.
- Background-service story (always-on voice trigger).
- Mia Essential App (audio capture, voice-app dispatch, response rendering).
- Routines engine — local automation that composes operations across services without
  any cloud round-trip.
- Tenshu Hub hardware path for the smart-speaker form factor (see below).
- ML model distribution that does not bottleneck on a single provider.

### Tenshu Hub

A small always-on home node — a *minimal home server* — that runs a Tenshu proxy and acts as
the centre other devices in the home connect to. It is **not** itself a smart speaker; it is the
hub a smart speaker (or several) plugs into, alongside IoT devices (smart bulbs, thermostats),
the user's phone, and the user's other realm devices. The shape is modular and **stackable**:
the base unit is a small PC, and the user grows it with expansion boxes — additional disk, a
dedicated AI/ML processing unit, etc. — to match whatever their household actually needs
(one speaker or five, with or without local inference). The Hub is the answer to "I want a
trusted always-on node in my own home — ambient compute, smart-home control, and my own
storage — without renting it from Amazon or Google." Pairs with the user's smartphone and
provides the **Hub Management Service** so it can be administered from any realm device.

It earns its keep across several roles at once, and which ones a given user wants varies:

- **Home trust root / always-on proxy.** A resident Tenshu proxy in the home — the natural
  brokering endpoint for the External Proxy Connection pattern (the iOS/web "proxy on another
  device" can be *this* device), so the household has a proxy that is always reachable and never
  someone else's hardware.
- **Ambient compute hub.** Smart speakers connect to it for the Mia voice integration (capture
  on the speaker → Voice App Service on the Hub), so the microphone lives in a small dedicated
  appliance, not a general-purpose cloud account. An optional AI/ML expansion box keeps Mia's
  inference on-device per the Minimal-Information principle.
- **Smart-home hub.** Bridges IoT devices (bulbs, thermostats, sensors) into the realm so
  Routines can automate them locally without a cloud round-trip.
- **Storage + relay resilience.** Disk-expansion boxes give the user their *own* growable
  capacity, reducing dependence on (and cost of) relay storage; and the Hub can act as a
  **store-and-forward buffer / cache** for the realm's ciphertext `SyncBlock`s — absorbing
  writes when the relay is full or down and forwarding them once it recovers. This rides the
  same storage SPI the relay-persistence work introduced (durable block + blob stores), so the
  Hub is "a relay you own a slice of," not a parallel implementation.

The exact product shape is still open (this entry captures intent, not a committed design), but
the through-line is "one modular box you grow," not a single fixed appliance.

Major dependencies:

- Hub Management Service interface.
- Hardware reference design + an **expansion-module interface** for stackable storage / compute
  boxes (the "grow it to fit the household" property).
- Smart-speaker audio pipeline (capture on the connected speaker → Voice App Service → response).
- IoT-device bridge (smart bulbs, thermostats, etc.) for the smart-home use case.
- Local SyncBlock store / store-and-forward buffer fronting the relay, on the relay-persistence
  storage SPI; threat-model note (a home device holding ciphertext + acting as a proxy/trust
  root is within the user's trust domain, but it is a new at-rest location and an
  always-reachable proxy surface).

---

## Out of Scope (For Now)

The following are mentioned in the whitepaper or otherwise intended but are not currently sized
as discrete milestones. They will become milestones once their dependencies firm up.

- **Maps full (Explore + Navigate tabs)** — an *interactive* (pan/zoom) map, routing,
  turn-by-turn navigation. Static offline tile rendering already ships in slim; what is
  deferred is interactivity and everything route-shaped. Interactivity is not free: the
  streamed region delivery fetches only the covering tiles for a *fixed* camera, so a pannable
  map needs a demand-driven fetch loop (or a fully downloaded region), not just enabling
  gestures. The privacy-preserving version of the rest is nearly its own startup:
  OSRM/Valhalla-class routing without leaking origin/destination to a third party. *Maps —
  Places (slim)* ships first and covers the parts of Maps that fall out of the existing Places
  service for free; the Explore + Navigate expansion is the harder, deferred work.

- **Element (Matrix client)** — a Tenshu-frame chat client based on the Matrix protocol,
  potentially derived from `vector-im/element-android`. The scope question to settle before
  this becomes a milestone: is it a Matrix *client* that lives in the Tenshu app frame and
  uses Tenshu identity to log into a chosen homeserver (cheap, mostly UI integration), or is
  Tenshu *itself* a Matrix homeserver (substantial protocol-integration work touching the
  relay)? Sized very differently in each case.

- **Tenshu Essential Apps** beyond the named ones above (Drive, Photos, Calendar, Contacts,
  Alarm, Timeline, Maps slim, Fit, Mia). The category is open — anything where Tenshu's first-party
  app is the trusted default for a use case also covered by Google or Apple.

- **Higher Tenshu Rei layers** (above REL) that enable cross-app-store interoperability and
  privacy-preserving digital-item ownership.

---

## How Milestones Get Promoted

A future-app entry on this page becomes "in development" once: (a) it has a design doc in
`docs/`, (b) its module shape is set in `settings.gradle.kts`, and (c) its required threat-model
amendments have landed. Until then, entries here are intent statements, not commitments.
