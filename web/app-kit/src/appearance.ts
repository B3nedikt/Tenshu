// The apex's appearance in a hosted app (docs/proposals/web-apex-home.md §4.15): mode, language
// and background tint, so a framed app matches the overview around it.
//
// Two channels, because neither alone is enough:
// - at OPEN, the frame's `name` (`tenshu-appearance:{…}`) — synchronous, before any script of the
//   app's own runs, so `appearance-boot.js` (a same-origin classic script in <head>: the app CSP is
//   `script-src 'self'`, no inline) paints the right mode and tint with no flash. A frame name is
//   never sent to any server and survives the app's own `replaceState`s.
// - LATER, the `tenshu.appearance` message from the apex origin, whenever the user changes one.
//
// Opened on its own (not framed, or framed by anything else), an app has neither and follows the
// OS and the browser's languages, as before. ⚠ Only the apex origin is listened to.
// ⚠ The message shape is duplicated in the apex (`web/apex/src/appearance/appAppearance.ts`) — the
// apex does not depend on the app-kit. Change both together.
// ⚠ No i18next here: Drive does not localise and must not bundle it. `i18n.ts` subscribes.
import { configuredApexOrigin } from './bridge'

import { FRAME_NAME_PREFIX, MAX_CHROMA } from './appearanceBoot.ts'

export { APPEARANCE_BOOT_SCRIPT, FRAME_NAME_PREFIX } from './appearanceBoot.ts'
export const APPEARANCE_MESSAGE = 'tenshu.appearance'

export interface AppAppearance {
  readonly mode: 'light' | 'dark' | 'auto'
  /** The language the apex SHOWS (resolved, never "auto"), so both always agree. */
  readonly language: string
  /** The tint token's numeric form (`h195c100`), or null for the brand's own colours. */
  readonly tint: string | null
}

const TINT = /^h(\d{1,3})c(\d{1,3})$/

/** [data] as an appearance, or null when it is not one. Tolerant of unknown extra fields. */
export function readAppearance(data: unknown): AppAppearance | null {
  if (typeof data !== 'object' || data === null) return null
  const d = data as Record<string, unknown>
  const mode = d.mode === 'light' || d.mode === 'dark' || d.mode === 'auto' ? d.mode : null
  if (mode === null) return null
  const language = typeof d.language === 'string' && /^[a-z]{2}(-[A-Z]{2})?$/.test(d.language) ? d.language : ''
  const tint = typeof d.tint === 'string' && TINT.test(d.tint) ? d.tint : null
  return { mode, language, tint }
}

/** The appearance a frame name carries, or null. */
export function appearanceFromFrameName(name: string): AppAppearance | null {
  if (!name.startsWith(FRAME_NAME_PREFIX)) return null
  try {
    return readAppearance(JSON.parse(name.slice(FRAME_NAME_PREFIX.length)))
  } catch {
    return null
  }
}

/** Mode and tint onto <html>; the same attributes the apex's tokens read (tokens.css, tint.css). */
export function applyAppearance(appearance: AppAppearance, root: HTMLElement = document.documentElement): void {
  if (appearance.mode === 'auto') root.removeAttribute('data-theme')
  else root.setAttribute('data-theme', appearance.mode)
  const m = appearance.tint === null ? null : TINT.exec(appearance.tint)
  if (m === null) {
    root.removeAttribute('data-tint')
    root.style.removeProperty('--tint-h')
    root.style.removeProperty('--tint-c')
  } else {
    root.setAttribute('data-tint', '')
    root.style.setProperty('--tint-h', String(Number(m[1]) % 360))
    root.style.setProperty('--tint-c', String(Math.min(Number(m[2]) / 1000, MAX_CHROMA)))
  }
}

let current: AppAppearance | null = typeof window === 'undefined' ? null : appearanceFromFrameName(window.name)
const listeners = new Set<(appearance: AppAppearance) => void>()

/** The apex's appearance as last heard: the frame name at first, then each message. */
export function currentAppearance(): AppAppearance | null {
  return current
}

/** Called on every change the apex sends (not for the opening one: read `currentAppearance`). */
export function subscribeAppearance(listener: (appearance: AppAppearance) => void): () => void {
  listeners.add(listener)
  return () => listeners.delete(listener)
}

/**
 * Applies the opening appearance (again — the boot script already did, unless it was blocked) and
 * follows the apex's messages. Returns the uninstall. `mountApp` calls it.
 */
export function installAppearance(apexOrigin: string = configuredApexOrigin()): () => void {
  if (current !== null) applyAppearance(current)
  const onMessage = (event: MessageEvent) => {
    if (event.origin !== apexOrigin || event.source !== window.parent) return
    const data = event.data as { type?: unknown } | null
    if (data?.type !== APPEARANCE_MESSAGE) return
    const next = readAppearance(data)
    if (next === null) return
    current = next
    applyAppearance(next)
    listeners.forEach(l => l(next))
  }
  window.addEventListener('message', onMessage)
  return () => window.removeEventListener('message', onMessage)
}
