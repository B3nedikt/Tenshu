# TrayAgent — Control menu-bar agent (macOS, Phase 1C)

A persistent `NSStatusItem` (the Tenshu Control **cat** icon) with a flush
menu-bar dropdown showing sync state, recent notifications, *Open Control*, and an
app launcher — all over the JSON native bridge
(`docs/proposals/desktop-macos-integration.md` §4 task 3). Decision: Swift
(proposal §8 Q1).

## Files

- `BridgeClient.swift` — synchronous JSON bridge client (one request/response
  per call over the group-container UDS; same framing as the Phase-0 HelloClient,
  generalised). Socket resolution mirrors `core-common`'s `SocketPathResolver`.
- `main.swift` — `TrayModel` (bridge → display), `PopoverView` (SwiftUI), the
  cat menu-bar icon (`trayIcon()`, an embedded template PDF), the **flush
  borderless `TrayPanel`** `AppDelegate`, and an `.accessory` bootstrap (no
  Dock icon — runtime equivalent of `LSUIElement`).

## Menu-bar icon

The icon is the brand **cat** as a black-on-transparent vector PDF, set as a
macOS *template* image (`isTemplate = true`) so the menu bar tints it for
light/dark. It's embedded in `main.swift` as base64 (`catTemplatePdfBase64`) so
the standalone `swiftc` binary needs no external file; a `cat.fill` SF Symbol is
the decode-failure fallback.

Regenerate from the themed control silhouette (the source lives in the gitignored
`docs/design/tenshu-icons/`):

```bash
# white themed ink → black template ink; tight-crop to the cat bounds
# (the box hugs ears→head-bottom + whiskers so the cat fills the menu-bar height).
sed -e 's/#FFFFFF/#000000/g' \
    -e 's/viewBox="0 0 432 432"/viewBox="94 138 252 172"/' \
    -e 's/width="432"/width="252"/' -e 's/height="432"/height="172"/' \
    docs/design/tenshu-icons/icons/android/themed/control.svg > cat.svg
rsvg-convert -f pdf -o cat.pdf cat.svg
base64 < cat.pdf | tr -d '\n'      # paste into catTemplatePdfBase64
```

## App launcher — what the menu offers

The Apps rows are decided by **LaunchServices, asked on every refresh**
(`TrayModel.isLaunchable`), not by registration alone
(`docs/proposals/control-app-catalogue.md` D5):

- `listApps` returns the registered apps plus every first-party macOS app that has
  never connected (`registered: false`). Those are candidates only.
- A row is shown if its bundle id resolves to a copy in `/Applications` or
  `~/Applications`, or — for an app that has registered on this Mac — to a copy
  anywhere. Copies in the Trash never count.

So a newly installed app appears the next time the menu opens, before it has ever
been launched, and an uninstalled one disappears although its registration stays.
macOS has no public "app installed" notification; asking at the moment of use needs
none.

⚠ The answer is only as good as the LaunchServices record. A bundle installed with
`cp -R` can be recorded half-copied (no `Info.plist`) and then stays unknown by
bundle id until `lsregister -f <bundle>` — `scripts/reinstall-all-apps.sh` runs that
after each copy. A Finder drag-install does not have the problem.

## Dropdown — flush borderless panel (no NSPopover arrow)

The dropdown is a borderless `NSPanel` (`TrayPanel`) anchored **flush under the
menu bar** — no arrow, no gap — with a `NSVisualEffectView` (`.menu` material,
rounded corners) for the native blurred look (cf. the input-source / Wi-Fi
menus). It's positioned at the status item's bottom edge, left-aligned and
clamped on-screen, and dismisses transiently via a global mouse-down monitor
(which doesn't fire for our own status-item / panel clicks). `NSPopover` was
replaced because it always draws an arrow and floats below the item.

## Build & run

```bash
swiftc -O -o tray-agent main.swift BridgeClient.swift PendingRequestNotifier.swift

# daemon must be running (resolves ~/.tenshu/bridge.sock in dev):
./gradlew :control-desktop:run        # from repo root, in another shell

./tray-agent
```

Click the cat menu-bar icon → the dropdown shows live sync state, a **Needs
action** section for inbound actionable requests, recent notifications, the
installed apps, and *Open Control* / *Quit* (`quitApp`).

## Pending-request notifications (Phase 3b)

`PendingRequestNotifier.swift` is the desktop OS-level elevation for Control's
**central notifications** (`docs/proposals/control-central-notifications.md`
§4.3.4) — the counterpart to Android's `AndroidPendingRequestNotifier`. The tray
polls the daemon's `pendingRequests` bridge method on a 10 s background timer
(independent of the popover), owns the *arrived* / *resolved* diff (mirroring the
Kotlin `PendingRequestAlerts`), and posts/cancels a `UNUserNotificationCenter`
notification keyed by the request's stable id (so the shade replaces, not stacks,
and resolving removes exactly that one). Tapping a notification raises Control,
which opens to Home where Phase 2b's pinned request cards carry the
Accept/Decline (or Confirm) actions — so there's no separate desktop
deep-link-to-center plumbing.

- **Generic / PII-free copy** (§6 proxy isolation): the bridge ships only the
  opaque id + a `kind` code; the title is derived from `kind` alone — never the
  grantor fingerprint or the peer's quarantined claimed name (same constraint as
  the Android notification + the foreground-service notification). This surface
  owns its display strings (ADR 0016).
- **Poll, not push**: the bridge is request/response, and a macOS notification can
  only be posted by this GUI agent (which holds notification authorization), not
  the headless JVM daemon — so the daemon serves state and the tray owns the diff.
  When the daemon is unreachable the poll is skipped (delivered alerts left
  intact); the resident daemon means there's normally something to poll.
- **Signing gate** (why this is its own PR): `UNUserNotificationCenter` needs a
  bundle identifier, which a bare `swiftc` binary lacks. In dev (`./tray-agent`)
  `notificationsAvailable` is `false`, the OS calls no-op, and the requests still
  render in the popover. The notifications only **display** from a packaged,
  **code-signed** `Control.app` (Phase 4 embeds + signs the agent). So the live
  banner / tap / authorization-prompt path is verifiable only on a signed build.

*Open Control* is daemon-state-aware: if the daemon is running it raises the
window via the `openControlWindow` bridge method; if it isn't, it cold-launches
the packaged `Control.app` through LaunchServices (`NSWorkspace.openApplication`,
located by `controlBundleId` = `app.tenshu.control.ios`), then raises the window once
the daemon's bridge comes up. App bundles are single-instance, so the launch is
idempotent. In **dev** there's no installed bundle for LaunchServices to find, so
the cold-launch path logs and no-ops — it lights up with Phase 4 packaging
(`nativeDistributions.macOS.bundleID` pins the matching `CFBundleIdentifier`).

## Status (Phase 1C + 3b)

- ✅ Builds with `swiftc`; `BridgeClient.swift` proven to round-trip against the
  real Kotlin `NativeBridgeServer` over a live UDS (`ping` + `getSyncState`).
- Sync state + pending requests are **polled** (`getSyncState` on popover open;
  `pendingRequests` on a 10 s background timer) — `subscribeSyncState` push is
  deferred (1B deviation note).
- `launchApp` returns `not_supported` on desktop until Phase 4 packaging.
- **Manual GUI verification** (not auto-testable): icon appears, popover renders,
  Open Control raises the window, closing it keeps the icon + daemon alive, Quit
  exits both. The cold-launch path (Open Control while the daemon is *not*
  running boots `Control.app`) needs a packaged, LaunchServices-registered bundle
  — verifiable only once Phase 4 packaging lands.
- **Pending-request notifications (3b)** display only from a packaged,
  code-signed `Control.app` (the `UNUserNotificationCenter` bundle-id gate above);
  the heads-up banner, tap-raises-Control, authorization prompt, and
  arrived/resolved replace/cancel are verifiable only on a signed build. In dev
  the **Needs action** popover section is the observable surface.

## Deferred to 1D / Phase 4

LaunchAgent autostart + `LSUIElement` in the packaged `.app` Info.plist;
embedding/signing the agent inside `Control.app`.
