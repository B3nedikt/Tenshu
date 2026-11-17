// The hosted app's appearance boot script (`appearance.ts`), apart from it: the shared Vite preset
// (`vite.ts`) imports this under plain Node, so it has no imports and no `import.meta`.

export const FRAME_NAME_PREFIX = 'tenshu-appearance:'
export const MAX_CHROMA = 0.16
/** Where the preset serves the script; first in every app's <head>. */
export const APPEARANCE_BOOT_PATH = '/appearance-boot.js'

/**
 * `appearance-boot.js`: the same parse and apply as above, as a dependency-free classic script the
 * shared Vite preset serves and puts first in <head> (`vite.ts`). Kept a literal string so no
 * bundler changes what ships; appearance.test.ts evaluates it.
 */
export const APPEARANCE_BOOT_SCRIPT =
  `(function(){try{var n=window.name||'';var p=${JSON.stringify(FRAME_NAME_PREFIX)};` +
  `if(n.indexOf(p)!==0)return;var a=JSON.parse(n.slice(p.length));var d=document.documentElement;` +
  `if(a.mode==='light'||a.mode==='dark')d.setAttribute('data-theme',a.mode);` +
  `var m=/^h(\\d{1,3})c(\\d{1,3})$/.exec(typeof a.tint==='string'?a.tint:'');` +
  `if(m){d.setAttribute('data-tint','');d.style.setProperty('--tint-h',String(Number(m[1])%360));` +
  `d.style.setProperty('--tint-c',String(Math.min(Number(m[2])/1000,${MAX_CHROMA})))}` +
  `if(typeof a.language==='string'&&/^[a-z]{2}/.test(a.language))d.lang=a.language}catch(e){}})();`
