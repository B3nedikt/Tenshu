// The support hub, `/apps/support/<lang>`, and the Getting started page,
// `/apps/support/getting-started/<lang>` — store-presence-and-support-pages.md §4.7.
//
// What lives here: the hub's data (which apps it lists, which of them have support pages, which
// screenshots represent each), its copy per language, and the inline script that turns the static
// list into the carousel. generate.ts renders the pages from it.
//
// ⚠ `/support` is deliberately NOT generated and nothing redirects from it: it is reserved for a
// possible future support app (maintainer, 2026-10-16). The hub sits at `/apps/support`.
//
// ⚠ Pure: no imports. Plain Node runs it for the support-page build.

export interface HubApp {
  /** The app's id: `content/apps/<app>/support/`, `store/<app>/`, `/apps/<app>/support`. */
  readonly app: string
  /** The short name the landing page uses ("Drive"), which the breadcrumb shows too. */
  readonly name: string
  /**
   * ⚠ The one-line switch: `true` once `content/apps/<app>/support/` exists. The build fails when
   * this and the content disagree, so the hub can neither link a page that is not there nor hide
   * one that is. `false` shows the app greyed out, "Coming soon", with no link.
   */
  readonly support: boolean
  /**
   * The captures the hub shows for the app, per platform (`ios/iphone69/…`, `android/phone/…`), in
   * carousel order; the carousel's "iPhone | Android" switch picks the set. A platform with none
   * published shows the other's, with the switch disabled; neither shows a placeholder card.
   */
  readonly screens: { readonly android: readonly string[]; readonly ios: readonly string[] }
}

const NO_SCREENS = { android: [], ios: [] } as const

/** Every first-party app, in the order the hub lists them (maintainer, 2026-10-16). */
export const HUB_APPS: readonly HubApp[] = [
  {
    app: 'control',
    name: 'Control',
    support: true,
    screens: { android: ['01-welcome', '06-settings', '09-add-device'], ios: ['01-welcome', '06-settings', '09-add-device'] },
  },
  { app: 'drive', name: 'Drive', support: true, screens: { android: ['01-files', '02-photos', '03-preview'], ios: ['01-files', '02-photos', '03-preview'] } },
  // iPhone only (store/photos/screenshots.manifest.json has no android/phone set): the hub's Android switch stays disabled for Photos.
  { app: 'photos', name: 'Photos', support: true, screens: { android: [], ios: ['01-library', '03-album', '04-viewer'] } },
  { app: 'maps', name: 'Maps', support: false, screens: NO_SCREENS },
  { app: 'timeline', name: 'Timeline', support: false, screens: NO_SCREENS },
  { app: 'calendar', name: 'Calendar', support: true, screens: { android: ['01-month', '07-week', '02-agenda'], ios: ['01-month', '02-agenda', '03-event'] } },
  { app: 'contacts', name: 'Contacts', support: true, screens: { android: ['01-contacts', '02-contact', '04-duplicates'], ios: ['01-contacts', '02-contact', '04-duplicates'] } },
  { app: 'fit', name: 'Fit', support: true, screens: { android: ['01-home', '03-workout', '06-sleep'], ios: ['01-home', '03-workout', '06-sleep'] } },
  { app: 'home', name: 'Home', support: false, screens: NO_SCREENS },
  { app: 'mia', name: 'Mia', support: false, screens: NO_SCREENS },
]

/**
 * Apps with an icon under public/icons/apps/ (scripts/web-app-icons.py, from each iOS asset
 * catalogue), light and dark. Both carousels put it beside the app's name (the landing page also on
 * its card); an app missing from this list gets a neutral tile with its initial instead.
 */
export const APP_ICON_IDS: readonly string[] = ['control', 'drive', 'photos', 'maps', 'timeline', 'calendar', 'contacts', 'fit', 'home', 'mia']

/** An app's icon as site paths (carouselModel.ts CarouselIcon), or null for the neutral tile. */
export function appIcon(app: string): { readonly light: string; readonly dark: string } | null {
  return APP_ICON_IDS.includes(app) ? { light: `/icons/apps/${app}.webp`, dark: `/icons/apps/${app}-dark.webp` } : null
}

/** The Getting started entry's icon in the hub's list: Tenshu's own mark, one file for both themes. */
export const GETTING_STARTED_ICON = { light: '/icons/tenshu.svg', dark: '/icons/tenshu.svg' } as const

/**
 * The platform whose captures the carousels show without JavaScript (and the landing page's
 * fallback device map's default). With JavaScript the visitor's switch decides, defaulting by OS
 * (defaultShotPlatform). The link under the hub's phone still goes to the visitor's own platform
 * (the hub script).
 */
export const HUB_SHOT_PLATFORM = 'android'

/**
 * ⚠ THE rule for which device the carousels show first, before the visitor has used the switch:
 * an iPhone on macOS, iOS and iPadOS (iPadOS reports a Mac user agent), an Android phone on
 * everything else — Windows, Linux, Android, ChromeOS. `uaPlatform` is
 * `navigator.userAgentData?.platform` (Chromium; "macOS", "Windows", "Android", "Chrome OS",
 * "Linux"), the user agent string the fallback where it is absent (Safari, Firefox).
 *
 * ⚠ One copy for both carousels: the landing page calls it, and the hub's inline script embeds
 * its SOURCE (`defaultShotPlatform.toString()` in HUB_SCRIPT_BODY). So it stays plain JavaScript
 * — default parameters, no type annotations, no ES2015+ syntax a browser might lack — so that
 * what plain Node's type stripping leaves is exactly what ships. hub.test.ts pins the table.
 */
export function defaultShotPlatform(uaPlatform = '', userAgent = '') {
  if (uaPlatform) return /^(macOS|iOS|iPadOS)$/i.test(uaPlatform) ? 'ios' : 'android'
  return /iPhone|iPad|iPod|Macintosh|Mac OS X/.test(userAgent) ? 'ios' : 'android'
}

/** The Getting started entry's capture: Control's welcome screen, where everyone starts. */
export const GETTING_STARTED_SHOT = { app: 'control', screen: '01-welcome' } as const

/** The id the Getting started entry uses in the hub's markup and script. */
export const GETTING_STARTED_ID = 'getting-started'

export interface HubCopy {
  /** <title> */
  readonly title: string
  readonly description: string
  /** The hub's <h1>. */
  readonly heading: string
  readonly lede: string
  readonly gettingStarted: string
  readonly comingSoon: string
  /** One note under the lists in place of a label on every greyed-out name. */
  readonly soonNote: string
  /** The button under the carousel: "Open Drive support". */
  readonly open: (name: string) => string
  readonly openGettingStarted: string
  /** The aria-label of the breadcrumb <nav>. */
  readonly breadcrumb: string
  /** The aria-label of the hub's lists of apps. */
  readonly apps: string
  /** A carousel dot's label, and its screenshot's: "Screenshot 2 of 3: Drive". */
  readonly screenshot: (index: number, count: number, name: string) => string
  /** The carousel's pause/play button, while its screenshots cycle and while they are stopped. */
  readonly pauseShots: string
  readonly playShots: string
  /** The narrow layout's position indicator, one button per card: "Show Drive". */
  readonly show: (name: string) => string
  /** The "iPhone | Android" switch: its group label, its two options, and the disabled one's note. */
  readonly device: string
  readonly iphone: string
  readonly android: string
  readonly onlyAndroid: string
  readonly onlyIos: string
  /** The line under the carousel: the frames are illustrations; whose trademarks the names are. */
  readonly frameNote: string
}

// ⚠ de and fr are machine-translated, pending native review (reviewed: false, like the pages they
// frame — src/landing/languages.ts SITE_LANGUAGES). That includes `frameNote`, the trademark line.
// "Coming soon" is the landing page's own (locales/<lang>/landing.json `cta.comingSoon`).
export const HUB_COPY: Record<string, HubCopy> = {
  en: {
    title: 'Tenshu support',
    description: 'Help for every Tenshu app: how to get started, how your data stays private, answers to common problems, and how to reach support.',
    heading: 'Support',
    lede: 'New to Tenshu? Begin with Getting started, or pick an app.',
    gettingStarted: 'Getting started',
    comingSoon: 'Coming soon',
    soonNote: 'Greyed-out apps get their help pages soon.',
    open: (name) => `Open ${name} support`,
    openGettingStarted: 'Open Getting started',
    breadcrumb: 'Breadcrumb',
    apps: 'Tenshu apps',
    screenshot: (i, n, name) => `Screenshot ${i} of ${n}: ${name}`,
    pauseShots: 'Pause screenshots',
    playShots: 'Play screenshots',
    show: (name) => `Show ${name}`,
    device: 'Device',
    iphone: 'iPhone',
    android: 'Android',
    onlyAndroid: 'No iPhone screenshots yet',
    onlyIos: 'No Android screenshots yet',
    frameNote: 'Device frames are illustrations. iPhone is a trademark of Apple Inc., Android is a trademark of Google LLC. Tenshu is not affiliated with or endorsed by Apple or Google.',
  },
  de: {
    title: 'Tenshu – Support',
    description: 'Hilfe zu jeder Tenshu-App: wie du loslegst, wie deine Daten privat bleiben, Antworten auf häufige Probleme und wie du den Support erreichst.',
    heading: 'Support',
    lede: 'Neu bei Tenshu? Beginne mit „Erste Schritte“ oder wähle eine App.',
    gettingStarted: 'Erste Schritte',
    comingSoon: 'Bald verfügbar',
    soonNote: 'Ausgegraute Apps bekommen ihre Hilfeseiten bald.',
    open: (name) => `Support für ${name} öffnen`,
    openGettingStarted: 'Erste Schritte öffnen',
    breadcrumb: 'Navigationspfad',
    apps: 'Tenshu-Apps',
    screenshot: (i, n, name) => `Screenshot ${i} von ${n}: ${name}`,
    pauseShots: 'Screenshots anhalten',
    playShots: 'Screenshots abspielen',
    show: (name) => `${name} zeigen`,
    device: 'Gerät',
    iphone: 'iPhone',
    android: 'Android',
    onlyAndroid: 'Noch keine iPhone-Screenshots',
    onlyIos: 'Noch keine Android-Screenshots',
    frameNote: 'Die Geräterahmen sind Illustrationen. iPhone ist eine Marke der Apple Inc., Android ist eine Marke der Google LLC. Tenshu ist weder mit Apple noch mit Google verbunden und wird von ihnen nicht unterstützt.',
  },
  fr: {
    title: 'Assistance Tenshu',
    description: 'De l’aide pour chaque appli Tenshu : comment démarrer, comment vos données restent privées, des réponses aux problèmes courants et comment joindre l’assistance.',
    heading: 'Assistance',
    lede: 'Nouveau sur Tenshu ? Commencez par « Premiers pas », ou choisissez une appli.',
    gettingStarted: 'Premiers pas',
    comingSoon: 'Bientôt disponible',
    soonNote: 'Les applis grisées auront bientôt leurs pages d’aide.',
    open: (name) => `Ouvrir l’assistance ${name}`,
    openGettingStarted: 'Ouvrir Premiers pas',
    breadcrumb: 'Fil d’Ariane',
    apps: 'Applis Tenshu',
    screenshot: (i, n, name) => `Capture ${i} sur ${n} : ${name}`,
    pauseShots: 'Mettre les captures en pause',
    playShots: 'Lire les captures',
    show: (name) => `Afficher ${name}`,
    device: 'Appareil',
    iphone: 'iPhone',
    android: 'Android',
    onlyAndroid: 'Pas encore de captures iPhone',
    onlyIos: 'Pas encore de captures Android',
    frameNote: 'Les cadres d’appareils sont des illustrations. iPhone est une marque d’Apple Inc., Android est une marque de Google LLC. Tenshu n’est ni affilié à Apple ou Google, ni approuvé par eux.',
  },
}

/** The hub's languages, English first (the order the language pickers list them in). */
export const HUB_LANGUAGES = ['en', 'de', 'fr'] as const

export function hubUrl(lang: string): string {
  return `/apps/support/${lang}`
}

/** Where `/apps/support` is: the redirect page that picks a language (generate.ts). */
export const HUB_ENTRY_URL = '/apps/support'

export function gettingStartedUrl(lang: string): string {
  return `/apps/support/getting-started/${lang}`
}

/**
 * The carousel, as browser JavaScript inlined into the hub verbatim — a string, so no bundler or
 * type-stripper changes what ships (like the redirect pages' REDIRECT_LOGIC, which generate.ts
 * puts in front of it for `detect`, together with `cfg`: `{lang, initial, platforms}`).
 *
 * Without it the hub is a plain list: every entry with its screenshots and a real link. With it:
 *   - every panel moves into one stage between the two lists of names, and only the selected one
 *     shows (CSS `.is-enhanced`); all of them stay laid out, so the images are loaded before a
 *     hover swaps them and the stage never changes size. The stage is ONE phone frame, the same
 *     size whatever is selected, so the names never move under the pointer;
 *   - hovering a name, or focusing it with the keyboard, selects it; with a mouse a click then
 *     follows the link;
 *   - a tap (touch or pen) only selects: the panel's button opens the page. A keyboard Enter on a
 *     link (`detail === 0`) follows it;
 *   - a coming-soon name, which has no link, is made focusable so the keyboard reaches it too;
 *   - the selected entry's screenshots cross-fade in the frame every ADV ms (AppCarousel.tsx
 *     ADVANCE_MS), and selecting an entry shows its first and restarts the cycle; the SELECTION
 *     never changes on its own. The dots (static markup, `hidden` until now) pick a screenshot; the
 *     pause/play button stops the cycle (WCAG 2.2.2, the APG Carousel pattern: roledescriptions,
 *     `aria-live` off while rotating, polite when not). It holds while the pointer is over the
 *     phone or the dots, while keyboard focus is in them, while the tab is hidden and while the
 *     stage is offscreen; under reduced motion there is no cycle (and CSS hides the button);
 *   - the "iPhone | Android" switch under the dots (static markup, `hidden` until now) picks which
 *     platform's captures every entry shows, in that platform's frame: the visitor's stored choice
 *     (`tenshu.shotPlatform`), else defaultShotPlatform's rule. An entry with one platform's
 *     captures shows those, its switch disabled with a note (static markup);
 *   - each support link is pointed at the visitor's own platform, if the app has a page for it
 *     (the static href is the app's defaultPlatform);
 *   - the narrow layout's row of cards (`[data-hub-mobile]`, AppCarousel.tsx CardRow; it swipes
 *     and snaps without any of this) gets its "iPhone | Android" switch (the same `plat` as the
 *     stage's) and its indicator: one icon button per card, `aria-current` on the card nearest the
 *     row's centre (read on scroll, once per frame), a tap scrolls to its card; focus in a card
 *     scrolls it to the centre and the arrow keys move to the next or previous card — instantly
 *     under reduced motion.
 *   - once enhanced, the screenshots on screen — the stage's current slide, or the row's first two
 *     cards — load eagerly (`loading="eager"`) if visible: the markup is all lazy, since it cannot
 *     know the layout, the theme or the platform, and a lazy image waits on layout.
 * ⚠ The same behaviour as AppCarousel.tsx's live mode, which the landing page renders: change both.
 */
export const HUB_SCRIPT_BODY = `
var hub=document.querySelector('[data-hub]');if(!hub)return;
var p=detect(navigator.userAgent,navigator.maxTouchPoints||0);
var links=document.querySelectorAll('a[data-hub-app]'),i;
for(i=0;i<links.length;i++){var a=links[i],ps=cfg.platforms[a.getAttribute('data-hub-app')];
if(p&&ps&&ps.indexOf(p)>=0)a.setAttribute('href','/apps/'+a.getAttribute('data-hub-app')+'/support/'+p+'/'+cfg.lang);}
var lists=hub.querySelectorAll('.hub-list'),panels=hub.querySelectorAll('[data-hub-panel]'),names=hub.querySelectorAll('[data-hub-name]'),mob=document.querySelector('[data-hub-mobile]');
if(lists.length!==2)return;
var stage=document.createElement('div');stage.className='hub-stage';
for(i=0;i<panels.length;i++)stage.appendChild(panels[i]);
hub.insertBefore(stage,lists[1]);
var ADV=4000,rm=window.matchMedia?window.matchMedia('(prefers-reduced-motion: reduce)'):null;
var cur=null,slide=0,stopped=false,hover=false,focusIn=false,away=false,timer=0;
${defaultShotPlatform.toString()}
var SPK='tenshu.shotPlatform',plat=null;try{plat=localStorage.getItem(SPK)}catch(e){}
if(plat!=='ios'&&plat!=='android')plat=defaultShotPlatform(navigator.userAgentData&&navigator.userAgentData.platform||'',navigator.userAgent);
function shown(pn){var vs=pn.querySelectorAll('[data-hub-viewer]'),j;for(j=0;j<vs.length;j++)if(vs[j].getAttribute('data-hub-platform')===plat)return vs[j];return vs[0]||null}
function viewer(id){var pn=stage.querySelector('[data-hub-panel="'+id+'"]');return pn?shown(pn):null}
function platform(){var j,k;hub.setAttribute('data-shot-platform',plat);
for(j=0;j<panels.length;j++){var s=shown(panels[j]),vs=panels[j].querySelectorAll('[data-hub-viewer]'),on=s&&s.getAttribute('data-hub-platform'),b=panels[j].querySelectorAll('[data-hub-pick]');
for(k=0;k<vs.length;k++)vs[k].classList.toggle('is-shown',vs[k]===s);
for(k=0;k<b.length;k++)b[k].setAttribute('aria-pressed',String(b[k].getAttribute('data-hub-pick')===(on||plat)));}
if(mob){mob.setAttribute('data-shot-platform',plat);b=mob.querySelectorAll('[data-hub-pick]');for(k=0;k<b.length;k++)b[k].setAttribute('aria-pressed',String(b[k].getAttribute('data-hub-pick')===plat));}}
function slides(v){return v?v.querySelectorAll('[data-hub-slide]'):[]}
function kbd(el){try{return el.matches(':focus-visible')}catch(e){return true}}
function render(){var v=viewer(cur),s=slides(v),j,n=s.length;
var run=n>1&&!stopped&&!hover&&!focusIn&&!away&&!document.hidden&&!(rm&&rm.matches);
clearTimeout(timer);if(!v)return;
for(j=0;j<n;j++)s[j].classList.toggle('is-current',j===slide);
var d=v.querySelectorAll('[data-hub-dot]');
for(j=0;j<d.length;j++){if(j===slide)d[j].setAttribute('aria-current','true');else d[j].removeAttribute('aria-current');}
var sh=v.querySelector('.hub-shots');if(n>1&&sh)sh.setAttribute('aria-live',run?'off':'polite');
var b=hub.querySelectorAll('[data-hub-rotate]');
for(j=0;j<b.length;j++){b[j].setAttribute('aria-label',b[j].getAttribute(stopped?'data-label-play':'data-label-pause'));b[j].setAttribute('data-state',stopped?'stopped':'rotating');if(rm&&rm.matches)b[j].setAttribute('hidden','');else b[j].removeAttribute('hidden');}
if(run)timer=setTimeout(function(){slide=(slide+1)%n;render()},ADV);}
function hold(el){el.addEventListener('mouseenter',function(){hover=true;render()});
el.addEventListener('mouseleave',function(){hover=false;render()});
el.addEventListener('focusin',function(e){focusIn=kbd(e.target);render()});
el.addEventListener('focusout',function(e){if(!el.contains(e.relatedTarget)){focusIn=false;render()}});}
var sw=document.querySelectorAll('.hub-platform');for(i=0;i<sw.length;i++)sw[i].removeAttribute('hidden');
var picks=document.querySelectorAll('[data-hub-pick]');
for(i=0;i<picks.length;i++)(function(b){b.addEventListener('click',function(){if(b.disabled)return;plat=b.getAttribute('data-hub-pick');
try{localStorage.setItem(SPK,plat)}catch(e){}slide=0;hover=false;focusIn=false;platform();render();})})(picks[i]);
platform();
var row=mob&&mob.querySelector('[data-hub-row]');
if(row)(function(){var cards=row.querySelectorAll('[data-hub-card]'),dots=mob.querySelectorAll('[data-hub-show]'),act=-1,q=0,j;
var raf=window.requestAnimationFrame||function(f){return setTimeout(f,16)};
function idx(t){var c=t&&t.closest?t.closest('[data-hub-card]'):null,k;for(k=0;k<cards.length;k++)if(cards[k]===c)return k;return -1}
function go(k){var c=cards[k];if(!c)return;var l=c.offsetLeft-(row.clientWidth-c.offsetWidth)/2;
if(row.scrollTo)row.scrollTo({left:l,behavior:rm&&rm.matches?'auto':'smooth'});else row.scrollLeft=l;}
function mark(b){var k;if(b===act)return;act=b;for(k=0;k<dots.length;k++){if(k===b)dots[k].setAttribute('aria-current','true');else dots[k].removeAttribute('aria-current');}}
function sync(){q=0;var mid=row.scrollLeft+row.clientWidth/2,b=0,bd=1e9,k,d;
for(k=0;k<cards.length;k++){d=Math.abs(cards[k].offsetLeft+cards[k].offsetWidth/2-mid);if(d<bd){bd=d;b=k}}mark(b);}
row.addEventListener('scroll',function(){if(!q)q=raf(sync)},{passive:true});
row.addEventListener('focusin',function(e){var k=idx(e.target);if(k>=0)go(k)});
row.addEventListener('keydown',function(e){if(e.key!=='ArrowRight'&&e.key!=='ArrowLeft')return;var k=idx(e.target);if(k<0)return;
k=Math.min(cards.length-1,Math.max(0,k+(e.key==='ArrowRight'?1:-1)));e.preventDefault();
var f=cards[k].querySelector('a[href]')||cards[k];f.focus({preventScroll:true});go(k);});
for(j=0;j<dots.length;j++)(function(k){dots[k].addEventListener('click',function(){mark(k);go(k)})})(j);
var nav=mob.querySelector('.hub-m__nav');if(nav)nav.removeAttribute('hidden');
sync();})();
var views=stage.querySelectorAll('[data-hub-viewer]');
for(i=0;i<views.length;i++)(function(v){var s=slides(v),j;if(s.length<2)return;
v.setAttribute('role','region');v.setAttribute('aria-roledescription','carousel');
var id=v.closest('[data-hub-panel]').getAttribute('data-hub-panel');
for(j=0;j<names.length;j++)if(names[j].getAttribute('data-hub-name')===id)v.setAttribute('aria-label',names[j].textContent);
for(j=0;j<s.length;j++)s[j].setAttribute('aria-roledescription','slide');
var c=v.querySelector('.hub-controls');if(c)c.removeAttribute('hidden');
var sh=v.querySelector('.hub-shots'),dots=v.querySelector('.hub-dots');if(sh)hold(sh);if(dots)hold(dots);
var d=v.querySelectorAll('[data-hub-dot]');for(j=0;j<d.length;j++)(function(k){d[k].addEventListener('click',function(){slide=k;render()})})(j);
var r=v.querySelector('[data-hub-rotate]');if(r)r.addEventListener('click',function(){stopped=!stopped;render()});
})(views[i]);
function select(id){var j;if(id!==cur){cur=id;slide=0;hover=false;focusIn=false;}
for(j=0;j<panels.length;j++)panels[j].classList.toggle('is-selected',panels[j].getAttribute('data-hub-panel')===id);
for(j=0;j<names.length;j++){if(names[j].getAttribute('data-hub-name')===id)names[j].setAttribute('aria-current','true');else names[j].removeAttribute('aria-current');}
render();}
document.addEventListener('visibilitychange',render);
if(rm&&rm.addEventListener)rm.addEventListener('change',render);
if(window.IntersectionObserver)new IntersectionObserver(function(es){away=!es[0].isIntersecting;render()}).observe(stage);
var touch=false;
for(i=0;i<names.length;i++)(function(n){var id=n.getAttribute('data-hub-name');
if(n.tagName!=='A')n.setAttribute('tabindex','0');
n.addEventListener('mouseenter',function(){select(id)});
n.addEventListener('focus',function(){select(id)});
n.addEventListener('pointerdown',function(e){touch=e.pointerType!=='mouse'});
n.addEventListener('click',function(e){if(n.tagName==='A'&&(e.detail===0||!touch))return;e.preventDefault();select(id)});
})(names[i]);
select(cfg.initial);
hub.classList.add('is-enhanced');
var im=document.querySelectorAll('[data-hub] .hub-shot.is-current img,[data-hub-row]>:nth-child(-n+2) img');
for(i=0;i<im.length;i++)if(im[i].checkVisibility&&im[i].checkVisibility())im[i].loading='eager';
`
