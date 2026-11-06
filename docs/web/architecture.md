# Tenshu on the Web — Architecture

**Status:** Draft v0.1
**Scope:** All web deployments of Tenshu — hosted web, browser extension, PWA install — and how they
compose.

This document specifies the architecture of Tenshu's web presence. It complements (and in places
extends) the App Platform Connection Patterns section of
the [Tenshu White Paper](../whitepaper.adoc), the [Threat Model](../sync/threat-model.md), and
the [Pairing Flow](../sync/pairing-flow.md). It is the source of truth for how Tenshu maps onto the
browser platform, how onboarding works for web users, and how the three deployment layers (web /
extension / PWA) interact.

---

## 1. Why This Document Exists

The whitepaper covers the case where Tenshu reaches the browser via a cross-browser extension acting
as a local proxy — modelled on the *Direct Proxy Connection* pattern. That deployment is correct but
partial. There is also a hosted-web deployment where users visit `tenshu.app` without installing
anything, and there is a PWA-install deployment where users keep Tenshu apps as standalone
installable applications. These three deployments are not alternatives; they compose. A real user
can be in any combination of them at any time.

This doc commits to that layered model and specifies how it works end-to-end.

---

## 2. The Three Layers

Each layer adds something genuinely orthogonal to the layers below it. The base layer (hosted web)
is fully functional on its own; the others are additive.

| Layer                                        | What it adds                                                                                                                      | Connection pattern                                                                                                                         |
|----------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------|
| **Hosted web** (`tenshu.app` and subdomains) | Reach (no install required), full first-party app experience, server-side ciphertext relay                                        | *External Proxy Connection* — the proxy is at `tenshu.app`, split between a browser-side service worker and a server-side ciphertext relay |
| **+ Browser extension**                      | Local proxy semantics; can intercept requests from *any* origin; safer master-key storage; enables third-party Tenshu integration | *Direct Proxy Connection* — the extension is the local proxy, exactly like Control on Android or desktop                                   |
| **+ PWA install**                            | App-icon launching, standalone window, **durable local storage (⚠ not UX — see §2.1)**, push notifications, OS integration        | Same connection pattern as the underlying layer                                                                                            |

The web is the only Tenshu platform where the user can *choose* their connection pattern. Without
the extension installed, web Tenshu uses External Proxy Connection. With the extension installed, it
uses Direct Proxy Connection. PWA install is orthogonal to that choice and contributes icon and window chrome on top of
either pattern — plus storage durability, which is **not** UX (§2.1).

This composability is intentional. It means:

- A first-time user who clicks `tenshu.app` is fully functional immediately, with first-party Tenshu
  apps and server-side relay.
- The user installs the browser extension when they want to use Tenshu integrations on third-party
  websites — and only then.
- The user installs apps as PWAs when they use them often enough to want a standalone window.

### 2.1 ⚠⚠ Storage durability is not a UX upgrade

This doc called PWA persistence "purely UX" in three places until 2026-08-27. It is not, and the
distinction is load-bearing.

A web device's realm identity — its signing keypair and master-key material — lives in per-origin
IndexedDB. In an ordinary tab that storage is **evictable**: the browser may delete it under
pressure, with no prompt. In an installed PWA, Chrome auto-grants persistent storage, so it may
not. Same location, same trust model, opposite durability.

⚠⚠ **And the app never asks.** Neither `web/apex` nor `:tenshu-sdk-js` calls
`navigator.storage.persist()` — a live browser reports `persisted() === false`. So even the
installed case rides a browser heuristic the code never requests, and browsers that require an
explicit call (or a prompt) never grant it at all.

⚠ **Losing that storage is recoverable but destructive.** Eviction takes the whole origin, so the
`deviceId` goes with the keys and the next visit mints a fresh identity — it does not strand the
browser on the relay's removal ledger. What it does destroy is the device and everything local to
it, including anything not yet pushed, with no warning before and no explanation after. Full
analysis in [`drive-web-parity.md`](../proposals/drive-web-parity.md) §1.2d / W10.

⚠ Not verified here: Firefox prompts on `persist()` rather than auto-granting, and Safari's
eviction rules for non-installed origins are stricter still. Both need checking before any claim
that "install it as a PWA" is a sufficient answer.

Each install asks for itself when the user wants something they can't have without it.

---

## 3. The Apex / Subdomain Architecture

### 3.1 Origins

- **`tenshu.app`** — the apex domain. Hosts the **control plane**: login, device list, recovery
  manifest, semantic-permission grants, app launcher, settings. Same role as the Control app on
  mobile/desktop.
- **`drive.tenshu.app`, `mia.tenshu.app`, etc.** — first-party Tenshu apps. Each lives on its own
  origin so the browser's Same-Origin Policy gives real isolation: per-app storage, per-app service
  workers, per-app cookies.
- **Direct paths** like `tenshu.app/drive` resolve via 302 redirect to the canonical subdomain (
  `drive.tenshu.app`). The user can type or share whichever; the address bar always shows the
  canonical app origin.

Same-Origin Policy is the only meaningful trust boundary inside a browser. Putting each app on its
own subdomain is the only credible way to get app-level isolation without requiring the user to
install something.

### 3.2 Cross-origin "IPC" via postMessage

A Tenshu app on `drive.tenshu.app` does not call backend services directly. It posts requests to the
apex (`tenshu.app`, loaded in a hidden iframe or reachable via service-worker channel), which
evaluates each request against the user's semantic-permission grants and either fulfils it from
local synced state or routes it through the user's E2EE-encrypted realm.

This is the web equivalent of `app.sendNotification(...)` going through Control over Android Binder.
The protocol is the same Tenshu binary protocol used elsewhere; the transport is `postMessage` with
structured-clone serialization. PostMessage is verbose and adds latency compared to a function call;
mitigations are batching at the app side and caching of frequently-read data.

### 3.3 Server-side has no business logic

The `tenshu.app` backend serves:

1. Static files for the apex and each subdomain (HTML, JS, CSS, manifest).
2. The sync relay protocol (same as mobile, defined in [
   `sync-backend-relay-plan.md`](../proposals/sync-backend-relay-plan.md)). Ciphertext only; never
   decrypts.

Nothing else. No server-side notifications, no server-side search, no server-side AI. The backend is
governed by the same Zero-Trust Backend invariant as the mobile relay: a relay breach yields
ciphertext and timing metadata, never plaintext or long-term keys.

This is non-negotiable — adding any other server-side responsibility would erode the principle and
break the threat model's promises.

### 3.4 Named hosts: apps, services, and the apex

Three host namespaces, by role:

- **Apex — `tenshu.app`.** The control plane: holds master keys, runs the postMessage proxy. The
  most sensitive origin in the system.
- **User apps — `<app>.tenshu.app`** (`drive.tenshu.app`, `mia.tenshu.app`, …). First-party apps,
  each its own browser origin so Same-Origin Policy gives real isolation (§3.1).
- **Backend services — `<svc>.service.tenshu.app`.** Machine endpoints with no browser UI that
  native clients and the apex server call: the sync relay (`relay.service.tenshu.app`), the commerce
  issuer (`commerce.service.tenshu.app`), and future services (logging, storage, a future Rei). A
  dedicated parent keeps services off both the apex and the user-app namespace, so a service never
  shares an origin or cookie scope with the key-holding apex or a user app. Host names mirror the
  module names (`:sync-relay-server` → `relay.service…`, `:commerce-service` → `commerce.service…`).
- **Internal ops tools** (e.g. the commerce admin console). Human-facing internal UIs sit at the
  **apex level** as deliberate exceptions — `admin.tenshu.app` — internal-only (allowlist / VPN /
  mTLS). They are *not* under `*.service.…` (not machine services) and *not* public user apps.

Rules that make the shared `service.tenshu.app` parent safe:

- **No parent-domain cookies.** A cookie scoped to `.service.tenshu.app` would be readable across
  *all* services. Services authenticate with **bearer headers only** (device-key JWT, commerce
  session token), never cookies. Each service's CORS allowlist is explicit.
- **CORS allowlists name app origins, not service hosts.** A service rename does not touch a
  relay's `CORS_ALLOWED_ORIGINS` (that lists `tenshu.app` + the app subdomains that call it).
- **A host rename is deploy *plus* code *plus* migration, not pure config** — clients bake default
  service URLs and the relay URL is embedded in pairing payloads. See
  [`commerce-accounts-and-sessions.md`](../proposals/commerce-accounts-and-sessions.md) §4.6 for the
  touch-point breakdown and the `relay.tenshu.app` → `relay.service.tenshu.app` retrofit.

---

## 4. Identity & Authentication

### 4.1 Realm Device = Wherever the Device Key Lives

The threat model's asset A4 (Device Signing Keys) maps onto the browser as follows:

A **realm device** is the cryptographic boundary defined by *wherever the device signing key is
stored*. In the web, that's the browser profile's IndexedDB scope for `tenshu.app`. So in practice,
*each browser profile = one realm device*, with one important caveat below.

A browser profile is not a *user identity* — it's a **storage scope** managed by the browser. What
it gives Tenshu is reliable storage isolation, not a cryptographic claim about who the user is. The
cryptographic identity comes from the device signing key generated within that scope.

### 4.2 The Browser-Sync Caveat

If the user has Chrome Sync (or Firefox Sync) enabled with `tenshu.app` IndexedDB syncing, the same
device key is reachable from any browser signed into that sync account. Functionally, those become
*one realm device*, not several — even though they're on different physical machines.

Tenshu does not unilaterally decide what to do here. The control plane surfaces the situation
explicitly:

- *"This realm device is shared via Chrome Sync. Revoking it will affect all browsers signed into
  your Google account."*
- An onboarding step (see §8) explains how to exclude `tenshu.app` from sync if the user wants
  per-machine granularity, with a link to the browser-specific instructions.

### 4.3 Master-Key Storage: IndexedDB Only

The user's master identity private key (asset A1) is stored in **IndexedDB scoped to
the `tenshu.app` origin**, encrypted at rest under a session key derived from the user's
authenticator (see §4.4). It is never copied to extension storage.

Besides the `tenshu-sdk*` IndexedDB databases, the apex origin keeps two `localStorage`
entries at rest: the diagnostics ring (W9), and **`tenshu.session.hint`** — the device name
and session-token expiry, mirrored by the runtime so a public page can greet a returning
visitor without starting the runtime ("Cross-tab" below). No key material; a session clear
removes it and the dev reset wipes it with the rest of `localStorage`.

The extension, when installed, does not hold the master key. It is a **request interceptor** that
routes Tenshu requests from arbitrary origins to the apex tab/PWA, where crypto operations actually
happen. This means:

- One canonical home for the key — no migration on extension install/uninstall.
- The key never crosses the IndexedDB → extension process boundary; an extension exploit cannot
  extract it.
- The cost: the extension needs an active `tenshu.app` tab or installed PWA to function. The PWA
  install layer (§2) directly addresses this by giving the user an always-available apex window.

Without the extension, the apex tab is the proxy. With the extension, the apex tab is *still* the
proxy; the extension just delegates to it. The master key location does not change with extension
presence.

### 4.4 Authentication: WebAuthn Primary, Recovery Phrase Fallback

Daily login uses **WebAuthn** (passkey or hardware key). At signup, the user registers a passkey for
the `tenshu.app` origin; the resulting credential is used to unwrap the IndexedDB-stored master key
on each subsequent login. The unwrap key is derived from the WebAuthn credential rather than stored
anywhere.

The **recovery phrase** (24-word BIP-39 mnemonic, per threat-model §11.2) is the cold backup for the
rarest case: all passkeys lost. It is never entered during regular login flows; it is treated as a
sensitive secret that lives in the user's offline storage (paper, password manager, hardware
wallet).

This split aligns with how 1Password and other password managers handle the same problem: the
daily-login secret is convenient and replaceable; the recovery secret is high-friction and cold.

WebAuthn is well-supported in modern browsers (all major desktop browsers, iOS 16+, Android with
biometric authenticators). For browsers without passkey support, the fallback is a passphrase (
Argon2id-derived) plus the recovery phrase. This is the rare path; the doc commits to WebAuthn as
the default.

---

## 5. App Isolation Strategy

### 5.1 Per-Subdomain Isolation

Each first-party Tenshu app gets its own subdomain. This buys real Same-Origin Policy isolation: no
cross-app cookie reading, no shared IndexedDB, no shared service workers. Cross-app communication is
impossible without cooperation through the apex's postMessage proxy, which enforces semantic
permissions.

Per-app subdomains are managed by Tenshu (DNS, certificates, deployment). New first-party apps land
at new subdomains.

### 5.2 The Apex as Mediator

The apex (`tenshu.app`) holds the user's master keys, runs the proxy, and mediates every cross-app
and external call. Apps living on subdomains do not have direct internet access; they must
postMessage to the apex for any external IO. The apex evaluates the request against:

1. The app's declared **semantic permissions** (from its manifest — see §6).
2. The user's standing **permission grants** for that app (managed in the control plane).

Only if both match does the apex route the request — either fulfilling locally from synced state or
sending it to the sync relay.

This is the web equivalent of an Android app having no `INTERNET` permission and routing every
external call through Control. The mechanism is different (postMessage instead of Binder); the
contract is the same.

### 5.3 The Apex's Content-Security-Policy

The apex origin holds the master keys, so an injected script there is the worst bug the web layer
can have (one has happened: a sharer-chosen `text/html` blob framed same-origin). The CSP is the
defence in depth for the next one. It is **enforced**, not report-only, and comes in two halves
that the browser applies together (a load must pass both):

- **A `<meta http-equiv>` on every page the build emits** — the SPA's `index.html` (Vite plugin
  `web/apex/csp/vitePlugin.ts`) and every static support / legal page (`support-pages/generate.ts`).
  Built by `web/apex/csp/csp.ts`. It is a `<meta>` and not the header because the origins it names
  are **inlined into the bundle at build time** (`VITE_TENSHU_RELAY_URL`,
  `VITE_TENSHU_COMMERCE_URL`, `VITE_<ID>_ORIGIN`), and Caddy's runtime env never sees them: the dev
  lab builds the same image against another relay, and a runtime copy would drift silently.
- **A header in `web/apex/Caddyfile`** for what a `<meta>` cannot carry: `frame-ancestors`. It also
  repeats `object-src` / `base-uri` / `form-action`, so files the build did not generate (the SVGs)
  are covered.

| Directive | Value | Why |
|---|---|---|
| `default-src` | `'self'` | Anything not listed below. |
| `script-src` | `'self'` + a `sha256` per inline script | No `'unsafe-inline'`, no eval. The inline scripts (theme boot, the support pages' switches and redirects) are hashed from the **final** HTML at build time, so a changed script cannot outrun its hash. The Kotlin/JS bundle uses no eval and no WebAssembly. |
| `style-src` | `'self' 'unsafe-inline'` | React `style={…}` and the support pages' `style="aspect-ratio…"` are style *attributes*; only `'unsafe-inline'` admits those. Accepted: style injection is not script execution. |
| `img-src` | `'self' data: blob:` + the screenshot host | `data:` contact avatars and QR codes, `blob:` decrypted previews, and the origin of every `base` in `store/*/screenshots.*.json` (assets.tenshu.app). |
| `media-src` | `'self' blob:` | Decrypted audio/video previews. |
| `connect-src` | `'self' blob:` + relay + commerce | The SDK and the share viewer talk to the relay, the account surface to commerce; `blob:` is the share viewer reading its text blob back. Static pages get no relay or commerce. |
| `frame-src` | `'self' blob:` + every hosted app origin | `AppEmbed` frames the apps; the share viewer frames its PDF blob. |
| `worker-src`, `manifest-src`, `font-src` | `'self'` | The apex has no worker and loads no remote fonts. |
| `object-src` | `'none'` | No plugins. Chrome's PDF viewer in a `blob:` iframe still renders under it (checked in Chrome 2026-10). |
| `base-uri` | `'none'` | No `<base>`; an injected one would re-point every relative script URL. |
| `form-action` | `'self'` | Every form is a JS `onSubmit`; checkout and the billing portal are `location.assign`, which `form-action` does not govern. |
| `frame-ancestors` (header) | `'self'` + every `{$<ID>_SITE}` | A hosted app opened on its own mounts the apex in a hidden iframe (`web/sdk/src/bridge.ts`); nobody else may frame the apex. |

The hosted apps keep their own header (`hosted_app` snippet): `frame-ancestors` = the apex, plus
`object-src 'none'; base-uri 'none'; form-action 'self'`. A full per-app policy is follow-up work.

**Extending it.** A new kind of load on the apex fails closed, visibly, in the browser console
("violates the following Content Security Policy directive"). Then:

- *A new build-configured origin the SPA calls or frames* — derive it in `csp.ts` from the same
  `VITE_*` variable the SPA reads, with the same fallback, and add the pair to
  `csp/origins.test.ts`, which loads the SPA's own modules as a production build would and fails
  on drift. Never hard-code a host the build can override.
- *A new hosted app* — nothing in `csp.ts` (its id comes from the registry directory), but add its
  `{$<ID>_SITE}` to the apex's `frame-ancestors` line in the Caddyfile (`csp/caddyfile.test.ts`
  fails until you do).
- *A new inline script* — nothing; it is hashed at build time. Prefer a file anyway.
- *A new third-party origin* (fonts, analytics, an embed) — that is a privacy-notice change first
  (`support-pages/privacy.ts` promises "no fonts or scripts from other servers"), then a source in
  `contentSecurityPolicy()` with the reason beside it.

---

## 6. Extended PWA Manifest

Each Tenshu-enabled web app declares itself with a manifest served from its origin. The manifest
extends the W3C Web App Manifest (PWA standard) with Tenshu-specific fields under a `tenshu.*`
namespace.

```jsonc
{
  // Standard PWA Web App Manifest fields
  "name": "Drive",
  "short_name": "Drive",
  "icons": [...],
  "display": "standalone",
  "start_url": "https://drive.tenshu.app/",

  // Tenshu extensions
  "tenshu": {
    "appId": "tenshu/app/drive",
    "version": "1.4.2",
    "proxyOrigin": "https://tenshu.app",
    "reiPublication": "rei://publication/0xabc123...",
    "versionHash": "sha256:def456...",
    "semanticPermissions": [
      {
        "id": "tenshu/file/service/listFiles",
        "humanDescription": "List files in folders you select",
        "scope": ["/Documents", "/Photos"]
      },
      ...
    ]
  }
}
```

**Required fields**:

- `tenshu.appId` — globally unique app identifier; matches the Rei publication's app ID.
- `tenshu.version` — app version; declared so the user can see what they've installed.
- `tenshu.proxyOrigin` — the apex origin the app must postMessage to. Must be `tenshu.app` for
  first-party; could in principle be a self-hosted proxy origin in the future.
- `tenshu.reiPublication` — Rei publication reference for the app. Required when third-party apps
  become supported (post-Rei); first-party apps can omit it during early development but should
  populate it once Rei is operational.
- `tenshu.versionHash` — SHA-256 hash of the app's HTML/JS bundle, anchored in the Rei publication.
  Verified by the apex proxy before loading the app.
- `tenshu.semanticPermissions` — list of declared permissions. Each entry has an ID matching a
  Tenshu IDL operation, a human-readable description, and an optional scope.

**Manifest verification flow**:

1. User navigates to `drive.tenshu.app` (or clicks a Tenshu app launcher).
2. Apex proxy fetches the app's manifest from its origin.
3. Apex validates the Rei publication signature (when third-party support lands).
4. Apex computes the hash of the served bundle and compares to `versionHash`.
5. Apex compares the declared semantic permissions to the user's grants for this app; prompts for
   any new grants.
6. If all checks pass, the app loads. Otherwise, the apex shows an error explaining why.

For first-party apps before Rei is operational, steps 3 and 4 are bypassed — the apex trusts
`*.tenshu.app` by domain ownership. After Rei is operational, even first-party apps go through full
Rei verification.

---

## 7. Third-Party Trust: Hard-Blocked on Rei

### 7.1 v1 Scope

Web Tenshu v1 supports **first-party Tenshu apps only**. Apps on `*.tenshu.app` work. Apps on any
other origin are not supported, regardless of whether they declare a Tenshu manifest. The browser
extension v1 does not allow third-party origin integration.

### 7.2 Why

Third-party integration is the killer feature of the browser extension — the moment the ecosystem
opens up beyond first-party Tenshu apps. But it is also the riskiest extension of the trust model. A
malicious or compromised third-party site requesting Tenshu access has access to potentially
anything the user grants it.

The whitepaper's design specifies that Rei is the trust anchor for code signing and developer
identity. Until Rei's RPL/RIL/REL layers are operational, there is no platform-level mechanism to
verify a third-party app's identity, code signing, or developer trustworthiness. The extension would
have to fall back on either pure-domain trust (vulnerable to phishing) or a Tenshu-curated
allowlist (Tenshu-as-gatekeeper, which the whitepaper explicitly avoids).

Neither fallback meets the whitepaper's design integrity. Therefore, third-party support waits.

### 7.3 The Milestone Sequence

1. **Web Baseline** (this doc) — first-party `*.tenshu.app` apps work in all three deployment
   layers.
2. **Browser Extension v1** — adds the extension layer. Still first-party only. No new trust
   surface; the extension just changes where the proxy runs.
3. **Tenshu Rei (RPL/RIL/REL operational)** — milestone in [`milestones.md`](../milestones.md),
   under the App Console & Tenshu Store entry.
4. **Browser Extension v1.1: Third-Party Support** — once Rei is operational, the extension gains
   the ability to load third-party Tenshu-enabled origins, with full Rei verification on every load.

This sequencing means the web ecosystem starts closed (first-party only) and opens deliberately when
the trust foundation is ready. Users get a fully working web Tenshu before that — they just can't
use Tenshu integrations on `example.com` until Rei is live.

### 7.4 Future: First-Time Grant UX (Post-Rei)

When third-party integration becomes available, the user-facing flow when an unfamiliar origin
requests Tenshu access:

1. User visits `example.com`. The site declares a Tenshu manifest and requests permissions.
2. Apex proxy fetches the manifest, runs full Rei verification (publication, code signing, evaluator
   confirmations).
3. If verification passes, the apex shows a Tenshu Control modal: app identity (from Rei), requested
   semantic permissions (with human descriptions), code-signing chain summary. User grants or
   denies.
4. If verification fails, the apex shows a clear error with the specific failure mode (unknown
   publication, mismatched hash, no evaluator confirmations). Access denied; no override.

The modal is a Tenshu-controlled UI, not a browser permission prompt — this avoids the "which prompt
am I clicking" confusion users experience with cross-site permissions today.

---

## 8. Onboarding Flow

When a user opens `tenshu.app`, three states are possible. The flow branches accordingly.

### 8.1 Active Session (returning user, recently unlocked)

IndexedDB contains an unwrapped session; the WebAuthn challenge is still within validity. Land
directly in the control plane. No flow.

### 8.2 Locked (returning user, signed out)

IndexedDB contains wrapped state; passkey is registered. Flow:

1. *"Welcome back, [device name]"* — display a friendly greeting using the locally-stored device
   name.
2. WebAuthn challenge — user authenticates with their passkey.
3. Apex unwraps the master key from IndexedDB.
4. Land in the control plane.

One step from the user's perspective. This is the common path.

### 8.3 First-Time on This Browser

No Tenshu state in IndexedDB. The user has never signed in here. Flow branches into 8.3a (
existing-realm device) or 8.3b (new user):

#### 8.3a: First-Time, Existing Realm Device

The user has Tenshu on a phone or laptop and wants to add this browser as a new realm device.

1. **Landing**: *"Welcome to Tenshu. Do you have Tenshu on another device?"* User picks "Yes."
2. **Pair this browser**: explain the pairing handshake; show a QR code containing the pairing
   parameters (per [`pairing-flow.md`](../sync/pairing-flow.md)).
3. **User scans QR on existing device**, approves the pairing in their existing device's Control
   app.
4. **Browser receives encrypted master keys** via the relay, decrypts in-tab, persists wrapped form
   to IndexedDB.
5. **Set up WebAuthn for this browser**: prompt the user to register a passkey for `tenshu.app`. The
   passkey is per-storage-scope (this browser profile), not synced from the existing device.
6. **Browser-sync warning**: explain that if Chrome Sync is enabled with `tenshu.app` IndexedDB
   included, this browser is shared across all signed-in browsers. Offer a link to browser-specific
   instructions to exclude `tenshu.app` from sync. *Skip / Show me how / I'll decide later*.
7. **Brief layered explainer**: one screen, three icons (Web / Extension / PWA), each with a short
   description of what it adds and *yes/no* prompts:
    - *"Want to install the extension to use Tenshu on third-party websites?"* (No-op pre-Rei;
      reframed as *"…coming soon, when Tenshu Rei is operational"*)
    - *"Want to install Tenshu apps as PWAs for easier launching?"* (Triggers the browser's native
      PWA install prompt for `tenshu.app` and lets the user install per-app PWAs from the control
      plane later.)
    - User answers yes/no for each; the flow guides them through the install only if they say yes.
8. **Land in control plane**: device list shows this browser alongside existing devices.

#### 8.3b: First-Time, New User

The user has never used Tenshu. This is the longer flow.

1. **Landing**: *"Welcome to Tenshu. Are you new to Tenshu, or do you have an account on another
   device?"* User picks "I'm new."
2. **Brief intro screen**: one paragraph explaining the proxy-mediated, end-to-end-encrypted,
   recoverable model. Set the mental model; no sales pitch.
3. **Generate master identity**: in-browser, using the platform CSPRNG. Generates a 24-word BIP-39
   mnemonic per threat-model §11.2.
4. **Show recovery phrase**: full-screen, with strong warnings — write it down, store it offline,
   never enter it into a wallet UI, never share with support. Per threat-model T-RC-01.
5. **Confirm recovery phrase**: ask for 3–5 randomly-selected words. The "did you actually write it
   down" gate.
6. **Set up WebAuthn passkey**: register a passkey for `tenshu.app`. Daily logins use the passkey;
   recovery phrase is the cold backup.
7. **Browser-sync warning**: same as 8.3a step 6.
8. **Brief layered explainer with yes/no**: same as 8.3a step 7.
9. **Storage tier mention (informational)**: *"Tenshu's free tier gives you X GB. You can upgrade
   later from the control plane if you need more."* No purchase flow during onboarding; the upgrade
   lives in the control plane / Tenshu Store and surfaces organically when the user hits the limit.
10. **Land in control plane**: empty device list (just this browser), empty recovery manifest, no
    apps installed yet. A short tour highlights the device list, recovery manifest, and apps
    section.

### 8.4 What's Deferred to the Control Plane

Onboarding is deliberately scoped to identity setup. The following are *mentioned but not actioned*
during onboarding; they live in the control plane:

- App installation (Drive, Mia, etc. — the user installs them after onboarding when they want to use
  them).
- Storage tier upgrade (mentioned at step 9 of 8.3b; upgrade flow lives in the control plane).
- Browser extension install (offered as yes/no during the layered explainer; if yes, the flow guides
  them to the browser store; otherwise, deferred).
- PWA install for individual apps (the apex PWA can be installed during onboarding if the user opts
  in; per-app PWAs are installed as the user opens each app for the first time).

---

## 9. Returning-User Flow

§8.1 and §8.2 cover the returning-user states. Three additional details:

- **Device-key rotation**: if the user re-authenticates after a long absence (e.g. > 30 days), the
  apex MAY rotate the device's signing key as part of the unlock. ⚠ *Withdrawn as written
  (workstream B, `admission-revocation-names-the-capability.md` §7i):* a device id is derived from its
  signing key, so a rotated key is a **new** device id that must be admitted again; it cannot be
  "bound to the same realm device identifier". This is a
  hardening step, not a hard requirement; it can be added incrementally.
- **Recovery from a different browser**: if the user has lost access to all their existing devices (
  lost phone, browser data wiped, no other realm device), they can recover by entering the recovery
  phrase on `tenshu.app/recover`. This path uses the same recovery flow as mobile/desktop (per
  threat-model §11.1) and is the only flow that ever asks for the recovery phrase outside of initial
  setup.
- **Multiple browser profiles**: the user can use the same realm from Chrome and Firefox on the same
  machine; each browser becomes its own realm device (subject to the browser-sync caveat). The
  control plane shows them as separate devices. Revoking one doesn't affect the other.

---

## 10. Layer Coexistence: How Web/Extension/PWA Combine

All three deployment layers can be present, absent, or any combination. The coexistence rules:

- **Web baseline** is always present (the user has visited `tenshu.app` at least once).
- **PWA install** — additive. Installing the apex PWA gives the user a standalone window for
  `tenshu.app`; installing per-app PWAs gives standalone windows for each first-party app. PWA
  presence does not change the connection pattern, the master-key location, or the trust model.
  ⚠⚠ It **does** change whether the browser may delete those keys — see §2.1. That is a durability
  property, not "persistence UX", and this bullet used to say otherwise.
- **Browser extension** — adds the third-party-integration capability (post-Rei). Pre-Rei, the
  extension is functional but has no third-party scope; it just acts as a more-convenient
  first-party proxy. The extension does *not* hold the master key (per §4.3); it routes requests
  through the apex tab/PWA.

The control plane shows the user which layers are installed and what each does. When the user opens
the control plane, a *"How you're using Tenshu"* panel summarises:

- Web baseline: ✓ (always on)
- PWA installed: ✓ / ✗ — link to install if not.
- Browser extension installed: ✓ / ✗ — link to install if not.

This makes the coexistence legible. The user is never confused about what they have and what each
layer adds.

---

## 11. Server-Side Components

The `tenshu.app` backend is the absolute minimum needed to serve a web app and route ciphertext. It
runs:

- **Static file serving** for `tenshu.app` and each subdomain. HTML, JS, CSS, manifests, icons.
  Standard CDN territory; no business logic.
- **The Tenshu sync relay protocol**, exactly as defined in [
  `sync-backend-relay-plan.md`](../proposals/sync-backend-relay-plan.md). Same code as the mobile relay.
  Stores and
  forwards ciphertext blocks. Never decrypts.

That is the entire server-side footprint. Anything beyond — server-side notifications, server-side
search, server-side AI, server-side anything — would erode the Zero-Trust Backend invariant and is
excluded by design.

---

## 12. Implementation Stack

The architecture above specifies the *shape* of web Tenshu — origins, manifests, postMessage,
onboarding. This section pins the *technology choices* used to implement that shape.

### 12.1 The Stack

**Apex and first-party web apps**: TypeScript + React. The apex (`tenshu.app`), the control plane,
and each first-party Tenshu web app (Drive, eventually Mia, Timeline) are React applications written
in TypeScript. They consume the Tenshu SDK described in §12.2.

**Shared protocol/domain layer**: KMP `commonMain` compiled to JavaScript, exported as the npm
package **`@tenshu/sdk`**. Contains everything that must be identical between platforms:

- The Tenshu binary protocol (serializer, parser)
- Schema definitions and the schema DSL
- Domain models (`Folder`, `File`, `Notification`, `SyncBlock`, etc.)
- Operation interfaces and the request DSL
- Pure-Kotlin crypto helpers (BIP-39 encoding/decoding, schema crypto)
- The Interactor pattern ([ADR 0012](../adr/0012-interactor-pattern.md))

**Mobile and desktop apps**: Compose Multiplatform, unchanged. The Android and Desktop Drive/Control
apps continue to use the existing `:core`, `:core-common`, and feature-service modules. Adding a JS
target to those modules is the only mobile/desktop-side change required by the web work.

**Third-party Tenshu apps** (post-Rei): consume `@tenshu/sdk` directly from npm, just like the apex
does. No special privilege; the apex is just a consumer that happens to also handle key crypto,
permission grants, and request routing.

### 12.2 The `@tenshu/sdk` Package

Single npm package. One artifact, one API surface. Consumed by:

- The apex (`tenshu.app`) — privileged consumer; handles key handling, permission grants, manifest
  validation, request routing.
- First-party Tenshu web apps (`drive.tenshu.app`, etc.) — restricted consumers.
- Third-party Tenshu apps (post-Rei) — restricted consumers, identical API.

The "privilege" of the apex is not a separate code path — it's that the apex runs in the apex
origin (`tenshu.app`) where the master keys live in IndexedDB. Restricted consumers run in subdomain
origins (or third-party origins) and don't have access to that storage; they can only request
operations through the apex via postMessage. This means anything a third-party app can do, the apex
can also do, plus more. There is no "internal" SDK call surface hidden from third parties.

This structure is the dogfooding win mentioned in §12.5. If a feature is missing from the SDK, every
consumer feels it equally — there's no first-class/second-class divide.

### 12.3 What's Shareable, Honestly

Realistic estimate: **20–40% of total code** can be shared via `commonMain` → JS. Heavily weighted
toward the protocol/domain layer.

| Layer                              | Sharing                                                                       |
|------------------------------------|-------------------------------------------------------------------------------|
| Tenshu binary protocol             | **Full** — pure Kotlin                                                        |
| Schema definitions, schema DSL     | **Full**                                                                      |
| Domain models                      | **Full**                                                                      |
| Operation interfaces / request DSL | **Full** — TypeScript consumers get typed APIs                                |
| BIP-39, schema crypto helpers      | **Mostly** — pure Kotlin where possible                                       |
| Ed25519, X25519, AEAD, Argon2id    | **Wrapper shared, backend per-platform** — JS uses `@noble/*`, JVM BouncyCastle |
| HTTP client                        | **Per-platform** — Ktor JVM/Android vs. `fetch` in browser                    |
| Storage                            | **Per-platform** — files/keychain on mobile/desktop, IndexedDB on web         |
| Coroutines                         | **Mostly works** with caveats around dispatch (single-threaded JS)            |
| ViewModel logic (pure transforms)  | **Partially** — pure logic yes; framework integration no                      |
| UI components                      | **Zero** — Compose vs. React are different worlds                             |
| Navigation                         | **Per-platform**                                                              |
| Forms / inputs                     | **Per-platform**                                                              |

The shared layer is where bugs are most expensive (cross-platform divergence is silent and
pernicious). Sharing it is worth the build complexity.

### 12.4 Kotlin/JS Practical Concerns

Things to design for, none of which are blockers:

- **Bundle size.** Shared logic adds bundle weight. Use `commonMain` modular exports rather than a
  single blob; aggressive tree-shaking; lazy-load schema modules per app.
- **TypeScript types.** Dukat generates `.d.ts` files from Kotlin sources; the output usually needs
  a hand-written wrapper layer to feel idiomatic. Plan for a small TypeScript adapter on top of the
  auto-generated bindings.
- **Coroutines on JS.** JS is single-threaded. Coroutines give async scheduling, not parallelism.
  Most Tenshu code is IO-bound, so this is fine; anything that expected parallelism (rare) needs
  JS-specific code.
- **Crypto backends.** Koin-injected interfaces (`Ed25519Backend`, `X25519Backend`, `AeadBackend`,
  `KdfBackend`) bound per platform by the `expect val cryptoBackendsModule` seam in
  `:core-common/security/`, which `coreCommonModule` includes; a new primitive group is one
  interface plus one binding per platform. The choices, each vetted and cross-browser:
  - **Ed25519 / X25519:** `@noble/curves` on JS — audited, sync, ~30 KB tree-shaken; picked over
    libsodium-wasm for bundle size. JVM uses BouncyCastle, because Android's provider has Ed25519
    only from API 33 and `minSdk` is 24.
  - **AEAD:** XChaCha20-Poly1305, as threat-model TA3 specifies — `@noble/ciphers` on JS,
    BouncyCastle `ChaCha20Poly1305` plus a hand-rolled HChaCha20 sub-key on JVM. Not WebCrypto
    AES-GCM.
  - **Argon2id:** `@noble/hashes`' sync `argon2id` on JS (slower than wasm, fine for the rare
    recovery flow), BouncyCastle `Argon2BytesGenerator` on JVM. Picked over `hash-wasm` /
    `argon2-browser`: one author family, and no wasm-init async hop, which the sync
    `KdfBackend.argon2id` cannot take.
  - **SHA-256:** `@noble/hashes`, sync. WebCrypto's would force every caller async.

  RFC vectors pin sign/verify/derive on both Ed25519 backends, and a fixed-input shared-secret
  vector pins cross-backend X25519 agreement.
- **Storage backend.** IndexedDB on web vs. file/keychain on mobile/desktop. The shared `commonMain`
  interface is generic; platform-specific impls live in the platform source sets.

### 12.5 Why Not Compose Multiplatform Web

Three reasons, in order of weight:

1. **Web users judge by first-load and feel.** Bundle size, time-to-interactive, shareable URLs,
   working back button, accessibility, SEO — these matter an order of magnitude more on web than on
   installed apps. CMP Web's Wasm target has multi-MB bundles and renders to canvas (no DOM → hard
   a11y, no SEO). The HTML target is less mature and less performant.
2. **CMP Web is not yet stable.** Ongoing platform churn while we're trying to ship. Bad trade for a
   project with this many in-flight refactors.
3. **The decisive interaction.** Third-party Tenshu apps must use a JS SDK (third parties won't
   import a Compose runtime). The apex is *itself* a Tenshu app — per §3.1 and §5.2, it's the
   privileged Tenshu-enabled origin. So the apex should use the same SDK third parties use. CMP for
   the apex would mean two SDK paths to maintain. Single-path consistency wins.

If CMP Web stabilises substantially in the future, the protocol/domain layer is already KMP and
could be paired with a CMP Web UI as an experiment without disturbing the JS SDK. The decision here
is reversible at the UI layer; what's not reversible is whether the protocol layer is shared.

### 12.6 Build Pipeline

High-level shape:

1. Existing KMP modules (`:core`, `:core-common`, `:core-client`, feature services) gain a `js(IR)`
   target.
2. A new `:tenshu-sdk-js` module is the npm-publishable artifact. It depends on the JS targets of
   the relevant `commonMain` modules and exposes a curated TypeScript API.
3. CI: `./gradlew :tenshu-sdk-js:jsBrowserProductionWebpack` produces the npm-ready bundle;
   publishes to npm on tag.
4. The apex (a separate web project, outside the Gradle build) imports `@tenshu/sdk` like any other
   npm dependency.
5. First-party Tenshu web apps are also separate web projects, each importing `@tenshu/sdk`.

This keeps the web projects out of the Gradle build — web devs don't need to learn Gradle to work on
the apex — while still sharing the protocol layer through a familiar npm dependency.

#### 12.6.1 Phased Rollout of JS Targets

Adding the `js(IR)` target to a KMP module is only "additive" if its `commonMain` is already free
of JVM-only API references. In practice the codebase has a small amount of pre-existing leakage
that the JS compile surfaces. Rather than refactoring the offending sites (most are bridges to
suspend functions via `runBlocking`, which is exactly what the Interactor pattern
([ADR 0012](../adr/0012-interactor-pattern.md)) was designed to remove), the JS
target is rolled out in phases:

**Phase 1 (landed):**

- `:core` — JS target added. Two files extracted from `commonMain`:
  - `Stream.kt::flow()` extension (uses `runBlocking` in `awaitClose`) → `nonJsMain` source set
    (a custom intermediate parent of `jvmMain` + `androidMain` + `iosMain`).
  - `AndroidType.kt` (uses `KClass.qualifiedName`, dead code as of Phase 1) → `androidMain`.
- `:identity-service`, `:file-service`, `:notification-service`, `:sync-service` — JS target
  added with no source changes. These umbrella modules contain the schema definitions, operation
  interfaces, and per-service domain models — most of the §12.3 "Full sharing" categories.
- `:tenshu-sdk-js` — new module aggregating the above five. JS-only target. Currently a
  placeholder; the TypeScript adapter layer per §12.6 / [tech-debt.md](../tech-debt.md)
  "Dukat Output Quality" comes next.

**Phase 2 (landed after the Interactor migration):**

- `:core-common` — `js(IR)` with `useEsModules()`. The remaining `runBlocking` site
  (`ProxyGateway.call`) was promoted to `suspend`; the synchronous transports that wrap it
  (Android Binder `ProxyService`, desktop `UnixSocketServer`, the `TestProxy.handleRequest`
  test convenience) own the `runBlocking` bridge instead. JS actuals added for the `expect`s
  in `core-common/util/`: `nextSecureRandomBytes` (WebCrypto `getRandomValues`),
  `TimestampUtil.formatTimestamp` (`Date.toLocaleString`), `sha256` and `pbkdf2Sha512`
  (the [`@noble/hashes`](https://github.com/paulmillr/noble-hashes) npm dep — small, sync,
  vetted; matches the cross-platform sync API). `KmpByteArrayOutputStream` was deleted along
  the way (no callers anywhere).
- `:core-client`, `:identity-service-common/-client`, `:file-service-common/-client`,
  `:notification-service-common/-client`, `:sync-service-common/-client` — `js(IR)` added.
  Their `commonMain` was already JS-clean once `:core-common` cleared.
- `:core-test-utils`, `:file-service-test-utils`, `:notification-service-test-utils` also
  gained `js(IR)`. Each puts proxy-coupled fixtures (`TestProxy`, `FileTestProxy`,
  `NotificationTestProxy`) and their `:*-proxy` `api` deps in `nonJsMain` so JS klibs ship
  only the portable test fixtures.
- `:tenshu-sdk-js` `api`-depends on all 15 in-scope modules now: `:core` + `:core-common` +
  `:core-client` + four feature-service umbrellas + their `*-common` and `*-client` variants.
- A few modules disable `compileTestKotlinJs` because their `commonTest` sources still import
  the proxy-bridge fixtures from `nonJsMain`. Main JS compile (the SDK surface) is unaffected.
  Folding those test sources into a `nonJsTest` intermediate source set is **Phase T**
  (testing infrastructure) per [`roadmap.md`](roadmap.md).

The infrastructure layer is now reachable from JS: the binary parser/serializer, schema
registry, request chain, request DSL, and per-service operation interfaces all compile to
ES-module klibs and ship via `:tenshu-sdk-js`. Crypto primitives (`sha256`, `pbkdf2Sha512`)
work synchronously through `@noble/hashes`; the rest of the crypto/storage backend choices
stay in **Phase 4** of the roadmap.

**Phase P — Proxy modules (landed):**

- `:core-proxy`, `:notification-service-proxy`, `:identity-service-proxy`,
  `:file-service-proxy`, `:sync-service-proxy` — `js(IR) { browser(); useEsModules() }`
  added; **zero source changes required**. The "multi-day" estimate that gated this
  for several earlier branches turned out to be wrong: the proxy logic was already
  written in clean `commonMain` Kotlin against the abstractions in
  `:core-common.proxy.*` (which Phase 2 had already JS-cleared), and the runtime
  dependencies (Ktor multiplatform `client-core`, `client-content-negotiation`,
  `serialization-json`; `kotlinx-coroutines`) all have JS variants. Each proxy
  module's `compileTestKotlinJs` is disabled with the same
  `tasks.matching { it.name == "compileTestKotlinJs" }.configureEach { enabled = false }`
  pattern as Phase 2 modules — `commonTest` still pulls `TestProxy`/`FileTestProxy`
  from `nonJsMain`, addressed under Phase T.
- Platform-specific operation implementations stay where they were:
  `:file-service-proxy/androidMain` (preview rendering, file uploads),
  `:file-service-proxy/jvmMain` (preview rendering),
  `:sync-service-proxy/androidMain` (`SyncWorker` on WorkManager) — none of these
  are referenced from `commonMain`, so the JS klib simply omits them. A
  `JsFileServiceProxyImplementationFactory` (canvas-backed previews, Blob-backed
  uploads) is the future hook for surfacing those operations from JS.

What this enabled, in turn: `:tenshu-sdk-js` now `api`-depends on the four
feature-service proxy modules, and the JS-side `ExecutionContext` / `SchemaRegistry`
wiring landed (§12.12 slices 2–4). `FileSystem.list` and `Notifications.observe`
dispatch real in-process operations against the bundled proxy modules — the §12.10
stub paragraphs below describe the *original* contract; see §12.12 slice 4 for the
real wiring that replaced them.

#### 12.6.2 The `nonJsMain` Source-Set Convention

Modules that need to host code which is JVM/Native-portable but not JS-portable (e.g. uses
`runBlocking`) declare a `nonJsMain` intermediate source set:

```kotlin
sourceSets {
    val nonJsMain by creating {
        dependsOn(commonMain)
    }
    val jvmMain by getting { dependsOn(nonJsMain) }
    val androidMain by getting { dependsOn(nonJsMain) }
    val iosMain by getting { dependsOn(nonJsMain) }
    // jsMain stays a direct child of commonMain only.
}
```

This avoids per-target duplication and avoids `expect`/`actual` stubs whose JS actual just throws
("compiles but doesn't work"). If a feature isn't yet supported on JS, it lives in `nonJsMain`
and JS users simply can't reference it — surfaced as a compile-time absence, not a runtime
exception.

### 12.7 Mobile/Desktop Impact

Adding a JS target to KMP modules is *additive*; it doesn't change existing mobile/desktop
behaviour. Practical impact:

- Module `build.gradle.kts` files gain a `js(IR) { browser() }` block in their `kotlin { ... }`
  configuration.
- Pure-Kotlin code (most of `commonMain`) compiles to JS automatically.
- Code in `commonMain` that uses JVM-specific APIs (e.g. `java.util.UUID`,
  `java.security.MessageDigest`, `java.io.File`) becomes a portability error. These get extracted
  into platform source sets behind shared interfaces. Most of this is already done; what isn't can
  be tracked as it surfaces.
- Existing tests on JVM and Android continue to run unchanged. New tests for JS behaviour live
  alongside as additions.

The mobile/desktop apps see no functional change. Build times grow modestly (the JS compilation
step), mitigated by Gradle caching.

### 12.8 Web Project Layout

The web projects live in a sibling directory under `Drive/`, managed as a pnpm workspace:

```
Drive/
  ...existing KMP modules (:core, :core-common, etc.)...
  tenshu-sdk-js/                # Gradle-built KMP→JS module; npm-publishable
  web/
    pnpm-workspace.yaml
    package.json
    apex/                       # the tenshu.app React app
      package.json
      vite.config.ts
      tsconfig.json
      src/
    drive/                      # eventually, the Drive web app
    shared/                     # extracted shared UI when there are ≥2 apps
```

**Per-app stack**:

- React 19 with TypeScript in strict mode (`strict: true`, `noUncheckedIndexedAccess`).
- Vite 5 for dev server and bundling. SSR is explicitly not used (the backend is ciphertext-only;
  rendering user data server-side would violate the Zero-Trust Backend invariant).
- Tailwind CSS for styling.
- React Router for client-side navigation.
- TanStack Query for data-fetching against the local proxy (postMessage / service worker), giving
  cache, refetch, and staleness for free.
- Zustand or Jotai for cross-component state. Not Redux — the protocol state lives in the SDK;
  React's job is just rendering and forms.
- WebAuthn via the standard browser API (`navigator.credentials`); a thin wrapper handles the basic
  flow. Consider `@simplewebauthn/browser` if the API gets repetitive.
- **Vitest + jsdom** for unit tests (`pnpm test` / `pnpm test:watch`). Vite's transform pipeline is
  reused, so test files can import the Kotlin/JS bundle the same way the app does — pure-logic tests
  for apex helpers live next to their source as `*.test.ts`. React Testing Library + Playwright
  are tracked under [`roadmap.md`](roadmap.md) Phase T (T.1 component tests, T.3 e2e); they're
  unblocked but not yet wired in.

**Workspace + package management**: pnpm with workspaces. Each web app and the SDK are workspace
members. The pnpm workspace root is `web/`, so the Gradle-built `tenshu-sdk-js/build/...` output
sits *outside* the workspace. The bridge: `web/sdk/src/index.ts` imports the webpack bundle via a
relative path (`../../../tenshu-sdk-js/build/dist/js/productionExecutable/tenshu-sdk-js.js`);
`web/sdk/src/native.d.ts` is the hand-written ambient declaration of the `@JsExport` surface;
apex's `vite.config.ts` sets `server.fs.allow: ['..', '../..']` to let the dev server read across
the workspace boundary. Web apps declare `"@tenshu/sdk": "workspace:*"` and Vite hot-reloads
on bundle changes after `./gradlew :tenshu-sdk-js:assemble`.

**Local development workflow**:

```bash
# Day-to-day on the apex
cd web/apex
pnpm dev                              # Vite dev server, hot reload

# When the SDK changes
./gradlew :tenshu-sdk-js:assemble     # rebuild SDK
# pnpm + Vite pick up the change automatically, hot-reload
```

**CI integration**: two parallel tracks that don't bottleneck each other.

- **Gradle CI** builds and tests KMP modules including the JS target. On tag, publishes
  `:tenshu-sdk-js` to npm.
- **Web CI** runs `pnpm install && pnpm build && pnpm test` for each app under `web/`. Day-to-day it
  pins to a specific `@tenshu/sdk` version from npm, so it doesn't depend on Gradle CI succeeding.
  SDK upgrades arrive as regular PRs (dependabot-style version bumps), not as a coupled CI failure.
- On tag, web apps deploy to hosting (Cloudflare Pages or similar — Vite's static-site output is
  hosting-agnostic).

**Splittability**: monorepo is not a permanent commitment. If web grows into its own team, has a
different release cadence, or wants to be open-sourced separately, splitting `web/` into its own
repository is a few-day operation — pnpm workspaces, the SDK contract, and the per-app projects are
already cleanly separated. Don't split prematurely.

### 12.9 Composite → JS via the Schema Walker

`Composite` (the base of every Tenshu domain model — Device, File, Folder, Notification, etc.)
resists `@JsExport` directly:

- The base interface declares `<T : Data> getField(reference: String): T` — `@JsExport`
  rejects generic methods on exported types.
- Field accessors return reactive wrapper types (`TString`, `TBoolean`, `TByteArray`); JS
  callers want the underlying primitive, not `device.name.get()`.
- `Composite.schema: CompositeSchema` drags in the schema DSL (sealed builders, lambdas,
  generics) — the Explore survey for this work flagged that as the largest `@JsExport`-hostile
  surface in the codebase.
- Companion-object factories like `Device.create(context, ...)` take a `TContext` that has no
  JS analogue.

The SDK sidesteps all of this with a **schema walker** that runs Kotlin-side and emits plain
JS objects:

```kotlin
// tenshu-sdk-js/src/jsMain/.../internal/CompositeSnapshot.kt — internal, no @JsExport
internal fun Composite.toJs(): dynamic {
    val obj: dynamic = js("({})")
    fields.forEach { (id, data) ->
        val key = id.substringAfterLast('/')   // "tenshu/identity/device/deviceId" → "deviceId"
        obj[key] = unwrap(data)
    }
    return obj
}

// Branch ordering matters: more specific types must come before their
// supertypes (UnixInstant : TInteger; TList : Composite).
private fun unwrap(data: Data): dynamic = when (data) {
    is TString     -> data.get()
    is TBoolean    -> data.get()
    is TByteArray  -> data.get()
    is UnixInstant -> data.toEpochMilliseconds().toDouble()  // JS number, epoch ms
    is TInteger    -> data.get().toDouble()                  // JS number, lossy >2^53
    is TList<*>    -> data.toList().map { unwrap(it) }.toTypedArray()
    is Composite   -> data.toJs()
    else -> error("compositeToJs: unsupported Data leaf ${data::class.simpleName}")
}
```

Per-domain `@JsExport` wrappers (e.g. `app.tenshu.sdk.Devices`) call the walker internally;
JS only ever sees flat objects with primitive values:

```typescript
// web/sdk/src/index.ts
export interface Device {
  readonly deviceId: string
  readonly name: string
  readonly publicKey: Int8Array
  readonly isCurrent: boolean
  readonly isAuthorized: boolean
}
export const Devices = bundle.app.tenshu.sdk.Devices
```

Properties of this design:

- **Adding a new domain model** is a Kotlin-side `@JsExport` wrapper that produces a Composite
  + the walker call, plus a hand-written TS interface in `native.d.ts`. No new walker per
  type.
- **Adding a new leaf `Data` type** is one branch in `unwrap`. The `error` fallback is
  intentional — silent skips would let mismatches between the Kotlin schema and the JS surface
  go unnoticed. `UnixInstant` is the first non-`TValue.get()`-shaped leaf added (it routes
  through `toEpochMilliseconds()` and emits a JS `number` — see Notification below).
- **Schema inheritance comes for free.** The schema DSL's `composite(parent = ...)` builder
  merges parent `values` with child `fields` at materialisation time, so the walker iterating
  `composite.fields` sees inherited fields. `Folder.Schema` (parent = `File.Schema`) emits
  `{path, size, lastModified, files}` from `Folders.demo()`.
- **`TList<T>`** is a `Composite` (interface inheritance), so the `TList<*>` branch must
  come before `Composite` in `unwrap`. `SimpleTList` implements `List<T>` by delegation,
  which is what `TList<T>.toList()` (extension in `core/collection/list/TList.kt`) keys
  off — proxy-derived `TList`s that don't implement `List<T>` will throw, which is the
  signal to introduce a non-snapshot iteration mode (lazy/streaming) when that case
  arises in real wiring.
- **Snapshot semantics**: the returned JS object is a one-shot snapshot. The Kotlin `TString`
  wrapper exists *because* values can change; flattening to a `string` loses that
  observability. For the React + TanStack Query + Zustand stack, this is the correct shape —
  refetches are query-driven anyway, and React doesn't observe Kotlin reactives. But it is a
  semantic shift worth naming when wiring real proxy-derived data.
- **Field-id collapsing**: the walker uses `substringAfterLast('/')` so namespaced ids
  (`tenshu/identity/device/deviceId`) become bare keys (`deviceId`). Fields within a single
  Composite always have distinct suffixes by construction; collisions across nested Composites
  don't happen because nesting recurses into separate objects.
- **`TArray`** that's not a `Composite` or `TList` still has no branch — none of the
  current domain types use it directly. The `error` fallback in `unwrap` will surface
  the case loudly when it appears.

**Decision deliberately avoided**: do *not* `@JsExport` `Composite` itself, and do not invent
a `JsComposite` shadow interface. The walker pattern is uniform — one mechanism handles every
domain type — and avoids duplicating the schema definitions in TypeScript form.

### 12.10 Operation surface: `Promise`-returning callables

> **Status (2026-05-09):** the snippets below are the *original stub* design. The real
> in-process wiring landed in §12.12 slice 4 — `FileSystem.list` / `Notifications.observe`
> dispatch through `JsRuntime.tenshuClient` against the bundled proxy modules. The public
> shape is unchanged from what's shown here.

Operations (`UploadFile`, list-files, observe-notifications, etc.) follow the rule from AGENTS.md
house rule #3: *"Operations stay non-suspend at the interface level."* The `suspend` lives one
layer up, in the caller-side bridge that converts a `RequestObject` into a `TaskInteractor` and
calls `.execute()`. JS gets that bridge as a vanilla `Promise<T>` via `kotlinx.coroutines`'
`GlobalScope.promise { ... }`:

```kotlin
@JsExport
object FileSystem {
    fun list(path: String): Promise<Array<dynamic>> = GlobalScope.promise {
        // Real wiring (later): findOperation<ListFiles>(...).asTaskInteractor<...>().execute()
        delay(150)
        listOf(...).map { it.toJs() }.toTypedArray()
    }
}
```

The TS adapter declares `list(path: string): Promise<File[]>`; React code drives it through
TanStack Query (`useQuery({ queryKey, queryFn: () => FileSystem.list(path) })`).

**Currently a stub.** The proxy modules (`:core-proxy`, `:file-service-proxy`, etc.) don't yet
have `js(IR)` targets — see §12.6.1 — so `LocalTaskExecutor` can't run real operations
in-process from the JS bundle. The stub returns canned data after a 150 ms simulated delay.
The public shape is the contract; the executor underneath swaps to a real one (an in-process
`LocalTaskExecutor` once proxy modules JS-target, or a postMessage-driven proxy gateway for
production) without touching the JS surface.

**Streams** (operations carrying both `TerminalOperation.Schema` and `StreamOperation.Schema`
traits) follow a **callback-based subscribe** pattern instead of `Promise`. Each emission
delivers the full snapshot, matching the merged `Flow<TResult<TList<T>>>` shape from
`core/collection/list/Stream.kt`:

```kotlin
@JsExport
object Notifications {
    fun observe(callback: (Array<dynamic>) -> Unit): () -> Unit {
        // Real wiring (later): findOperation<Stream<Notification>>(...)
        //   .asStreamInteractor<Notification>()
        //   .registerCallback(callback that calls back on each merged snapshot)
        val items = mutableListOf<SimpleComposite>(...)
        callback(items.map { it.toJs() }.toTypedArray())   // initial snapshot
        val job = GlobalScope.launch {
            while (coroutineContext.isActive) {
                delay(2000); items.add(...)
                callback(items.map { it.toJs() }.toTypedArray())
            }
        }
        return { job.cancel() }   // mirrors StreamInteractor.endStream()
    }
}
```

The TS adapter exposes
`observe(callback: (items: Notification[]) => void): () => void`. React composes this with
`useEffect` (the cleanup function maps directly onto the unsubscribe):

```typescript
useEffect(() => Notifications.observe(setItems), [])
```

**Why callback-based, not `AsyncIterable`.** `for await` is more elegant in plain JS, but
React's render loop expects subscriptions whose lifecycle matches mount/unmount. A callback
returning an unsubscribe function maps one-for-one onto `useEffect`'s cleanup contract.
`AsyncIterable` would require a wrapping hook anyway (most apps would hand-roll their own),
so we publish the lower-level shape and let consumers add `AsyncIterable`/Suspense
adapters on top if they want. Same shape used by RxJS `Observable`, Zustand subscribe,
and the standard Web Streams `ReadableStream` (which itself adapts callback-style sources).

Same proxy-module gating applies as for one-shot operations: the underlying `Stream<T>` →
`StreamInteractor` route isn't reachable from JS until `:*-proxy` modules JS-target. The
stub mirrors the contract — initial snapshot + periodic emissions, plus a working
unsubscribe — so the JS API is exactly the one real wiring will land into.

**Bundle cost.** `kotlinx.coroutines`' JS runtime costs roughly 65 KB minified (apex bundle
went from 372 KB to 437 KB when this landed). Alternatives (raw `Promise` constructors,
hand-rolled async loops) lose the suspension semantics and don't compose with the rest of the
proxy/Interactor pipeline once it's reachable from JS, so we accept the cost. Per-service
exports (§12.11) will let apps that don't use the operations API skip the coroutines
runtime entirely *once the Kotlin bundle itself is split*.

### 12.11 Per-Service Exports Map

`@tenshu/sdk` ships four per-service entry points alongside the aggregated default:

```typescript
import { TenshuSdk, Bip39 } from '@tenshu/sdk/core'
import { Devices, type Device } from '@tenshu/sdk/identity'
import { Notifications, type Notification } from '@tenshu/sdk/notifications'
import { FileSystem, Files, Folders, type File, type Folder } from '@tenshu/sdk/files'
```

Wired through the `exports` field in `web/sdk/package.json`:

```json
"exports": {
  ".": "./src/index.ts",
  "./core": "./src/core.ts",
  "./identity": "./src/identity.ts",
  "./notifications": "./src/notifications.ts",
  "./files": "./src/files.ts"
}
```

The aggregated `@tenshu/sdk` re-exports from all four for back-compat / convenience. Each
per-service file imports a shared `_bundle.ts` (which holds the relative-path import +
`/// <reference path="./native.d.ts" />` directive) and re-exports its slice.

**This is API ergonomics, not real bundle splitting.** The Kotlin webpack bundle is a single
UMD blob whose IIFE initializes the entire `app.tenshu.sdk` namespace as a side effect. When
apex imports `@tenshu/sdk/files`, Vite/esbuild reads `files.ts`, sees the `_bundle.ts`
import, and pulls in the *whole* Kotlin bundle — no part of the namespace is reachable
without the rest. apex's bundle stays the same regardless of which per-service entry points
it imports.

`:tenshu-sdk-js` declares `useEsModules()` so webpack itself does ESM-aware tree-shaking
across the inter-module compileSync output before producing the UMD bundle — that drops
about 28 KB off the final webpack output (apex went from 533 KB → 505 KB). The savings are
real but modest; the bundle is still over Vite's default 500 KB warning threshold.

The contract is what consumers want, though: third-party apps and first-party apex code
both get to write the natural import statements, and the npm sub-paths line up with the
service partition. Real per-service splitting lands when the Kotlin output becomes
tree-shakeable across services (see below); the TS adapter shape stays identical across
that transition. See `docs/tech-debt.md` "Kotlin-side bundle splitting" for the sized
follow-up.

**Why now, when it doesn't shrink anything beyond `useEsModules()`?** Because retrofitting
per-service paths later means retrofitting every consumer's import statements. Publishing
the contract early costs nothing and avoids a churning migration when real splitting lands.

**Per-module ESM consumption tried, didn't pay off.** Pointing `_bundle.ts` directly at
`tenshu-sdk-js/build/compileSync/.../Drive-tenshu-sdk-js.mjs` (skipping webpack and letting
Vite/Rolldown bundle the per-module ES klibs) was prototyped on this branch and reverted.
Two findings:

- It does enable per-service slicing — apex importing only `@tenshu/sdk/core` produced a
  19 KB smaller bundle than importing all four services. Real but small.
- The Rolldown-bundled output is roughly 20 KB *larger* than the webpack output for the
  same inputs, so the slicing-on net is a wash for apex (which uses everything anyway).
  Webpack uses Kotlin-aware plugins (`kotlin-web-helpers`) that Rolldown doesn't replicate.

It also pulled npm deps the Kotlin output references (`@noble/hashes`) outside webpack's
scope, requiring a Vite alias + duplicating the dep in apex. More moving parts for no
net win.

For real per-service slicing the right path is **multiple Gradle webpack outputs** (one
per service module, sharing core via webpack `splitChunks`), or **lazy-loading the in-process
runtime** (`JsRuntime`) so Rolldown can dead-code-eliminate it for apps that don't need it.
Both are bigger projects than the one-line `useEsModules()` win documented here.

### 12.12 In-Process Runtime Construction (JS)

The JS bundle's operation surfaces (`FileSystem.list`, `Notifications.observe`, …) need a
runtime to resolve against. The production KMP apps (Drive, Control on JVM/Android) wire
this through Koin in `:core-common/.../di/CoreCommonModule.kt`. The JS SDK does the same
wiring **by hand** because we don't ship Koin in JS — `JsRuntime` in
`tenshu-sdk-js/jsMain/.../internal/JsRuntime.kt` mirrors the Koin module's component graph
one-for-one, using the same production helpers (`createDefaultSchemaRegistry`,
`DefaultSyncCryptoManager`, `SystemClock`, `TraitResolverImpl`,
`PureOperationRunnerImplementation`). Construction is `lazy`; the runtime is built on
first access and lives behind an `internal` object so JS callers can't reach into it
directly.

```kotlin
internal object JsRuntime {
    private val syncCryptoManager: SyncCryptoManager = DefaultSyncCryptoManager()
    private val systemClock: Clock = SystemClock()

    val schemaRegistry: SchemaRegistry by lazy {
        createDefaultSchemaRegistry(tContextProvider = { tContext })
    }

    val tContext: TContext by lazy {
        object : TContext {
            override val components: Map<String, TComponent> = buildMap {
                put(DataFactory.COMPONENT_ID, schemaRegistry)
                put(SchemaProvider.COMPONENT_ID, schemaRegistry)
                put(RoRegistry.COMPONENT_ID, schemaRegistry)
                put(OperationResolver.COMPONENT_ID, schemaRegistry)
                put(TraitResolver.ID, TraitResolverImpl { tContext })
                put(PureOperationRunner.ID, /* PureOperationRunnerImplementation… */)
                put(SyncCryptoManager.ID, syncCryptoManager)
                put(Clock.COMPONENT_ID, systemClock)
            }
        }
    }
}
```

`SchemaRegistry` and `TContext` reference each other (the registry needs the context to
materialise schemas; the context exposes the registry as a `TComponent`). Lazy init breaks
the cycle: the registry's `tContextProvider = { tContext }` lambda only fires when
something inside the registry actually needs the context, by which time `tContext` is
fully initialised.

**Slice 1.** Wired the foundation listed above. `TenshuSdk.runtimeStatus()` returned
`{ ready, nowMs }`, forcing lazy construction and reading `Clock.nowEpochMillis()` to
prove the assembly works and the time-system hookup was end-to-end.

**Slice 2.** Extends the runtime to a complete proxy-side stack:

- **Per-service schemas** registered via `createDefaultSchemaRegistry`'s `extraConfigs`.
  Eight modules — `fileServiceCommonModule`, `fileServiceClientModule`,
  `notificationServiceCommonModule`, `notificationServiceClientModule`,
  `identityServiceCommonModule`, `identityServiceClientModule`,
  `syncServiceCommonModule`, `syncServiceClientModule` — the same set
  `:control-shared/.../di/ProxyModule.kt` registers under its `coreSchemaConfigs` named
  list. The SDK now knows about `File`, `Folder`, `FileSystem`, `Notification`,
  identity types, and sync types, with their CRDT trait wiring.
- **`Database`** via `DatabaseImpl(InMemoryObjectStore(), TenshuProtocolObjectDeserializer(...))`.
  In-memory only for now; an IndexedDB-backed `ObjectStore` is a follow-up slice
  (see "JS-platform impls" in `tech-debt.md`).
- **`CallstackLinearizer`** + **`ClientRegistry`** + **default `ExecutionContext`**.
  The `ClientRegistry` is wired with `clientConnectionFactory = { _ -> LocalClientConnection() }`
  (no remote connections yet) and an empty `initialization`. `JsRuntime.executionContext`
  resolves a single-tenant context for the `tenshu-js-sdk` client id.

**Slice 3.** Wires the executor side, completing the in-process operation dispatch path:

- **`LocalTaskExecutor`** over the slice-2 `ExecutionContext`, exposed as
  `JsRuntime.taskExecutor: AsyncTaskExecutor`. The bridging
  `LocalClientConnection.addListener { … defaultInteractorProvider.dispatchElement(it) }`
  is installed lazily inside the same getter — same shape `ProxyModule` uses to wire its
  `AsyncTaskExecutor`.
- **`DefaultInteractorProvider`** with a `taskExecutorProvider = { taskExecutor }` lambda
  to break the `TContext ↔ ExecutionContext ↔ TaskExecutor ↔ InteractorProvider` cycle.
  The provider is constructed eagerly (cheap — captures the lambda + a mutex) so it can
  be referenced from `tContext.components` without forcing the executor chain. Bound under
  `InteractorProvider.COMPONENT_ID`, satisfying the same key the production
  `CoreCommonModule` registers.

**Slice 4.** Wires the client surface and swaps the operation stubs to real dispatch:

- **`TenshuEnvironment`** + **`DefaultTenshuClient`** in `JsRuntime`. The environment uses
  the slice-3 `taskExecutor` and a `LocalRequestChain` factory; the client wraps the
  environment. Same shape as `:control-shared/.../di/ProxyModule.kt`'s registrations.
  `JsRuntime.tenshuClient` is the entry point DSL extensions navigate through
  (`client.fileService.defaultFileSystem.root.files`,
  `client.notificationService.notifications`).
- **`FileSystem.list(path)`** is no longer a stub. It calls
  `client.fileService.defaultFileSystem.root.files`, narrows to the requested folder via
  `search<Folder> { path equalTo … }.ifEmptyError().files` when `path != "/"`, and takes
  `flow().first()` as the one-shot result — mirroring the production navigation in
  `:drive-shared/.../TenshuFilesRepository.getFiles`. Each entry is walked through the
  schema walker into a flat JS object.
- **`Notifications.observe(callback)`** is no longer a stub. It launches a collector on
  `client.notificationService.notifications.flow()`; each emission delivers the merged
  `TList<Notification>` snapshot, walked through the schema walker. The returned lambda
  cancels the collector, which walks the `Flow`'s `finally` block and `endStream`s the
  underlying `StreamInteractor` (see `:core/.../collection/list/Stream.kt`).
- **`TenshuSdk.runtimeStatus()`** now touches `JsRuntime.tenshuClient`, walking the
  deepest lazy chain end-to-end (schema parsing for all eight modules, trait resolution,
  `ClientRegistry` assembly, `ExecutionContext.init()`, dispatcher listener install,
  `TenshuEnvironment` construction) before reporting `ready=true`.

The runtime is feature-complete for in-process operation dispatch. Apps that consume
`@tenshu/sdk` from JS can `await FileSystem.list("/")` or
`Notifications.observe(setItems)` and the underlying execution is real — the canned data
is gone. With an empty `InMemoryObjectStore` the lists are empty, but the dispatch path
exercises every layer (`flow → StreamInteractor → DefaultInteractorProvider → LocalTaskExecutor → ExecutionContext.evaluateCallstack → Database`).

**Slice 5.** Demo seed + minor SDK addition:

- **`FileSystem.createFolder(path): Promise<Boolean>`** added to the SDK surface. Mirrors
  the production extension `org.tenshu.core.file.system.createFolder`. Resolves `false`
  on any error (including `AlreadyExistsError`) so seed-style callers can treat it as
  idempotent.
- **Apex demo seed.** A `useEffect` on first mount calls
  `FileSystem.createFolder('/Documents' | '/Photos' | '/Music')` in parallel, then flips a
  `seeded` flag that gates the `FileSystem.list` query (`enabled: seeded`). The default
  `listPath` switched from `/Documents` to `/`, so the rendered list shows the seed
  folders directly. The seeded `FolderAddedNotification`s also surface in the
  `Notifications.observe` section, exercising the streaming dispatch.

**Slice 6 (this commit).** First JS-side tests + per-service factory wiring (slice 4
left this incomplete):

- **`jsTest` source set** added to `:tenshu-sdk-js` with `kotlin("test-js")` +
  `kotlinx-coroutines-test`. Default `js(IR) { browser() }` test runner is Karma +
  headless Chrome; works out of the box. First `jsTest` source set in the repo —
  several modules even disabled `compileTestKotlinJs` because the wiring wasn't there
  yet (notably `:core-common`, `:file-service-client`).
- **First two SDK tests** in `JsRuntimeTest`: `runtimeStatus_isReady` (exercises the
  full lazy chain), and `createFolder_succeedsAgainstEmptyDatabase` (exercises real op
  dispatch through the per-service factory).
- **Per-service `OperationImplementationFactory` wiring.** Slice 4 left this as the
  default core factory, which only knows core ops (Search/Filter/Stream/etc.). The
  test exposed it: `FileSystem.createFolder` was failing silently against the unwired
  factory. Now chained:
  `implementationFactory(id) ?: fileServiceProxyImplementationFactory(id, fileDatabase) ?: notificationServiceProxyImplementationFactory(id)`.
  Same composition `:control-shared/.../di/PlatformProxyModule.kt` uses; sync + identity
  factories deferred (need `SyncRelay`/`SyncManager`/extra crypto wiring, only matter once
  a JS sync transport lands). New deps: `:file-service-proxy`, `:notification-service-proxy`.
  `InMemoryFileDatabase()` added as a `JsRuntime.fileDatabase` lazy property.
- **Apex slice-5 seed silently fixed.** The slice-5 `Promise.all([createFolder('/Documents'), …])`
  was returning `false` for all three calls because the factory had no `CreateFolder`
  implementation. With slice 6's wiring, the seeds actually create the folders.

**Slice 8.** Closes the slice-6 "Known gap" — `createFolder → list`
now round-trips:

- **`ClientRegistry.initialization`** in `JsRuntime` now invokes
  `ExecutionContext.initFileService()` (from `:file-service-proxy`), the same
  hook production's `platformProxyInitialization` (PlatformProxyModule.kt) uses
  to create the root `/` `Folder` entity at boot. Without this, `addDataToArray`
  calls under `/` silently no-op'd because the parent didn't exist — the
  symptom the slice-6 test had to paper over with a soft assertion. Sync +
  identity inits from production stay omitted (out of scope without a JS sync
  transport).
- **Schema walker `ArrayDatabaseReference` branch.** With the root folder
  populated, `FileSystem.list("/")` now returns real `Folder` elements whose
  `files` field is an unresolved `ArrayDatabaseReference` (DB-backed list,
  not yet materialised in this context). The walker emits an empty array
  for these — JS surface contract is flat-per-level, so callers navigate
  nested collections via separate `FileSystem.list("/path")` calls instead of
  inlining child collections into the parent. The explicit `error` fallback
  for unrecognised `Data` leaves stays.
- **`JsRuntimeTest.createFolder_thenList_roundTrips`** is no longer soft:
  it now asserts the listed `path`s contain `/JsRuntimeTest`.

**Slice 9.** IndexedDB-backed `ObjectStore` so writes survive
page reloads. The `ObjectStore` interface is synchronous and consumed inline
by `DatabaseImpl`, so making it `suspend` would ripple into Android (SQLite)
+ Desktop wiring — out of scope for this slice. Instead the JS impl is
**cache-aside**:

- **`IndexedDbObjectStore.open(dbName, scope)`** is a suspend factory: it
  opens IDB, reads every entry into an in-memory `MutableMap<Long, DbEntry>`,
  and returns the store. `getObject` reads from the cache (sync). `storeObject`
  writes to the cache, fires listeners, and queues an async IDB `put` on a
  child supervisor scope — fire-and-forget, so the tab-close window where a
  queued write can be lost is the durability gap; a follow-up could `await`
  the put before the operation resolves. `removeObject` is symmetric.
  Listeners stay in-memory only (per-session subscriptions, not persistent).
- **`JsRuntime.hydration`** is a `Deferred<IndexedDbObjectStore>` started on
  first access. SDK entrypoints (`FileSystem.list`, `FileSystem.createFolder`,
  `Notifications.observe`, `TenshuSdk.awaitReady`) `await` it before touching
  `tenshuClient`. The lazy `database` property reads the store via
  `getCompleted()`, so accessing the chain pre-hydration throws — by design,
  keeps the rest of the runtime synchronous-lazy without bleeding `Deferred`
  through the type graph.
- **`TenshuSdk.awaitReady(): Promise<Unit>`** added to the JS surface. Apex
  calls it once before mounting; SDK ops also await internally so it's
  defense-in-depth, not strictly required.
- **`TenshuSdk.runtimeStatus()`** no longer forces tenshuClient construction;
  it just reports `ready = JsRuntime.isReady()` (the hydration `Deferred`'s
  completion). Cheap to call — no chain walk.
- **DbValue encoding for IDB.** `Long` values go through `toString()` /
  `toLong()` because IDB structured-clone has no native `Long` and `BigInt`
  is not a valid IDB key. `ByteArray` maps to `Int8Array`, which is the JS
  runtime representation Kotlin/JS already uses, so it round-trips through
  structured clone unchanged. Encoding is tagged (`{ t: 's' | 'l' | 'b' | 'o' | 'n', v: ... }`).
- **Tests.** `IndexedDbObjectStoreTest` covers the persistence + encoding
  round-trip (open → put → flush → reopen → get; covers all five `DbValue`
  variants + properties map). `JsRuntimeTest` is now suspend-based (awaits
  ready first); `createFolder_thenList_roundTrips` uses a timestamped path
  because IDB persists across test runs. Bundle adds **8 KB** (442 → 450 KB)
  for the IDB binding + cache.

**Cross-tab.** Each tab would open its own `JsRuntime` singleton with its
own cache, and IDB is shared across tabs with last-write-wins — so cache
coherence is still out of scope, and instead **only one tab per origin runs
the runtime** (decided 2026-10-17). `RuntimeLease` takes the
exclusive Web Lock `tenshu-runtime` before *any* store opens (every
hydration awaits it); a tab that does not get it opens no IndexedDB database
and every SDK call there rejects with `tenshu/runtime-elsewhere`. The apex
shows "Tenshu is open in another tab" (`RuntimeLeaseNotice`), web Drive the
same via the bridge (`runtime.lease` → `RuntimeBanner`).

- ⚠⚠ **Why it is a rule and not a nicety:** the sync cursors are durable
  rows in `tenshu-sdk` (A1). Two runtimes writing it could leave an older
  data row behind a newer cursor; a reload would never re-pull it.
- ⚠ **One lock for apex and Drive.** Drive has no stores of its own — its
  hidden apex iframe runs the runtime under the apex origin, so it shares
  the IDB databases *and* the lock with every top-level apex tab. Two
  surfaces that both need the runtime exclude each other; "Use here" moves
  it (accepted, 2026-10-17).
- ⚠⚠ **Apex takes the runtime lazily** (decided 2026-10-17). Starting
  `JsRuntime` takes the lock, so public pages — landing, marketing, pricing,
  support, legal, the share viewer `/s/:id` — must never start it. The one
  entry point is `web/apex/src/lib/runtime.ts` `ensureRuntime()` (it is
  `TenshuSdk.configure`, which must precede any sync-touching call anyway),
  called by `RequiresRuntime` around every runtime route in `App.tsx`
  (`/start`, open `/onboarding`, `/lock`, `/recovery`, `/i`, the whole
  ControlShell tree), by the proxy gateway per handler (never on mount;
  `bridge.ping` excepted), and on the landing banner's "Lock" click.
  Importing an SDK module does not start it — only touching `JsRuntime`
  does. The landing greets a returning visitor from `Session.peek`, a
  runtime-free hint the runtime mirrors into `localStorage`
  (`SessionHint.kt` ↔ `lib/sessionHint.ts`) — a label only, never access.
  A new route that reads or writes the realm goes inside `RequiresRuntime`;
  `RuntimeLaziness.test.tsx` fails if the landing reaches the lock. The
  notice (`RuntimeLeaseNotice`) therefore appears on the app page that was
  refused, never on a public one.
- **"Use here"** posts `release` on the `tenshu-runtime` BroadcastChannel;
  the holder answers `yielding` at once, marks itself displaced (its
  `awaitReady` refuses), cancels the runtime's work, `close()`s every store
  (flush, then refuse further writes — a write queued during that flush is
  dropped, a suffix and never a hole) and only then releases. The requester
  reloads; the fresh runtime takes the lock and hydrates everything the old
  one wrote. An acknowledged holder gets 2 minutes however slow its flush;
  only one that does not acknowledge within 3 s (a frozen background tab —
  iOS Safari freezes them routinely) is **stolen** from. ⚠ So a holder
  whose main thread is busy for more than 3 s is stolen from too, and the
  rows it still had queued are lost — bounded to that queue, and the tab
  shows itself displaced rather than carrying on.
- ⚠⚠ **A stolen tab can still write, so every database is fenced**
  (`StoreFence.kt`). The runtime that opens a database claims it with a
  fresh token (`meta` in `tenshu-sdk`, a reserved key in the others); every
  write transaction reads the token in the same transaction and aborts
  unless it is its own. IndexedDB serialises readwrite transactions, so a
  thawed tab's late writes are refused whole — they cannot land behind the
  new holder's cursor. The stolen tab also *abandons* its queue instead of
  flushing it. ⚠⚠ A refused write is never only logged: the first one
  marks the runtime displaced and drops its queue (`RuntimeLease.fencedOut`)
  — in every mode, and it is the *only* way an unguarded (no Web Locks)
  runtime learns another tab took over. `IndexedDbObjectStore.clearAll` is
  fenced too (a tab frozen mid-purge must not wipe the new holder). The open
  path's own writes are not: only the claimer runs them. The HLC watermark store is unfenced: it only ever raises to a
  max, so a late write is harmless.
- ⚠⚠ **Drive embedded in the apex uses the parent.** Apex hosts Drive at
  `/drive/files` inside its runtime-holding shell; a hidden apex iframe of
  Drive's own would be a second contender for the lock against the page
  that embeds it — every call refused. `mountApexProxy` therefore tries
  `window.parent` first (hello posted with `targetOrigin = apexOrigin`, so a
  non-apex parent never receives it; `ready` accepted only from exactly that
  origin *and* that window). ⚠ A parent **known** to be the apex
  (`location.ancestorOrigins[0]`, else the referrer's origin in Firefox) is
  never given up on: the hello is re-sent until it answers, because a host
  busy hydrating can block for seconds and a timer fallback would bring the
  lock contention back. Only a parent of unknown origin falls back to the
  hidden iframe after 1.5 s; one known not to be apex goes there directly.
  Every client message is checked for origin **and** `source`, because the
  parent and a nested frame share the apex origin.
- A crashed or closed holder releases the lock with the page; its committed
  IDB transactions are what the next tab hydrates (queued-but-uncommitted
  puts are the known tab-close gap above).
- **No Web Locks** (or the request refused): the runtime runs *unguarded*
  and the sync cursors are reset at every boot — a
  re-pull-from-0, which heals any cross-tab divergence. Conservative, not
  BroadcastChannel leader election, which cannot be made race-free.

**Slice 10.** File bytes round-trip across reloads.

- **`IndexedDbFileDatabase`** mirrors `IndexedDbObjectStore`'s cache-aside
  shape but for the path-keyed `FileDatabase` interface. Lives in a
  separate IDB database (`tenshu-sdk-files`) from the protocol store so
  the slice-9 schema version stays untouched and we avoid a
  version-bump dance to add a second object store. The two stores
  don't share transactions, but `UploadFile` doesn't need atomicity
  across metadata + bytes — the metadata write goes through the
  protocol `Database`; the byte write is a separate fire-and-forget
  IDB put. Worst case on a tab-close mid-upload: metadata exists,
  bytes are missing.
- **`JsRuntime.fileHydration`** is a second `Deferred<...>` awaited
  alongside `hydration` inside `awaitReady()`. The lazy `fileDatabase`
  property reads via `getCompleted()` on the same gate-or-throw shape as
  slice 9. `InMemoryFileDatabase` is gone.
- **`FileSystem.uploadFile(parentPath, fileName, Int8Array)`** added to
  the JS surface. Mirrors the production `org.tenshu.core.file.system.uploadFile`
  extension; resolves `true` on success or `false` on any error
  (including `AlreadyExistsError`).
- **`FileSystem.getData(path): Promise<Int8Array | null>`** added.
  Reads directly from `JsRuntime.fileDatabase.readBytes(path)` instead
  of dispatching the protocol-level `GetData` operation: the in-process
  runtime IS the byte source on this device, so the protocol round-trip
  is needless indirection until a sync HTTP transport lands and bytes
  can live on another device. The Kotlin `ByteArray?` return surfaces
  to JS as `Int8Array | null`. Direct read also sidesteps a deeper
  schema-walker bug in `select(relations(File.Relation.Data))` that
  surfaced when the in-process runtime tried to dispatch `GetData`
  through the streaming flow — worth a separate investigation but not
  required to land upload/download.
- **Tests.** `JsRuntimeTest.uploadFile_thenGetData_roundTrips` writes
  bytes via `uploadFile`, asserts they appear in `list("/")`, then
  reads via `getData` and `assertContentEquals` against the original
  payload (covers all-byte values: positive, negative, zero, byte
  extremes). The path is timestamped because IDB persists across runs.
- **Apex demo.** New `FileSystem.uploadFile / getData` section: file
  picker → upload → reload → download. Bytes survive the reload via
  IDB.

**Carries forward the apex-dev fix from slice 9.** `tenshu-sdk-js/webpack.config.d/esm.js`
was added in slice 9's follow-up commit but didn't make it through the
squash-merge — restored here so the production webpack bundle emits ESM
(not the default UMD) for Vite 8's strict ESM resolver. The TS adapter
in `web/sdk/src/` is rewired to ESM named imports + `.getInstance()`
because Kotlin/JS `@JsExport` on `object` declarations under
`useEsModules()` exposes a `{ getInstance(): T }` accessor rather than
the singleton directly. Webpack config-d is guarded with `if (config.output)`
because the same file is also inlined into karma.conf.js for `jsBrowserTest`,
where `config.output` is undefined.

**Slice 11 (this commit).** Canvas-driven preview generation.

- **`CanvasPreview.kt`** in `:tenshu-sdk-js/jsMain/.../internal/`: a
  single suspend `generatePreview(bytes, width, height): ByteArray?`
  that decodes via `createImageBitmap`, draws scaled into an
  `OffscreenCanvas`, and re-encodes via `convertToBlob({ type:
  'image/webp', quality: 0.85 })`. Aspect ratio is preserved; if the
  source already fits within the requested bounds the original
  dimensions are kept (mirrors `AndroidGetPreviewImplementation`'s
  sizing). Decode failures (corrupted bytes, unsupported format)
  resolve to `null`. Browser-API external decls (`JsBlob`,
  `JsImageBitmap`, `JsOffscreenCanvas`, `createImageBitmap`) are
  hand-rolled rather than pulling in `kotlinx-browser`-style deps;
  declared `internal` so jsTest can reuse them as fixtures.
- **`FileSystem.getPreview(path, width, height): Promise<Int8Array | null>`**
  added to the JS surface. Reads bytes directly from
  `JsRuntime.fileDatabase` (same direct-runtime pattern as slice 10's
  `getData` — protocol-level `GetPreview` round-trip is needless
  indirection for the in-process runtime).
- **Test.** `JsRuntimeTest.uploadFile_thenGetPreview_returnsEncodedBytes`
  generates a real PNG via OffscreenCanvas (avoids hand-crafting
  zlib-compressed IDAT chunks), uploads it, calls `getPreview`, and
  asserts non-empty bytes. No pixel comparison — that's brittle
  across browser image-codec versions; the assertion is that the
  decode + re-encode pipeline ran end-to-end.
- **Apex demo.** Uploaded files automatically render a 240x240 WEBP
  thumbnail next to the upload status, using
  `URL.createObjectURL(new Blob([thumb]))`. Non-image uploads skip
  the preview cleanly.

**Pending slices.** JS sync HTTP transport (talks to the relay; needed
only when the runtime should sync with other devices). Investigate the
`select(relations(File.Relation.Data))` schema-walker bug that slice 10
sidestepped.

**Bundle cost.** SDK webpack bundle was 117 KB after Phase P, 268 KB after slice 1
(+`useEsModules()`), 286 KB after slice 2, 300 KB after slice 3, 420 KB after slice 4,
423 KB after slice 5; slice 6 brings it to **442 KB** (+19 KB — pulls in
`:file-service-proxy` + `:notification-service-proxy` for the per-service factory
chain). Slice 8 stays at **442 KB** (no new deps — `initFileService` is reachable
through the existing `:file-service-proxy` dependency); slice 9 brings it to
**450 KB** (+8 KB — `IndexedDbObjectStore` + raw IDB bindings, no library
pulled in); slice 10 lands at **455 KB** (+5 KB — `IndexedDbFileDatabase`,
no new deps); slice 11 lands at **458 KB** (+3 KB — `CanvasPreview`,
no new deps; the browser APIs are external decls, not pulled-in libs). Apex's overall bundle is **713 KB minified / 209 KB gzipped**
as of slice 6.
The 209 KB gzipped transfer is fine over the wire; the ~700 KB minified parse is the
only real cost, and apex is a logged-in app where users have it cached after first
load. Real per-service Kotlin-side bundle splitting will pay off when third-party
SDK consumption becomes meaningful (a third-party app that only needs
`Notifications.observe` shouldn't ship the full runtime), but for apex alone it's
preemptive engineering. Slice 7 bumps apex's
`vite.config.ts` `chunkSizeWarningLimit` to 1000 so the warning stops nagging
without papering over the underlying signal — the
[`tech-debt.md`](../tech-debt.md) "Kotlin-side bundle splitting" entry stays open
with the multi-Gradle-output and lazy-load-runtime paths described.

---

## 13. Open Items

Items the doc deliberately defers, listed so they don't get lost.

- **Code signing for browser apps.** No web standard exists. The current approach (manifest declares
  `tenshu.versionHash`; apex compares the hash of the served bundle) verifies static content but
  does not cover dynamically-fetched code. Web Bundles + Signed HTTP Exchanges would address this if
  standardisation revives. Until then, Tenshu apps should minimise dynamic code-loading.
- **Phishing on look-alike subdomains.** `tenhsu.app` registered by an attacker is the same trust UX
  problem as `paypa1.com` today. Mitigations: only `*.tenshu.app` with valid Rei publications can be
  loaded by the apex post-Rei; the apex shows a "Verified by Rei" indicator; first-time-grant flows
  specifically warn about new origins. Hard problem; not unique to this design.
- **PostMessage performance.** Cross-origin postMessage with structured-clone serialization is
  significantly slower than a native function call. For chatty UIs, the cost is real. Mitigation:
  batch requests at the app side; cache frequently-read data in the app's own service worker.
- **Subdomain provisioning for first-party apps.** Each new first-party app needs DNS, a TLS
  certificate, a deployment pipeline. Mostly operational, not architectural; flag here so it doesn't
  surprise the team.
- **Browser-sync UX consistency.** Different browsers expose the "exclude origin from sync" option
  differently. The onboarding step 6 (browser-sync warning) needs per-browser instructions; this is
  a UX content task.
- **WebAuthn fallback for unsupported browsers.** Modern browsers all support WebAuthn, but
  corporate or locked-down environments may not. Fallback path (passphrase + recovery phrase) needs
  its own design.
- **Cross-device login fast path.** Today's pairing flow requires physical proximity (QR code on
  existing device). For web users without their existing device handy (e.g. signing in to a friend's
  laptop), the only path is the recovery phrase, which the threat model treats as cold storage.
  Whether to add a fast path (e.g. push approval to existing device) is an open question.

---

## 14. References

- [Tenshu White Paper](../whitepaper.adoc) — App Platform Connection Patterns, Realm Device Pairing,
  Recovery, Data Privacy & Security Design Principles. This doc extends the whitepaper's web
  coverage.
- [Threat Model](../sync/threat-model.md) — A1 (Master Identity), A4 (Device Signing Keys), §11.2 (
  recovery phrase entropy), §11.1 (recovery flow), T-RC-01 (recovery code handling).
- [Pairing Flow](../sync/pairing-flow.md) — used by the existing-realm-device onboarding branch (
  §8.3a).
- [Backend Relay Plan](../proposals/sync-backend-relay-plan.md) — Phase 3.1's master/device identity
  split.
  The web's identity model is consistent with that.
- [Milestones](../milestones.md) — where Web Baseline, Browser Extension, and Tenshu PWAs each get
  milestone entries (to be added).
- [`docs/agent.md`](../agent.md) §17 — `LazyComposite` is stateless; per-context schema
  caching lives on `SchemaRegistry`. The web SDK's KMP→JS bundle assumes this is in place.

---

## 15. What's Next

Suggested follow-ups, in rough order:

1. **Add three milestone entries to [`milestones.md`](../milestones.md)**: Web Baseline, Browser
   Extension v1 (first-party only), and Tenshu PWAs. Each with dependencies and ordering. Tenshu Rei
   sits as a dependency for Browser Extension v1.1 (third-party support).
2. **Amend the whitepaper's App Platform Connection Patterns section** to distinguish the
   *browser-extension* and *hosted-web* variants of the web platform, with a forward pointer to this
   doc for the layered model.
3. **Detailed UX flow doc** at `docs/proposals/web-onboarding-ux.md` — wireframes, copy, edge cases.
   Beyond
   the scope of this architecture doc.
4. **Threat-model amendment**: a one-paragraph addition to §10 (out-of-scope/accepted-risk) noting
   browser-sync as a risk that the user can choose to opt out of, with the apex's UI making the
   trade-off legible.
5. **Browser-extension manifest spec** for the extension layer specifically — what permissions it
   requests, what content scripts it injects, how it talks to the apex. Lives in
   `docs/proposals/web-extension.md` when the extension milestone starts.
6. **Add `js(IR)` targets to the existing KMP modules** — Phase 1 landed: `:core` and the four
   feature-service umbrella modules (`:identity-service`, `:file-service`, `:notification-service`,
   `:sync-service`) now have JS targets; `:tenshu-sdk-js` aggregates them. Phase 2 (`:core-common`,
   `:core-client`, and the feature-service `*-common` / `*-client` variants) is gated on the
   Interactor pattern migration removing the `runBlocking` bridges in `StreamRo`. See §12.6.1.
7. **Create the `:tenshu-sdk-js` module** — landed (placeholder). Module exists with a `js(IR)`
   target, depends on the Phase-1 modules, and produces an npm-publishable webpack bundle via
   `:tenshu-sdk-js:jsBrowserProductionWebpack`. Still to do: hand-written TypeScript adapter layer
   on top of Dukat-generated bindings, per-service `exports` map for tree-shaking
   ([tech-debt.md](../tech-debt.md) "Bundle-Size Strategy"), and initial private-npm-registry
   publish target until the API surface stabilises.
8. **Tech-debt entry for the KMP → JS toolchain**: bundle-size strategy, Dukat output quality,
   crypto backend selection (libsodium-wasm vs. WebCrypto fallbacks), TypeScript interop ergonomics.
   Captures the practical concerns from §12.4 as concrete tasks rather than burying them as
   forever-doc-only items.
9. **Scaffold `web/apex/`** per the project layout in §12.8. Initial steps: create
   `web/pnpm-workspace.yaml`, scaffold `web/apex/` with `pnpm create vite . --template react-ts`,
   install Tailwind / React Router / TanStack Query / Zustand, wire up the workspace dependency on
   `@tenshu/sdk`. Total time: under an hour once `:tenshu-sdk-js` exists. Gating dependency: items 6
   and 7 above (KMP→JS targets + the SDK module).
