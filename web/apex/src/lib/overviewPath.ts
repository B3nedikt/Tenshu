// Where a signed-in user lands: the apex overview (docs/proposals/web-apex-home.md §3.1) — after
// onboarding, after unlock, after recovery, from the landing page's "Open Tenshu", and from the
// brand in every top bar.
//
// ⚠⚠ NOT `/home`, and never labelled "Home" in the UI: `/home` is the Home APP's route (registry
// `apps/home.ts`, `routePath: 'home/*'`), and "Home" is that app's name. The overview is labelled
// with the brand, "Tenshu".
//
// ⚠ A plain constant with no imports: the public half of the apex (the landing CTA) reads it, and
// nothing that half imports may reach a `@tenshu/sdk/*` value (App.tsx, web-app-suite.md F6).
export const OVERVIEW_PATH = '/overview'

// The overview with its "Your apps" dialog open (web-apex-home.md §3.5) — a route, so the launcher
// can open it from inside any app and it survives a reload.
export const YOUR_APPS_PATH = '/overview/apps'

// The overview in Customize mode (web-apex-home.md §3.5.1) — the only place its layout changes. A
// route, so the top bar (AppShell) knows to swap itself for the Customize bar.
export const CUSTOMIZE_PATH = '/overview/customize'

// Control's own entry: its notification feed, where Control opened before the overview (§3.4).
export const CONTROL_PATH = '/control'

/**
 * Where to go after a successful unlock: the `next` path the session guard sent the user away
 * from (`shell/SessionLayout.tsx` → `/lock?next=…`), else the overview.
 *
 * ⚠⚠ `next` arrives in the URL, so anyone can craft it: only a same-origin absolute PATH is
 * accepted. `//evil.example` and `/\evil.example` are protocol-relative to a browser, and anything
 * not starting with `/` (`https://…`, `javascript:…`) is refused outright.
 */
export function postUnlockPath(next: string | null): string {
  if (next === null || !next.startsWith('/')) return OVERVIEW_PATH
  if (next.startsWith('//') || next.startsWith('/\\')) return OVERVIEW_PATH
  if (next === '/lock' || next.startsWith('/lock?') || next.startsWith('/lock/')) return OVERVIEW_PATH
  return next
}
