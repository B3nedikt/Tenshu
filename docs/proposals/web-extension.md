# Tenshu Browser Extension — Specification

**Status:** Draft v0.1
**Scope:** The cross-browser extension component of web Tenshu, as introduced by
[`docs/web/architecture.md`](../web/architecture.md) and tracked as a milestone in
[`docs/milestones.md`](../milestones.md).

This document specifies the browser extension's manifest, components, request-interception
model, message protocol with the apex, apex-tab discovery, and the v1 / v1.1 phased delivery
scope. It complements the architecture doc, which covers the higher-level *why* (Direct Proxy
Connection on web, master-key location, third-party trust); this doc covers the *how*
(MV3 manifest fields, content scripts, webRequest hooks, message routing).

---

## 1. Goals and Non-Goals

The extension exists to **add the Direct Proxy Connection pattern to web Tenshu**. Specifically:

- Intercept outgoing HTTP requests from Tenshu-enabled origins and route the Tenshu-relevant
  ones through the user's apex (`tenshu.app`).
- Enable third-party Tenshu integrations (post-Rei) on origins outside `*.tenshu.app`.
- Block requests from Tenshu-enabled origins that exceed the user's granted semantic
  permissions for that origin.

The extension explicitly does **not**:

- Hold the master key. Per [architecture §4.3](../web/architecture.md), the master key lives in
  IndexedDB scoped to `tenshu.app`. The extension is a request interceptor and message
  router; crypto operations happen in the apex.
- Run a full Tenshu proxy. The proxy logic (permission evaluation, request resolution, sync
  state) lives in the apex tab/PWA. The extension is a thin layer.
- Replace the Web Baseline. Without the extension, web Tenshu still works for first-party
  apps via the External Proxy Connection pattern. The extension is additive.

---

## 2. Component Architecture

The extension consists of four components, each addressing a specific responsibility:

### 2.1 Background Service Worker

The extension's coordinator. Responsibilities:

- Listens for `webRequest` events on Tenshu-enabled origins (matching depends on phase — see §6
  and §7).
- Locates the active `tenshu.app` apex tab/PWA (see §5).
- Forwards Tenshu API calls from content scripts to the apex via `chrome.tabs.sendMessage`.
- Returns apex responses back to the content script that originated the call.
- Maintains a small in-memory cache of recently-validated Rei publications (post-v1.1) to
  avoid repeated fetches.

In MV3 this is a service worker, not a persistent background page. It can be torn down by the
browser when idle and revived when an event arrives. State that must survive teardown lives in
`chrome.storage.session`.

### 2.2 Content Scripts

Injected into Tenshu-enabled origins:

- v1: `*.tenshu.app` only.
- v1.1: also third-party origins that declare a Tenshu manifest *and* have a valid Rei
  publication (verified by the service worker before injection).

Responsibilities:

- Inject a `window.tenshu` API into the page's main-world context. This is what page scripts
  call (`window.tenshu.connect()`, etc.).
- Bridge the main-world API to the service worker via `chrome.runtime.sendMessage`.
- Surface permission-grant prompts when the page makes a request that requires a permission
  the user hasn't yet granted.

### 2.3 Action / Popup UI

Minimal popup shown when the user clicks the extension's toolbar icon. Shows:

- Whether `tenshu.app` is currently open (and, if not, a button to open it).
- Which Tenshu permissions are active for the current origin (read from the apex, not stored
  by the extension).
- A link to the apex's control plane for managing permissions.

The popup is intentionally thin. Permission grants, revocations, device management, and
recovery all live in the apex's control plane — not in the extension. The extension is a
*router*, not an *administrator*.

### 2.4 webRequest / declarativeNetRequest Rules

For the request-interception path. See §4.

---

## 3. Manifest (`manifest.json`)

Manifest V3, with cross-browser compatibility in mind:

```jsonc
{
  "manifest_version": 3,
  "name": "Tenshu",
  "version": "0.1.0",
  "description": "Tenshu proxy for the web — adds local-proxy semantics and third-party Tenshu integration.",

  "background": {
    "service_worker": "background.js",
    "type": "module"
  },

  "action": {
    "default_popup": "popup.html",
    "default_icon": { "16": "icons/16.png", "48": "icons/48.png", "128": "icons/128.png" }
  },

  "content_scripts": [
    {
      "matches": ["https://*.tenshu.app/*"],
      "js": ["content-firstparty.js"],
      "run_at": "document_start",
      "world": "ISOLATED"
    }
  ],

  "host_permissions": [
    "https://*.tenshu.app/*"
  ],

  "permissions": [
    "storage",
    "activeTab",
    "declarativeNetRequest",
    "scripting"
  ],

  "web_accessible_resources": [
    {
      "resources": ["main-world-api.js"],
      "matches": ["https://*.tenshu.app/*"]
    }
  ],

  "browser_specific_settings": {
    "gecko": {
      "id": "tenshu@tenshu.app",
      "strict_min_version": "115.0"
    }
  }
}
```

Notes:

- `host_permissions` is constrained to `*.tenshu.app` for v1. v1.1 expands this to all origins
  but uses runtime-granted permission requests (via `chrome.permissions.request`) so the user
  is prompted per-origin rather than granting blanket access at install time.
- `declarativeNetRequest` is preferred over `webRequest` where possible (more efficient,
  no need for the long-lived listener). Dynamic permission-driven blocking still uses
  `webRequest.onBeforeRequest` for cases where the rule depends on the user's current grants.
- `world: "ISOLATED"` for content scripts is the default; the extension's logic stays out of
  the page's JavaScript context. The injected `window.tenshu` API runs in `MAIN` world via
  `chrome.scripting.executeScript({ world: "MAIN" })` invoked from the isolated content script.
- `browser_specific_settings.gecko` is for Firefox compatibility. Safari requires a separate
  Xcode wrapper project for distribution, but the manifest is otherwise compatible.

---

## 4. Request Interception

The extension intercepts outgoing requests from Tenshu-enabled origins to enforce two
properties:

1. The origin cannot make HTTP requests that bypass the Tenshu proxy. Direct `fetch()` calls
   to non-Tenshu endpoints from a Tenshu-enabled origin are blocked unless the user has
   granted a permission that allows them.
2. The origin's Tenshu API calls (which the extension routes to the apex) are subject to the
   user's permission grants.

### 4.1 Static Rules (declarativeNetRequest)

For predictable cases, static rules block known-bad patterns at install time. Example: a
Tenshu-enabled origin should never make requests to ad networks, analytics services, or
non-Tenshu CDNs. A bundled blocklist of common third-party trackers is applied to all
origins matched by `host_permissions`.

### 4.2 Dynamic Rules (webRequest)

The dynamic path handles permission-aware blocking:

```
chrome.webRequest.onBeforeRequest.addListener(
  (details) => {
    const origin = new URL(details.initiator).origin;
    if (!isTenshuEnabled(origin)) return {}; // not our concern

    if (details.url.startsWith("postmessage://tenshu-proxy")) return {}; // routed call

    const grantedPermissions = grantsCache.get(origin) ?? [];
    if (!isAllowedByGrants(details, grantedPermissions)) {
      return { cancel: true };
    }

    return {};
  },
  { urls: ["<all_urls>"] },
  ["blocking"]
);
```

The grants cache is populated on demand by querying the apex; entries expire after a short
TTL (5 minutes) to pick up control-plane changes.

### 4.3 What Counts as "Tenshu-Enabled"

- v1: any origin matching `*.tenshu.app`.
- v1.1: an origin matching `*.tenshu.app`, *or* an origin whose served manifest passes Rei
  verification (see §7). Verification is cached per-origin until the manifest's `versionHash`
  changes.

Origins not in this set are not subject to extension interception; the user's other
extensions and the browser's normal request handling apply.

---

## 5. Apex Tab Discovery

The extension needs to find the active `tenshu.app` apex tab/PWA to route messages to it. The
service worker maintains a "current apex tab" reference, updated via:

```
chrome.tabs.onCreated, onUpdated, onRemoved
chrome.tabs.query({ url: "https://tenshu.app/*" })
```

Discovery rules:

1. **PWA installed**: prefer the PWA window if open. PWA windows persist across browser
   restarts and are the most reliable target. The architecture doc's §10 explicitly recommends
   the PWA install for this reason.
2. **Standard tab open**: any tab on `tenshu.app` works. If multiple tabs are open, pick the
   most recently focused.
3. **Nothing open**: the extension shows a notification (`chrome.notifications`) inviting the
   user to open `tenshu.app`. Pending requests queue in `chrome.storage.session` for up to 30
   seconds; if no tab opens in that window, queued requests fail with a "Tenshu apex not
   reachable" error which the page's `tenshu.connect()` Promise surfaces.

Future option: `chrome.windows.create` to open a pinned tab on demand. Decided against in v1
because silent tab creation is an aggressive UX; better to ask the user.

### 5.1 Cross-Tab Coordination

If multiple Tenshu-enabled origins are open simultaneously across multiple tabs, all of their
content scripts route through the same apex tab via the service worker. The apex serializes
requests from different origins; permission grants are origin-scoped, so cross-origin
contention is not a concern.

---

## 6. Message Protocol

The extension and the apex tab speak the same Tenshu binary protocol used elsewhere on the
platform. The transport differs:

- Apex ↔ apex (intra-process): direct function calls.
- Apex ↔ same-origin first-party app (e.g. apex tab to `drive.tenshu.app` iframe): cross-origin
  `postMessage`.
- **Apex ↔ extension service worker**: `chrome.tabs.sendMessage` (extension → apex tab) and
  `chrome.runtime.sendMessage` (apex tab → extension, since the apex's content script forwards
  to the service worker).
- **Extension service worker ↔ content script in a Tenshu-enabled origin**:
  `chrome.runtime.sendMessage`.
- **Content script ↔ page main-world script**: `window.postMessage` to the same window with a
  protocol prefix (`tenshu:proto-v1:...`) so other postMessage consumers don't see Tenshu
  traffic.

Each hop wraps the Tenshu binary blob in the appropriate transport envelope. The serialized
binary protocol stays opaque to all hops except the apex (which decodes) and the originating
page (which encoded).

### 6.1 Connection Handshake

When a Tenshu-enabled page calls `window.tenshu.connect()`:

1. The page's main-world script posts a `tenshu:connect-request` message with the page's
   declared `appId` from its Tenshu manifest.
2. The content script forwards to the service worker.
3. The service worker forwards to the apex tab.
4. The apex looks up the user's grants for the origin's `appId`. If the origin is not yet
   granted, the apex shows the permission-grant prompt.
5. On grant (or with existing grants), the apex returns a `connect-ack` containing a
   short-lived `sessionId`.
6. The session ID is used as a credential for subsequent calls; the apex enforces per-session
   rate limits and revokes the session when the page navigates away.

### 6.2 Request Routing

Every subsequent Tenshu API call from the page carries the `sessionId`. The service worker
validates that the call is from a session-issued origin and forwards to the apex. The apex
processes the call (locally or via the relay) and returns the result through the same chain.

---

## 7. Phase 1 Scope: First-Party Only

For v1, the extension's `host_permissions` and content-script matches are constrained to
`*.tenshu.app`. The extension does not interact with any other origin. Practically, this means:

- A first-party Tenshu app on `drive.tenshu.app` makes Tenshu API calls; those calls go through
  the extension to the apex (faster than direct postMessage in some cases, and subject to the
  extension's request-blocking enforcement).
- Origins outside `*.tenshu.app` see no extension behavior. The extension is invisible to
  them.

v1 is useful primarily for the request-interception capability — even first-party apps on
subdomains can have bugs that try to fetch resources they shouldn't, and the extension blocks
those at the network layer rather than relying on Same-Origin Policy and CORS alone.

v1 does **not** require Tenshu Rei to be operational. It ships when Web Baseline is
functional and the extension's code is ready.

---

## 8. Phase 1.1 Scope: Third-Party with Rei

v1.1 adds third-party origin support, gated on Rei. Key additions:

### 8.1 Origin Onboarding

When a user visits a third-party origin that declares a Tenshu manifest (via the
`<link rel="tenshu-manifest" href="/manifest.json">` element or similar discovery mechanism),
the extension's content script reads the manifest and forwards to the service worker.

The service worker:

1. Fetches the manifest URL.
2. Resolves `tenshu.reiPublication` against the Rei layers (RPL/RIL/REL).
3. Verifies the Rei publication signature.
4. Verifies the manifest's `tenshu.versionHash` matches the served bundle (computed by
   intercepting the page's first script-load and hashing the response).
5. If verification passes, requests the user's permission to enable Tenshu integration on
   this origin (via `chrome.permissions.request({ origins: ["https://example.com/*"] })`).
6. If granted at the browser level *and* by the user in the apex's permission-grant prompt,
   injects the content script into the origin and adds a webRequest rule for it.

If verification fails, the extension shows a one-time notification explaining why the origin
cannot be Tenshu-enabled. No browser permission is requested in this case.

### 8.2 Per-Origin Permissions

The user's grants for a third-party origin are stored in the apex (not in the extension), keyed
by `(origin, appId)`. Granting `tenshu/file/service/listFiles` to `example.com` does not grant
the same permission to `attacker.com`. Permission scopes (e.g. allowing only `/Documents`) are
honored per-origin.

### 8.3 Origin De-Authorization

The user can revoke a third-party origin's grants from the apex's control plane. After
revocation, the next time the extension checks the grants cache it picks up the change; the
origin's content script is removed and webRequest rules expire.

---

## 9. Cross-Browser Compatibility

| Browser                                   | Status                                                                                                                                                                                                                                                         | Notes |
|-------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|-------|
| **Chrome / Edge**                         | Primary target. MV3-native; everything works out of the box.                                                                                                                                                                                                   |
| **Firefox**                               | Full support since 115; MV3 is supported via `browser.*` namespace (the extension code uses `chrome.*` which is polyfilled). `browser_specific_settings.gecko` block in the manifest supplies the addon ID.                                                    |
| **Safari**                                | Requires an Xcode wrapper project. The web-extension API surface is similar but a few APIs differ (`chrome.notifications`, `chrome.declarativeNetRequest` constraints). Safari extensions are distributed via the Mac App Store rather than as `.crx` bundles. |
| **Brave / Opera / others Chromium-based** | Inherit Chrome support.                                                                                                                                                                                                                                        |

Distribution: Chrome Web Store, Firefox Add-ons, Mac App Store. The extension is published
under tenshu.app's developer account; the manifest's `id` field is consistent across stores.

---

## 10. Open Items

- **Service worker teardown / revival.** MV3 service workers can be killed when idle. State
  that needs persistence (the apex tab reference, the grants cache, queued requests) goes in
  `chrome.storage.session` (which survives service-worker teardown but not browser restart).
  Verify behavior under aggressive teardown scenarios.
- **declarativeNetRequest rule limits.** Chrome enforces a cap (currently 30,000 dynamic rules
  per extension). For v1.1 with many third-party origins, this could become a constraint. Plan
  for rule consolidation if needed.
- **Web Bundle / Signed Exchange support.** Currently no plan; if W3C-standard signed packaging
  ships, the verification flow in §8.1 step 4 simplifies (the bundle's signature is verified
  once and the content is trusted).
- **Multi-account support.** What if a user has multiple Tenshu accounts (e.g. personal +
  work)? Today: separate browser profiles. The extension does not need multi-account logic
  because each profile has its own extension state and its own apex tab.
- **Performance: webRequest blocking has a cost.** Every outgoing request from a
  Tenshu-enabled origin goes through the extension. For high-frequency apps, this is real
  overhead. Mitigation: declarativeNetRequest for as many cases as possible; webRequest only
  for permission-aware paths.
- **Coordinated reload after a permission grant.** When a user grants a new permission via the
  apex modal, the originating page's outstanding requests should retry (rather than failing).
  Need to design the "permission granted, retry queued requests" flow.

---

## 11. References

- [`docs/web/architecture.md`](../web/architecture.md) — the architecture doc this spec implements;
  in particular §2 (the three layers), §4.3 (extension is pure interceptor), §6 (extended
  manifest), §7 (third-party trust hard-blocked on Rei), §10 (layer coexistence).
- [`docs/sync/threat-model.md`](../sync/threat-model.md) — A4 (Device Signing Keys), §10
  (the new browser-sync addition), TA2 (platform keystores).
- [`docs/milestones.md`](../milestones.md) — Browser Extension milestone with the v1 / v1.1
  phasing.
- Manifest V3 spec: https://developer.chrome.com/docs/extensions/develop/migrate
- WebExtensions cross-browser
  compatibility: https://developer.mozilla.org/en-US/docs/Mozilla/Add-ons/WebExtensions
