// The support pages' Light / Dark / Auto switch: the inline script that wires it. The markup is
// the landing footer's own switch in its static form (StaticThemeSwitch, src/theme/ThemeSwitch.tsx:
// `hidden`, id "theme-switch", inputs named "theme"), rendered with the footer. A pure string with
// no Node import, so the SPA's jsdom tests (src/theme/theme.test.ts) can run the exact script
// against that exact markup, and generate.ts inlines it verbatim.
import { THEME_STORAGE_KEY } from '../../shared/theme.ts'

/**
 * Checks the current choice (what THEME_BOOT_SCRIPT put on <html>), then applies and stores a new
 * one on change; Auto removes both the attribute and the key. The same contract as the SPA's
 * src/theme/theme.ts. A literal string so no bundler or type-stripper changes what ships.
 */
export const THEME_SWITCH_SCRIPT =
  `(function(){var f=document.getElementById('theme-switch');if(!f)return;` +
  `var r=document.documentElement,k=${JSON.stringify(THEME_STORAGE_KEY)},c=r.getAttribute('data-theme')||'auto';` +
  `var x=f.querySelectorAll('input[name="theme"]');` +
  `for(var i=0;i<x.length;i++){x[i].checked=x[i].value===c;x[i].addEventListener('change',function(e){` +
  `var v=e.target.value;if(v==='auto')r.removeAttribute('data-theme');else r.setAttribute('data-theme',v);` +
  `try{if(v==='auto')localStorage.removeItem(k);else localStorage.setItem(k,v)}catch(_){}})}` +
  `f.hidden=false})();`
