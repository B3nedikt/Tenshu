// The Android download pages, `/apps/<app>/download/android/<lang>`
// (docs/proposals/android-direct-apk-distribution.md Phase 1 item 5; the URL is reserved in
// store-presence-and-support-pages.md §4.1): one page per app and language, built by the
// support-page generator (generate.ts buildAndroidDownloads) like the pricing page.
//
// What goes on it — the rules, so the page stays honest:
//   - The release comes from the committed `store/android-release.json`, read at BUILD time: no
//     runtime fetch, so no CSP change (a download link is a navigation, which CSP does not govern).
//     The publish step (Phase 1 item 4, `scripts/android-direct-publish.sh`) writes it after the
//     objects are uploaded; its schema is docs/how-to/android-release.md's (schema 1).
//   - ⚠ No file, or no entry for an app, renders "not available yet" and links nowhere: never a
//     link to an APK that is not there. A malformed file fails the build instead.
//   - Updating is downloading again (D4): no updater, no update check. The page says so.
//   - arm64 only (D2), Control first, never mix Play and website copies, and how to check the
//     signature with `apksigner verify --print-certs` against the fingerprint printed here.
//   - ⚠ Nothing on it may link to Google Play: a website page is not where Play installs come from,
//     and the Play listing must not link here either (Phase 1 item 10).
//
// English is the source; German and French are translations nobody has read yet (`reviewed: false`).
import { existsSync, readFileSync } from 'node:fs'
import { join } from 'node:path'

/** The ten Android apps `scripts/android-release.sh` builds (ALL_APPS), Control first. */
export const ANDROID_APPS = ['control', 'drive', 'photos', 'maps', 'timeline', 'calendar', 'contacts', 'fit', 'home', 'mia'] as const
export type AndroidApp = (typeof ANDROID_APPS)[number]

/** The display names, as the hub lists them (hub.ts HUB_APPS). */
const NAMES: Record<AndroidApp, string> = {
  control: 'Control',
  drive: 'Drive',
  photos: 'Photos',
  maps: 'Maps',
  timeline: 'Timeline',
  calendar: 'Calendar',
  contacts: 'Contacts',
  fit: 'Fit',
  home: 'Home',
  mia: 'Mia',
}

export const ANDROID_DOWNLOAD_LANGUAGES = ['en', 'de', 'fr'] as const

/** The committed release manifest, under `store/`. */
export const ANDROID_RELEASE_FILE = 'android-release.json'

/** The only ABI the direct channel ships (D2; scripts/lib/direct_release.py ABI). */
export const ANDROID_ABI = 'arm64-v8a'

/**
 * One app in the release. Its version is its own: publishing one app leaves the others' entries as
 * they were (`direct_publish.py`).
 */
export interface AndroidReleaseApp {
  /** `app.tenshu.<app>.android`. */
  readonly package: string
  readonly versionCode: number
  readonly versionName: string
  /** SHA-256 of the APK file, lowercase hex. */
  readonly sha256: string
  readonly bytes: number
  /** What the browser saves it as (the object's Content-Disposition), `tenshu-<app>-<versionCode>.apk`. */
  readonly filename: string
  /** `base` + sha256 + `.apk`: the object on the release bucket. */
  readonly url: string
  /** UTC date (YYYY-MM-DD) these bytes were first published. */
  readonly published: string
}

/**
 * `store/android-release.json`, schema 1. ⚠ Written only by the publish step
 * (`scripts/android-direct-publish.sh`; schema in docs/how-to/android-release.md), after
 * every object it names is uploaded. This reader re-checks what the page depends on, because the
 * page links `url` as is and prints the fingerprint people verify against.
 */
export interface AndroidRelease {
  readonly schema: 1
  /** The bucket's public prefix, `https://…/sha256/`. */
  readonly base: string
  readonly abi: typeof ANDROID_ABI
  /** The side-channel signing certificate's SHA-256, `AB:CD:…` (keytool / FirstPartySigning form). */
  readonly signingCertSha256: string
  readonly apps: Partial<Record<AndroidApp, AndroidReleaseApp>>
}

const CERT_DIGEST = /^[0-9A-F]{2}(?::[0-9A-F]{2}){31}$/

/**
 * The release, or null when no file is committed yet. ⚠ Throws on anything malformed: a broken
 * manifest must fail the build, never publish a page with a wrong link or fingerprint.
 */
export function loadAndroidRelease(storeDir: string): AndroidRelease | null {
  const path = join(storeDir, ANDROID_RELEASE_FILE)
  if (!existsSync(path)) return null
  const where = `store/${ANDROID_RELEASE_FILE}`
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
  if (typeof r.base !== 'string' || !/^https:\/\/[a-z0-9.-]+\/([A-Za-z0-9._-]+\/)*$/.test(r.base)) fail('base must be an https URL ending in /')
  if (r.abi !== ANDROID_ABI) fail(`abi must be ${ANDROID_ABI} (D2)`)
  if (typeof r.signingCertSha256 !== 'string' || !CERT_DIGEST.test(r.signingCertSha256)) fail('signingCertSha256 must be a colon-separated uppercase SHA-256')
  if (typeof r.apps !== 'object' || r.apps === null) fail('apps must be an object')
  const base = r.base as string
  const cert = r.signingCertSha256 as string
  const apps: Partial<Record<AndroidApp, AndroidReleaseApp>> = {}
  for (const [app, value] of Object.entries(r.apps as Record<string, unknown>)) {
    if (!(ANDROID_APPS as readonly string[]).includes(app)) fail(`unknown app "${app}"`)
    const e = value as Record<string, unknown>
    const bad = (what: string) => fail(`apps.${app}: ${what}`)
    if (typeof e !== 'object' || e === null) bad('not an object')
    if (e.package !== `app.tenshu.${app}.android`) bad(`package must be app.tenshu.${app}.android`)
    if (!Number.isInteger(e.versionCode) || (e.versionCode as number) <= 0) bad('versionCode must be a positive integer')
    if (typeof e.versionName !== 'string' || e.versionName.trim() === '' || e.versionName.length > 64) bad('versionName is missing or malformed')
    // One key and one ABI sign and build all ten (D2): an entry that disagrees is a broken file.
    if (e.abi !== undefined && e.abi !== ANDROID_ABI) bad(`abi must be ${ANDROID_ABI}`)
    if (e.signingCertSha256 !== undefined && e.signingCertSha256 !== cert) bad('signingCertSha256 differs from the top-level one')
    if (typeof e.sha256 !== 'string' || !/^[0-9a-f]{64}$/.test(e.sha256)) bad('sha256 must be 64 lowercase hex')
    if (!Number.isInteger(e.bytes) || (e.bytes as number) <= 0) bad('bytes must be a positive integer')
    if (e.filename !== `tenshu-${app}-${e.versionCode}.apk`) bad(`filename must be tenshu-${app}-<versionCode>.apk`)
    if (e.url !== `${base}${e.sha256}.apk`) bad('url must be base + sha256 + ".apk"')
    if (typeof e.published !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(e.published)) bad('published must be YYYY-MM-DD')
    apps[app as AndroidApp] = {
      package: e.package as string,
      versionCode: e.versionCode as number,
      versionName: e.versionName as string,
      sha256: e.sha256 as string,
      bytes: e.bytes as number,
      filename: e.filename as string,
      url: e.url as string,
      published: e.published as string,
    }
  }
  return { schema: 1, base, abi: ANDROID_ABI, signingCertSha256: cert, apps }
}

export function androidDownloadUrl(app: string, lang: string): string {
  return `/apps/${app}/download/android/${lang}`
}

/**
 * `/apps/<app>/download/android`: x-default, and the URL Caddy 302s by Accept-Language. ⚠ Control's
 * is compiled into the apps (`CONTROL_ANDROID_DOWNLOAD_URL`), so it must keep resolving.
 */
export function androidDownloadNeutralUrl(app: string): string {
  return `/apps/${app}/download/android`
}

/** The form `apksigner verify --print-certs` prints: lowercase hex, no colons. */
export function apksignerDigest(cert: string): string {
  return cert.replaceAll(':', '').toLowerCase()
}

export interface AndroidDownloadPage {
  readonly reviewed: boolean
  readonly title: (name: string) => string
  readonly description: (name: string) => string
  readonly lede: string
  readonly download: string
  /** "Version {name} · {size} · 64-bit ARM phones" */
  readonly meta: (versionName: string, versionCode: number, size: string) => string
  readonly fileHash: string
  readonly notYet: string
  readonly controlFirst: (controlHref: string) => string
  readonly installHeading: string
  readonly install: (isControl: boolean, controlHref: string) => readonly string[]
  readonly updateHeading: string
  readonly update: readonly string[]
  readonly mixHeading: string
  readonly mix: readonly string[]
  readonly verifyHeading: string
  readonly verifyIntro: string
  readonly verifyFingerprint: string
  readonly verifyApksigner: string
  readonly verifyFile: string
  readonly verifyNotYet: string
  readonly requirementsHeading: string
  readonly requirements: readonly string[]
  readonly allAppsHeading: string
  readonly allAppsNotYet: string
  readonly closing: string
  /** "35 MB": the size in the page's language. */
  readonly size: (bytes: number) => string
}

const mb = (bytes: number, decimal: string) => {
  const value = bytes / 1_000_000
  const text = value >= 10 ? Math.round(value).toString() : value.toFixed(1).replace('.', decimal)
  return text
}

const SUPPORT = '<a href="mailto:support@tenshu.app">support@tenshu.app</a>'

const en: AndroidDownloadPage = {
  reviewed: true,
  title: (name) => `Download Tenshu ${name} for Android`,
  description: (name) => `Install Tenshu ${name} on Android without Google Play: download the APK, check its signature, and update it by downloading the latest version.`,
  lede: 'For Android phones without Google Play, or if you prefer not to use it: download the app here and install it yourself.',
  download: 'Download APK',
  meta: (v, code, size) => `Version ${v} (${code}) · ${size} · 64-bit ARM phones only`,
  fileHash: 'SHA-256 of the file',
  notYet: 'Not available yet. The download will appear here once this app is released outside Google Play.',
  controlFirst: (href) => `Install <a href="${href}">Tenshu Control</a> first. Every other Tenshu app works through it.`,
  installHeading: 'Install',
  install: (isControl, href) => [
    isControl ? 'Install Control before any other Tenshu app: the other apps work through it.' : `Install <a href="${href}">Tenshu Control</a> first, if it is not on your phone yet.`,
    'Download the APK on your phone and open it, from the browser’s downloads or a notification.',
    'Android asks whether your browser may install apps. Allow it in the screen it opens (often under Settings, Apps, Special app access, Install unknown apps). You can switch it off again afterwards.',
    'Google Play Protect may offer to scan the app, or warn that it does not know it. Let it scan. If you are unsure, check the signature first (below), then choose to install.',
    'Open the app. Control guides you through setting up; the other apps find Control on their own.',
  ],
  updateHeading: 'Update',
  update: [
    'Apps from this page do not update themselves and do not check for updates. To update, come back here, download the latest APK and install it over the app you have. Your data stays.',
    'Update all your Tenshu apps together. An app much older than Control may stop syncing until you update it, and it tells you so.',
  ],
  mixHeading: 'Google Play or this page, not both',
  mix: [
    'Install all Tenshu apps on a phone from the same place: all from Google Play, or all from this page. Android will not install a downloaded copy over one from Google Play, or the other way round.',
    'To switch, sync first, then uninstall the app and install it again from the other source. Uninstalling removes the app’s data from this phone.',
    'In apps from this page you buy storage from us and pay by card; see <a href="/pricing">pricing</a>.',
  ],
  verifyHeading: 'Check the download',
  verifyIntro: 'Optional, for the careful. Every Tenshu app from this page is signed with the same key, and its certificate has this SHA-256 fingerprint:',
  verifyFingerprint: 'Signing certificate',
  verifyApksigner: 'With the Android SDK’s build tools installed, run this on your computer. The “Signer #1 certificate SHA-256 digest” it prints must be:',
  verifyFile: 'To check that the file arrived complete, compare its SHA-256 (<code>sha256sum</code> on Linux, <code>shasum -a 256</code> on a Mac) with the one under the download button.',
  verifyNotYet: 'The signing certificate’s fingerprint will be listed here with the first release.',
  requirementsHeading: 'Requirements',
  requirements: [
    'Android 7.0 or newer.',
    'A phone or tablet with a 64-bit ARM processor (arm64-v8a), which nearly every Android phone of recent years has. Other processors are not supported.',
    'Google Play services are not needed.',
  ],
  allAppsHeading: 'All Tenshu apps for Android',
  allAppsNotYet: 'not available yet',
  closing: `Something not working? Write to us at ${SUPPORT}.`,
  size: (b) => `${mb(b, '.')} MB`,
}

const de: AndroidDownloadPage = {
  reviewed: false,
  title: (name) => `Tenshu ${name} für Android herunterladen`,
  description: (name) => `Tenshu ${name} ohne Google Play auf Android installieren: APK herunterladen, Signatur prüfen und durch erneutes Herunterladen aktualisieren.`,
  lede: 'Für Android-Geräte ohne Google Play, oder wenn du es lieber nicht nutzt: Lade die App hier herunter und installiere sie selbst.',
  download: 'APK herunterladen',
  meta: (v, code, size) => `Version ${v} (${code}) · ${size} · nur 64-Bit-ARM-Geräte`,
  fileHash: 'SHA-256 der Datei',
  notYet: 'Noch nicht verfügbar. Der Download erscheint hier, sobald diese App außerhalb von Google Play erscheint.',
  controlFirst: (href) => `Installiere zuerst <a href="${href}">Tenshu Control</a>. Jede andere Tenshu-App arbeitet darüber.`,
  installHeading: 'Installieren',
  install: (isControl, href) => [
    isControl ? 'Installiere Control vor jeder anderen Tenshu-App: Die anderen Apps arbeiten darüber.' : `Installiere zuerst <a href="${href}">Tenshu Control</a>, falls es noch nicht auf deinem Gerät ist.`,
    'Lade die APK auf deinem Gerät herunter und öffne sie, aus den Downloads des Browsers oder über die Benachrichtigung.',
    'Android fragt, ob dein Browser Apps installieren darf. Erlaube es in dem Bildschirm, der sich öffnet (oft unter Einstellungen, Apps, Spezieller App-Zugriff, Unbekannte Apps installieren). Danach kannst du es wieder ausschalten.',
    'Google Play Protect bietet eventuell an, die App zu scannen, oder warnt, dass es sie nicht kennt. Lass sie scannen. Wenn du unsicher bist, prüfe zuerst die Signatur (unten) und installiere dann.',
    'Öffne die App. Control führt dich durch die Einrichtung; die anderen Apps finden Control von selbst.',
  ],
  updateHeading: 'Aktualisieren',
  update: [
    'Apps von dieser Seite aktualisieren sich nicht selbst und suchen nicht nach Updates. Zum Aktualisieren kommst du hierher zurück, lädst die neueste APK herunter und installierst sie über die vorhandene App. Deine Daten bleiben erhalten.',
    'Aktualisiere alle deine Tenshu-Apps gemeinsam. Eine App, die viel älter als Control ist, synchronisiert unter Umständen nicht mehr, bis du sie aktualisierst, und sagt dir das.',
  ],
  mixHeading: 'Google Play oder diese Seite, nicht beides',
  mix: [
    'Installiere alle Tenshu-Apps auf einem Gerät aus derselben Quelle: alle aus Google Play oder alle von dieser Seite. Android installiert eine heruntergeladene Kopie nicht über eine aus Google Play, und umgekehrt.',
    'Zum Wechseln synchronisiere zuerst, deinstalliere dann die App und installiere sie aus der anderen Quelle neu. Beim Deinstallieren werden die Daten der App auf diesem Gerät gelöscht.',
    'In Apps von dieser Seite kaufst du Speicher bei uns und zahlst per Karte; siehe <a href="/pricing">Preise</a>.',
  ],
  verifyHeading: 'Download prüfen',
  verifyIntro: 'Optional, für Vorsichtige. Jede Tenshu-App von dieser Seite ist mit demselben Schlüssel signiert, und sein Zertifikat hat diesen SHA-256-Fingerabdruck:',
  verifyFingerprint: 'Signaturzertifikat',
  verifyApksigner: 'Mit installierten Build-Tools des Android SDK führst du dies auf deinem Computer aus. Der ausgegebene „Signer #1 certificate SHA-256 digest“ muss lauten:',
  verifyFile: 'Ob die Datei vollständig angekommen ist, prüfst du, indem du ihren SHA-256 (<code>sha256sum</code> unter Linux, <code>shasum -a 256</code> auf dem Mac) mit dem unter dem Download-Button vergleichst.',
  verifyNotYet: 'Der Fingerabdruck des Signaturzertifikats steht hier mit der ersten Veröffentlichung.',
  requirementsHeading: 'Voraussetzungen',
  requirements: [
    'Android 7.0 oder neuer.',
    'Ein Smartphone oder Tablet mit 64-Bit-ARM-Prozessor (arm64-v8a), wie ihn fast jedes Android-Gerät der letzten Jahre hat. Andere Prozessoren werden nicht unterstützt.',
    'Google-Play-Dienste werden nicht benötigt.',
  ],
  allAppsHeading: 'Alle Tenshu-Apps für Android',
  allAppsNotYet: 'noch nicht verfügbar',
  closing: `Funktioniert etwas nicht? Schreib uns an ${SUPPORT}.`,
  size: (b) => `${mb(b, ',')} MB`,
}

const fr: AndroidDownloadPage = {
  reviewed: false,
  title: (name) => `Télécharger Tenshu ${name} pour Android`,
  description: (name) => `Installez Tenshu ${name} sur Android sans Google Play : téléchargez l’APK, vérifiez sa signature et mettez-la à jour en téléchargeant la dernière version.`,
  lede: 'Pour les appareils Android sans Google Play, ou si vous préférez ne pas l’utiliser : téléchargez l’application ici et installez-la vous-même.',
  download: 'Télécharger l’APK',
  meta: (v, code, size) => `Version ${v} (${code}) · ${size} · appareils ARM 64 bits uniquement`,
  fileHash: 'SHA-256 du fichier',
  notYet: 'Pas encore disponible. Le téléchargement apparaîtra ici dès que cette application sera publiée hors de Google Play.',
  controlFirst: (href) => `Installez d’abord <a href="${href}">Tenshu Control</a>. Toutes les autres applications Tenshu passent par lui.`,
  installHeading: 'Installer',
  install: (isControl, href) => [
    isControl ? 'Installez Control avant toute autre application Tenshu : les autres applications passent par lui.' : `Installez d’abord <a href="${href}">Tenshu Control</a>, s’il n’est pas encore sur votre appareil.`,
    'Téléchargez l’APK sur votre appareil et ouvrez-la, depuis les téléchargements du navigateur ou la notification.',
    'Android demande si votre navigateur peut installer des applications. Autorisez-le dans l’écran qui s’ouvre (souvent dans Paramètres, Applications, Accès spécifiques des applications, Installer des applications inconnues). Vous pourrez le désactiver ensuite.',
    'Google Play Protect peut proposer d’analyser l’application, ou avertir qu’il ne la connaît pas. Laissez-le l’analyser. En cas de doute, vérifiez d’abord la signature (ci-dessous), puis installez.',
    'Ouvrez l’application. Control vous guide pour la configuration ; les autres applications trouvent Control d’elles-mêmes.',
  ],
  updateHeading: 'Mettre à jour',
  update: [
    'Les applications de cette page ne se mettent pas à jour toutes seules et ne recherchent pas de mises à jour. Pour mettre à jour, revenez ici, téléchargez la dernière APK et installez-la par-dessus l’application existante. Vos données sont conservées.',
    'Mettez à jour toutes vos applications Tenshu ensemble. Une application bien plus ancienne que Control peut cesser de se synchroniser jusqu’à sa mise à jour, et elle vous le signale.',
  ],
  mixHeading: 'Google Play ou cette page, pas les deux',
  mix: [
    'Installez toutes les applications Tenshu d’un appareil depuis la même source : toutes depuis Google Play, ou toutes depuis cette page. Android n’installe pas une copie téléchargée par-dessus une copie de Google Play, ni l’inverse.',
    'Pour changer, synchronisez d’abord, puis désinstallez l’application et réinstallez-la depuis l’autre source. La désinstallation supprime les données de l’application sur cet appareil.',
    'Dans les applications de cette page, vous achetez de l’espace chez nous et payez par carte ; voir les <a href="/pricing">tarifs</a>.',
  ],
  verifyHeading: 'Vérifier le téléchargement',
  verifyIntro: 'Facultatif, pour les prudents. Toutes les applications Tenshu de cette page sont signées avec la même clé, dont le certificat a cette empreinte SHA-256 :',
  verifyFingerprint: 'Certificat de signature',
  verifyApksigner: 'Avec les build tools du SDK Android installés, exécutez ceci sur votre ordinateur. Le « Signer #1 certificate SHA-256 digest » affiché doit être :',
  verifyFile: 'Pour vérifier que le fichier est arrivé complet, comparez son SHA-256 (<code>sha256sum</code> sous Linux, <code>shasum -a 256</code> sur Mac) avec celui affiché sous le bouton de téléchargement.',
  verifyNotYet: 'L’empreinte du certificat de signature figurera ici dès la première publication.',
  requirementsHeading: 'Configuration requise',
  requirements: [
    'Android 7.0 ou plus récent.',
    'Un téléphone ou une tablette avec un processeur ARM 64 bits (arm64-v8a), comme presque tous les appareils Android de ces dernières années. Les autres processeurs ne sont pas pris en charge.',
    'Les services Google Play ne sont pas nécessaires.',
  ],
  allAppsHeading: 'Toutes les applications Tenshu pour Android',
  allAppsNotYet: 'pas encore disponible',
  closing: `Quelque chose ne fonctionne pas ? Écrivez-nous à ${SUPPORT}.`,
  size: (b) => `${mb(b, ',')} Mo`,
}

export const ANDROID_DOWNLOAD: Record<string, AndroidDownloadPage> = { en, de, fr }

export function androidAppName(app: AndroidApp): string {
  return NAMES[app]
}

const escape = (text: string) =>
  text.replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;').replaceAll('"', '&quot;')

const BUTTON_PRIMARY = 'inline-flex min-h-11 items-center justify-center gap-2 rounded-lg bg-primary px-5 text-sm font-semibold text-on-primary no-underline hover:opacity-90'

/**
 * The page body as HTML. The copy is trusted (written above); the release's strings were validated
 * by [loadAndroidRelease] and are escaped anyway. Every `<h2 id>` is bare, so generate.ts lists
 * them in the page's table of contents.
 */
export function androidDownloadHtml(page: AndroidDownloadPage, app: AndroidApp, lang: string, release: AndroidRelease | null): string {
  const entry = release?.apps[app] ?? null
  const isControl = app === 'control'
  const controlHref = androidDownloadUrl('control', lang)
  const pricing = (html: string) => html.replace('href="/pricing"', `href="/pricing/${lang}"`)
  const card = entry && release
    ? [
        '<section class="mt-8 rounded-2xl border border-line bg-surface p-6" aria-label="APK">',
        `<a href="${escape(entry.url)}" class="${BUTTON_PRIMARY}" data-apk="${app}">${escape(page.download)}</a>`,
        `<p class="mt-3 text-sm text-text-2">${escape(page.meta(entry.versionName, entry.versionCode, page.size(entry.bytes)))}</p>`,
        `<p class="mt-2 text-sm text-text-3">${escape(page.fileHash)}: <code class="break-all">${entry.sha256}</code></p>`,
        '</section>',
      ].join('\n')
    : `<section class="mt-8 rounded-2xl border border-dashed border-line p-6" aria-label="APK"><p class="text-text-2" data-apk-unavailable="${app}">${escape(page.notYet)}</p></section>`
  const verify = release
    ? [
        `<p>${escape(page.verifyIntro)}</p>`,
        `<p><strong>${escape(page.verifyFingerprint)}:</strong> <code class="break-all" data-cert>${release.signingCertSha256}</code></p>`,
        `<p>${escape(page.verifyApksigner)}</p>`,
        `<pre class="overflow-x-auto"><code>apksigner verify --print-certs ${escape(entry?.filename ?? `tenshu-${app}.apk`)}</code></pre>`,
        `<p><code class="break-all">${apksignerDigest(release.signingCertSha256)}</code></p>`,
        `<p>${page.verifyFile}</p>`,
      ].join('\n')
    : `<p>${escape(page.verifyNotYet)}</p>`
  const allApps = ANDROID_APPS.map((a) => {
    const e = release?.apps[a]
    const link = a === app ? `<strong>Tenshu ${NAMES[a]}</strong>` : `<a href="${androidDownloadUrl(a, lang)}">Tenshu ${NAMES[a]}</a>`
    return `<li>${link}${e ? ` · ${escape(page.size(e.bytes))}` : ` · ${escape(page.allAppsNotYet)}`}</li>`
  })
  return [
    `<p class="text-lg text-text-2">${escape(page.lede)}</p>`,
    isControl ? '' : `<p>${page.controlFirst(controlHref)}</p>`,
    card,
    `<h2 id="install">${escape(page.installHeading)}</h2>`,
    '<ol>',
    ...page.install(isControl, controlHref).map((step) => `<li>${step}</li>`),
    '</ol>',
    `<h2 id="update">${escape(page.updateHeading)}</h2>`,
    ...page.update.map((p) => `<p>${escape(p)}</p>`),
    `<h2 id="google-play">${escape(page.mixHeading)}</h2>`,
    ...page.mix.map((p) => `<p>${pricing(p)}</p>`),
    `<h2 id="verify">${escape(page.verifyHeading)}</h2>`,
    verify,
    `<h2 id="requirements">${escape(page.requirementsHeading)}</h2>`,
    '<ul>',
    ...page.requirements.map((r) => `<li>${escape(r)}</li>`),
    '</ul>',
    `<h2 id="all-apps">${escape(page.allAppsHeading)}</h2>`,
    `<ul>${allApps.join('')}</ul>`,
    `<p>${page.closing}</p>`,
  ]
    .filter((line) => line !== '')
    .join('\n')
}
