// @tenshu/web-tokens/theme — the opt-in Light / Dark / Auto choice over tokens.css.
//
// tokens.css already honours `<html data-theme="light|dark">` over `prefers-color-scheme`, and
// without the attribute the media query decides. This module is the other half: where the choice
// is stored and the tiny script that applies it before first paint. A page opts in by putting
// THEME_BOOT_SCRIPT in its <head>; a page that never does (web Drive today) never gets the
// attribute and keeps following the OS exactly as before.
//
// ⚠ Pure: no DOM types, no imports. The apex support-page generator runs it under plain Node
// (web/apex/support-pages/generate.ts) and the SPA imports it through the package export.

export type ThemeChoice = 'light' | 'dark' | 'auto'

export const THEME_CHOICES: readonly ThemeChoice[] = ['light', 'dark', 'auto']

/**
 * The localStorage key. ⚠ Per origin: apex's SPA and its static support pages share it (Caddy
 * serves both from one origin), so a choice made on one holds on the other.
 */
export const THEME_STORAGE_KEY = 'tenshu.theme'

/** A stored value as a choice; anything unknown, absent or unreadable is Auto. */
export function parseThemeChoice(value: unknown): ThemeChoice {
  return value === 'light' || value === 'dark' ? value : 'auto'
}

/**
 * Runs inline in <head>, before the stylesheet paints: sets `data-theme` on <html> for an explicit
 * choice and leaves it absent for Auto. try/catch because localStorage throws when storage is
 * blocked (Safari private mode with some settings, sandboxed iframes, a strict cookie policy) —
 * the page then simply follows the OS. Kept as a literal string so no bundler or type-stripper can
 * change what ships; theme.test.ts evaluates the same string.
 */
export const THEME_BOOT_SCRIPT =
  `(function(){try{var t=localStorage.getItem(${JSON.stringify(THEME_STORAGE_KEY)});` +
  `if(t==='light'||t==='dark')document.documentElement.setAttribute('data-theme',t)}catch(e){}})();`
