// The Turnstile widget itself: renders Cloudflare's challenge into a div and reports its token.
// Used through `useTurnstile` (lib/useTurnstile.tsx), which owns the token.
import { useEffect, useRef } from 'react'
import { loadTurnstile } from '../lib/turnstileScript'

export function TurnstileWidget({
  siteKey,
  action,
  generation,
  onToken,
  onLoadError,
}: {
  readonly siteKey: string
  readonly action: string
  readonly generation: number
  readonly onToken: (token: string | null) => void
  readonly onLoadError: () => void
}) {
  const container = useRef<HTMLDivElement>(null)
  const widgetId = useRef<string | null>(null)

  useEffect(() => {
    let cancelled = false
    loadTurnstile().then(
      (api) => {
        if (cancelled || container.current === null) return
        widgetId.current = api.render(container.current, {
          sitekey: siteKey,
          action,
          theme: 'auto',
          language: 'auto',
          callback: (value: string) => onToken(value),
          'expired-callback': () => onToken(null),
          // Returning nothing lets the widget retry on its own; the request just waits for a token.
          'error-callback': () => onToken(null),
        })
      },
      () => {
        if (!cancelled) onLoadError()
      },
    )
    return () => {
      cancelled = true
      if (widgetId.current !== null) window.turnstile?.remove(widgetId.current)
      widgetId.current = null
    }
  }, [siteKey, action, onToken, onLoadError])

  useEffect(() => {
    if (generation > 0 && widgetId.current !== null) window.turnstile?.reset(widgetId.current)
  }, [generation])

  return <div ref={container} data-testid="turnstile" className="min-h-[65px]" />
}
