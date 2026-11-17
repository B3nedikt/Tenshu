// The viewer's appearance in this browser (docs/proposals/web-apex-home.md §4.13): light / dark /
// auto and the language. One small store so the overview's edit bar, Settings and the hosted-app
// bridge (W4) all follow the same value and re-render when it changes.
//
// ⚠ Per BROWSER, on purpose, and in localStorage only — the same keys the marketing footer already
// writes (`tenshu.theme`, `tenshu.lang`), so a pick made on the landing page holds here and the
// reverse. Not a DEVICE preference: a browser's preference row lives in the same origin's storage,
// so it would be cleared with it and adds nothing, and the boot script must read it before the
// runtime exists (no flash of the wrong mode).
import { useSyncExternalStore } from 'react'
import i18n from 'i18next'
import { LANGUAGE_STORAGE_KEY } from '../landing/languages'
import { applyThemeChoice, readThemeChoice } from '../theme/theme'
import type { ThemeChoice } from '@tenshu/web-tokens/theme'
import { supportedLanguages } from '../i18n'

/** `auto` = the browser's own list decides. */
export type LanguageChoice = 'auto' | (typeof supportedLanguages)[number]

export interface Appearance {
  readonly mode: ThemeChoice
  readonly language: LanguageChoice
}

const listeners = new Set<() => void>()
let snapshot: Appearance = read()

function read(): Appearance {
  return { mode: readThemeChoice(), language: readLanguage() }
}

function readLanguage(): LanguageChoice {
  try {
    const stored = window.localStorage.getItem(LANGUAGE_STORAGE_KEY)
    return (supportedLanguages as readonly string[]).includes(stored ?? '') ? (stored as LanguageChoice) : 'auto'
  } catch {
    return 'auto'
  }
}

// ⚠ The value just SET, not a re-read: with storage blocked a re-read would say "auto" and the
// controls (and the hosted apps) would disagree with the page they are on.
function publish(next: Appearance) {
  snapshot = next
  listeners.forEach(l => l())
}

export function subscribeAppearance(listener: () => void): () => void {
  listeners.add(listener)
  return () => listeners.delete(listener)
}

export function getAppearance(): Appearance {
  return snapshot
}

export function useAppearance(): Appearance {
  return useSyncExternalStore(subscribeAppearance, getAppearance, getAppearance)
}

export function setMode(mode: ThemeChoice): void {
  applyThemeChoice(mode)
  publish({ ...snapshot, mode })
}

/** Stores the pick and switches at once; `auto` forgets it and re-detects from the browser. */
export function setLanguage(language: LanguageChoice): void {
  try {
    if (language === 'auto') window.localStorage.removeItem(LANGUAGE_STORAGE_KEY)
    else window.localStorage.setItem(LANGUAGE_STORAGE_KEY, language)
  } catch {
    // Blocked storage: the switch still holds for this page.
  }
  // No argument re-runs detection (the stored pick first, then the browser's list).
  void i18n.changeLanguage(language === 'auto' ? undefined : language)
  publish({ ...snapshot, language })
}

// Another tab of this origin changed it: follow, so two open tabs never disagree for long.
if (typeof window !== 'undefined') {
  window.addEventListener('storage', e => {
    if (e.key !== null && e.key !== LANGUAGE_STORAGE_KEY && !e.key.startsWith('tenshu.theme')) return
    const before = snapshot
    publish(read())
    if (snapshot.mode !== before.mode) applyThemeChoice(snapshot.mode)
    if (snapshot.language !== before.language) void i18n.changeLanguage(snapshot.language === 'auto' ? undefined : snapshot.language)
  })
}
