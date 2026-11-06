// The SPA's side of the Light / Dark / Auto switch (@tenshu/web-tokens/theme). The head script
// (THEME_BOOT_SCRIPT, injected into index.html by vite.config.ts) applies the stored choice before
// first paint; these helpers read and change it afterwards. The static support pages carry the
// same behaviour as an inline script (support-pages/generate.ts) — same key, same attribute.
import { THEME_STORAGE_KEY, parseThemeChoice, type ThemeChoice } from '@tenshu/web-tokens/theme'

/** The stored choice; Auto when nothing is stored or storage is unreadable. */
export function readThemeChoice(storage: Pick<Storage, 'getItem'> | undefined = safeLocalStorage()): ThemeChoice {
  try {
    return parseThemeChoice(storage?.getItem(THEME_STORAGE_KEY))
  } catch {
    return 'auto'
  }
}

/**
 * Applies [choice] to <html> and stores it. Auto removes both the attribute and the key, so the
 * tokens' media query decides again. A storage failure still applies the choice for this page.
 */
export function applyThemeChoice(
  choice: ThemeChoice,
  root: HTMLElement = document.documentElement,
  storage: Pick<Storage, 'setItem' | 'removeItem'> | undefined = safeLocalStorage(),
): void {
  if (choice === 'auto') root.removeAttribute('data-theme')
  else root.setAttribute('data-theme', choice)
  try {
    if (choice === 'auto') storage?.removeItem(THEME_STORAGE_KEY)
    else storage?.setItem(THEME_STORAGE_KEY, choice)
  } catch {
    // Blocked storage: the choice holds until the page is left.
  }
}

function safeLocalStorage(): Storage | undefined {
  try {
    return window.localStorage
  } catch {
    return undefined
  }
}
