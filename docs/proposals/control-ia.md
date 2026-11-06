# Tenshu Control — Information Architecture

**Status:** Draft v0.1
**Scope:** The platform-neutral information architecture of Tenshu's Control surface — the
section structure, what each section contains, and the user actions each enables. Web,
Desktop, and Android render this same IA; chrome and platform-specific affordances live
in their respective platform specs.

This doc is the canonical IA the per-platform Control surfaces consume:

- [`web-control-plane.md`](web-control-plane.md) — web/apex rendering.
- *(Future)* native Control screens spec — Android + Desktop, working from this IA.

The auth flows that lead into Control are specced separately:
[`web-onboarding-ux.md`](web-onboarding-ux.md) for web,
[`control-onboarding-ux.md`](control-onboarding-ux.md) for native.

This doc does not commit to visual design, layout, or navigation chrome — those are
platform concerns and live in the per-platform specs.

---

## 1. Goals

Control is the user-facing management surface for the Tenshu platform. It surfaces:

- What's happening in the realm (notifications).
- Which apps are installed and what each can do (apps and their permissions).
- What data lives in the realm (files, devices, places, future types).
- Which platform services are configured and how (location, storage, notifications,
  identity).
- Realm-level configuration (sync, recovery, appearance, advanced).

Each section maps onto a Tenshu-specific architectural concept rather than a generic
"settings" pattern. This IA expresses the platform's thesis — that data is the user's,
persists across apps, and apps are temporary lenses — visibly, in the structure of the UI.

The IA is identical on all three platforms (Web, Desktop, Android) so that learning
Control on one transfers unmodified to the others.

---

## 2. Section Map

Five top-level sections:

| Section      | Purpose                                                                     |
|--------------|-----------------------------------------------------------------------------|
| **Home**     | Realm-wide notifications stream — the heartbeat across all apps             |
| **Apps**     | Installed apps and their per-app permissions, services, and metadata        |
| **Data**     | The realm's data inventory — files, devices, future types (places, photos…) |
| **Services** | Platform services apps consume, configured once and applied everywhere      |
| **Settings** | Realm-level configuration — profile, appearance, sync, recovery, advanced   |

Recovery deliberately lives under Settings (§7.2), not as its own top-level. Devices
deliberately lives under Data (§5.2), not as its own top-level. Both decisions are pinned
in their sections with rationale.

---

## 3. Home

**Purpose**: orient a returning user on what's happened in their realm since they last
looked.

**Content**: a chronological feed of notifications from `notification-service`, covering:

- Data changes (files added, modified, removed; future: places visited, photos imported).
- Security events (devices paired, devices revoked, recovery phrase verified).
- Permission decisions (apps granted or denied permissions, ask-each-time prompts
  resolved).
- Service events (storage tier changes, sync errors, relay reachability changes).

Each entry is single-line, timestamped, scannable. Most recent ~10–20 entries shown by
default with progressive expansion.

**Actions**:

- Drill into entry detail (e.g. file → opens that file in Drive).
- Mark all read.
- Clear individual notification.

**v1 scope: unfiltered feed.** All notifications surface in chronological order without
per-app or per-importance filtering.

**Future improvement** (also pinned in §9 Open Items): filtering by **importance**
(security events and permission decisions surface first; data changes deprioritise) and
by **app** (silence Drive while still seeing Timeline). The `notification-service` already
has a channel/category model that filtering should map onto rather than introducing a new
one.

**Empty state**: a brand-new user just out of onboarding sees a friendly *"Activity will
show up here as you use Tenshu"* with a link to the Apps section.

**Recovery surfacing**: when the recovery phrase is unverified or stale (>6 months since
last test), Home shows a prominent *"Verify your recovery phrase"* tile or banner that
links to Settings → Recovery (§7.2). This is how Recovery stays findable in panic
scenarios despite living one level down — Home does the alerting, Settings owns the
steady-state surface.

---

## 4. Apps

**Purpose**: manage installed Tenshu apps — what they are, what they can do, what they
depend on.

### 4.1 Apps List

A grid (or list — platform's choice) of installed apps. Each app card shows: icon, name,
install date, version, short description from manifest.

v1 ships with **Drive**. Other first-party apps (Mia, Timeline, Hub, future) join the
list as they ship.

The record this list reads from — how an app becomes *installed in the realm* on its first
verified connection, and how `install date` is produced — is specified in
[`control-app-registration.md`](control-app-registration.md). In short: registration is a
synced `AppRegistration` record keyed per `(appId, deviceId)`, written by the proxy on an
app's first authenticated handshake (silent for default apps; non-default apps surface
consent via permission/primary-handler grants anyway). The list groups registrations by app
and shows per-device install status.

**Apex / Control itself is not listed** under Apps. The apex (on web) and Control (on
native) is the management surface; treating it as an app to permission against is
mental-model overhead without a real configuration target. The web-specific framing of
this is in `web-control-plane.md` §1; the same logic applies on native.

### 4.2 App Detail

Click an app card to drill into its detail. **v1 fields**:

- **Permissions**: full list of declared semantic permissions from the manifest, with
  the user's current grant state (granted / denied / ask-each-time). Per-permission
  human-readable description, scope (if applicable), last-used timestamp.
- **Services**: which platform services this app consumes (location, storage, etc.).
  Read-only here — actual configuration lives in §6 Services. Cross-link from each entry
  to the service's detail page.
- **Install date**: when the app was added to the realm.
- **Version**: current version installed; manifest source.

Per-app actions:

- Revoke all permissions (resets grants to ask-each-time defaults).
- Uninstall the app. Per [`control-app-registration.md`](control-app-registration.md) §5,
  "Uninstall" here means **deregister** — Control cannot delete the binary. It defaults to
  *this device only* (tombstones the `(appId, deviceId)` registration and purges grants),
  offers an explicit *Remove from all devices*, and shows a *"to fully remove, uninstall
  from your OS"* hint.

**Deferred fields** (worth surfacing as the platform grows):

- **Source / Rei publication and code-signing chain** — gated on Tenshu Rei being
  operational. When Rei lands, App Detail grows publication identity and evaluator
  confirmations as additional fields without disrupting the IA.
- **Per-app data summary** — link to *"data this app has touched"* once Data's per-app
  filtering matures. Designed in [`control-data-reset.md`](control-data-reset.md) §8 (R2),
  which also settles where the *delete* verb lives: on the **Data** type, never on the app
  (§2 — apps share rows, so no app owns its data).
- **Per-app activity log** — currently lives at the realm level (Home); a per-app filter
  is a future improvement.
- **App billing state** — once apps can be paid (App Console / Tenshu Store milestone),
  one-time-purchase or subscription state surfaces here.

The IA is intentionally extensible: new fields slot into App Detail as platform features
land, without disrupting the section structure.

### 4.3 EPC app pairing lives under Apps — Decision (2026-06-21)

On iOS (and any future EPC client surface), an app reaches its services through an **External
Proxy Connection** that must be *paired* — a QR + SAS ceremony establishing an app↔proxy
channel ([`ios-proxy-architecture.md`](ios-proxy-architecture.md) §10). The question was where
that pairing surface and the resulting connection state belong in this IA. **Decision: under
Apps, not under Data → Devices and not as a new screen in Settings.**

Rationale:

- **Not Data → Devices.** A realm device holds the master key (§5.2). An EPC-paired app is
  *keyless* — it never receives key material; the proxy holds the keys and does the crypto on
  its behalf (ios-proxy §10.1; app-registration §1.3). Listing it among realm devices would
  teach the user their phone's *app* holds realm keys, which is false and security-relevant.
- **Not a Settings screen.** Settings is realm-level configuration with no multi-step flows
  (§7.1); a scan/SAS ceremony is exactly a multi-step flow, and a second "Apps" surface under
  Settings duplicates this section.
- **Apps is the natural home.** The EPC-paired app (e.g. Drive on the phone) is the user's own
  app and already produces an `AppRegistration{appId, deviceId}` rendered here as
  *"Drive — installed on Phone"* (§4.1). What is *new* is not the app but the **remote
  connection** to it. Surface the connection as a property of the app: **App Detail →
  Connections** (*"Drive on iPhone — connected via EPC, paired <date>, SAS confirmed ·
  Reconnect / Unpair"*), with a *Connect on another device* action at the top of the Apps list.
  The pairing entry point runs on the **Control that acts as proxy** (it shows the QR).

Copy should do the mental-model work — e.g. *"your phone's Drive talks to this Control, which
keeps your keys"* — conveying that the app is routed through Control without implying the phone
is a full realm device.

---

## 5. Data

**Purpose**: surface the realm's data inventory — what data the user owns, regardless of
which app produced or consumes it. This section materialises the platform thesis that
*data persists across apps; apps are temporary lenses*.

### 5.1 Data Types

v1 surfaces:

- **Files** — count, total size, growth over time, source apps. *Not* a file browser:
  Drive is the file browser. Data → Files is an inventory and meta-view; clicking a file
  link routes into Drive for actual interaction.
- **Devices** — list of realm devices with security metadata (pairing date, fingerprint,
  last sync). Per-device actions: rename, revoke, view fingerprint. Detail in §5.2.

Future data types as they ship:

- **Places** (Timeline) — locations the realm has recorded.
- **Photos** (Photos app, future) — photo inventory, storage breakdown.
- **Notes** (Notes app, future).
- **Contacts**, **Calendar entries**, etc.

Each data type has its own list/inventory view inside Data. The set is open-ended; new
data types added by feature services or apps register with Control to appear here.

### 5.2 Devices Under Data — Decision

Devices fits semantically as "data the realm has." A realm has files, has devices, has
places. This is internally consistent and avoids a separate top-level just for devices.

The security-flavoured operations (revoke, fingerprint, browser-sync handling on web)
live in the device detail panel. High-frequency security actions like *Lock now* and
*Sign out everywhere* are surfaced from the chrome (per the platform-specific renderings)
so they're not buried inside Data.

The pair-new-device flow lives at the top of the Devices list view, not in chrome —
pairing is a less frequent operation than locking, and benefits from the surrounding
context of *"here are the devices I have."*

**Control-on-iOS appears here as a realm device.** When Control runs on iOS it is a full,
key-holding realm member — including the iPhone-only user whose phone is the *first* device of
the realm (genesis), or a second Control that joined via device-pairing
([`ios-proxy-architecture.md`](ios-proxy-architecture.md) §11). That is the distinction from
§4.3: Control instances are realm devices and belong here; EPC-paired *apps* are keyless and
belong under Apps.

### 5.3 Shares Under Data — Decision (locked)

Outbound shares — the anonymous link shares minted from Drive
([`docs/file-sharing.md`](../file-sharing.md) + [ADR 0018](../adr/0018-file-sharing-mode-a.md),
Mode A), and, as shared realms land
([`tenshu-rei.md`](tenshu-rei.md) §2.3 / §5.C), shared folders + their members — surface
as a **Shares** subsection under Data. Rationale: Control's thesis is the realm-level
security/data dashboard, and "what is my realm exposing, and let me shut any of it off" is
exactly that. Mirrors the Devices-under-Data call (§5.2): the realm *has* shares the same
way it *has* devices.

Division of labour, locked:

- **Per-file create / copy-link stays in Drive** — the app owns the act of sharing a file
  (`CreateShareLink*` in `drive-shared`). Control does not mint links.
- **Realm-wide inventory + revoke lives in Control's Shares subsection** — a single place
  to audit every outbound share (file name, created date, expiry/opens caps, opens-so-far,
  a Revoked badge) with a per-row Revoke. Bound to the realm-wide `FileService.shares`
  projection (populated by `ShareRelationMaintainer`); revoke flips the Eager `Share.revoked`
  flag *and* issues `DELETE /share/{shareId}` to the relay.
- **Shared-folder member management** (when shared realms land) also surfaces here: a member
  list (pseudonym, role, joined-at) with Remove member, capped by the role model
  (`tenshu-rei.md` §5.C Q29). Stubbed/empty until the shared-realm protocol exists.

This subsection is the Control half of Track C
([`docs/tracks/track-c-shared-realm-sharing.md`](../tracks/track-c-shared-realm-sharing.md));
the protocol half (`Share` entity, relay store, `RestShareRelayClient`, per-chunk AEAD under
`K_share`) landed with the Drive sharing work. The recipient-side viewer is Drive's surface
(`web/apex` `/s/:shareId`), distinct from this management view.

**Implemented (C2 + C3 + C4).** *Compose (Android/desktop):* `ShareManager`
(`control-shared/.../data/ShareManager.kt`) projects the realm-wide `FileService.shares`
collection (newest-first, revoked rows kept) + `revoke`; `SharesScreen` / `SharesViewModel`
render the inventory with per-row revoke-confirm + a Revoked badge + empty state, reached from a
Data-screen *Shares* row (`Destination.Shares`). *Web:* the authenticated `Shares` SDK surface
(`@tenshu/sdk/share` — `Shares.list()` / `Shares.revoke()` over the same projection, distinct
from the anonymous `ShareViewer` recipient surface) backs `SharesPage` at `/control/data/shares`
(per-row revoke behind a confirm dialog, Revoked badge, empty state). The shared-folder surface
(`sharedFolders`/`members`/`removeMember`) is stubbed until the shared-realm protocol lands;
`ShareDetail` (a per-share detail screen) is deferred — the list rows carry enough to audit +
revoke. Shared-realm primitives (C3-later) remain.

---

## 6. Services

**Purpose**: configure platform services that apps consume across the realm. A standing
configuration set once here applies to every app that asks for that service — apps don't
configure platform services themselves.

This section is the most architecturally distinctive in Tenshu's IA. No general-purpose
competitor has it: Google and Apple platforms expose per-app permission grants (similar
to §4) but not user-configurable platform services that apply uniformly.

### 6.1 What Counts as a Service

A **Service** in this IA is a platform capability that:

1. Multiple apps may consume.
2. Has user-meaningful configuration (precision, frequency, defaults, subscription tier,
   etc.).
3. The platform mediates uniformly — the user's standing config takes precedence over
   any individual app's request.

Hidden from this surface (platform plumbing the user doesn't configure per-app):

- SyncService — relay endpoint, sync interval. Surfaces under Settings → Sync (§7) since
  it's a single global config, not a service apps interact with through grants.
- IdentityService internals — key derivation, master-key wrapping. Not user-configurable.

### 6.2 v1 Services

- **NotificationService** — silence per-app, badge config, do-not-disturb schedules.
- **StorageService** — current tier, consumption breakdown, subscription management,
  upgrade flow. This is where storage tier upgrade lives, referenced from
  `web-onboarding-ux.md` §4b step 9 as *"the control plane / Tenshu Store."*
- **IdentityService** — display name across apps, avatar, language preference.

Future services as feature areas land:

- **LocationService** — GPS precision (block / street / GPS), frequency caps,
  per-time-of-day rules. Required for Timeline.
- **AIService** (Mia) — local model selection, when to permit cloud-fallback (if ever).
  **Superseded 2026-07-27 by [`inference-service.md`](inference-service.md)**: the surface
  is a per-**capability** opt-in registry (OCR / semantic search / tagging / faces / STT —
  IN-D3), not a model picker, and it rides `PreferenceService` split across `RealmSetting`
  ("may this realm be indexed") and `DeviceSetting` ("may *this* device do the work").
  Mia's read-access grants are a separate, per-service list —
  [`mia-query-layer.md`](mia-query-layer.md) MQ-D6.
- **ExternalNetworkService** — when the proxy permits an app to reach a non-Tenshu URL,
  default cache duration, allowlist UX.

### 6.3 Service-App Interaction

When an app asks the platform for a service capability, the user's standing service config
applies. Examples:

- Timeline asks LocationService for GPS-precision location → user has set LocationService
  default to *block-level* → Timeline gets block-level data, not GPS.
- Drive asks StorageService for X bytes → StorageService surfaces tier and consumption;
  if over quota, the request fails with a typed error and Control prompts an upgrade.

App-side permissions (§4) and Service-side configuration (§6) are orthogonal: the app
must *be permitted* to ask, and the service *configuration* shapes the answer. Both gates
apply.

---

## 7. Settings

**Purpose**: realm-level configuration that doesn't fit Apps, Data, or Services.

### 7.1 v1 Surface

- **Profile** — device name, display name (reused by IdentityService), language
  preference.
- **Appearance** — theme (light / dark / system).
- **Sync** — relay endpoint (hidden by default; advanced expand for self-hosted relay),
  sync interval, bandwidth-limit toggle, sync diagnostics link.
- **Recovery** (§7.2 — pinned location).
- **Advanced** — clear local cache, export device key fingerprints (for verification),
  developer tools toggle, telemetry opt-ins (default off).
- **About** — version info, links to whitepaper / threat-model / repo.

Each item is a small inline-edit field or toggle. No multi-step flows in Settings
proper — anything that needs a flow lives in its own sub-section (Recovery), or in its
own top-level section (Devices under Data, etc.).

### 7.2 Recovery Under Settings — Decision (v1, locked)

Recovery lives as **Settings → Recovery** rather than its own top-level. Rationale:

- Recovery is high-stakes but low-frequency. Most users touch it at onboarding and
  rarely again. A top-level slot would over-weight its day-to-day relevance.
- Findability in panic is preserved by Home's surfacing of *"Verify your recovery
  phrase"* as a prominent banner when the phrase is unverified or stale (per §3). Home
  does the alerting; Settings owns the steady-state surface.

Recovery section content (per `sync/threat-model.md` §11):

- **Recovery status** — green/amber summary of *"Your recovery phrase will restore X out
  of Y things,"* with a CTA to fix gaps.
- **Recovery manifest** — list of opt-in coverage items per threat-model §11.1. Master
  identity is always covered; per-volume opt-ins (Drive folders, future per-app data)
  are toggleable.
- `[Re-display recovery phrase]` — gated behind a fresh authenticator challenge (WebAuthn
  on web, biometric on native). Shows the 24-word mnemonic full-screen with the same
  warnings as during onboarding.
- `[Test recovery]` — a non-destructive flow asking the user to type 3 random words from
  the phrase. Stamps a "verified at {timestamp}" mark stored locally so Home and the
  Recovery status indicator know the backup is fresh.
- **Lost-everything explainer** — small inline explainer linking to the recovery-claim
  flow specced in `web-onboarding-ux.md` §6 (web) / `control-onboarding-ux.md` §8
  (native).

**v2 reconsideration trigger**: if user research surfaces that users can't find Recovery
when they need it, promote it to a top-level. v1 commits to Settings → Recovery; revisit
based on real-world findings.

---

## 8. Drill-In and Cross-Section Linking

A consistent pattern across sections:

- **List → detail panel** (or pane / sheet on mobile) for any item with per-item config.
- **Section → sub-section** for grouped content (e.g. Settings → Recovery, Data →
  Devices).
- **Detail entry → cross-section link**: e.g. Apps → Drive's detail showing *"Uses
  LocationService"* links to Services → LocationService for configuration.

Cross-section linking is encouraged — IA boundaries are conceptual, not navigational
walls. The platform-specific specs implement these links with whatever affordance is
natural (URL navigation on web, fragment navigation on native).

---

## 9. Open Items

- **Notification filtering on Home (§3 future improvement)**. v1 ships unfiltered. Once
  notification volume warrants, add filters by **importance** (security events and
  permission decisions surface first; data changes deprioritise) and by **app** (silence
  Drive while still seeing Timeline). Map onto `notification-service`'s existing
  channel/category model rather than introducing a new one.
- **Per-app activity log (§4.2 deferred)**. Currently lives at the realm level (Home).
  Adding a per-app filter or an Activity tab in App Detail is a follow-up.
- **Data → Files browser scope**. Pinned: not a file browser (Drive owns that). Open:
  what *exactly* Data → Files surfaces beyond inventory. Last-modified summary?
  Visualisations of file size by source app? Storage breakdown by folder? Scope before
  designing.
  *Web, 2026-10-28:* the apex built the §5.1 inventory only — counts, total size, newest change,
  size by kind (from extensions) — over `FileSystem.inventory()`; native still has no Files screen.
  Growth over time and source apps remain unbuilt (`web-app-suite.md` §4.C).
- **Services discovery copy**. Service names are jargony to a non-technical user
  ("LocationService," "IdentityService"). Each service needs plain-language framing on
  its detail screen — e.g. *"Location: how precisely apps know where you are"* — following
  the same tone as `web-about-tenshu-public.md` §12.
- **Source / Rei publication on App Detail**. Deferred until Tenshu Rei is operational.
- **App billing surface**. If apps eventually become paid (App Console / Tenshu Store
  milestone), the billing surface likely lands as a per-app field in App Detail and as a
  Service-level surface for cross-app spend.
- **External-data-type registration**. The "any data type can register with Control to
  appear in Data" claim from §5.1 needs an actual registration mechanism in the schema
  layer. Not blocking v1 (which hard-codes Files and Devices) but blocks future apps
  shipping new data types without core changes.
- **App registration & removal mechanism**. *Now specced* in
  [`control-app-registration.md`](control-app-registration.md): the synced `AppRegistration`
  record (per `(appId, deviceId)`), proxy-written silent first-run registration, and
  per-device-default removal that backs §4.1's `install date` and §4.2's *Uninstall*. Open
  sub-items (module home, re-add UX) tracked there.
- **EPC app pairing placement**. *Decided* (§4.3, 2026-06-21): under Apps (App Detail →
  Connections), not Data → Devices and not Settings. Control-on-iOS, by contrast, is a realm
  device and surfaces under Data → Devices (§5.2). Both trace to
  [`ios-proxy-architecture.md`](ios-proxy-architecture.md) §10/§11. Open sub-item: the concrete
  App Detail → Connections layout (a UI design task, not an IA one).
- **v2 reconsideration of Recovery placement**. Locked to Settings → Recovery for v1;
  revisit if findability proves a problem.

---

## 10. References

- [`docs/web/architecture.md`](../web/architecture.md) — §3 (apex/subdomain), §6
  (manifest), §8 (onboarding entry).
- [`docs/proposals/web-control-plane.md`](web-control-plane.md) — web rendering of this
  IA.
- [`docs/proposals/web-onboarding-ux.md`](web-onboarding-ux.md) — auth flows that land in
  Control on web.
- [`docs/proposals/control-onboarding-ux.md`](control-onboarding-ux.md) — auth flows that
  land in Control on native; cross-platform cousin for naming and copy decisions.
- [`docs/sync/threat-model.md`](../sync/threat-model.md) §11 — recovery model
  (referenced from §7.2).
- [`docs/sync/pairing-flow.md`](../sync/pairing-flow.md) — pairing flow referenced from
  §5.2.
- [`docs/sync/design.md`](../sync/design.md) §2.2 + [ADR 0007](../adr/0007-sync-device-management.md)
  — device revocation, sign-out everywhere, referenced from §5.2.
