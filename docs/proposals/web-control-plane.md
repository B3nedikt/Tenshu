# Tenshu Web Control Plane — Routes, Chrome, and Web-Specific Rendering

**Status:** Draft v0.2 — Phase A LANDED (2026-05-09)
**Scope:** The web-specific rendering of the platform-neutral IA in
[`control-ia.md`](control-ia.md). Specifies routes, the apex shell chrome, the app launcher
(web-specific because native uses the OS app drawer), and how Drive's eventual move to
`drive.tenshu.app` interacts with Control's apex chrome.

**Phase A status (2026-05-09)**: shell chrome (§3 top bar + side rail + bottom nav),
app launcher (§3.1 with Drive + Tenshu entries), device-name pill + Lock now + Sign out
everywhere (§3.2), Home (§4.1) with the SDK's `Notifications.observe` stream + recovery
banner (read-only IDB stamp; the write path lands with §4.6 Test recovery in Phase B), and
the §2 routes (`/control` → home, placeholders for Apps/Data/Services/Settings, legacy
`/files/*` → `/drive/files/*` redirect, `/drive` → `/drive/files`) are implemented.
Sync-status indicator renders the green-default placeholder pending
`SyncManager.divergenceWarning` exposure. Post-onboarding/unlock landing destinations
moved from `/files` to `/control/home`.

This doc *consumes* the IA in `control-ia.md`. The IA — Home, Apps, Data, Services,
Settings — is identical on Web, Desktop, and Android. This doc adds the web-specific
layer: routes, chrome composition, launcher behaviour, cross-origin transitions.

It complements:

- [`control-ia.md`](control-ia.md) — the IA each section's content references.
- [`web-apex-landing.md`](web-apex-landing.md) — the public landing that gates entry into
  Control.
- [`web-onboarding-ux.md`](web-onboarding-ux.md) — auth flows that land in Control.
- [`docs/web/architecture.md`](../web/architecture.md) §3 — the apex/subdomain split.

**Changelog from v0.1**:

- The IA sections (originally §§4–8 of v0.1) have moved into `control-ia.md` so that
  web, Desktop, and Android share a canonical IA. This doc retains the web-specific
  routing, chrome, launcher, and Drive cross-origin pieces.
- Routes adopt a per-app prefix shape (`/control/home`, `/drive/files`) so the URL tells
  you which app you're in and the path-suffix is preserved across the eventual apex →
  subdomain split (`tenshu.app/drive/files` ⇒ `drive.tenshu.app/files`).

---

## 1. Naming Decision: "Control" Is the Apex

On Android and Desktop, Drive and Control are **separate installable apps**. A user opens
the app drawer and picks one. Control mediates Drive (and other Tenshu apps) over IPC.

On web, the architecture is different. Per `web/architecture.md` §3.1, **the apex
(`tenshu.app`) hosts the control plane**, and Tenshu apps live on per-app subdomains
(`drive.tenshu.app`, eventually). The apex *is* the control plane — there's no separate
"Control web app" to install or switch into. Branding the apex's control-plane sections
as a distinct "Control" surface would create a mental model that doesn't match the
architecture: users would wonder why the URL doesn't change when they "open Control."

**This doc commits to**: the web user-facing name for the apex's control-plane surfaces is
**Tenshu** — same as the wordmark in the chrome. The internal section names are Home,
Apps, Data, Services, Settings — descriptive UI labels matching `control-ia.md`'s IA, not
app names. Native Control's role maps onto the web's chrome and these sections, not onto
a separate URL or origin.

The terminology *Control* still appears in:

- Documentation (this doc, `control-ia.md`, the architecture doc).
- The native Android/Desktop apps where it's the literal app name.
- Cross-references between web and native ("the web equivalent of Control").

It does **not** appear as a user-visible label inside `tenshu.app`'s chrome or navigation.

---

## 2. Route Map

The apex routes every app's surface under a per-app prefix that matches the app's name.
Control's surfaces live under `/control/*`; Drive's under `/drive/*`. Each app prefix has
an explicit `home` route — there are no implicit defaults at the prefix root.

| Route                             | Renders (`control-ia.md` reference)                                   |
|-----------------------------------|-----------------------------------------------------------------------|
| `/control`                        | 302 → `/control/home`                                                 |
| `/control/home`                   | Home — notifications feed (IA §3)                                     |
| `/control/apps`                   | Apps list (IA §4.1)                                                   |
| `/control/apps/:appId`            | App detail (IA §4.2)                                                  |
| `/control/data`                   | 302 → `/control/data/files`                                           |
| `/control/data/files`             | Files inventory (IA §5.1)                                             |
| `/control/data/devices`           | Devices list (IA §5.1 + §5.2)                                         |
| `/control/data/devices/:deviceId` | Device detail panel                                                   |
| `/control/services`               | Services list (IA §6)                                                 |
| `/control/services/:serviceId`    | Service detail (IA §6.2)                                              |
| `/control/settings`               | Settings landing (IA §7)                                              |
| `/control/settings/recovery`      | Recovery (IA §7.2 — locked location)                                  |
| `/drive`                          | 302 → `/drive/files` (until Drive ships a separate home view)         |
| `/drive/files/*`                  | Drive — embedded apex-side today; cross-origin Phase 8                |
| `/files/*`                        | 302 → `/drive/files/*` (legacy compatibility, can drop after Phase 8) |

`/control/home` is the **landing destination after onboarding** and after a successful
unlock (per `web-apex-landing.md` §4 smart-CTA). A signed-in user clicking the wordmark
in the apex chrome lands at `/control/home` (when not on the public landing) or at `/`
(when the wordmark links there).

**Why per-app prefixes**:

- Symmetric with `/drive/*` and any future app (`/mia/*`, `/timeline/*`) so the URL tells
  you which app surface you're in.
- The path suffix is preserved across the apex/subdomain split: `tenshu.app/drive/files`
  becomes `drive.tenshu.app/files` after Phase 8 — pure prefix swap.
- Aligns with how Compose Navigation typically structures routes on native
  (`Control.Home`, `Control.Apps`), so cross-platform parity goes one level deeper than
  just IA-section names.

**Shorthand aliases not committed in v1**: `/apps`, `/data`, `/services`, `/settings` at
the apex root. Recommend keeping `/control/...` canonical to match the Control app's
name on native and to keep symmetry with `/drive/...`.

---

## 3. Apex Shell Chrome

The post-auth shell wraps every `/control/*` and `/drive/*` route. It's distinct from
the public-landing chrome (`web-apex-landing.md` §5).

**Layout** — app shell pattern.

- **Top bar** (sticky, ~56px tall):
    - Left: Tenshu wordmark (links to `/control/home`, *not* to `/`).
    - Center: app launcher trigger (§3.1).
    - Right: device-name pill + lock button (§3.2).
- **Side rail** (desktop, ≥`md` breakpoint) with section navigation matching `control-ia.md`'s
  five sections:
    - Home — `/control/home`
    - Apps — `/control/apps`
    - Data — `/control/data` (resolves to `/control/data/files` for now)
    - Services — `/control/services`
    - Settings — `/control/settings`
- **Bottom nav** (mobile, <`md`) — same five items as the side rail.
- **Main content area** — the active section's screens.

The side rail renders the user's current realm device name and a small status indicator
(green = synced, amber = syncing, red = sync error). This mirrors Control on
Android/Desktop, which surfaces sync status persistently.

### 3.1 App Launcher

The launcher is the *"switch to Drive"* mechanism the architecture calls out but doesn't
spec. It opens from the top-bar center trigger as a small popover (or full-screen sheet
on mobile).

**Contents**:

- A grid of installed Tenshu apps. v1 ships with two entries:
    - **Drive** — `tenshu.app/drive/files` today; `drive.tenshu.app` after web roadmap
      Phase 8.
    - **Tenshu** (the apex itself) — `/control/home`. Self-referential but useful as a
      "go home" affordance from anywhere, including from inside Drive.
- Each entry shows the app's icon, name, and a one-line description (sourced from the
  app's manifest, per `web/architecture.md` §6).
- Below the grid, a `+ Install another app` affordance — links to the apex's app-store
  surface (out of scope for v1; gated on Phase 8 + Tenshu Rei).

**Behaviour**:

- Click an entry → navigate to that app's URL. For first-party apps on `*.tenshu.app`,
  the apex shell unmounts and the subdomain takes over. PWA-installed apps open in their
  own window per the manifest's `display: standalone`.
- The launcher is keyboard-navigable (arrow keys + Enter); the trigger has the standard
  `aria-haspopup="dialog"` semantics.
- Persistent across the apex shell — visible from every section, including from
  `/drive/*`.

**Note on the launcher vs. native parity**: native platforms don't render an in-app
launcher because the OS app drawer plays that role. The launcher is a web-only chrome
piece; `control-ia.md` doesn't define it because it's not part of the IA. It exists here
to fulfil the same affordance the OS app drawer fulfils on native.

**Copy decisions** (working drafts):

- Trigger button label: an icon-only 9-dot grid icon with `aria-label="Apps"` and a
  tooltip *"Open another Tenshu app"*.
- Apex's own entry label: **Tenshu** (not "Control" — per §1). Tooltip: *"Devices,
  permissions, recovery, settings."*
- Drive's entry tooltip: *"Your end-to-end encrypted file system."*
- Empty state: there's never truly an empty state — the grid always shows at least Tenshu
  and Drive.

### 3.2 Device-Name Pill + Lock Button

Top-right slot. The pill shows the user's current realm-device name (truncated if long).
Clicking the pill opens a small menu:

- *"You're signed in on {device_name}."*
- `[Lock now]` — wraps the master key, redirects to `/lock`.
- `[Sign out everywhere]` — full sign-out, requires re-onboarding to return. Uses the
  device-revocation path from [`docs/sync/design.md`](../sync/design.md) §2.2 +
  [ADR 0007](../adr/0007-sync-device-management.md). Confirmation modal required.

The Lock button is also surfaced as a standalone icon next to the pill for one-click
access — same affordance as native Control's lock-now action.

These are deliberately surfaced from chrome rather than buried under Data → Devices: per
`control-ia.md` §5.2, *"high-frequency security actions like Lock now and Sign out
everywhere are surfaced from the chrome ... so they're not buried inside Data."*

---

## 4. Web-Specific Section Renderings

The IA's content lives in `control-ia.md`. Web-specific deviations or elaborations are
captured here.

### 4.1 Home (Notifications) — `/control/home`

Renders the IA's notification feed (`control-ia.md` §3). Web-specific:

- React component: `<NotificationFeed />` subscribed to `notification-service`'s stream
  via the SDK's `Notifications.observe(...)` operation.
- Mark-all-read, clear-individual notification, and feed pagination all use the SDK's
  notification operations.
- Notification → Drive cross-origin transition (post-Phase 8): clicking a file
  notification navigates the browser to `drive.tenshu.app` with the file's path in the
  URL fragment.
- Recovery banner from `control-ia.md` §3 ("Verify your recovery phrase" when stale or
  unverified) renders above the feed. It links to `/control/settings/recovery`.

### 4.2 Apps — `/control/apps`

Renders the IA's apps list (§4.1) and detail (§4.2) screens. Web-specific:

- App icons / manifest sourced from each app's per-subdomain manifest, fetched by the apex
  per `web/architecture.md` §6.
- App detail's Permissions surface uses the same tri-state pattern (granted / denied /
  ask-each-time) as the rest of Control; per-permission "Last used" timestamp comes from
  the apex's permission-grant audit log.
- Apex (Tenshu itself) is not listed (per IA §4.1).
- "Uses {service}" cross-links (per IA §8) navigate to `/control/services/:serviceId`.

### 4.3 Data — `/control/data`

Renders the IA's Data sections (§5). Web-specific:

- **Files inventory** (`/control/data/files`) — meta-view (counts, total size, source
  apps); not a file browser. *"Browse"* affordances cross-origin into
  `drive.tenshu.app` once Phase 8 lands; today they navigate to `/drive/files`.
- **Devices list** (`/control/data/devices`) — surfaces the browser-sync caveat from
  `web/architecture.md` §4.2 with a clear *"This browser shares its identity with other
  browsers signed into your {Chrome|Firefox} sync account"* indicator. Link to
  browser-specific instructions.
- **Pair-new-device action** at the top of the devices list — initiates the apex-side
  pairing flow per `pairing-flow.md`. The web is both pairing-initiator and
  pairing-acceptor: the action defaults to *"Show my pairing code"* with *"Scan one
  instead"* as a secondary option.
- **Device detail panel** (`/control/data/devices/:deviceId`) — rename, revoke, view
  fingerprint, current realm-epoch.

### 4.4 Services — `/control/services`

Renders the IA's Services list and detail screens (§6). v1 services on web:
NotificationService, IdentityService, StorageService.

- **StorageService** surfaces tier and consumption from sync-relay metadata. The upgrade
  flow opens a Tenshu Store path (gated on Phase 8 + the App Console milestone). For v1,
  StorageService displays consumption read-only and points users to email contact for
  upgrades.
- **NotificationService** per-app silencing reads/writes the `notification-service` Settings
  schema.
- **IdentityService** display name and avatar are scoped to the realm — changing them
  here propagates via sync to all realm devices.
- Service icons and copy follow IA §9's open item — plain-language framing per service.
  v1 ships with provisional copy; the design round refines.

### 4.5 Settings — `/control/settings`

Renders the IA's Settings landing (§7). Web-specific:

- Sync settings include the relay endpoint (advanced expand, hidden by default for v1
  since self-hosted relays aren't yet supported).
- About surfaces the Kotlin/JS SDK version (`TenshuSdk.version` from `@tenshu/sdk/core`)
  alongside the apex bundle version.
- Telemetry default off, per `web/architecture.md` §3.3 zero-trust-backend invariant.

### 4.6 Recovery — `/control/settings/recovery`

Renders the IA's Recovery sub-section (`control-ia.md` §7.2 — locked location).
Web-specific:

- The `[Test recovery]` flow stamps a "verified at {timestamp}" mark in IndexedDB. Home
  reads this and surfaces the Recovery banner if the mark is missing or older than 6
  months.
- Lost-everything explainer cross-links to `/recovery` (the recovery-claim flow per
  `web-onboarding-ux.md` §6).

#### 4.6.1 Phrase Reveal — Auth Model

The `[Re-display recovery phrase]` action shows the user's 24-word BIP-39 mnemonic. The
phrase grants total realm takeover to whoever reads it; the auth gate has to match that.

**End state (target)**: a **dedicated WebAuthn re-challenge** at the moment of click.
Fresh ceremony with its own challenge context (so an assertion captured during normal
unlock can't be replayed for reveal), action-bound, with a freshness check that the
assertion was produced within ~60 seconds. The user gets a Touch ID / passkey /
hardware-key prompt at the moment of reveal *even if their session is already unlocked*.
Implementation extends `web/apex/src/lib/webauthn.ts` with a challenge variant separate
from the unlock path's `getPasskeyAssertion()`.

**v1 fallback (acceptable, time-bounded)**: if the dedicated re-challenge isn't ready
when Phase B ships Recovery, reveal falls back to the cached session token from
`/lock` — the page checks the session is valid and shows the phrase. Phase B ships
Recovery either way; we don't block the section on the re-challenge work.

Risk envelope of the fallback:

- **In scope**: an attacker with physical access to an unlocked, unattended browser tab
  navigates to recovery, clicks reveal, captures the phrase. The exposure is real and
  bounded by the session timeout (default 24h, configurable down to *"ask every time"*
  via `/control/settings`). Higher-risk users have the timeout dial as a mitigation in
  v1.
- **Out of scope** (the fallback doesn't widen these): remote attackers — they still
  need an already-unlocked local tab. And the phrase derives from material the unlocked
  session already implicitly trusts (the master key in IndexedDB), so the fallback
  exposes no new asset class beyond what an unlocked session already does.

**Sunset**: the fallback is not v1-permanent. Track the dedicated re-challenge as a
sized follow-up; ship it as soon as the WebAuthn primitive lands and remove the fallback
path. Every supported browser has WebAuthn re-challenge available, so no graceful
degradation is needed once it lands.

**Threat-model amendment**: the unattended-unlocked-tab vector isn't covered in
`docs/sync/threat-model.md` §11 today. Whichever variant ships in v1 (re-challenge or
fallback) should be reflected with matching honesty as a T-RC-* entry — the fallback
deserves a temporary entry that retires when the re-challenge lands. This amendment is
an explicit prerequisite for shipping Phase B Recovery, per the AGENTS.md house rule
that threat-model changes land with the code change.

---

## 5. Drive on the Web — Today and Future

> **As-built (2026-06-26), superseding the §9 "own chrome vs apex chrome" open
> item:** Drive now ships as a standalone own-origin app (`web/drive` →
> `drive.tenshu.app`), and the apex **hosts it in an iframe inside the apex
> chrome** rather than navigating away. The legacy in-apex `FilesPage` browser is
> retired. The "launcher switches href / apex shell unmounts / Drive renders its
> own chrome" plan described below is **not** how it landed — see
> [ADR 0020](../adr/0020-web-app-structure.md) for the apex-as-host decision and
> the reusable per-app structure convention. The subsections below are kept for
> historical context.

Drive is the first per-app subdomain per `web/architecture.md` §3.1, but it ships first
embedded into the apex at `/drive/*`. The shell chrome from §3 wraps Drive while it's
embedded; the user sees the apex top bar and side rail with Drive's UI in the main
content area.

When web roadmap Phase 8 lands and Drive moves to `drive.tenshu.app`, the path-suffix is
preserved across the origin swap:

- `tenshu.app/drive/files` ⇒ `drive.tenshu.app/files`
- `tenshu.app/drive/files/folder/x` ⇒ `drive.tenshu.app/files/folder/x`
- (future) `tenshu.app/drive/home` ⇒ `drive.tenshu.app/home`

That's a pure prefix-strip; no other URL gymnastics. Concretely:

- The launcher's Drive entry switches its href from `/drive/files` to
  `https://drive.tenshu.app/files` (or `/home` once that exists). Clicking it navigates
  the browser to the new origin; the apex shell unmounts.
- The legacy `/files/*` redirect (per §2's route map) can be dropped after Phase 8
  ships. ⚠ *Dropped at the 1.0.1 re-cut (2026-11), together with `/drive` → `/drive/files`: no
  user ever held such a bookmark.*
- The Drive subdomain renders its own chrome (which it can mirror from the apex shell
  for consistency, but doesn't have to — see §9 open items).
- A small "Open Tenshu" affordance in Drive's chrome links back to
  `https://tenshu.app/control/home`.
- PostMessage IPC between the two origins becomes the cross-app glue per
  `web/architecture.md` §3.2.

This doc doesn't spec Drive's own UI — that's `drive`'s concern. It only commits to the
seam: where Drive lives within the apex today, and how the apex shell handles the
eventual cross-origin transition.

---

## 6. Cross-Platform Mapping

| Web surface (`/control/...`) | IA section (`control-ia.md`) | Native rendering target     |
|------------------------------|------------------------------|-----------------------------|
| `/control/home`              | §3 Home                      | Dashboard / home (TBD spec) |
| `/control/apps`              | §4 Apps                      | Apps screen (TBD spec)      |
| `/control/data`              | §5 Data                      | Data screen (TBD spec)      |
| `/control/services`          | §6 Services                  | Services screen (TBD spec)  |
| `/control/settings`          | §7 Settings                  | Settings screen (TBD spec)  |
| App launcher                 | (web-specific chrome)        | OS app drawer               |

The web URL shape (`/control/home`, `/control/apps`, …) intentionally mirrors what
Compose Navigation routes look like on native (`Control.Home`, `Control.Apps`, …), so
cross-platform parity holds at the navigation layer too — not just at IA-section names.

Side rail (web ≥`md`) ≈ bottom nav (web <`md`) ≈ bottom nav (Android) ≈ side rail
(Desktop). The IA is the same; only the chrome differs.

The native steady-state spec is the explicit follow-up — sized as *"port `control-ia.md`
into a native-flavoured screens spec, with chrome and platform affordances per
`control-onboarding-ux.md`'s style."* The IA in `control-ia.md` is locked, so the
follow-up is layout/chrome work, not IA work.

---

## 7. Implementation Notes

- All `/control/*` routes mount under a single `<ControlShell />` layout component that
  renders the apex chrome (§3) and the active section in the main slot.
- The shell subscribes to the SDK's `Devices` and `Notifications` streams to keep the
  device-name pill, sync status indicator, and Home feed live.
- The launcher reads installed apps from a manifest registry maintained by the apex
  (v1: hard-coded list of Drive + Tenshu; later: per-user installed apps from sync state).
- Session-expired guard from `web-apex-landing.md` §6 applies — any `/control/*` or
  `/drive/*` route hit without a valid session redirects to `/lock?next=...`.
- The `/control` → `/control/home`, `/control/data` → `/control/data/files`, `/drive` →
  `/drive/files`, and `/files/*` → `/drive/files/*` redirects are React Router
  `<Navigate replace />` rewrites — client-side, no HTTP round-trip. The browser URL
  updates instantly to the canonical path. Document the legacy `/files/*` redirect's
  removal as a Phase 8 follow-up so it doesn't outlive its purpose.
- Each section is a separate React route; they don't share state beyond what's in the
  SDK. Route-level code-splitting is fine and recommended once bundle size matters.
- The sync-status indicator in the side rail subscribes to the same `SyncManager` stream
  that surfaces `divergenceWarning` (per `sync-backend-relay-plan.md` Phase 3.1
  piggyback). This is the pending downstream piece called out in CLAUDE.md's in-flight
  refactor table; this doc's chrome status indicator + Home banner are its rendering
  surfaces.

---

## 8. Phasing

- **Phase A** (after `web-apex-landing.md` lands): §3 shell chrome + §4.1 Home + the
  launcher minimum (Drive + Tenshu entries). Enables the post-onboarding landing
  experience.
- **Phase B**: §4.2 Apps + §4.3 Data → Devices + §4.6 Settings → Recovery. The
  high-impact security-flavoured surfaces; pairs well with web roadmap Phase 4/5
  crypto/WebAuthn work.
- **Phase C**: §4.4 Services (NotificationService, IdentityService; StorageService
  read-only pending sync-relay tier metadata).
- **Phase D**: §4.3 Data → Files inventory. Lower priority; gated on Drive having stable
  metadata APIs.
- **Phase E**: §5 Drive cross-origin transition (web roadmap Phase 8).
- **Phase F**: rest of §4.5 Settings (sync, advanced, telemetry, about beyond the basics).

---

## 9. Open Items

IA-level open items live in `control-ia.md` §9 (notification filtering, services copy,
Files inventory scope, etc.). This list is web-specific.

- **Visual design pass.** The five sections need a coherent visual design. Ship Phase A
  with the existing M3 tokens; commission the design round before Phase B exposes
  Devices and Recovery.
- ~~**Cross-origin chrome consistency.** When Drive moves to `drive.tenshu.app`, should it
  render the same shell chrome (with the apex launcher) or its own?~~ **Resolved
  2026-06-26 ([ADR 0020](../adr/0020-web-app-structure.md)):** the apex hosts the
  own-origin app in an **iframe inside the apex chrome** (consistent shell), rather than
  navigating away. Same pattern third-party apps will use (sandbox + Rei) later.
- **Mobile launcher placement.** The launcher trigger competing with the bottom nav for
  thumb space needs a layout resolution. Recommend launcher in the top bar even on
  mobile, with the bottom nav reserved for the five sections.
- **Naming review.** Pin §1's "Tenshu" naming — audit error messages, tooltips, and help
  text for accidental "Control" surfacing on web.
- **Apex permission-mediation runtime.** Phase C (Services) requires the apex to
  actually mediate. The runtime is currently stubbed; wiring lands as part of web roadmap
  Phase 4/5.
- **Phase B threat-model amendment.** §4.6.1 specifies what the Recovery phrase-reveal
  auth gate looks like in both end-state and v1-fallback variants. Whichever variant
  ships needs a matching `T-RC-*` entry in `docs/sync/threat-model.md` §11 in the same
  PR, per the AGENTS.md house rule.

---

## 10. References

- [`control-ia.md`](control-ia.md) — the platform-neutral IA this doc renders.
- [`docs/web/architecture.md`](../web/architecture.md) — §3 (apex/subdomain), §4 (auth),
  §6 (manifest).
- [`docs/proposals/web-apex-landing.md`](web-apex-landing.md) — public landing.
- [`docs/proposals/web-onboarding-ux.md`](web-onboarding-ux.md) — onboarding flows.
- [`docs/proposals/control-onboarding-ux.md`](control-onboarding-ux.md) — native
  counterpart for onboarding.
- [`docs/sync/threat-model.md`](../sync/threat-model.md) §11 — recovery model.
- [`docs/sync/pairing-flow.md`](../sync/pairing-flow.md) — referenced from §4.3 (Data →
  Devices).
- [`docs/sync/design.md`](../sync/design.md) §2.2 + [ADR 0007](../adr/0007-sync-device-management.md)
  — referenced from §3.2 (sign-out everywhere) and §4.3.
- [`docs/proposals/sync-backend-relay-plan.md`](sync-backend-relay-plan.md) Phase 3.1 —
  `divergenceWarning` rendering surfaces.
- [`docs/web/roadmap.md`](../web/roadmap.md) — web track Phase 7 / Phase 8 reference
  points.
