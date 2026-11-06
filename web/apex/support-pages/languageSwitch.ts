// The site's language choice, shared by the SPA's landing page and the static support pages: the
// storage key and the inline script that records a pick on a never-hydrated page. Like the theme
// (themeSwitch.ts, @tenshu/web-tokens/theme) the choice is a per-viewer convenience: a link
// carries the language in its URL (`/de`, `/apps/<app>/support/<platform>/de`), and the stored
// value only decides what an address WITHOUT a language shows — the landing page at `/` (the
// SPA's LanguageDetector, src/i18n.ts) and `/apps/<app>/support` (generate.ts's redirect page).
//
// ⚠ Pure: no imports, no Node or DOM types. Plain Node runs it for the support-page build, and the
// SPA imports the key from here (src/landing/languages.ts).

/** ⚠ Per origin, like THEME_STORAGE_KEY: the SPA and the support pages share it. */
export const LANGUAGE_STORAGE_KEY = 'tenshu.lang'

/** Links that record their language when followed carry it here (`data-lang-choice="de"`). */
export const LANGUAGE_CHOICE_ATTRIBUTE = 'data-lang-choice'

/**
 * Stores the language of a followed `[data-lang-choice]` link: either language picker (the
 * footer's, or the one under a support page's title). try/catch because blocked storage throws;
 * the link still works, it is only not remembered. A literal string so no bundler or
 * type-stripper changes what ships.
 */
export const LANGUAGE_SWITCH_SCRIPT =
  `document.addEventListener('click',function(e){var a=e.target&&e.target.closest&&` +
  `e.target.closest('a[${LANGUAGE_CHOICE_ATTRIBUTE}]');if(!a)return;` +
  `try{localStorage.setItem(${JSON.stringify(LANGUAGE_STORAGE_KEY)},a.getAttribute('${LANGUAGE_CHOICE_ATTRIBUTE}'))}catch(_){}});`
