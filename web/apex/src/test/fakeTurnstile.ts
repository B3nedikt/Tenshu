// A stand-in for Cloudflare's `window.turnstile`, so a test can drive the widget's callbacks
// (lib/useTurnstile.tsx) without loading the real script. With it installed `useTurnstile`
// renders straight away — the loader resolves to `window.turnstile` when it is already there.
import { act } from '@testing-library/react'
import { vi } from 'vitest'

export interface FakeTurnstile {
  /** The options of the last `render`. */
  readonly options: () => Record<string, unknown> | null
  /** How many times the widget was asked for a fresh token. */
  readonly resets: () => number
  /** Cloudflare hands the page a token. */
  solve(token: string): void
}

export function installFakeTurnstile(siteKey = '0x4AAAAAAAtest'): FakeTurnstile {
  vi.stubEnv('VITE_TURNSTILE_SITE_KEY', siteKey)
  let options: Record<string, unknown> | null = null
  let resets = 0
  window.turnstile = {
    render: (_container, opts) => {
      options = opts
      return 'widget-1'
    },
    reset: () => {
      resets += 1
    },
    remove: () => {},
  }
  return {
    options: () => options,
    resets: () => resets,
    solve(token) {
      const callback = options?.callback as ((value: string) => void) | undefined
      if (!callback) throw new Error('fake turnstile: the widget was never rendered')
      act(() => callback(token))
    },
  }
}

/** Undo [installFakeTurnstile]. */
export function uninstallFakeTurnstile(): void {
  delete window.turnstile
  vi.unstubAllEnvs()
}
