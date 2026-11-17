// The overview's background colour (docs/proposals/web-apex-home.md §4.14): a REALM preference —
// it follows the user to every browser — mirrored into localStorage so the boot script can paint
// it before the runtime exists (`@tenshu/web-tokens/tint`).
//
// ⚠ The stored list is the truth and the mirror only a cache: a ready read always overwrites the
// mirror (another browser's change lands on the next load here), an unreadable one leaves it.
import { useSyncExternalStore } from 'react'
import { DEFAULT_TINT_PRESET, tintMirrorValue, TINT_STORAGE_KEY, type Tint } from '@tenshu/web-tokens/tint'
import { useBackgroundState } from '../overview/shownAppsContext'

/** Applies [tint] to <html> (null = the brand's own tokens) and updates the boot mirror. */
export function applyTint(tint: Tint | null, root: HTMLElement = document.documentElement): void {
  if (tint === null) {
    root.removeAttribute('data-tint')
    root.style.removeProperty('--tint-h')
    root.style.removeProperty('--tint-c')
  } else {
    root.setAttribute('data-tint', '')
    root.style.setProperty('--tint-h', String(Math.round(tint.hue * 10) / 10))
    root.style.setProperty('--tint-c', String(Math.round(tint.chroma * 1000) / 1000))
  }
  try {
    const value = tintMirrorValue(tint)
    if (value === null) window.localStorage.removeItem(TINT_STORAGE_KEY)
    else window.localStorage.setItem(TINT_STORAGE_KEY, value)
  } catch {
    // Blocked storage: the colour holds for this page, the next load paints the default first.
  }
  current = tint
  listeners.forEach(l => l())
}

// The tint drawn right now — what the hosted-app bridge forwards (W4), whatever set it.
let current: Tint | null = readRoot()
const listeners = new Set<() => void>()

function readRoot(): Tint | null {
  if (typeof document === 'undefined' || !document.documentElement.hasAttribute('data-tint')) return null
  const style = document.documentElement.style
  const hue = Number(style.getPropertyValue('--tint-h'))
  const chroma = Number(style.getPropertyValue('--tint-c'))
  return Number.isFinite(hue) && Number.isFinite(chroma) ? { hue, chroma } : null
}

export function getTint(): Tint | null {
  return current
}

export function subscribeTint(listener: () => void): () => void {
  listeners.add(listener)
  return () => listeners.delete(listener)
}

export function useCurrentTint(): Tint | null {
  return useSyncExternalStore(subscribeTint, getTint, getTint)
}

export interface BackgroundEditor {
  /** The stored token (`teal`, `h212c094`), `''` = default; null while not ready. */
  readonly token: string | null
  readonly pending: boolean
  /** Stores [token] (`''` = back to the default). False when it failed or nothing is loaded. */
  readonly choose: (token: string) => Promise<boolean>
}

export function useBackground(): BackgroundEditor {
  const { state, pending, apply } = useBackgroundState()
  return {
    token: state.kind === 'ready' ? (state.stored[0] ?? '') : null,
    pending,
    // The default (blue) is stored as nothing: a realm that never picked and one that picked it agree.
    choose: token => apply(token === '' || token === DEFAULT_TINT_PRESET ? [] : [token]),
  }
}

