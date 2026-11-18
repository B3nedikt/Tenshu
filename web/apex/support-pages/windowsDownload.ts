// The Windows download pages, `/apps/<app>/download/windows/<lang>` (desktop-windows-port.md §5.1, D6):
// one page per app and language, built by the support-page generator (generate.ts buildWindowsDownloads)
// like the macOS pages (macosDownload.ts), whose rules this follows.
//
//   - The release comes from the committed `store/windows-release.json`, read at BUILD time: no runtime
//     fetch, so no CSP change. `scripts/windows-direct-publish.sh` writes it after the objects are
//     uploaded; its schema is docs/how-to/windows-release.md §3 (schema 1).
//   - ⚠ No file, or no entry for an app, renders "not available yet" and links nowhere — and the app
//     page's Windows button is "Coming soon" (generate.ts withWindowsRelease). A malformed file fails the build.
//   - ⚠⚠ The packages are signed with our own certificate, which Windows does not trust by itself. The
//     page hands out that certificate and the one command that trusts it, and prints its thumbprint so the
//     reader can check what they trust. That trust is threat model A45: say plainly what it means.
//   - One .msix per app, x64, Windows 10 1809+ (the manifest's MinVersion), Control first.
//
// English is the source; German and French are translations nobody has read yet (`reviewed: false`).
// No em dash in the copy (the maintainer's rule for public text).
import { existsSync, readFileSync } from 'node:fs'
import { join } from 'node:path'

/** Every desktop app, in scripts/lib/app-catalogue.sh order (Control first); a page exists for each. */
export const WINDOWS_APPS = ['control', 'drive', 'maps', 'home', 'calendar', 'timeline', 'mia', 'photos', 'fit', 'contacts'] as const
export type WindowsApp = (typeof WINDOWS_APPS)[number]

const NAMES: Record<WindowsApp, string> = {
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

/** The MSIX Identity/@Name each packaged app carries: what the peer gate admits (scripts/lib/windows_publish.py APPS). */
const IDENTITY: Partial<Record<WindowsApp, string>> = {
  control: '62647B3nedikt.TenshuControl',
  drive: 'Tenshu.Drive',
}

export const WINDOWS_DOWNLOAD_LANGUAGES = ['en', 'de', 'fr'] as const

/** The committed release manifest, under `store/`. */
export const WINDOWS_RELEASE_FILE = 'windows-release.json'

export interface WindowsReleaseApp {
  readonly identityName: string
  /** What the browser saves it as (the object's Content-Disposition), `Tenshu<App>-<version>.msix`. */
  readonly file: string
  /** `base` + sha256 + `.msix`. */
  readonly url: string
  readonly sha256: string
  readonly bytes: number
}

export interface WindowsCertificate {
  readonly file: string
  readonly url: string
  readonly sha256: string
  readonly bytes: number
  /** SHA-1, upper-case hex: what Windows calls the thumbprint. */
  readonly thumbprint: string
  readonly notAfter: string
}

/** `store/windows-release.json`, schema 1 (scripts/lib/windows_publish.py). One version for every app (HR #9). */
export interface WindowsRelease {
  readonly schema: 1
  readonly base: string
  readonly version: string
  readonly minimumWindows: string
  readonly publisher: string
  readonly certificate: WindowsCertificate
  readonly published: string
  readonly apps: Partial<Record<WindowsApp, WindowsReleaseApp>>
}

/**
 * The release, or null when no file is committed yet. ⚠ Throws on anything malformed: a broken
 * manifest must fail the build, never publish a page with a wrong link, checksum or thumbprint.
 */
export function loadWindowsRelease(storeDir: string): WindowsRelease | null {
  const path = join(storeDir, WINDOWS_RELEASE_FILE)
  if (!existsSync(path)) return null
  const where = `store/${WINDOWS_RELEASE_FILE}`
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
  if (typeof r.version !== 'string' || !/^\d+\.\d+\.\d+$/.test(r.version)) fail('version must be x.y.z')
  if (r.arch !== 'x64') fail('arch must be x64')
  if (typeof r.minimumWindows !== 'string' || !/^10\.0\.\d+\.\d+$/.test(r.minimumWindows)) fail('minimumWindows must be 10.0.<build>.<rev>')
  if (typeof r.publisher !== 'string' || !r.publisher.startsWith('CN=') || r.publisher === 'CN=Tenshu Local Test') fail('publisher must be a release CN=')
  if (typeof r.published !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(r.published)) fail('published must be YYYY-MM-DD')
  const base = r.base as string
  const version = r.version as string
  const c = r.certificate as Record<string, unknown> | null
  if (typeof c !== 'object' || c === null) fail('certificate must be an object')
  const cert = c as Record<string, unknown>
  if (typeof cert.sha256 !== 'string' || !/^[0-9a-f]{64}$/.test(cert.sha256)) fail('certificate.sha256 must be 64 lowercase hex')
  if (cert.url !== `${base}${cert.sha256}.cer`) fail('certificate.url must be base + sha256 + ".cer"')
  if (typeof cert.file !== 'string' || !/^[A-Za-z0-9]+\.cer$/.test(cert.file)) fail('certificate.file must be <Name>.cer')
  if (typeof cert.thumbprint !== 'string' || !/^[0-9A-F]{40}$/.test(cert.thumbprint)) fail('certificate.thumbprint must be 40 upper-case hex')
  if (typeof cert.notAfter !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(cert.notAfter)) fail('certificate.notAfter must be YYYY-MM-DD')
  if (!Number.isInteger(cert.bytes) || (cert.bytes as number) <= 0) fail('certificate.bytes must be a positive integer')
  if (typeof r.apps !== 'object' || r.apps === null) fail('apps must be an object')
  const apps: Partial<Record<WindowsApp, WindowsReleaseApp>> = {}
  for (const [app, value] of Object.entries(r.apps as Record<string, unknown>)) {
    if (!(WINDOWS_APPS as readonly string[]).includes(app)) fail(`unknown app "${app}"`)
    const e = value as Record<string, unknown>
    const bad = (what: string) => fail(`apps.${app}: ${what}`)
    if (typeof e !== 'object' || e === null) bad('not an object')
    const identity = IDENTITY[app as WindowsApp]
    if (!identity) bad('has no Windows package identity (IDENTITY in windowsDownload.ts)')
    if (e.identityName !== identity) bad(`identityName must be ${identity}`)
    const file = `Tenshu${NAMES[app as WindowsApp]}-${version}.msix`
    if (e.file !== file) bad(`file must be ${file}`)
    if (typeof e.sha256 !== 'string' || !/^[0-9a-f]{64}$/.test(e.sha256)) bad('sha256 must be 64 lowercase hex')
    if (e.url !== `${base}${e.sha256}.msix`) bad('url must be base + sha256 + ".msix"')
    if (!Number.isInteger(e.bytes) || (e.bytes as number) <= 0) bad('bytes must be a positive integer')
    apps[app as WindowsApp] = { identityName: identity!, file, url: e.url as string, sha256: e.sha256 as string, bytes: e.bytes as number }
  }
  return {
    schema: 1,
    base,
    version,
    minimumWindows: r.minimumWindows as string,
    publisher: r.publisher as string,
    certificate: {
      file: cert.file as string,
      url: cert.url as string,
      sha256: cert.sha256 as string,
      bytes: cert.bytes as number,
      thumbprint: cert.thumbprint as string,
      notAfter: cert.notAfter as string,
    },
    published: r.published as string,
    apps,
  }
}

export function windowsDownloadUrl(app: string, lang: string): string {
  return `/apps/${app}/download/windows/${lang}`
}

/** `/apps/<app>/download/windows`: x-default, and the URL Caddy 302s by Accept-Language. */
export function windowsDownloadNeutralUrl(app: string): string {
  return `/apps/${app}/download/windows`
}

export function windowsAppName(app: WindowsApp): string {
  return NAMES[app]
}

/** The Windows build number in a MinVersion (`10.0.17763.0` → 17763). */
const buildOf = (minimum: string) => Number(minimum.split('.')[2])

export interface WindowsDownloadPage {
  readonly reviewed: boolean
  readonly title: (name: string) => string
  readonly description: (name: string) => string
  readonly lede: string
  readonly earlyNote: string
  readonly download: string
  /** "Version 1.0.1 · 311 MB · Windows 10 version 1809 or later, x64" */
  readonly meta: (version: string, size: string, minimum: string) => string
  readonly fileHash: string
  readonly notYet: string
  readonly sectionNotYet: string
  readonly controlFirst: (controlHref: string) => string
  readonly certificateHeading: string
  readonly certificateWhy: string
  readonly certificateSteps: (cerHref: string, cerFile: string) => readonly string[]
  readonly certificateCommand: (cerFile: string, thumbprint: string) => string
  readonly certificateThumbprint: (thumbprint: string, until: string) => string
  /** [subject]: the certificate's name as Windows lists it (the publisher's CN). */
  readonly certificateMeaning: (subject: string) => string
  readonly installHeading: string
  readonly install: (isControl: boolean, name: string, controlHref: string) => readonly string[]
  readonly updateHeading: string
  readonly update: readonly string[]
  readonly storeHeading: string
  readonly store: readonly string[]
  readonly verifyHeading: string
  readonly verifyFile: string
  readonly verifySigner: (thumbprint: string) => string
  readonly uninstallHeading: string
  readonly uninstall: readonly string[]
  readonly requirementsHeading: string
  /** [build] is the MinVersion's build number; 17763 is Windows 10 version 1809. */
  readonly requirements: (build: number) => readonly string[]
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
// The one command that trusts the certificate. ⚠⚠ It checks the thumbprint (from the committed manifest, which the
// bucket key cannot change) BEFORE it imports: comparing afterwards would only detect a swapped .cer once it is
// already trusted (threat model T-WD-01). `TrustedPeople` on the local machine: what MSIX sideloading checks, and
// nothing broader (never `Root`, which would let the certificate vouch for websites).
const trustCommand = (file: string, thumbprint: string) =>
  `$f = "$env:USERPROFILE\\Downloads\\${file}"; if ((Get-PfxCertificate -FilePath $f).Thumbprint -eq '${thumbprint}') ` +
  `{ Import-Certificate -FilePath $f -CertStoreLocation Cert:\\LocalMachine\\TrustedPeople } else { Write-Warning 'NOT the Tenshu certificate. Nothing was added.' }`
const minimumLabel = (build: number, w10: (v: string) => string, other: (b: number) => string) =>
  build === 17763 ? w10('1809') : build === 19041 ? w10('2004') : other(build)

const en: WindowsDownloadPage = {
  reviewed: true,
  title: (name) => `Download Tenshu ${name} for Windows`,
  description: (name) => `Install Tenshu ${name} on a Windows 10 or 11 PC: trust our certificate once, download the app, and check the download.`,
  lede: 'For PCs with Windows 10 or Windows 11. Download the app here.',
  earlyNote: 'This is an early release for Windows. It is not in the Microsoft Store yet, so the apps are signed with Tenshu\'s own certificate, and Windows needs to be told once to trust it.',
  download: 'Download for Windows',
  meta: (v, size, min) => `Version ${v} · ${size} · ${min}, 64-bit (x64)`,
  fileHash: 'SHA-256 of the file',
  notYet: 'Not available yet. The download will appear here once this app is released for Windows.',
  sectionNotYet: 'This appears here once this app is released for Windows.',
  controlFirst: (href) => `Install <a href="${href}">Tenshu Control</a> first. Every other Tenshu app works through it.`,
  certificateHeading: 'Trust our certificate (once per PC)',
  certificateWhy: 'Windows installs an app only if it trusts whoever signed it. Until Tenshu is in the Microsoft Store, we sign the apps ourselves, so you add our certificate once. You need to be an administrator on the PC.',
  certificateSteps: (href, file) => [
    `Download <a href="${href}">${file}</a>, the certificate. It is the public half only: it cannot run anything.`,
    'Open the Start menu, type PowerShell, and choose <strong>Run as administrator</strong>.',
    'Paste this line and press Enter. If the browser saved the file elsewhere or under another name (such as TenshuBeta (1).cer), change the path at its start first.',
  ],
  certificateCommand: (file, t) => trustCommand(file, t),
  certificateThumbprint: (t, until) => `The line first checks that the file is our certificate, thumbprint <code class="break-all">${t}</code>, and adds it only then; PowerShell prints it. It is valid until ${until}. If you see <strong>NOT the Tenshu certificate</strong> instead, nothing was added: delete the file, do not install anything from it, and write to us.`,
  certificateMeaning: (s) => `What this means: your PC will install app packages signed with this certificate, the way it installs Store apps signed by Microsoft. It is not a root certificate, so it does not make Windows trust any website. To undo it, open "Manage computer certificates", go to Trusted People, Certificates, and delete "${s}".`,
  installHeading: 'Install',
  install: (isControl, name, href) => [
    isControl ? 'Install Control before any other Tenshu app: the other apps work through it.' : `Install <a href="${href}">Tenshu Control</a> first, if it is not on your PC yet.`,
    'Trust our certificate, as described above. You do this only once.',
    `Open the downloaded file. Windows shows Tenshu ${name}; choose Install.`,
    isControl ? 'Control starts and guides you through setting up. It keeps running in the notification area next to the clock.' : 'The app finds Control on its own.',
  ],
  updateHeading: 'Update',
  update: [
    'Apps from this page do not update themselves yet. To update, download the latest version here and open it: it replaces the old version, and your data stays.',
    'Update all your Tenshu apps together. An app much older than Control may stop syncing until you update it, and it tells you so.',
  ],
  storeHeading: 'Microsoft Store',
  store: [
    'Tenshu is not in the Microsoft Store yet. Once it is, the Store copy is a separate app from this one: keep one copy of each app on a PC, never two, and uninstall this one before you install from the Store.',
  ],
  verifyHeading: 'Check the download',
  verifyFile: 'Optional, for the careful. To check that the file arrived complete, compare its SHA-256 (<code>Get-FileHash</code> in PowerShell) with the one under the download button.',
  verifySigner: (t) => `To check who signed it, <code>Get-AuthenticodeSignature</code> on the file must name the signer certificate with thumbprint <code class="break-all">${t}</code>.`,
  uninstallHeading: 'Uninstall',
  uninstall: [
    'Settings, Apps, Installed apps: find the Tenshu app and choose Uninstall. Uninstall the other Tenshu apps before Control.',
    'Then remove our certificate if you no longer need it (see above).',
  ],
  requirementsHeading: 'Requirements',
  requirements: (build) => [
    minimumLabel(build, (v) => `Windows 10 version ${v} or later, or Windows 11.`, (b) => `Windows 10 build ${b} or later, or Windows 11.`),
    'A 64-bit PC with an Intel or AMD processor (x64). Windows on Arm is not supported yet.',
    'An administrator account, once, to trust our certificate.',
  ],
  allAppsHeading: 'All Tenshu apps for Windows',
  allAppsNotYet: 'not available yet',
  closing: `Something not working? Write to us at ${SUPPORT}.`,
  size: (b) => `${mb(b, '.')} MB`,
}

const de: WindowsDownloadPage = {
  reviewed: false,
  title: (name) => `Tenshu ${name} für Windows herunterladen`,
  description: (name) => `Tenshu ${name} auf einem PC mit Windows 10 oder 11 installieren: einmal unserem Zertifikat vertrauen, die App herunterladen und den Download prüfen.`,
  lede: 'Für PCs mit Windows 10 oder Windows 11. Lade die App hier herunter.',
  earlyNote: 'Dies ist eine frühe Version für Windows. Sie ist noch nicht im Microsoft Store, darum sind die Apps mit Tenshus eigenem Zertifikat signiert, und Windows muss einmal gesagt bekommen, dass es ihm vertrauen soll.',
  download: 'Für Windows herunterladen',
  meta: (v, size, min) => `Version ${v} · ${size} · ${min}, 64 Bit (x64)`,
  fileHash: 'SHA-256 der Datei',
  notYet: 'Noch nicht verfügbar. Der Download erscheint hier, sobald diese App für Windows erscheint.',
  sectionNotYet: 'Das erscheint hier, sobald diese App für Windows erscheint.',
  controlFirst: (href) => `Installiere zuerst <a href="${href}">Tenshu Control</a>. Jede andere Tenshu-App arbeitet darüber.`,
  certificateHeading: 'Unserem Zertifikat vertrauen (einmal pro PC)',
  certificateWhy: 'Windows installiert eine App nur, wenn es dem Signierenden vertraut. Bis Tenshu im Microsoft Store ist, signieren wir die Apps selbst, deshalb fügst du unser Zertifikat einmal hinzu. Dafür brauchst du Administratorrechte auf dem PC.',
  certificateSteps: (href, file) => [
    `Lade <a href="${href}">${file}</a> herunter, das Zertifikat. Es ist nur der öffentliche Teil: Es kann nichts ausführen.`,
    'Öffne das Startmenü, tippe PowerShell und wähle <strong>Als Administrator ausführen</strong>.',
    'Füge diese Zeile ein und drücke die Eingabetaste. Hat der Browser die Datei woanders oder unter anderem Namen gespeichert (etwa TenshuBeta (1).cer), ändere vorher den Pfad am Anfang.',
  ],
  certificateCommand: (file, t) => trustCommand(file, t),
  certificateThumbprint: (t, until) => `Die Zeile prüft zuerst, ob die Datei unser Zertifikat ist, Fingerabdruck (Thumbprint) <code class="break-all">${t}</code>, und fügt es erst dann hinzu; PowerShell zeigt es an. Es ist gültig bis ${until}. Steht dort stattdessen <strong>NOT the Tenshu certificate</strong>, wurde nichts hinzugefügt: Lösche die Datei, installiere nichts daraus und schreib uns.`,
  certificateMeaning: (s) => `Was das bedeutet: Dein PC installiert App-Pakete, die mit diesem Zertifikat signiert sind, so wie er Store-Apps installiert, die Microsoft signiert hat. Es ist kein Stammzertifikat, Windows vertraut dadurch also keiner Website. Rückgängig machen: „Computerzertifikate verwalten“ öffnen, unter „Vertrauenswürdige Personen“, „Zertifikate“ den Eintrag „${s}“ löschen.`,
  installHeading: 'Installieren',
  install: (isControl, name, href) => [
    isControl ? 'Installiere Control vor jeder anderen Tenshu-App: Die anderen Apps arbeiten darüber.' : `Installiere zuerst <a href="${href}">Tenshu Control</a>, falls es noch nicht auf deinem PC ist.`,
    'Vertraue unserem Zertifikat wie oben beschrieben. Das machst du nur einmal.',
    `Öffne die heruntergeladene Datei. Windows zeigt Tenshu ${name}; wähle „Installieren“.`,
    isControl ? 'Control startet und führt dich durch die Einrichtung. Es läuft im Infobereich neben der Uhr weiter.' : 'Die App findet Control von selbst.',
  ],
  updateHeading: 'Aktualisieren',
  update: [
    'Apps von dieser Seite aktualisieren sich noch nicht selbst. Zum Aktualisieren lädst du hier die neueste Version herunter und öffnest sie: Sie ersetzt die alte Version, und deine Daten bleiben erhalten.',
    'Aktualisiere alle deine Tenshu-Apps gemeinsam. Eine App, die viel älter als Control ist, synchronisiert unter Umständen nicht mehr, bis du sie aktualisierst, und sagt dir das.',
  ],
  storeHeading: 'Microsoft Store',
  store: [
    'Tenshu ist noch nicht im Microsoft Store. Sobald es dort ist, ist die Store-Kopie eine andere App als diese: Behalte von jeder App nur eine Kopie auf einem PC, nie zwei, und deinstalliere diese, bevor du aus dem Store installierst.',
  ],
  verifyHeading: 'Download prüfen',
  verifyFile: 'Optional, für Vorsichtige. Ob die Datei vollständig angekommen ist, prüfst du, indem du ihren SHA-256 (<code>Get-FileHash</code> in PowerShell) mit dem unter dem Download-Button vergleichst.',
  verifySigner: (t) => `Wer sie signiert hat, zeigt <code>Get-AuthenticodeSignature</code> auf der Datei: Das Signaturzertifikat muss den Fingerabdruck <code class="break-all">${t}</code> haben.`,
  uninstallHeading: 'Deinstallieren',
  uninstall: [
    'Einstellungen, Apps, Installierte Apps: die Tenshu-App suchen und „Deinstallieren“ wählen. Deinstalliere die anderen Tenshu-Apps vor Control.',
    'Entferne danach unser Zertifikat, wenn du es nicht mehr brauchst (siehe oben).',
  ],
  requirementsHeading: 'Voraussetzungen',
  requirements: (build) => [
    minimumLabel(build, (v) => `Windows 10 Version ${v} oder neuer, oder Windows 11.`, (b) => `Windows 10 Build ${b} oder neuer, oder Windows 11.`),
    'Ein 64-Bit-PC mit Intel- oder AMD-Prozessor (x64). Windows on Arm wird noch nicht unterstützt.',
    'Einmal ein Administratorkonto, um unserem Zertifikat zu vertrauen.',
  ],
  allAppsHeading: 'Alle Tenshu-Apps für Windows',
  allAppsNotYet: 'noch nicht verfügbar',
  closing: `Funktioniert etwas nicht? Schreib uns an ${SUPPORT}.`,
  size: (b) => `${mb(b, ',')} MB`,
}

const fr: WindowsDownloadPage = {
  reviewed: false,
  title: (name) => `Télécharger Tenshu ${name} pour Windows`,
  description: (name) => `Installez Tenshu ${name} sur un PC Windows 10 ou 11 : faites confiance une fois à notre certificat, téléchargez l’application et vérifiez le téléchargement.`,
  lede: 'Pour les PC sous Windows 10 ou Windows 11. Téléchargez l’application ici.',
  earlyNote: 'Ceci est une première version pour Windows. Elle n’est pas encore dans le Microsoft Store : les applications sont donc signées avec le certificat de Tenshu, et il faut indiquer une fois à Windows de lui faire confiance.',
  download: 'Télécharger pour Windows',
  meta: (v, size, min) => `Version ${v} · ${size} · ${min}, 64 bits (x64)`,
  fileHash: 'SHA-256 du fichier',
  notYet: 'Pas encore disponible. Le téléchargement apparaîtra ici dès que cette application sera publiée pour Windows.',
  sectionNotYet: 'Ceci apparaîtra ici dès que cette application sera publiée pour Windows.',
  controlFirst: (href) => `Installez d’abord <a href="${href}">Tenshu Control</a>. Toutes les autres applications Tenshu passent par lui.`,
  certificateHeading: 'Faire confiance à notre certificat (une fois par PC)',
  certificateWhy: 'Windows n’installe une application que s’il fait confiance à son signataire. Tant que Tenshu n’est pas dans le Microsoft Store, nous signons nous-mêmes les applications : vous ajoutez donc notre certificat une fois. Il faut être administrateur du PC.',
  certificateSteps: (href, file) => [
    `Téléchargez <a href="${href}">${file}</a>, le certificat. Ce n’est que la partie publique : il ne peut rien exécuter.`,
    'Ouvrez le menu Démarrer, tapez PowerShell et choisissez <strong>Exécuter en tant qu’administrateur</strong>.',
    'Collez cette ligne et appuyez sur Entrée. Si le navigateur a enregistré le fichier ailleurs ou sous un autre nom (par exemple TenshuBeta (1).cer), modifiez d’abord le chemin au début.',
  ],
  certificateCommand: (file, t) => trustCommand(file, t),
  certificateThumbprint: (t, until) => `La ligne vérifie d’abord que le fichier est bien notre certificat, d’empreinte (thumbprint) <code class="break-all">${t}</code>, et ne l’ajoute qu’ensuite ; PowerShell l’affiche. Il est valable jusqu’au ${until}. Si vous voyez <strong>NOT the Tenshu certificate</strong> à la place, rien n’a été ajouté : supprimez le fichier, n’installez rien et écrivez-nous.`,
  certificateMeaning: (s) => `Ce que cela signifie : votre PC installera les paquets d’application signés avec ce certificat, comme il installe les applications du Store signées par Microsoft. Ce n’est pas un certificat racine : Windows ne fera donc confiance à aucun site web. Pour annuler : ouvrez « Gérer les certificats de l’ordinateur », allez dans Personnes autorisées, Certificats, et supprimez « ${s} ».`,
  installHeading: 'Installer',
  install: (isControl, name, href) => [
    isControl ? 'Installez Control avant toute autre application Tenshu : les autres applications passent par lui.' : `Installez d’abord <a href="${href}">Tenshu Control</a>, s’il n’est pas encore sur votre PC.`,
    'Faites confiance à notre certificat comme décrit ci-dessus. Une seule fois suffit.',
    `Ouvrez le fichier téléchargé. Windows affiche Tenshu ${name} ; choisissez Installer.`,
    isControl ? 'Control démarre et vous guide pour la configuration. Il reste actif dans la zone de notification, à côté de l’horloge.' : 'L’application trouve Control d’elle-même.',
  ],
  updateHeading: 'Mettre à jour',
  update: [
    'Les applications de cette page ne se mettent pas encore à jour toutes seules. Pour mettre à jour, téléchargez ici la dernière version et ouvrez-la : elle remplace l’ancienne, et vos données sont conservées.',
    'Mettez à jour toutes vos applications Tenshu ensemble. Une application bien plus ancienne que Control peut cesser de se synchroniser jusqu’à sa mise à jour, et elle vous le signale.',
  ],
  storeHeading: 'Microsoft Store',
  store: [
    'Tenshu n’est pas encore dans le Microsoft Store. Une fois qu’il y sera, la copie du Store sera une autre application que celle-ci : gardez une seule copie de chaque application sur un PC, jamais deux, et désinstallez celle-ci avant d’installer depuis le Store.',
  ],
  verifyHeading: 'Vérifier le téléchargement',
  verifyFile: 'Facultatif, pour les prudents. Pour vérifier que le fichier est arrivé complet, comparez son SHA-256 (<code>Get-FileHash</code> dans PowerShell) avec celui affiché sous le bouton de téléchargement.',
  verifySigner: (t) => `Pour vérifier qui l’a signé, <code>Get-AuthenticodeSignature</code> sur le fichier doit indiquer un certificat de signature d’empreinte <code class="break-all">${t}</code>.`,
  uninstallHeading: 'Désinstaller',
  uninstall: [
    'Paramètres, Applications, Applications installées : trouvez l’application Tenshu et choisissez Désinstaller. Désinstallez les autres applications Tenshu avant Control.',
    'Supprimez ensuite notre certificat si vous n’en avez plus besoin (voir ci-dessus).',
  ],
  requirementsHeading: 'Configuration requise',
  requirements: (build) => [
    minimumLabel(build, (v) => `Windows 10 version ${v} ou plus récent, ou Windows 11.`, (b) => `Windows 10 build ${b} ou plus récent, ou Windows 11.`),
    'Un PC 64 bits avec un processeur Intel ou AMD (x64). Windows sur Arm n’est pas encore pris en charge.',
    'Un compte administrateur, une fois, pour faire confiance à notre certificat.',
  ],
  allAppsHeading: 'Toutes les applications Tenshu pour Windows',
  allAppsNotYet: 'pas encore disponible',
  closing: `Quelque chose ne fonctionne pas ? Écrivez-nous à ${SUPPORT}.`,
  size: (b) => `${mb(b, ',')} Mo`,
}

export const WINDOWS_DOWNLOAD: Record<string, WindowsDownloadPage> = { en, de, fr }

const escape = (text: string) =>
  text.replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;').replaceAll('"', '&quot;')

const BUTTON_PRIMARY = 'inline-flex min-h-11 items-center justify-center gap-2 rounded-lg bg-primary px-5 text-sm font-semibold text-on-primary no-underline hover:opacity-90'

/** The short form a requirement line and the card's meta line use. */
function minimumShort(page: WindowsDownloadPage, build: number): string {
  return page.requirements(build)[0]!.replace(/, (or|oder|ou) Windows 11\.$/, '')
}

/**
 * The page body as HTML. The copy is trusted (written above); the release's strings were validated
 * by [loadWindowsRelease] and are escaped anyway. Every `<h2 id>` is bare, so generate.ts lists them in
 * the page's table of contents.
 */
export function windowsDownloadHtml(page: WindowsDownloadPage, app: WindowsApp, lang: string, release: WindowsRelease | null): string {
  const entry = release?.apps[app] ?? null
  const isControl = app === 'control'
  const name = NAMES[app]
  const controlHref = windowsDownloadUrl('control', lang)
  const build = buildOf(release?.minimumWindows ?? '10.0.17763.0')
  const cert = release?.certificate ?? null
  const card = entry && release
    ? [
        '<section class="mt-8 rounded-2xl border border-line bg-surface p-6" aria-label="msix">',
        `<a href="${escape(entry.url)}" class="${BUTTON_PRIMARY}" data-msix="${app}">${escape(page.download)}</a>`,
        `<p class="mt-3 text-sm text-text-2">${escape(page.meta(release.version, page.size(entry.bytes), minimumShort(page, build)))}</p>`,
        `<p class="mt-2 text-sm text-text-3">${escape(page.fileHash)}: <code class="break-all">${entry.sha256}</code></p>`,
        '</section>',
      ].join('\n')
    : `<section class="mt-8 rounded-2xl border border-dashed border-line p-6" aria-label="msix"><p class="text-text-2" data-msix-unavailable="${app}">${escape(page.notYet)}</p></section>`
  const allApps = WINDOWS_APPS.map((a) => {
    const e = release?.apps[a]
    const link = a === app ? `<strong>Tenshu ${NAMES[a]}</strong>` : `<a href="${windowsDownloadUrl(a, lang)}">Tenshu ${NAMES[a]}</a>`
    return `<li>${link}${e ? ` · ${escape(page.size(e.bytes))}` : ` · ${escape(page.allAppsNotYet)}`}</li>`
  })
  return [
    `<p class="text-lg text-text-2">${escape(page.lede)}</p>`,
    `<p>${escape(page.earlyNote)}</p>`,
    isControl ? '' : `<p>${page.controlFirst(controlHref)}</p>`,
    card,
    `<h2 id="certificate">${escape(page.certificateHeading)}</h2>`,
    // ⚠ Only with a published release: the certificate, its thumbprint and the command name real objects.
    ...(entry && cert
      ? [
          `<p>${escape(page.certificateWhy)}</p>`,
          '<ol>',
          ...page.certificateSteps(escape(cert.url), escape(cert.file)).map((step) => `<li>${step}</li>`),
          '</ol>',
          `<pre class="overflow-x-auto" data-trust-command><code>${escape(page.certificateCommand(cert.file, cert.thumbprint))}</code></pre>`,
          `<p data-thumbprint="${cert.thumbprint}">${page.certificateThumbprint(cert.thumbprint, escape(cert.notAfter))}</p>`,
          `<p>${escape(page.certificateMeaning(release!.publisher.replace(/^CN=/, '')))}</p>`,
        ]
      : [`<p data-certificate-unavailable>${escape(page.sectionNotYet)}</p>`]),
    `<h2 id="install">${escape(page.installHeading)}</h2>`,
    '<ol>',
    ...page.install(isControl, name, controlHref).map((step) => `<li>${step}</li>`),
    '</ol>',
    `<h2 id="update">${escape(page.updateHeading)}</h2>`,
    ...page.update.map((p) => `<p>${escape(p)}</p>`),
    `<h2 id="microsoft-store">${escape(page.storeHeading)}</h2>`,
    ...page.store.map((p) => `<p>${escape(p)}</p>`),
    `<h2 id="verify">${escape(page.verifyHeading)}</h2>`,
    ...(entry && cert ? [`<p>${page.verifyFile}</p>`, `<p>${page.verifySigner(cert.thumbprint)}</p>`] : [`<p>${escape(page.sectionNotYet)}</p>`]),
    `<h2 id="uninstall">${escape(page.uninstallHeading)}</h2>`,
    ...page.uninstall.map((p) => `<p>${escape(p)}</p>`),
    `<h2 id="requirements">${escape(page.requirementsHeading)}</h2>`,
    '<ul>',
    ...page.requirements(build).map((r) => `<li>${escape(r)}</li>`),
    '</ul>',
    `<h2 id="all-apps">${escape(page.allAppsHeading)}</h2>`,
    `<ul>${allApps.join('')}</ul>`,
    `<p>${page.closing}</p>`,
  ]
    .filter((line) => line !== '')
    .join('\n')
}
