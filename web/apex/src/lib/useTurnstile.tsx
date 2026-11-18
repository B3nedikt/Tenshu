// The Turnstile hook for one form — see lib/turnstileScript.ts for what Turnstile is doing here
// (commerce D16) and when it is on.
//
// ⚠ A token is single-use and expires after 300 s. Call `consume()` after EVERY request that sent
// one, whatever the answer, so the next attempt (a resend, a retry) carries a fresh one.
import { useCallback, useState, type ReactNode } from 'react'
import { TurnstileWidget } from '../components/TurnstileWidget'
import { turnstileSiteKey } from './turnstileScript'

export interface Turnstile {
  /** The widget to place in the form; null when Turnstile is off. */
  readonly widget: ReactNode
  /** The current token, or null — always null when off. */
  readonly token: string | null
  /** Whether a request may go: off, or a token is in hand. */
  readonly ready: boolean
  /** The script could not be loaded (blocked, offline): no token will come. */
  readonly failed: boolean
  /** Spend the token: clears it and asks the widget for a fresh one. */
  consume(): void
}

/** Turnstile for one form; [action] is reported to Cloudflare's analytics for that form. */
export function useTurnstile(action: string): Turnstile {
  const siteKey = turnstileSiteKey()
  const [token, setToken] = useState<string | null>(null)
  const [generation, setGeneration] = useState(0)
  const [failed, setFailed] = useState(false)
  const consume = useCallback(() => {
    setToken(null)
    setGeneration((g) => g + 1)
  }, [])
  const onLoadError = useCallback(() => setFailed(true), [])
  const widget =
    siteKey === null ? null : (
      <TurnstileWidget siteKey={siteKey} action={action} generation={generation} onToken={setToken} onLoadError={onLoadError} />
    )
  return { widget, token, ready: siteKey === null || token !== null, failed, consume }
}
