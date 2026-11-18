// Cloudflare Turnstile on the two web forms that make commerce send mail — sign-up/sign-in
// (FreeAccountSignupPage) and the public account-deletion page. Why, and why only the web:
// `commerce-accounts-and-sessions.md` D16. Keys: docs/how-to/set-up-otp-email.md §8.
//
// Off unless the build sets `VITE_TURNSTILE_SITE_KEY`: no script is loaded, no widget renders, and
// no token is sent — so a local build, vitest and a deployment without keys behave exactly as
// before. ⚠⚠ Set it only together with commerce's `TURNSTILE_SECRET_KEY`: a site key without the
// secret just costs a widget; a secret without the site key refuses every web sign-up.
//
// ⚠ A token is single-use and expires after 300 s. Call `consume()` after EVERY request that sent
// one, whatever the answer, so the next attempt (a resend, a retry) carries a fresh one.
//
// ⚠ Plain DOM + the Cloudflare script: the deletion page is on the PUBLIC half of the apex and
// must not pull in `@tenshu/sdk` (BundleSplit.test.ts).
/** The script origin the CSP names (csp/csp.ts TURNSTILE_ORIGIN; origins.test.ts pins the two). */
export const TURNSTILE_SCRIPT_URL = 'https://challenges.cloudflare.com/turnstile/v0/api.js?render=explicit'

export interface TurnstileApi {
  render(container: HTMLElement, options: Record<string, unknown>): string
  reset(widgetId: string): void
  remove(widgetId: string): void
}

declare global {
  interface Window {
    turnstile?: TurnstileApi
  }
}

/**
 * The build's site key, or null when Turnstile is off. A function, not a module constant, so
 * `vi.stubEnv` can drive it in tests (the signupGate.ts pattern).
 */
export function turnstileSiteKey(): string | null {
  const key = (import.meta.env.VITE_TURNSTILE_SITE_KEY ?? '').trim()
  return key === '' ? null : key
}

let loading: Promise<TurnstileApi> | null = null

/** The Turnstile API, loading Cloudflare's script on first use (once per page). */
export function loadTurnstile(): Promise<TurnstileApi> {
  if (window.turnstile) return Promise.resolve(window.turnstile)
  loading ??= new Promise<TurnstileApi>((resolve, reject) => {
    const script = document.createElement('script')
    script.src = TURNSTILE_SCRIPT_URL
    script.async = true
    script.onload = () => (window.turnstile ? resolve(window.turnstile) : reject(new Error('turnstile: no API')))
    script.onerror = () => reject(new Error('turnstile: script failed to load'))
    document.head.appendChild(script)
  }).catch((failure: unknown) => {
    // Let a later mount try again (a blocked network that came back) rather than caching the miss.
    loading = null
    throw failure
  })
  return loading
}
