# ADR 0020 — Web app structure: apps are own-origin packages, apex hosts them in an iframe

- **Status:** Accepted; first instance implemented (Drive embed, 2026-06-26).
  The reusable `@tenshu/app-kit` layer and the app scaffold are **built** (2026-10-23,
  extracted from Drive and Contacts, web app #2 — web-app-suite.md §3 F3/F4 as-built;
  [`how-to/new-web-app.md`](../how-to/new-web-app.md)). `web/shared` stays as it was;
  the `@tenshu/ui` promotion is still open.
- **Date:** 2026-06-26
- **Owner:** Benedikt
- **Supersedes:** the in-apex `web/apex/src/files/FilesPage.tsx` browser (retired)
- **Superseded by:** —

## Context

The web platform is heading toward a dozen first-party apps and, eventually,
third-party apps ([architecture.md §7](../web/architecture.md)). The
apex/subdomain split and the KMP→JS SDK boundary are already documented and
built; what was **not** written down is the *repeatable structure* a new app
should follow so a dozen of them stay uniform without bending the apex into the
same mould.

The trigger: the apex shipped its own simple file browser at
`tenshu.app/drive/files` (`FilesPage.tsx`) during the Phase-A bridge bring-up.
Phase 8 then delivered the **real** Drive app as a separate workspace package
(`web/drive` → `drive.tenshu.app`) at full feature parity. That left two Drive
implementations: the rich standalone app and the stripped-down embedded browser
that never received any of its features (no preview, no row menu, no recent, no
settings). A maintainer testing the apex-hosted Drive correctly noticed the
embedded one was "missing" everything.

The docs already anticipated this: the embedded→subdomain move was specced as a
"pure prefix-strip" with `FilesPage` as explicitly legacy
([web-control-plane.md §5](../proposals/web-control-plane.md)), and the open
question of whether a subdomain app should wear apex chrome or its own was left
as an open item (§9), with the note that *"UX-wise the consistent shell is
better."*

## Decision

### 1. An app is a separate, own-origin workspace package — not code inside apex

Every Tenshu web app is its own pnpm workspace member under `web/<app>/`,
running on its **own origin** (`<app>.tenshu.app`; a Vite dev server locally).
This is not a style preference — Same-Origin Policy is the only real trust
boundary in a browser ([architecture.md §3.1](../web/architecture.md)), so
own-origin is what buys per-app storage, service worker, and isolation. The app
reaches the in-process runtime **only** through the apex postMessage bridge.

apex is the **specialised host**, deliberately *not* an instance of this
structure: it owns the runtime, the gateway, the launcher, login/devices/
recovery. Apps are uniform; apex is singular.

The per-app contract (the "default structure"):

| Rule | Why |
|------|-----|
| pnpm workspace package in `web/<app>/`, own Vite + Tailwind + Router + TanStack Query | uniform stack ([architecture.md §12.8](../web/architecture.md)) |
| runs on its own origin / subdomain | SOP isolation (§3.1) |
| imports **types only** from `@tenshu/sdk` — never a value | a value import pulls the whole Kotlin/JS runtime, ~230 KB → ~1.7 MB ([drive-web-app.md §7](../proposals/drive-web-app.md)) |
| dials the runtime via `@tenshu/sdk/bridge` (`mountApexProxy`) | apps have no direct IO; apex mediates against the app's manifest permissions (§5.2) |
| ships a `manifest.webmanifest` with `tenshu.*` fields | how apex verifies + launches the app (§6) |
| React owns state; the SDK exposes *operations*, not VMs/repos | thin-SDK decision, House Rule #5 ([drive-web-app.md §2](../proposals/drive-web-app.md)) |

### 2. apex hosts an app by embedding its origin in an iframe inside apex chrome

When an app is opened from the apex launcher, apex renders the app's origin in
an `<iframe>` within the apex shell (top bar + launcher), rather than navigating
away or re-implementing the app. This is the documented "consistent shell"
answer to web-control-plane.md §9. The embedded app dials back to the **same**
apex origin for the bridge, so the session is shared — no second sign-in, no
duplicated runtime state.

The iframe is the right primitive for three reasons that compound:
- it keeps **one** implementation of each app (no in-apex fork to drift);
- it preserves the apex chrome/launcher for a consistent feel;
- it is the **same mechanism third-party apps will use** once Rei is operational
  ([architecture.md §7](../web/architecture.md)) — third-party embeds add a
  `sandbox` attribute + Rei verification on top. First-party apps are trusted,
  so no `sandbox` today.

First instance: `web/apex/src/drive/DriveEmbed.tsx` (since replaced by the generic
[`HostedAppEmbed`](../../web/apex/src/apps/HostedAppEmbed.tsx) over Drive's registry entry,
[`apps/registry/apps/drive.ts`](../../web/apex/src/apps/registry/apps/drive.ts)) embeds
`VITE_DRIVE_ORIGIN` (`drive.tenshu.app` / `:5174`) at the `/drive/files/*` route inside
`ControlShell`. The embedded origin must be on the bridge gateway's allowlist — the same
registry entry supplies both, so they cannot disagree.

### 3. The legacy in-apex `FilesPage` is retired

Removed `web/apex/src/files/FilesPage.tsx`; `/drive/files/*` now renders
`DriveEmbed`. The `/files/*` → `/drive/files/*` redirect and the `drive` →
`/drive/files` redirect are kept for back-compat. ⚠ *Both removed at the 1.0.1
re-cut (2026-11): no build had reached a user, so no bookmark needs them.*

## Not building yet (decided in principle)

> **Update 2026-10-23 — built** (web-app-suite.md Wave 1, run B2), as decided below:
> `web/app-kit` (`@tenshu/app-kit`: bridge bootstrap, lease/sync banners, error boundary,
> live-stream hooks, query-key factory, i18n setup, the Vite/ESLint/tsconfig/Vitest presets,
> manifest generation) and `scripts/new-web-app.sh` over `web/app-kit/template/`. Drive and
> Contacts run on it. The apex still does not consume it. The per-app `DriveEmbed` is now the
> generic `HostedAppEmbed` over one registry file per app
> (`web/apex/src/apps/registry/apps/<id>.ts`), which also carries the app's gateway allowlist.
> `web/shared/` (the third bullet) already existed and was left in place.

The reusable layer that makes apps "structured similarly" is the right idea but
should land with **app #2**, not speculatively (avoids a one-consumer
abstraction):

- **`@tenshu/app-kit`** — a workspace package every app depends on for the
  boilerplate: bridge bootstrap, session/auth guard, the Tailwind preset, a
  manifest helper, and the small amount of apex-chrome integration an embedded
  app needs. The apex does **not** consume it (it is the host, not an app).
- **App scaffold** (`create-tenshu-app` or a `templates/app/` directory) — Vite
  config + `manifest.webmanifest` + `main.tsx` bridge-mount + a starter shell,
  so a new app starts from the contract above instead of copy-pasting `web/drive`.
- **`web/shared/`** — shared React/UI components, per the §12.8 "extract when
  there are ≥2 apps" note.

When app #2 starts, extract `@tenshu/app-kit` from the delta between it and
`web/drive`, then retrofit `web/drive` onto it.

## Consequences

- **Single Drive implementation.** Bug fixes and features in `web/drive` now show
  up everywhere Drive appears, including inside apex. No more "embedded is missing
  X."
- **Apps and apex have different shapes, on purpose.** New contributors should
  read apex as the host, not as a template. The per-app contract above is the
  template.
- **Nested bridge iframes.** apex(top) → iframe app(`:5174`) → app's own hidden
  apex-bridge iframe(`:5173`). The inner apex is the same origin as the top, so
  it shares the session in storage — this works, but it means two apex documents
  load when an app is embedded. Acceptable; flagged so it isn't a surprise.
- **Deep-linking into an embedded app is not wired.** `/drive/files/<path>` no
  longer reflects into the embedded app's in-app navigation (the standalone Drive
  app uses internal state, not URL routes, for folders). Back-compat redirects
  still resolve to the embed root. A postMessage path-sync is a future nicety if
  deep links matter.
- **Third-party groundwork.** The iframe-host pattern is the foundation the
  Rei-gated third-party story builds on; this ADR is the first-party half.

## References

- [architecture.md §3 (apex/subdomain), §6 (manifest), §7 (third-party), §12.8 (layout)](../web/architecture.md)
- [drive-web-app.md §2 (thin SDK), §7 (types-only import)](../proposals/drive-web-app.md)
- [web-control-plane.md §5 (FilesPage legacy), §9 (consistent shell open item)](../proposals/web-control-plane.md)
- First implementation: `web/apex/src/drive/DriveEmbed.tsx` (now `web/apex/src/apps/HostedAppEmbed.tsx` + `apps/registry/`), `web/apex/src/App.tsx`
