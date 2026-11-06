// The landing page's runtime-free session probe (docs/web/architecture.md "Cross-tab").
//
// ⚠ A public page must not start the runtime (that takes the `tenshu-runtime` lock), so it cannot
// ask `Session.detect()`. The runtime mirrors detect()'s inputs into localStorage
// (`SessionHint.kt`, key below) whenever the session store changes or is read; this reads them back.
// A hint picks a CTA label only — following it reaches a route that starts the runtime and asks
// the real question — so a stale hint costs one wrong label, never access.
import type { SessionState } from '@tenshu/sdk/onboarding'

export const SESSION_HINT_KEY = 'tenshu.session.hint'

export function readSessionHint(now: number = Date.now()): SessionState {
  try {
    const storage = window.localStorage
    // jsdom (and some locked-down browsers) hand back a Storage-shaped object without methods.
    if (typeof storage?.getItem !== 'function') return { kind: 'firstTime' }
    const raw = storage.getItem(SESSION_HINT_KEY)
    if (!raw) return { kind: 'firstTime' }
    const hint = JSON.parse(raw) as { deviceName?: unknown; expiresAtMs?: unknown }
    if (typeof hint.deviceName !== 'string') return { kind: 'firstTime' }
    const expiresAtMs = typeof hint.expiresAtMs === 'number' ? hint.expiresAtMs : 0
    return expiresAtMs > now
      ? { kind: 'active', deviceName: hint.deviceName }
      : { kind: 'locked', deviceName: hint.deviceName }
  } catch {
    return { kind: 'firstTime' }
  }
}

export const peekSession = (): Promise<SessionState> => Promise.resolve(readSessionHint())
