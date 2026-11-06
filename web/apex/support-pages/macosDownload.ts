// The macOS download pages, `/apps/<app>/download/macos/<lang>`, and each app's
// `/apps/<app>/download/macos.json` (desktop-macos-distribution.md §2.12.6 H7): one page per app and
// language, built by the support-page generator (generate.ts buildMacosDownloads) like the Android
// pages (androidDownload.ts), whose rules this follows.
//
//   - The release comes from the committed `store/macos-release.json`, read at BUILD time: no runtime
//     fetch, so no CSP change. `scripts/macos-direct-publish.sh` writes it after the objects are
//     uploaded; its schema is docs/how-to/macos-release.md §7 (schema 1).
//   - ⚠ No file, or no entry for an app, renders "not available yet" and links nowhere. A malformed
//     file fails the build.
//   - `macos.json` is the Homebrew casks' `livecheck` source: version, build, url, sha256 of that
//     app. Written only when the release carries the app, so `brew livecheck` never reads a version
//     that was not published.
//   - One `.dmg` per app, Apple silicon and macOS 14+ only (D2, D10), Control first, one copy per app
//     (a Mac App Store Control is kept, never doubled: §2.12.3 T3), no updater (T4).
//
// English is the source; German and French are translations nobody has read yet (`reviewed: false`).
import { existsSync, readFileSync } from 'node:fs'
import { join } from 'node:path'

/** The ten desktop apps, in scripts/lib/app-catalogue.sh order (Control first). */
export const MACOS_APPS = ['control', 'drive', 'maps', 'home', 'calendar', 'timeline', 'mia', 'photos', 'fit', 'contacts'] as const
export type MacosApp = (typeof MACOS_APPS)[number]

const NAMES: Record<MacosApp, string> = {
  control: 'Control',
  drive: 'Drive',
  maps: 'Maps',
  home: 'Home',
  calendar: 'Calendar',
  timeline: 'Timeline',
  mia: 'Mia',
  photos: 'Photos',
  fit: 'Fit',
  contacts: 'Contacts',
}

export const MACOS_DOWNLOAD_LANGUAGES = ['en', 'de', 'fr'] as const

/** The committed release manifest, under `store/`. */
export const MACOS_RELEASE_FILE = 'macos-release.json'

/** The team every app is signed by; `spctl -a -vv` names it, so the page prints it. */
export const MACOS_TEAM_ID = '752LWAZMKZ'

export interface MacosReleaseApp {
  /** `Tenshu<App>`, the bundle's name without `.app`. */
  readonly bundle: string
  readonly bundleId: string
  /** What the browser saves it as (the object's Content-Disposition), `Tenshu<App>-<version>.dmg`. */
  readonly file: string
  /** `base` + sha256 + `.dmg`. */
  readonly url: string
  readonly sha256: string
  readonly bytes: number
}

/** `store/macos-release.json`, schema 1 (scripts/lib/macos_publish.py). One version for all ten (D9). */
export interface MacosRelease {
  readonly schema: 1
  readonly base: string
  readonly version: string
  readonly build: string
  readonly minimumMacOS: string
  readonly published: string
  readonly apps: Partial<Record<MacosApp, MacosReleaseApp>>
}

/**
 * The release, or null when no file is committed yet. ⚠ Throws on anything malformed: a broken
 * manifest must fail the build, never publish a page with a wrong link or checksum.
 */
export function loadMacosRelease(storeDir: string): MacosRelease | null {
  const path = join(storeDir, MACOS_RELEASE_FILE)
  if (!existsSync(path)) return null
  const where = `store/${MACOS_RELEASE_FILE}`
  let raw: unknown
  try {
    raw = JSON.parse(readFileSync(path, 'utf8'))
  } catch {
    throw new Error(`${where}: not JSON`)
  }
  const fail = (what: string): never => {
    throw new Error(`${where}: ${what}`)
  }
  if (typeof raw !== 'object' || raw === null) fail('not an object')
  const r = raw as Record<string, unknown>
  if (r.schema !== 1) fail(`schema must be 1, got ${JSON.stringify(r.schema)}`)
  if (typeof r.base !== 'string' || !/^https:\/\/[a-z0-9.-]+\/sha256\/$/.test(r.base)) fail('base must be https://<host>/sha256/')
  if (typeof r.version !== 'string' || !/^\d+(\.\d+){1,2}$/.test(r.version)) fail('version must be x.y[.z]')
  if (typeof r.build !== 'string' || !/^\d{12}$/.test(r.build)) fail('build must be the 12-digit CFBundleVersion')
  if (typeof r.minimumMacOS !== 'string' || !/^\d+(\.\d+){1,2}$/.test(r.minimumMacOS)) fail('minimumMacOS must be x.y[.z]')
  if (r.arch !== 'arm64') fail('arch must be arm64 (D2)')
  if (r.teamId !== MACOS_TEAM_ID) fail(`teamId must be ${MACOS_TEAM_ID}`)
  if (typeof r.published !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(r.published)) fail('published must be YYYY-MM-DD')
  if (typeof r.apps !== 'object' || r.apps === null) fail('apps must be an object')
  const base = r.base as string
  const version = r.version as string
  const apps: Partial<Record<MacosApp, MacosReleaseApp>> = {}
  for (const [app, value] of Object.entries(r.apps as Record<string, unknown>)) {
    if (!(MACOS_APPS as readonly string[]).includes(app)) fail(`unknown app "${app}"`)
    const e = value as Record<string, unknown>
    const bad = (what: string) => fail(`apps.${app}: ${what}`)
    if (typeof e !== 'object' || e === null) bad('not an object')
    const bundle = `Tenshu${NAMES[app as MacosApp]}`
    if (e.bundle !== bundle) bad(`bundle must be ${bundle}`)
    if (e.bundleId !== `app.tenshu.${app}.ios`) bad(`bundleId must be app.tenshu.${app}.ios (C4)`)
    if (e.file !== `${bundle}-${version}.dmg`) bad(`file must be ${bundle}-<version>.dmg`)
    if (typeof e.sha256 !== 'string' || !/^[0-9a-f]{64}$/.test(e.sha256)) bad('sha256 must be 64 lowercase hex')
    if (e.url !== `${base}${e.sha256}.dmg`) bad('url must be base + sha256 + ".dmg"')
    if (!Number.isInteger(e.bytes) || (e.bytes as number) <= 0) bad('bytes must be a positive integer')
    apps[app as MacosApp] = {
      bundle,
      bundleId: e.bundleId as string,
      file: e.file as string,
      url: e.url as string,
      sha256: e.sha256 as string,
      bytes: e.bytes as number,
    }
  }
  return { schema: 1, base, version, build: r.build as string, minimumMacOS: r.minimumMacOS as string, published: r.published as string, apps }
}

export function macosDownloadUrl(app: string, lang: string): string {
  return `/apps/${app}/download/macos/${lang}`
}

/** `/apps/<app>/download/macos`: x-default, and the URL Caddy 302s by Accept-Language. */
export function macosDownloadNeutralUrl(app: string): string {
  return `/apps/${app}/download/macos`
}

/** The casks' `livecheck` source. */
export function macosLivecheckFile(app: string): string {
  return `apps/${app}/download/macos.json`
}

/** What `macos.json` holds: the published version of this app, nothing a browser needs. */
export function macosLivecheckJson(release: MacosRelease, app: MacosApp): string | null {
  const e = release.apps[app]
  if (!e) return null
  return JSON.stringify({ version: release.version, build: release.build, url: e.url, sha256: e.sha256 }, null, 2) + '\n'
}

export function macosAppName(app: MacosApp): string {
  return NAMES[app]
}

export interface MacosDownloadPage {
  readonly reviewed: boolean
  readonly title: (name: string) => string
  readonly description: (name: string) => string
  readonly lede: string
  readonly download: string
  /** "Version 1.0.1 · 140 MB · Mac with Apple silicon, macOS 14 or later" */
  readonly meta: (version: string, size: string, minimum: string) => string
  readonly fileHash: string
  readonly notYet: string
  /** Under Homebrew and Check the download while the app has no release: no cask, no checksum yet. */
  readonly sectionNotYet: string
  readonly controlFirst: (controlHref: string) => string
  readonly installHeading: string
  readonly install: (isControl: boolean, name: string, controlHref: string) => readonly string[]
  readonly homebrewHeading: string
  readonly homebrewIntro: string
  readonly homebrewControl: string
  readonly updateHeading: string
  readonly update: readonly string[]
  readonly storeHeading: string
  readonly store: readonly string[]
  readonly verifyHeading: string
  readonly verifyFile: string
  readonly verifySigner: string
  readonly requirementsHeading: string
  readonly requirements: (minimum: string) => readonly string[]
  readonly allAppsHeading: string
  readonly allAppsNotYet: string
  readonly closing: string
  readonly size: (bytes: number) => string
}

const mb = (bytes: number, decimal: string) => {
  const value = bytes / 1_000_000
  return value >= 10 ? Math.round(value).toString() : value.toFixed(1).replace('.', decimal)
}

const SUPPORT = '<a href="mailto:support@tenshu.app">support@tenshu.app</a>'

const en: MacosDownloadPage = {
  reviewed: true,
  title: (name) => `Download Tenshu ${name} for Mac`,
  description: (name) => `Install Tenshu ${name} on a Mac with Apple silicon: download the disk image or use Homebrew, and check the download.`,
  lede: 'For Macs with Apple silicon. Download the app here, or install it with Homebrew.',
  download: 'Download for Mac',
  meta: (v, size, min) => `Version ${v} · ${size} · Mac with Apple silicon, macOS ${min} or later`,
  fileHash: 'SHA-256 of the file',
  notYet: 'Not available yet. The download will appear here once this app is released for Mac.',
  sectionNotYet: 'This appears here once this app is released for Mac.',
  controlFirst: (href) => `Install <a href="${href}">Tenshu Control</a> first. Every other Tenshu app works through it.`,
  installHeading: 'Install',
  install: (isControl, name, href) => [
    isControl ? 'Install Control before any other Tenshu app: the other apps work through it.' : `Install <a href="${href}">Tenshu Control</a> first, if it is not on your Mac yet.`,
    'Open the downloaded file. A window shows the app next to your Applications folder.',
    `Drag Tenshu ${name} onto the Applications folder.`,
    `Open Tenshu ${name} from Applications. The first time, macOS says it is an app downloaded from the Internet and that Apple checked it for malicious software. Choose Open.`,
    isControl ? 'Control guides you through setting up.' : 'The app finds Control on its own.',
  ],
  homebrewHeading: 'Install with Homebrew',
  homebrewIntro: 'If you use Homebrew, this installs the same app and checks the download for you:',
  homebrewControl: 'Install Control the same way first: <code>brew install --cask b3nedikt/tenshu/tenshu-control</code>.',
  updateHeading: 'Update',
  update: [
    'Apps from this page do not update themselves. To update, quit the app (for Control, choose Quit in its menu-bar menu), download the latest version here and drag it into Applications, replacing the old copy. With Homebrew, <code>brew upgrade --cask</code> does all of this. Your data stays.',
    'Update all your Tenshu apps together. An app much older than Control may stop syncing until you update it, and it tells you so.',
  ],
  storeHeading: 'Mac App Store',
  store: [
    'If you already have Tenshu Control from the Mac App Store, keep it and skip Control here: apps from this page work with it. Keep one copy of each app on a Mac, never two.',
  ],
  verifyHeading: 'Check the download',
  verifyFile: 'Optional, for the careful. To check that the file arrived complete, compare its SHA-256 (<code>shasum -a 256</code> in Terminal) with the one under the download button.',
  verifySigner: `Every Tenshu app is signed by us and notarized by Apple. In Terminal, <code>spctl -a -vv /Applications/TenshuControl.app</code> (or the app you installed) must say <code>source=Notarized Developer ID</code> and end its origin line with <code>(${MACOS_TEAM_ID})</code>.`,
  requirementsHeading: 'Requirements',
  requirements: (min) => [
    'A Mac with Apple silicon (M1 or newer). Macs with an Intel processor are not supported.',
    `macOS ${min} or later.`,
  ],
  allAppsHeading: 'All Tenshu apps for Mac',
  allAppsNotYet: 'not available yet',
  closing: `Something not working? Write to us at ${SUPPORT}.`,
  size: (b) => `${mb(b, '.')} MB`,
}

const de: MacosDownloadPage = {
  reviewed: false,
  title: (name) => `Tenshu ${name} für Mac herunterladen`,
  description: (name) => `Tenshu ${name} auf einem Mac mit Apple-Chip installieren: Disk-Image herunterladen oder Homebrew nutzen und den Download prüfen.`,
  lede: 'Für Macs mit Apple-Chip. Lade die App hier herunter oder installiere sie mit Homebrew.',
  download: 'Für Mac herunterladen',
  meta: (v, size, min) => `Version ${v} · ${size} · Mac mit Apple-Chip, macOS ${min} oder neuer`,
  fileHash: 'SHA-256 der Datei',
  notYet: 'Noch nicht verfügbar. Der Download erscheint hier, sobald diese App für den Mac erscheint.',
  sectionNotYet: 'Das erscheint hier, sobald diese App für den Mac erscheint.',
  controlFirst: (href) => `Installiere zuerst <a href="${href}">Tenshu Control</a>. Jede andere Tenshu-App arbeitet darüber.`,
  installHeading: 'Installieren',
  install: (isControl, name, href) => [
    isControl ? 'Installiere Control vor jeder anderen Tenshu-App: Die anderen Apps arbeiten darüber.' : `Installiere zuerst <a href="${href}">Tenshu Control</a>, falls es noch nicht auf deinem Mac ist.`,
    'Öffne die heruntergeladene Datei. Ein Fenster zeigt die App neben deinem Programme-Ordner.',
    `Zieh Tenshu ${name} auf den Programme-Ordner.`,
    `Öffne Tenshu ${name} aus „Programme“. Beim ersten Mal sagt macOS, dass die App aus dem Internet geladen wurde und Apple sie auf Schadsoftware geprüft hat. Wähle „Öffnen“.`,
    isControl ? 'Control führt dich durch die Einrichtung.' : 'Die App findet Control von selbst.',
  ],
  homebrewHeading: 'Mit Homebrew installieren',
  homebrewIntro: 'Wenn du Homebrew nutzt, installiert dies dieselbe App und prüft den Download für dich:',
  homebrewControl: 'Installiere zuerst Control auf dieselbe Weise: <code>brew install --cask b3nedikt/tenshu/tenshu-control</code>.',
  updateHeading: 'Aktualisieren',
  update: [
    'Apps von dieser Seite aktualisieren sich nicht selbst. Zum Aktualisieren beendest du die App (bei Control über „Quit“ im Menüleisten-Menü), lädst hier die neueste Version herunter und ziehst sie in „Programme“, wobei du die alte Kopie ersetzt. Mit Homebrew erledigt <code>brew upgrade --cask</code> das alles. Deine Daten bleiben erhalten.',
    'Aktualisiere alle deine Tenshu-Apps gemeinsam. Eine App, die viel älter als Control ist, synchronisiert unter Umständen nicht mehr, bis du sie aktualisierst, und sagt dir das.',
  ],
  storeHeading: 'Mac App Store',
  store: [
    'Wenn du Tenshu Control bereits aus dem Mac App Store hast, behalte es und überspringe Control hier: Apps von dieser Seite arbeiten damit. Behalte von jeder App nur eine Kopie auf einem Mac, nie zwei.',
  ],
  verifyHeading: 'Download prüfen',
  verifyFile: 'Optional, für Vorsichtige. Ob die Datei vollständig angekommen ist, prüfst du, indem du ihren SHA-256 (<code>shasum -a 256</code> im Terminal) mit dem unter dem Download-Button vergleichst.',
  verifySigner: `Jede Tenshu-App ist von uns signiert und von Apple notarisiert. Im Terminal muss <code>spctl -a -vv /Applications/TenshuControl.app</code> (oder die installierte App) <code>source=Notarized Developer ID</code> ausgeben, und die origin-Zeile muss mit <code>(${MACOS_TEAM_ID})</code> enden.`,
  requirementsHeading: 'Voraussetzungen',
  requirements: (min) => [
    'Ein Mac mit Apple-Chip (M1 oder neuer). Macs mit Intel-Prozessor werden nicht unterstützt.',
    `macOS ${min} oder neuer.`,
  ],
  allAppsHeading: 'Alle Tenshu-Apps für Mac',
  allAppsNotYet: 'noch nicht verfügbar',
  closing: `Funktioniert etwas nicht? Schreib uns an ${SUPPORT}.`,
  size: (b) => `${mb(b, ',')} MB`,
}

const fr: MacosDownloadPage = {
  reviewed: false,
  title: (name) => `Télécharger Tenshu ${name} pour Mac`,
  description: (name) => `Installez Tenshu ${name} sur un Mac avec puce Apple : téléchargez l’image disque ou utilisez Homebrew, et vérifiez le téléchargement.`,
  lede: 'Pour les Mac avec puce Apple. Téléchargez l’application ici, ou installez-la avec Homebrew.',
  download: 'Télécharger pour Mac',
  meta: (v, size, min) => `Version ${v} · ${size} · Mac avec puce Apple, macOS ${min} ou plus récent`,
  fileHash: 'SHA-256 du fichier',
  notYet: 'Pas encore disponible. Le téléchargement apparaîtra ici dès que cette application sera publiée pour Mac.',
  sectionNotYet: 'Ceci apparaîtra ici dès que cette application sera publiée pour Mac.',
  controlFirst: (href) => `Installez d’abord <a href="${href}">Tenshu Control</a>. Toutes les autres applications Tenshu passent par lui.`,
  installHeading: 'Installer',
  install: (isControl, name, href) => [
    isControl ? 'Installez Control avant toute autre application Tenshu : les autres applications passent par lui.' : `Installez d’abord <a href="${href}">Tenshu Control</a>, s’il n’est pas encore sur votre Mac.`,
    'Ouvrez le fichier téléchargé. Une fenêtre montre l’application à côté de votre dossier Applications.',
    `Faites glisser Tenshu ${name} sur le dossier Applications.`,
    `Ouvrez Tenshu ${name} depuis Applications. La première fois, macOS indique qu’il s’agit d’une application téléchargée sur Internet et qu’Apple l’a vérifiée. Choisissez Ouvrir.`,
    isControl ? 'Control vous guide pour la configuration.' : 'L’application trouve Control d’elle-même.',
  ],
  homebrewHeading: 'Installer avec Homebrew',
  homebrewIntro: 'Si vous utilisez Homebrew, ceci installe la même application et vérifie le téléchargement pour vous :',
  homebrewControl: 'Installez d’abord Control de la même façon : <code>brew install --cask b3nedikt/tenshu/tenshu-control</code>.',
  updateHeading: 'Mettre à jour',
  update: [
    'Les applications de cette page ne se mettent pas à jour toutes seules. Pour mettre à jour, quittez l’application (pour Control, choisissez « Quit » dans son menu de la barre des menus), téléchargez ici la dernière version et faites-la glisser dans Applications, en remplaçant l’ancienne copie. Avec Homebrew, <code>brew upgrade --cask</code> fait tout cela. Vos données sont conservées.',
    'Mettez à jour toutes vos applications Tenshu ensemble. Une application bien plus ancienne que Control peut cesser de se synchroniser jusqu’à sa mise à jour, et elle vous le signale.',
  ],
  storeHeading: 'Mac App Store',
  store: [
    'Si vous avez déjà Tenshu Control depuis le Mac App Store, gardez-le et ignorez Control ici : les applications de cette page fonctionnent avec lui. Gardez une seule copie de chaque application sur un Mac, jamais deux.',
  ],
  verifyHeading: 'Vérifier le téléchargement',
  verifyFile: 'Facultatif, pour les prudents. Pour vérifier que le fichier est arrivé complet, comparez son SHA-256 (<code>shasum -a 256</code> dans Terminal) avec celui affiché sous le bouton de téléchargement.',
  verifySigner: `Chaque application Tenshu est signée par nous et notarisée par Apple. Dans Terminal, <code>spctl -a -vv /Applications/TenshuControl.app</code> (ou l’application installée) doit afficher <code>source=Notarized Developer ID</code>, et la ligne origin doit se terminer par <code>(${MACOS_TEAM_ID})</code>.`,
  requirementsHeading: 'Configuration requise',
  requirements: (min) => [
    'Un Mac avec puce Apple (M1 ou plus récent). Les Mac à processeur Intel ne sont pas pris en charge.',
    `macOS ${min} ou plus récent.`,
  ],
  allAppsHeading: 'Toutes les applications Tenshu pour Mac',
  allAppsNotYet: 'pas encore disponible',
  closing: `Quelque chose ne fonctionne pas ? Écrivez-nous à ${SUPPORT}.`,
  size: (b) => `${mb(b, ',')} Mo`,
}

export const MACOS_DOWNLOAD: Record<string, MacosDownloadPage> = { en, de, fr }

const escape = (text: string) =>
  text.replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;').replaceAll('"', '&quot;')

const BUTTON_PRIMARY = 'inline-flex min-h-11 items-center justify-center gap-2 rounded-lg bg-primary px-5 text-sm font-semibold text-on-primary no-underline hover:opacity-90'

/**
 * The page body as HTML. The copy is trusted (written above); the release's strings were validated
 * by [loadMacosRelease] and are escaped anyway. Every `<h2 id>` is bare, so generate.ts lists them in
 * the page's table of contents.
 */
export function macosDownloadHtml(page: MacosDownloadPage, app: MacosApp, lang: string, release: MacosRelease | null): string {
  const entry = release?.apps[app] ?? null
  const isControl = app === 'control'
  const name = NAMES[app]
  const controlHref = macosDownloadUrl('control', lang)
  const minimum = (release?.minimumMacOS ?? '14.0').replace(/\.0$/, '')
  const card = entry && release
    ? [
        '<section class="mt-8 rounded-2xl border border-line bg-surface p-6" aria-label="dmg">',
        `<a href="${escape(entry.url)}" class="${BUTTON_PRIMARY}" data-dmg="${app}">${escape(page.download)}</a>`,
        `<p class="mt-3 text-sm text-text-2">${escape(page.meta(release.version, page.size(entry.bytes), minimum))}</p>`,
        `<p class="mt-2 text-sm text-text-3">${escape(page.fileHash)}: <code class="break-all">${entry.sha256}</code></p>`,
        '</section>',
      ].join('\n')
    : `<section class="mt-8 rounded-2xl border border-dashed border-line p-6" aria-label="dmg"><p class="text-text-2" data-dmg-unavailable="${app}">${escape(page.notYet)}</p></section>`
  const allApps = MACOS_APPS.map((a) => {
    const e = release?.apps[a]
    const link = a === app ? `<strong>Tenshu ${NAMES[a]}</strong>` : `<a href="${macosDownloadUrl(a, lang)}">Tenshu ${NAMES[a]}</a>`
    return `<li>${link}${e ? ` · ${escape(page.size(e.bytes))}` : ` · ${escape(page.allAppsNotYet)}`}</li>`
  })
  return [
    `<p class="text-lg text-text-2">${escape(page.lede)}</p>`,
    isControl ? '' : `<p>${page.controlFirst(controlHref)}</p>`,
    card,
    `<h2 id="install">${escape(page.installHeading)}</h2>`,
    '<ol>',
    ...page.install(isControl, name, controlHref).map((step) => `<li>${step}</li>`),
    '</ol>',
    `<h2 id="homebrew">${escape(page.homebrewHeading)}</h2>`,
    // ⚠ Only for a published app: `--casks` renders a cask only for apps the manifest names, and the
    // checksum steps point at the card's hash. "Not available" must not hand out a dead command.
    ...(entry
      ? [
          `<p>${escape(page.homebrewIntro)}</p>`,
          `<pre class="overflow-x-auto"><code>brew install --cask b3nedikt/tenshu/tenshu-${app}</code></pre>`,
          isControl ? '' : `<p>${page.homebrewControl}</p>`,
        ]
      : [`<p data-homebrew-unavailable>${escape(page.sectionNotYet)}</p>`]),
    `<h2 id="update">${escape(page.updateHeading)}</h2>`,
    ...page.update.map((p) => `<p>${p}</p>`),
    `<h2 id="mac-app-store">${escape(page.storeHeading)}</h2>`,
    ...page.store.map((p) => `<p>${escape(p)}</p>`),
    `<h2 id="verify">${escape(page.verifyHeading)}</h2>`,
    ...(entry ? [`<p>${page.verifyFile}</p>`, `<p>${page.verifySigner}</p>`] : [`<p>${escape(page.sectionNotYet)}</p>`]),
    `<h2 id="requirements">${escape(page.requirementsHeading)}</h2>`,
    '<ul>',
    ...page.requirements(minimum).map((r) => `<li>${escape(r)}</li>`),
    '</ul>',
    `<h2 id="all-apps">${escape(page.allAppsHeading)}</h2>`,
    `<ul>${allApps.join('')}</ul>`,
    `<p>${page.closing}</p>`,
  ]
    .filter((line) => line !== '')
    .join('\n')
}
