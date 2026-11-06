// The app marketing pages, `/apps/<app>/<lang>` (store-presence-and-support-pages.md §4.1): what an
// app does like the apps people know, what it does differently, and where to get it. Built by the
// support-page generator (generate.ts buildAppPages) into static pages with the landing page's
// header and footer, like the pricing page; `/apps/<app>` 302s by Accept-Language (web/apex/Caddyfile).
// The landing carousel links an app here once its page exists (landingApps.ts landingAppHref).
//
// The rules, so the pages stay honest:
//   - Compare with no other product (rule D6, EU comparative advertising): "the cloud drive you use
//     today", never a brand. Naming an export format we cannot import yet (Google Takeout) is a
//     fact about Tenshu, not a comparison, and the maintainer asked for it.
//   - No price: the pricing page is the one place that names one, so these link it.
//   - A store button only where the app can be installed from there. Everything else reads "Coming
//     soon" and links nowhere.
//   - No "open source" claim while the repository is private (generate.test.ts checks it).
//   - No em dash in the copy (the maintainer's rule for public text).
//
// English is the source; German and French are translations nobody has read yet (`reviewed: false`).
import { macosDownloadNeutralUrl } from './macosDownload.ts'
import { PRICING_NEUTRAL_URL } from './pricing.ts'

/** Where an app can be installed from, in the order a page lists them. */
export const DOWNLOAD_TARGETS = ['android', 'ios', 'macos', 'web'] as const
export type DownloadTarget = (typeof DOWNLOAD_TARGETS)[number]

export interface ParityRow {
  readonly title: string
  readonly detail: string
  /** yes = there today; soon = not (fully) there yet, the row says what is missing; no = not part of the app. */
  readonly status: 'yes' | 'soon' | 'no'
}

/** The picture beside a "what we do differently" block. */
export type DifferenceWidget =
  /** What our server sees and never sees. */
  | { readonly kind: 'sees'; readonly seesHeading: string; readonly sees: readonly string[]; readonly neverSeesHeading: string; readonly neverSees: readonly string[] }
  /** A share link, its key part highlighted. */
  | { readonly kind: 'shareLink'; readonly url: string; readonly key: string; readonly note: string }
  /** Four steps: from, how, through, to. */
  | { readonly kind: 'flow'; readonly steps: readonly [string, string, string, string] }
  /** A small table of yes/never answers. */
  | { readonly kind: 'table'; readonly heading: string; readonly rows: readonly { readonly label: string; readonly value: string; readonly good: boolean }[] }
  /** A few lines of code, quoted as is. */
  | { readonly kind: 'code'; readonly caption: string; readonly lines: readonly string[] }

export interface Difference {
  readonly kicker: string
  readonly heading: string
  readonly body: string
  readonly widget: DifferenceWidget
}

/** Another Tenshu app this one needs or works with. */
export interface WorksWith {
  readonly heading: string
  readonly body: string
  readonly link: string
  readonly href: string
}

export interface AppPage {
  /** The <title> and the meta description. */
  readonly metaTitle: string
  readonly description: string
  /** true = read by a native speaker. ⚠ false = not yet. */
  readonly reviewed: boolean
  /** The small line above the heading: the app's name. */
  readonly eyebrow: string
  /** The <h1>, in two lines. */
  readonly heading: readonly [string, string]
  readonly lede: string
  /** Each target's button label. */
  readonly targets: Record<DownloadTarget, string>
  /** Under a button whose target is not available yet. */
  readonly comingSoon: string
  /** Shown once the browser says which system it runs; `{platform}` is filled in. */
  readonly detectedReady: string
  readonly detectedSoon: string
  /** The screenshots' alt texts, in screen order. */
  readonly shotAlts: readonly [string, string, string]
  readonly parityHeading: string
  readonly parity: readonly ParityRow[]
  /** The three icons' accessible names. */
  readonly availableLabel: string
  readonly notYetLabel: string
  readonly notPartLabel: string
  readonly differentHeading: string
  readonly differentLede: string
  /** The first one is wide, with its picture beside it; the rest sit side by side. */
  readonly differences: readonly Difference[]
  readonly worksWith: readonly WorksWith[]
  readonly faqHeading: string
  /** [pricing]: the answer ends with the link to the pricing page. */
  readonly faq: readonly { readonly question: string; readonly answer: string; readonly pricing?: true }[]
  readonly pricingLink: string
  readonly helpLink: string
  readonly downloadHeading: string
}

export interface AppPageSet {
  /** The app id: `/apps/<app>/<lang>`. */
  readonly app: string
  /** The targets the page lists, in order, each with its store link or null (not available yet). */
  readonly downloads: readonly { readonly target: DownloadTarget; readonly href: string | null }[]
  /** The three screens the hero shows (manifest screen ids, `NN-name`). */
  readonly screens: readonly [string, string, string]
  /** The capture device: `phonelanding` is the set with the landing page's photos. */
  readonly device: string
  readonly pages: Record<string, AppPage>
}

const playUrl = (pkg: string) => `https://play.google.com/store/apps/details?id=${pkg}`

const driveEn: AppPage = {
  metaTitle: 'Tenshu Drive: your files on every device, end-to-end encrypted',
  description:
    'Folders, automatic backup, search and share links, like the cloud drive you use today. The difference: everything is end-to-end encrypted, so only you can read your files.',
  reviewed: true,
  eyebrow: 'Tenshu Drive',
  heading: ['Your files, on every device.', 'Readable only by you.'],
  lede: 'Folders, automatic backup, search and share links, just like the cloud drive you use today. The difference: everything is end-to-end encrypted.',
  targets: { android: 'Google Play', ios: 'App Store', macos: 'Mac', web: 'Web' },
  comingSoon: 'Coming soon',
  detectedReady: 'We think you’re on {platform}.',
  detectedSoon: 'We think you’re on {platform}. Tenshu Drive for it is coming soon.',
  shotAlts: ['The Files screen with folders and documents', 'A folder of photos in the grid view', 'A photo opened in the viewer'],
  parityHeading: 'Everything you expect from a cloud drive.',
  parity: [
    { title: 'Folders and uploads', detail: 'Upload files and photos, and keep them in folders.', status: 'yes' },
    { title: 'Automatic backup', detail: 'Your phone’s photos back up on their own: the whole library, or only what you choose.', status: 'yes' },
    { title: 'Search', detail: 'Find any file by its name.', status: 'yes' },
    { title: 'Share links', detail: 'Anyone with the link can view; revoke it any time.', status: 'yes' },
    { title: 'Every device', detail: 'Android today. iPhone, iPad, Mac and the web are coming soon.', status: 'soon' },
    { title: '5 GB free sync', detail: 'Sync between your devices for free up to 5 GB. More storage when you need it.', status: 'yes' },
    {
      title: 'Shared folders',
      detail: 'Share a folder with people you’ve connected with, and they can browse and open it. Not yet: adding or changing files in a folder someone shared with you.',
      status: 'soon',
    },
    { title: 'Edit documents together', detail: 'Not part of Drive. Open files in the apps you already use.', status: 'no' },
  ],
  availableLabel: 'Available',
  notYetLabel: 'Not yet',
  notPartLabel: 'Not part of Drive',
  differentHeading: 'What we do differently.',
  differentLede: 'Most cloud drives can read the files you give them. Tenshu Drive is built so that we can’t.',
  differences: [
    {
      kicker: 'End-to-end encrypted',
      heading: 'We store your files. We can’t open them.',
      body: 'Your files are encrypted on your phone before they are uploaded. Tenshu’s servers store only encrypted data, and they don’t have the keys to read it. File and folder names are encrypted too.',
      widget: { kind: 'sees', seesHeading: 'Our server sees', sees: ['How much you store', 'How many devices', 'When they sync'], neverSeesHeading: 'Our server never sees', neverSees: ['Your files', 'File and folder names', 'Your keys'] },
    },
    {
      kicker: 'Share links',
      heading: 'A link that carries its own key.',
      body: 'Anyone with the link can view the file until you revoke it. We deliver the file, but we can’t open it.',
      widget: { kind: 'shareLink', url: 'tenshu.app/s/Xq7f2', key: '#k=9cJ1…wA', note: 'The part after #k= is the key. Browsers never send it to a server.' },
    },
    {
      kicker: 'Local first',
      heading: 'Nothing leaves your phone until you say so.',
      body: 'Without a storage plan, Drive works entirely on your device. Turn on sync in Tenshu Control and your files go to our servers, end-to-end encrypted.',
      widget: { kind: 'flow', steps: ['Your phone', 'encrypted', 'Our servers', 'Your laptop'] },
    },
  ],
  worksWith: [{ heading: 'Drive works with Tenshu Control.', body: 'Control makes sure only your devices can open your data, and connects them. Install it first.', link: 'About Control', href: '/apps/control' }],
  faqHeading: 'Questions',
  faq: [
    {
      question: 'What does it cost?',
      answer: 'The app is free. To sync your files between your devices, you need a storage plan, and the first 5 GB are free. Without a plan, your files stay on the device you added them on.',
      pricing: true,
    },
    {
      question: 'Can I move my files from another cloud drive?',
      answer: 'Yes: download them from your current cloud drive, then upload them to Tenshu Drive. Importing a Google Takeout .zip straight into Drive isn’t supported yet.',
    },
    {
      question: 'What if I lose my phone?',
      answer: 'If your files were synced, nothing is lost. They are still on your other devices. If the phone was your only device, set up Tenshu Control on a new one with your recovery phrase, and your synced files come back. Files that were never synced were only on that phone, and nobody can recover them, not even us.',
    },
  ],
  pricingLink: 'See plans and prices',
  helpLink: 'Drive help and common problems',
  downloadHeading: 'Download from:',
}

const driveDe: AppPage = {
  metaTitle: 'Tenshu Drive: deine Dateien auf jedem Gerät, Ende-zu-Ende-verschlüsselt',
  description:
    'Ordner, automatische Sicherung, Suche und Freigabe-Links, wie bei dem Cloud-Speicher, den du heute nutzt. Der Unterschied: Alles ist Ende-zu-Ende-verschlüsselt, nur du kannst deine Dateien lesen.',
  reviewed: false,
  eyebrow: 'Tenshu Drive',
  heading: ['Deine Dateien, auf jedem Gerät.', 'Lesbar nur für dich.'],
  lede: 'Ordner, automatische Sicherung, Suche und Freigabe-Links, genau wie bei dem Cloud-Speicher, den du heute nutzt. Der Unterschied: Alles ist Ende-zu-Ende-verschlüsselt.',
  targets: { android: 'Google Play', ios: 'App Store', macos: 'Mac', web: 'Web' },
  comingSoon: 'Demnächst',
  detectedReady: 'Du nutzt wohl {platform}.',
  detectedSoon: 'Du nutzt wohl {platform}. Tenshu Drive dafür kommt bald.',
  shotAlts: ['Der Bildschirm „Dateien“ mit Ordnern und Dokumenten', 'Ein Fotoordner in der Rasteransicht', 'Ein Foto in der Vollansicht'],
  parityHeading: 'Alles, was du von einem Cloud-Speicher erwartest.',
  parity: [
    { title: 'Ordner und Uploads', detail: 'Lade Dateien und Fotos hoch und ordne sie in Ordnern.', status: 'yes' },
    { title: 'Automatische Sicherung', detail: 'Die Fotos deines Handys sichern sich von selbst: die ganze Mediathek oder nur, was du auswählst.', status: 'yes' },
    { title: 'Suche', detail: 'Finde jede Datei über ihren Namen.', status: 'yes' },
    { title: 'Freigabe-Links', detail: 'Alle, die den Link haben, können die Datei ansehen; du kannst ihn jederzeit widerrufen.', status: 'yes' },
    { title: 'Jedes Gerät', detail: 'Heute Android. iPhone, iPad, Mac und das Web folgen bald.', status: 'soon' },
    { title: '5 GB kostenlose Synchronisation', detail: 'Synchronisiere bis 5 GB kostenlos zwischen deinen Geräten. Mehr Speicher, wenn du ihn brauchst.', status: 'yes' },
    {
      title: 'Geteilte Ordner',
      detail: 'Teile einen Ordner mit Menschen, mit denen du verbunden bist; sie können ihn durchsuchen und öffnen. Noch nicht: Dateien in einem Ordner hinzufügen oder ändern, den jemand mit dir geteilt hat.',
      status: 'soon',
    },
    { title: 'Dokumente gemeinsam bearbeiten', detail: 'Nicht Teil von Drive. Öffne Dateien in den Apps, die du schon nutzt.', status: 'no' },
  ],
  availableLabel: 'Verfügbar',
  notYetLabel: 'Noch nicht',
  notPartLabel: 'Nicht Teil von Drive',
  differentHeading: 'Was wir anders machen.',
  differentLede: 'Die meisten Cloud-Speicher können die Dateien lesen, die du ihnen gibst. Tenshu Drive ist so gebaut, dass wir es nicht können.',
  differences: [
    {
      kicker: 'Ende-zu-Ende-verschlüsselt',
      heading: 'Wir speichern deine Dateien. Öffnen können wir sie nicht.',
      body: 'Deine Dateien werden auf deinem Handy verschlüsselt, bevor sie hochgeladen werden. Die Server von Tenshu speichern nur verschlüsselte Daten und haben keine Schlüssel, um sie zu lesen. Auch Datei- und Ordnernamen sind verschlüsselt.',
      widget: { kind: 'sees', seesHeading: 'Unser Server sieht', sees: ['Wie viel du speicherst', 'Wie viele Geräte', 'Wann sie synchronisieren'], neverSeesHeading: 'Unser Server sieht nie', neverSees: ['Deine Dateien', 'Datei- und Ordnernamen', 'Deine Schlüssel'] },
    },
    {
      kicker: 'Freigabe-Links',
      heading: 'Ein Link, der seinen eigenen Schlüssel trägt.',
      body: 'Alle, die den Link haben, können die Datei ansehen, bis du ihn widerrufst. Wir liefern die Datei aus, öffnen können wir sie nicht.',
      widget: { kind: 'shareLink', url: 'tenshu.app/s/Xq7f2', key: '#k=9cJ1…wA', note: 'Der Teil nach #k= ist der Schlüssel. Browser senden ihn nie an einen Server.' },
    },
    {
      kicker: 'Lokal zuerst',
      heading: 'Nichts verlässt dein Handy, bis du es willst.',
      body: 'Ohne Speicherplan läuft Drive komplett auf deinem Gerät. Schalte die Synchronisation in Tenshu Control ein, und deine Dateien gehen Ende-zu-Ende-verschlüsselt auf unsere Server.',
      widget: { kind: 'flow', steps: ['Dein Handy', 'verschlüsselt', 'Unsere Server', 'Dein Laptop'] },
    },
  ],
  worksWith: [{ heading: 'Drive funktioniert mit Tenshu Control.', body: 'Control sorgt dafür, dass nur deine Geräte deine Daten öffnen können, und verbindet sie. Installiere es zuerst.', link: 'Über Control', href: '/apps/control' }],
  faqHeading: 'Fragen',
  faq: [
    {
      question: 'Was kostet es?',
      answer: 'Die App ist kostenlos. Um deine Dateien zwischen deinen Geräten zu synchronisieren, brauchst du einen Speicherplan, und die ersten 5 GB sind kostenlos. Ohne Plan bleiben deine Dateien auf dem Gerät, auf dem du sie hinzugefügt hast.',
      pricing: true,
    },
    {
      question: 'Kann ich meine Dateien aus einem anderen Cloud-Speicher übernehmen?',
      answer: 'Ja: Lade sie aus deinem bisherigen Cloud-Speicher herunter und dann in Tenshu Drive hoch. Eine Google-Takeout-ZIP-Datei direkt in Drive zu importieren, geht noch nicht.',
    },
    {
      question: 'Was, wenn ich mein Handy verliere?',
      answer: 'Wenn deine Dateien synchronisiert waren, ist nichts verloren. Sie sind noch auf deinen anderen Geräten. War das Handy dein einziges Gerät, richte Tenshu Control auf einem neuen mit deiner Wiederherstellungsphrase ein, und deine synchronisierten Dateien kommen zurück. Dateien, die nie synchronisiert wurden, waren nur auf diesem Handy, und niemand kann sie wiederherstellen, auch wir nicht.',
    },
  ],
  pricingLink: 'Pläne und Preise ansehen',
  helpLink: 'Hilfe zu Drive und häufige Probleme',
  downloadHeading: 'Herunterladen bei:',
}

const driveFr: AppPage = {
  metaTitle: 'Tenshu Drive : vos fichiers sur tous vos appareils, chiffrés de bout en bout',
  description:
    'Dossiers, sauvegarde automatique, recherche et liens de partage, comme le stockage en ligne que vous utilisez aujourd’hui. La différence : tout est chiffré de bout en bout, vous seul pouvez lire vos fichiers.',
  reviewed: false,
  eyebrow: 'Tenshu Drive',
  heading: ['Vos fichiers, sur tous vos appareils.', 'Lisibles par vous seul.'],
  lede: 'Dossiers, sauvegarde automatique, recherche et liens de partage, comme le stockage en ligne que vous utilisez aujourd’hui. La différence : tout est chiffré de bout en bout.',
  targets: { android: 'Google Play', ios: 'App Store', macos: 'Mac', web: 'Web' },
  comingSoon: 'Bientôt',
  detectedReady: 'Vous semblez utiliser {platform}.',
  detectedSoon: 'Vous semblez utiliser {platform}. Tenshu Drive y arrive bientôt.',
  shotAlts: ['L’écran Fichiers avec des dossiers et des documents', 'Un dossier de photos en grille', 'Une photo ouverte dans la visionneuse'],
  parityHeading: 'Tout ce que vous attendez d’un stockage en ligne.',
  parity: [
    { title: 'Dossiers et envois', detail: 'Envoyez des fichiers et des photos, et rangez-les dans des dossiers.', status: 'yes' },
    { title: 'Sauvegarde automatique', detail: 'Les photos de votre téléphone se sauvegardent toutes seules : toute la photothèque, ou seulement ce que vous choisissez.', status: 'yes' },
    { title: 'Recherche', detail: 'Retrouvez n’importe quel fichier par son nom.', status: 'yes' },
    { title: 'Liens de partage', detail: 'Toute personne disposant du lien peut voir le fichier ; révoquez-le quand vous voulez.', status: 'yes' },
    { title: 'Tous vos appareils', detail: 'Android aujourd’hui. iPhone, iPad, Mac et le web arrivent bientôt.', status: 'soon' },
    { title: '5 Go de synchronisation gratuite', detail: 'Synchronisez gratuitement jusqu’à 5 Go entre vos appareils. Plus d’espace quand il vous en faut.', status: 'yes' },
    {
      title: 'Dossiers partagés',
      detail: 'Partagez un dossier avec les personnes avec qui vous êtes connecté ; elles peuvent le parcourir et l’ouvrir. Pas encore : ajouter ou modifier des fichiers dans un dossier que quelqu’un a partagé avec vous.',
      status: 'soon',
    },
    { title: 'Modifier des documents à plusieurs', detail: 'Ne fait pas partie de Drive. Ouvrez vos fichiers dans les apps que vous utilisez déjà.', status: 'no' },
  ],
  availableLabel: 'Disponible',
  notYetLabel: 'Pas encore',
  notPartLabel: 'Hors de Drive',
  differentHeading: 'Ce que nous faisons autrement.',
  differentLede: 'La plupart des stockages en ligne peuvent lire les fichiers que vous leur confiez. Tenshu Drive est conçu pour que nous ne le puissions pas.',
  differences: [
    {
      kicker: 'Chiffré de bout en bout',
      heading: 'Nous stockons vos fichiers. Nous ne pouvons pas les ouvrir.',
      body: 'Vos fichiers sont chiffrés sur votre téléphone avant d’être envoyés. Les serveurs de Tenshu ne stockent que des données chiffrées et n’ont pas les clés pour les lire. Les noms de fichiers et de dossiers sont chiffrés aussi.',
      widget: { kind: 'sees', seesHeading: 'Notre serveur voit', sees: ['Combien vous stockez', 'Combien d’appareils', 'Quand ils se synchronisent'], neverSeesHeading: 'Notre serveur ne voit jamais', neverSees: ['Vos fichiers', 'Les noms de fichiers et de dossiers', 'Vos clés'] },
    },
    {
      kicker: 'Liens de partage',
      heading: 'Un lien qui porte sa propre clé.',
      body: 'Toute personne disposant du lien peut voir le fichier jusqu’à ce que vous le révoquiez. Nous livrons le fichier, mais nous ne pouvons pas l’ouvrir.',
      widget: { kind: 'shareLink', url: 'tenshu.app/s/Xq7f2', key: '#k=9cJ1…wA', note: 'La partie après #k= est la clé. Les navigateurs ne l’envoient jamais à un serveur.' },
    },
    {
      kicker: 'Local d’abord',
      heading: 'Rien ne quitte votre téléphone sans votre accord.',
      body: 'Sans offre de stockage, Drive fonctionne entièrement sur votre appareil. Activez la synchronisation dans Tenshu Control et vos fichiers partent sur nos serveurs, chiffrés de bout en bout.',
      widget: { kind: 'flow', steps: ['Votre téléphone', 'chiffré', 'Nos serveurs', 'Votre ordinateur'] },
    },
  ],
  worksWith: [{ heading: 'Drive fonctionne avec Tenshu Control.', body: 'Control fait en sorte que seuls vos appareils puissent ouvrir vos données, et les relie. Installez-le d’abord.', link: 'À propos de Control', href: '/apps/control' }],
  faqHeading: 'Questions',
  faq: [
    {
      question: 'Combien ça coûte ?',
      answer: 'L’app est gratuite. Pour synchroniser vos fichiers entre vos appareils, il vous faut une offre de stockage, et les 5 premiers Go sont gratuits. Sans offre, vos fichiers restent sur l’appareil où vous les avez ajoutés.',
      pricing: true,
    },
    {
      question: 'Puis-je transférer mes fichiers depuis un autre stockage en ligne ?',
      answer: 'Oui : téléchargez-les depuis votre stockage actuel, puis envoyez-les dans Tenshu Drive. Importer directement un .zip Google Takeout dans Drive n’est pas encore possible.',
    },
    {
      question: 'Et si je perds mon téléphone ?',
      answer: 'Si vos fichiers étaient synchronisés, rien n’est perdu : ils sont encore sur vos autres appareils. Si le téléphone était votre seul appareil, installez Tenshu Control sur un nouveau avec votre phrase de récupération, et vos fichiers synchronisés reviennent. Les fichiers jamais synchronisés n’étaient que sur ce téléphone, et personne ne peut les récupérer, pas même nous.',
    },
  ],
  pricingLink: 'Voir les offres et les prix',
  helpLink: 'Aide sur Drive et problèmes fréquents',
  downloadHeading: 'Télécharger sur :',
}

const photosEn: AppPage = {
  metaTitle: 'Tenshu Photos: your photo library on every device, end-to-end encrypted',
  description:
    'Your photos and videos in one library: by day, in albums, with a scrubber through the years. The difference: it’s all end-to-end encrypted, so no cloud can look through it.',
  reviewed: true,
  eyebrow: 'Tenshu Photos',
  heading: ['Every photo you’ve taken.', 'Seen only by you.'],
  lede: 'Your photos and videos in one library, on every device: by day, in albums, with a scrubber through the years. The difference: it’s all end-to-end encrypted, so no cloud can look through it.',
  targets: { android: 'Google Play', ios: 'App Store', macos: 'Mac', web: 'Web' },
  comingSoon: 'Coming soon',
  detectedReady: 'We think you’re on {platform}.',
  detectedSoon: 'We think you’re on {platform}. Tenshu Photos for it is coming soon.',
  shotAlts: ['The photo library, grouped by day', 'An album of photos', 'A photo opened in the viewer'],
  parityHeading: 'Everything you expect from a photo app.',
  parity: [
    { title: 'Automatic backup', detail: 'Your camera and the folders you choose back up on their own, through Tenshu Drive.', status: 'yes' },
    { title: 'By day, month or year', detail: 'A mosaic grouped by day, and a scrubber to jump through the years.', status: 'yes' },
    { title: 'Albums and favourites', detail: 'Create, rename and fill albums. Mark the photos you love.', status: 'yes' },
    { title: 'Videos', detail: 'In the same library, playing silently in the grid.', status: 'yes' },
    { title: 'Duplicates', detail: 'Find exact copies, keep one, and trash the rest.', status: 'yes' },
    { title: 'Trash', detail: 'Restore what you deleted, or delete it for good.', status: 'yes' },
    { title: 'Every device', detail: 'Android today. iPhone, iPad and the web are coming soon.', status: 'soon' },
    { title: 'People', detail: 'Faces grouped on your Android phone, once you turn it on. Not yet on iPhone.', status: 'soon' },
    { title: 'Search', detail: 'By file name. On Android also by what is in a photo, on your phone, once you turn it on. Not yet on iPhone.', status: 'soon' },
    { title: 'Sharing and editing', detail: 'Not yet. For now, share a photo by link from Tenshu Drive.', status: 'soon' },
  ],
  availableLabel: 'Available',
  notYetLabel: 'Not yet',
  notPartLabel: 'Not part of Photos',
  differentHeading: 'What we do differently.',
  differentLede: 'Most photo clouds look through your pictures to sort them. Tenshu Photos is built so that we can’t.',
  differences: [
    {
      kicker: 'End-to-end encrypted',
      heading: 'We keep your library. We can’t look at it.',
      body: 'Your photos are end-to-end encrypted before they leave your phone, and so are your albums and favourites. Only your own devices can open them.',
      widget: {
        kind: 'sees',
        seesHeading: 'Our server sees',
        sees: ['How much you store', 'How many devices', 'When they sync'],
        neverSeesHeading: 'Our server never sees',
        neverSees: ['Your photos and videos', 'Your albums, favourites', 'Who is in them'],
      },
    },
    {
      kicker: 'On your device',
      heading: 'People, grouped on your phone.',
      body: 'Turn on “Group photos by people” in Tenshu Control, and your Android phone finds the faces itself. The groups stay in your encrypted library, like your albums.',
      widget: {
        kind: 'table',
        heading: 'Where faces are grouped',
        rows: [
          { label: 'On your phone', value: 'Yes', good: true },
          { label: 'On our servers', value: 'Never', good: false },
          { label: 'Before you turn it on', value: 'Never', good: false },
        ],
      },
    },
    {
      kicker: 'No ads, no analytics',
      heading: 'An app that can’t phone home.',
      body: 'Photos has no ads and no analytics. On Android it can’t even use the internet itself: your photos reach your other devices through Tenshu Control, end-to-end encrypted.',
      widget: {
        kind: 'code',
        caption: 'From Photos’ Android manifest',
        lines: ['<uses-permission', '  android:name="android.permission.INTERNET"', '  tools:node="remove" />'],
      },
    },
  ],
  worksWith: [
    { heading: 'Tenshu Control, required', body: 'Makes sure only your devices can open your data, and connects them. Install it first.', link: 'About Control', href: '/apps/control' },
    { heading: 'Tenshu Drive, for backup', body: 'Drive backs up your photos; Photos shows them. Install both to back up from this phone.', link: 'About Drive', href: '/apps/drive' },
  ],
  faqHeading: 'Questions',
  faq: [
    {
      question: 'What does it cost?',
      answer: 'The app is free. To sync your library between your devices, you need a storage plan, and the first 5 GB are free. Without a plan, your photos stay on this phone.',
      pricing: true,
    },
    {
      question: 'Can I bring my photos from another service?',
      answer: 'Yes: download your library from your current service and back up the folder with Tenshu Drive. Importing a Google Takeout .zip straight into Photos isn’t supported yet.',
    },
    {
      question: 'Does Photos read the photos on my phone?',
      answer: 'On iPhone and iPad, no: Drive backs up the photos you choose, and Photos shows them from your encrypted library. On Android, only if you allow it, and only to list this phone’s folders so you can pick which ones to back up.',
    },
    {
      question: 'Can I share or edit a photo?',
      answer: 'Not in Photos yet. To send a photo to someone, share it by link from Tenshu Drive.',
    },
  ],
  pricingLink: 'See plans and prices',
  helpLink: 'Photos help and common problems',
  downloadHeading: 'Download from:',
}

const photosDe: AppPage = {
  metaTitle: 'Tenshu Photos: deine Fotomediathek auf jedem Gerät, Ende-zu-Ende-verschlüsselt',
  description:
    'Deine Fotos und Videos in einer Mediathek: nach Tagen, in Alben, mit einem Schieberegler durch die Jahre. Der Unterschied: Alles ist Ende-zu-Ende-verschlüsselt, keine Cloud kann es durchsehen.',
  reviewed: false,
  eyebrow: 'Tenshu Photos',
  heading: ['Jedes Foto, das du gemacht hast.', 'Nur für dich sichtbar.'],
  lede: 'Deine Fotos und Videos in einer Mediathek, auf jedem Gerät: nach Tagen, in Alben, mit einem Schieberegler durch die Jahre. Der Unterschied: Alles ist Ende-zu-Ende-verschlüsselt, keine Cloud kann es durchsehen.',
  targets: { android: 'Google Play', ios: 'App Store', macos: 'Mac', web: 'Web' },
  comingSoon: 'Demnächst',
  detectedReady: 'Du nutzt wohl {platform}.',
  detectedSoon: 'Du nutzt wohl {platform}. Tenshu Photos dafür kommt bald.',
  shotAlts: ['Die Fotomediathek, nach Tagen gruppiert', 'Ein Fotoalbum', 'Ein Foto in der Vollansicht'],
  parityHeading: 'Alles, was du von einer Foto-App erwartest.',
  parity: [
    { title: 'Automatische Sicherung', detail: 'Deine Kamera und die Ordner, die du auswählst, sichern sich von selbst, über Tenshu Drive.', status: 'yes' },
    { title: 'Nach Tag, Monat oder Jahr', detail: 'Ein Mosaik nach Tagen und ein Schieberegler, um durch die Jahre zu springen.', status: 'yes' },
    { title: 'Alben und Favoriten', detail: 'Lege Alben an, benenne sie um und fülle sie. Markiere die Fotos, die du liebst.', status: 'yes' },
    { title: 'Videos', detail: 'In derselben Mediathek, stumm abgespielt im Raster.', status: 'yes' },
    { title: 'Duplikate', detail: 'Finde exakte Kopien, behalte eine und lege den Rest in den Papierkorb.', status: 'yes' },
    { title: 'Papierkorb', detail: 'Stelle Gelöschtes wieder her oder lösche es endgültig.', status: 'yes' },
    { title: 'Jedes Gerät', detail: 'Heute Android. iPhone, iPad und das Web folgen bald.', status: 'soon' },
    { title: 'Personen', detail: 'Gesichter, gruppiert auf deinem Android-Handy, sobald du die Funktion einschaltest. Auf dem iPhone noch nicht.', status: 'soon' },
    { title: 'Suche', detail: 'Nach Dateinamen. Auf Android auch nach dem, was auf einem Foto ist, direkt auf deinem Handy, sobald du die Funktion einschaltest. Auf dem iPhone noch nicht.', status: 'soon' },
    { title: 'Teilen und Bearbeiten', detail: 'Noch nicht. Teile ein Foto vorerst per Link aus Tenshu Drive.', status: 'soon' },
  ],
  availableLabel: 'Verfügbar',
  notYetLabel: 'Noch nicht',
  notPartLabel: 'Nicht Teil von Photos',
  differentHeading: 'Was wir anders machen.',
  differentLede: 'Die meisten Foto-Clouds sehen deine Bilder durch, um sie zu sortieren. Tenshu Photos ist so gebaut, dass wir es nicht können.',
  differences: [
    {
      kicker: 'Ende-zu-Ende-verschlüsselt',
      heading: 'Wir verwahren deine Mediathek. Ansehen können wir sie nicht.',
      body: 'Deine Fotos werden Ende-zu-Ende-verschlüsselt, bevor sie dein Handy verlassen, ebenso deine Alben und Favoriten. Nur deine eigenen Geräte können sie öffnen.',
      widget: {
        kind: 'sees',
        seesHeading: 'Unser Server sieht',
        sees: ['Wie viel du speicherst', 'Wie viele Geräte', 'Wann sie synchronisieren'],
        neverSeesHeading: 'Unser Server sieht nie',
        neverSees: ['Deine Fotos und Videos', 'Deine Alben, Favoriten', 'Wer darauf zu sehen ist'],
      },
    },
    {
      kicker: 'Auf deinem Gerät',
      heading: 'Personen, gruppiert auf deinem Handy.',
      body: 'Schalte „Group photos by people“ in Tenshu Control ein, und dein Android-Handy findet die Gesichter selbst. Die Gruppen bleiben in deiner verschlüsselten Mediathek, wie deine Alben.',
      widget: {
        kind: 'table',
        heading: 'Wo Gesichter gruppiert werden',
        rows: [
          { label: 'Auf deinem Handy', value: 'Ja', good: true },
          { label: 'Auf unseren Servern', value: 'Nie', good: false },
          { label: 'Bevor du die Funktion einschaltest', value: 'Nie', good: false },
        ],
      },
    },
    {
      kicker: 'Keine Werbung, keine Analyse',
      heading: 'Eine App, die nicht nach Hause telefonieren kann.',
      body: 'Photos hat keine Werbung und keine Analyse. Auf Android darf es nicht einmal selbst ins Internet: Deine Fotos erreichen deine anderen Geräte über Tenshu Control, Ende-zu-Ende-verschlüsselt.',
      widget: {
        kind: 'code',
        caption: 'Aus dem Android-Manifest von Photos',
        lines: ['<uses-permission', '  android:name="android.permission.INTERNET"', '  tools:node="remove" />'],
      },
    },
  ],
  worksWith: [
    { heading: 'Tenshu Control, erforderlich', body: 'Sorgt dafür, dass nur deine Geräte deine Daten öffnen können, und verbindet sie. Installiere es zuerst.', link: 'Über Control', href: '/apps/control' },
    { heading: 'Tenshu Drive, für die Sicherung', body: 'Drive sichert deine Fotos, Photos zeigt sie. Installiere beide, um von diesem Handy zu sichern.', link: 'Über Drive', href: '/apps/drive' },
  ],
  faqHeading: 'Fragen',
  faq: [
    {
      question: 'Was kostet es?',
      answer: 'Die App ist kostenlos. Um deine Mediathek zwischen deinen Geräten zu synchronisieren, brauchst du einen Speicherplan, und die ersten 5 GB sind kostenlos. Ohne Plan bleiben deine Fotos auf diesem Handy.',
      pricing: true,
    },
    {
      question: 'Kann ich meine Fotos aus einem anderen Dienst mitnehmen?',
      answer: 'Ja: Lade deine Mediathek aus deinem bisherigen Dienst herunter und sichere den Ordner mit Tenshu Drive. Eine Google-Takeout-ZIP-Datei direkt in Photos zu importieren, geht noch nicht.',
    },
    {
      question: 'Liest Photos die Fotos auf meinem Handy?',
      answer: 'Auf iPhone und iPad nicht: Drive sichert die Fotos, die du auswählst, und Photos zeigt sie aus deiner verschlüsselten Mediathek. Auf Android nur, wenn du es erlaubst, und nur, um die Ordner dieses Handys aufzulisten, damit du auswählen kannst, welche gesichert werden.',
    },
    {
      question: 'Kann ich ein Foto teilen oder bearbeiten?',
      answer: 'In Photos noch nicht. Um jemandem ein Foto zu schicken, teile es per Link aus Tenshu Drive.',
    },
  ],
  pricingLink: 'Pläne und Preise ansehen',
  helpLink: 'Hilfe zu Photos und häufige Probleme',
  downloadHeading: 'Herunterladen bei:',
}

const photosFr: AppPage = {
  metaTitle: 'Tenshu Photos : votre photothèque sur tous vos appareils, chiffrée de bout en bout',
  description:
    'Vos photos et vidéos dans une seule photothèque : par jour, en albums, avec un curseur pour parcourir les années. La différence : tout est chiffré de bout en bout, aucun cloud ne peut la parcourir.',
  reviewed: false,
  eyebrow: 'Tenshu Photos',
  heading: ['Chaque photo que vous avez prise.', 'Visible par vous seul.'],
  lede: 'Vos photos et vidéos dans une seule photothèque, sur tous vos appareils : par jour, en albums, avec un curseur pour parcourir les années. La différence : tout est chiffré de bout en bout, aucun cloud ne peut la parcourir.',
  targets: { android: 'Google Play', ios: 'App Store', macos: 'Mac', web: 'Web' },
  comingSoon: 'Bientôt',
  detectedReady: 'Vous semblez utiliser {platform}.',
  detectedSoon: 'Vous semblez utiliser {platform}. Tenshu Photos y arrive bientôt.',
  shotAlts: ['La photothèque, groupée par jour', 'Un album de photos', 'Une photo ouverte dans la visionneuse'],
  parityHeading: 'Tout ce que vous attendez d’une app photo.',
  parity: [
    { title: 'Sauvegarde automatique', detail: 'Votre appareil photo et les dossiers que vous choisissez se sauvegardent tout seuls, via Tenshu Drive.', status: 'yes' },
    { title: 'Par jour, mois ou année', detail: 'Une mosaïque groupée par jour, et un curseur pour sauter d’une année à l’autre.', status: 'yes' },
    { title: 'Albums et favoris', detail: 'Créez, renommez et remplissez des albums. Marquez les photos que vous aimez.', status: 'yes' },
    { title: 'Vidéos', detail: 'Dans la même photothèque, lues sans le son dans la grille.', status: 'yes' },
    { title: 'Doublons', detail: 'Trouvez les copies exactes, gardez-en une et mettez le reste à la corbeille.', status: 'yes' },
    { title: 'Corbeille', detail: 'Restaurez ce que vous avez supprimé, ou supprimez-le définitivement.', status: 'yes' },
    { title: 'Tous vos appareils', detail: 'Android aujourd’hui. iPhone, iPad et le web arrivent bientôt.', status: 'soon' },
    { title: 'Personnes', detail: 'Visages regroupés sur votre téléphone Android, une fois l’option activée. Pas encore sur iPhone.', status: 'soon' },
    { title: 'Recherche', detail: 'Par nom de fichier. Sur Android, aussi selon le contenu d’une photo, sur votre téléphone, une fois l’option activée. Pas encore sur iPhone.', status: 'soon' },
    { title: 'Partage et retouche', detail: 'Pas encore. En attendant, partagez une photo par lien depuis Tenshu Drive.', status: 'soon' },
  ],
  availableLabel: 'Disponible',
  notYetLabel: 'Pas encore',
  notPartLabel: 'Hors de Photos',
  differentHeading: 'Ce que nous faisons autrement.',
  differentLede: 'La plupart des clouds photo parcourent vos images pour les trier. Tenshu Photos est conçu pour que nous ne le puissions pas.',
  differences: [
    {
      kicker: 'Chiffré de bout en bout',
      heading: 'Nous gardons votre photothèque. Nous ne pouvons pas la regarder.',
      body: 'Vos photos sont chiffrées de bout en bout avant de quitter votre téléphone, tout comme vos albums et vos favoris. Seuls vos propres appareils peuvent les ouvrir.',
      widget: {
        kind: 'sees',
        seesHeading: 'Notre serveur voit',
        sees: ['Combien vous stockez', 'Combien d’appareils', 'Quand ils se synchronisent'],
        neverSeesHeading: 'Notre serveur ne voit jamais',
        neverSees: ['Vos photos et vidéos', 'Vos albums, favoris', 'Qui y figure'],
      },
    },
    {
      kicker: 'Sur votre appareil',
      heading: 'Les personnes, regroupées sur votre téléphone.',
      body: 'Activez « Group photos by people » dans Tenshu Control, et votre téléphone Android trouve lui-même les visages. Les groupes restent dans votre photothèque chiffrée, comme vos albums.',
      widget: {
        kind: 'table',
        heading: 'Où les visages sont regroupés',
        rows: [
          { label: 'Sur votre téléphone', value: 'Oui', good: true },
          { label: 'Sur nos serveurs', value: 'Jamais', good: false },
          { label: 'Avant que vous l’activiez', value: 'Jamais', good: false },
        ],
      },
    },
    {
      kicker: 'Ni publicité, ni statistiques',
      heading: 'Une app qui ne peut pas se connecter d’elle-même.',
      body: 'Photos n’a ni publicité ni statistiques. Sur Android, elle ne peut même pas utiliser Internet elle-même : vos photos rejoignent vos autres appareils via Tenshu Control, chiffrées de bout en bout.',
      widget: {
        kind: 'code',
        caption: 'Extrait du manifeste Android de Photos',
        lines: ['<uses-permission', '  android:name="android.permission.INTERNET"', '  tools:node="remove" />'],
      },
    },
  ],
  worksWith: [
    { heading: 'Tenshu Control, indispensable', body: 'Fait en sorte que seuls vos appareils puissent ouvrir vos données, et les relie. Installez-le d’abord.', link: 'À propos de Control', href: '/apps/control' },
    { heading: 'Tenshu Drive, pour la sauvegarde', body: 'Drive sauvegarde vos photos, Photos les affiche. Installez les deux pour sauvegarder depuis ce téléphone.', link: 'À propos de Drive', href: '/apps/drive' },
  ],
  faqHeading: 'Questions',
  faq: [
    {
      question: 'Combien ça coûte ?',
      answer: 'L’app est gratuite. Pour synchroniser votre photothèque entre vos appareils, il vous faut une offre de stockage, et les 5 premiers Go sont gratuits. Sans offre, vos photos restent sur ce téléphone.',
      pricing: true,
    },
    {
      question: 'Puis-je récupérer mes photos d’un autre service ?',
      answer: 'Oui : téléchargez votre photothèque depuis votre service actuel et sauvegardez le dossier avec Tenshu Drive. Importer directement un .zip Google Takeout dans Photos n’est pas encore possible.',
    },
    {
      question: 'Photos lit-elle les photos de mon téléphone ?',
      answer: 'Sur iPhone et iPad, non : Drive sauvegarde les photos que vous choisissez, et Photos les affiche depuis votre photothèque chiffrée. Sur Android, seulement si vous l’autorisez, et seulement pour lister les dossiers de ce téléphone afin que vous choisissiez lesquels sauvegarder.',
    },
    {
      question: 'Puis-je partager ou retoucher une photo ?',
      answer: 'Pas encore dans Photos. Pour envoyer une photo à quelqu’un, partagez-la par lien depuis Tenshu Drive.',
    },
  ],
  pricingLink: 'Voir les offres et les prix',
  helpLink: 'Aide sur Photos et problèmes fréquents',
  downloadHeading: 'Télécharger sur :',
}

// Control's copy has no canvas design of its own: it follows Drive's and Photos' pages, and every
// privacy sentence is one of store/control/claims.md's (en and de there, word for word where the
// sentence is the same). Features follow Control's Play description.
const controlEn: AppPage = {
  metaTitle: 'Tenshu Control: your data, your devices and your sync in one app',
  description:
    'Control is the app the other Tenshu apps need. It makes sure only your devices can open your data, connects them and keeps your apps in sync. On one device, no account needed.',
  reviewed: true,
  eyebrow: 'Tenshu Control',
  heading: ['You control your data.', 'Your apps stay in sync.'],
  lede: 'Control is the app the other Tenshu apps need. It connects your devices and keeps your apps in sync. The difference: only your devices can open your data, never our servers.',
  targets: { android: 'Google Play', ios: 'App Store', macos: 'Mac', web: 'Web' },
  comingSoon: 'Coming soon',
  detectedReady: 'We think you’re on {platform}.',
  detectedSoon: 'We think you’re on {platform}. Tenshu Control for it is coming soon.',
  shotAlts: ['Setup: protected by your device, with no password to remember', 'Adding a device with a QR code', 'Your recovery phrase, 24 words'],
  parityHeading: 'Your devices and your sync in one place.',
  parity: [
    { title: 'One realm for every app', detail: 'Drive, Photos, Calendar and the other Tenshu apps all use the realm Control sets up.', status: 'yes' },
    { title: 'Sign in with your email', detail: 'Only when you want to sync. A new account starts on the free plan.', status: 'yes' },
    { title: 'Add your other devices', detail: 'One device shows a code, the other scans it, and both show the same confirmation code.', status: 'yes' },
    { title: 'Recovery', detail: 'Set up a recovery phrase of 24 words, and it brings back your realm and everything you synced if you lose every device.', status: 'yes' },
    { title: 'App lock', detail: 'Control asks for your fingerprint, face or screen lock before it opens.', status: 'yes' },
    { title: 'Storage and plans', detail: 'See how much you use, and change your plan, under Services.', status: 'yes' },
    { title: 'Your share links', detail: 'See and revoke the links you shared from Tenshu Drive.', status: 'yes' },
    { title: 'Wi-Fi only', detail: 'Choose whether background sync and photo backups wait for Wi-Fi.', status: 'yes' },
    { title: 'Every device', detail: 'Android today. iPhone, iPad, Mac and the web are coming soon.', status: 'soon' },
  ],
  availableLabel: 'Available',
  notYetLabel: 'Not yet',
  notPartLabel: 'Not part of Control',
  differentHeading: 'What we do differently.',
  differentLede: 'With many services, the provider can open your data. With Tenshu, only your devices can.',
  differences: [
    {
      kicker: 'End-to-end encrypted',
      heading: 'Only your devices can open your data.',
      body: 'Your data is encrypted on your devices before it goes anywhere, and only your devices, and your recovery phrase, can unlock it. Tenshu’s servers cannot read your data: they pass on and store only encrypted data. Only what you share leaves that circle.',
      widget: {
        kind: 'sees',
        seesHeading: 'Our server sees',
        sees: ['How much you store', 'How many devices', 'When they sync'],
        neverSeesHeading: 'Our server never sees',
        neverSees: ['Your data', 'What unlocks it', 'Your recovery phrase'],
      },
    },
    {
      kicker: 'Adding a device',
      heading: 'A new device gets in. Our server doesn’t.',
      body: 'When you add a device, your existing device hands it what it needs to open your data, encrypted for that device alone, so our server can’t read it on the way.',
      widget: { kind: 'flow', steps: ['This phone', 'encrypted', 'Our server', 'Your new device'] },
    },
    {
      kicker: 'No account needed',
      heading: 'An account only when you want one.',
      body: 'On one device, Tenshu works with no account and sends nothing to our servers. Your recovery phrase is yours alone: nobody else holds a copy, so nobody, including us, can restore your realm without it.',
      widget: {
        kind: 'table',
        heading: 'What you need',
        rows: [
          { label: 'Tenshu on one device', value: 'Nothing', good: true },
          { label: 'Sync between devices', value: 'An email address', good: false },
          { label: 'Who holds your recovery phrase', value: 'Only you', good: true },
        ],
      },
    },
  ],
  worksWith: [
    { heading: 'Tenshu Drive', body: 'Your files, end-to-end encrypted. Needs Control on the same phone.', link: 'About Drive', href: '/apps/drive' },
    { heading: 'Tenshu Photos', body: 'Your photo library, end-to-end encrypted. Needs Control on the same phone.', link: 'About Photos', href: '/apps/photos' },
  ],
  faqHeading: 'Questions',
  faq: [
    {
      question: 'What does it cost?',
      answer: 'Control and every Tenshu app are free. To sync between your devices, you need a storage plan, and the first 5 GB are free.',
      pricing: true,
    },
    {
      question: 'Do I need an account?',
      answer: 'Not on one device: Tenshu works with no account and sends nothing to our servers. To sync between devices, enter your email address, and a new account starts on the free plan.',
    },
    {
      question: 'What if I lose my phone?',
      answer: 'If you have another device, your realm is still there: use it to add your new phone. If the phone was your only device, install Control on a new one and restore your realm with your recovery phrase. Everything you synced comes back; anything that was only on the lost phone does not. Without the phrase, nobody can restore it, not even us.',
    },
    {
      question: 'Will support ask for my recovery phrase?',
      answer: 'Never. Anyone who has it can open everything, so keep it offline and give it to nobody.',
    },
  ],
  pricingLink: 'See plans and prices',
  helpLink: 'Control help and common problems',
  downloadHeading: 'Download from:',
}

const controlDe: AppPage = {
  metaTitle: 'Tenshu Control: deine Daten, deine Geräte und deine Synchronisation in einer App',
  description:
    'Control ist die App, die die anderen Tenshu-Apps brauchen. Sie sorgt dafür, dass nur deine Geräte deine Daten öffnen können, verbindet sie und hält deine Apps synchron. Auf einem Gerät ganz ohne Konto.',
  reviewed: false,
  eyebrow: 'Tenshu Control',
  heading: ['Du kontrollierst deine Daten.', 'Deine Apps bleiben synchron.'],
  lede: 'Control ist die App, die die anderen Tenshu-Apps brauchen. Sie verbindet deine Geräte und hält deine Apps synchron. Der Unterschied: Nur deine Geräte können deine Daten öffnen, nie unsere Server.',
  targets: { android: 'Google Play', ios: 'App Store', macos: 'Mac', web: 'Web' },
  comingSoon: 'Demnächst',
  detectedReady: 'Du nutzt wohl {platform}.',
  detectedSoon: 'Du nutzt wohl {platform}. Tenshu Control dafür kommt bald.',
  shotAlts: ['Einrichtung: geschützt durch dein Gerät, ohne Passwort', 'Ein Gerät per QR-Code hinzufügen', 'Deine Wiederherstellungsphrase, 24 Wörter'],
  parityHeading: 'Deine Geräte und deine Synchronisation an einem Ort.',
  parity: [
    { title: 'Ein Realm für jede App', detail: 'Drive, Photos, Calendar und die anderen Tenshu-Apps nutzen alle das Realm, das Control einrichtet.', status: 'yes' },
    { title: 'Mit deiner E-Mail anmelden', detail: 'Nur wenn du synchronisieren möchtest. Ein neues Konto startet mit dem kostenlosen Plan.', status: 'yes' },
    { title: 'Weitere Geräte hinzufügen', detail: 'Ein Gerät zeigt einen Code, das andere scannt ihn, und beide zeigen denselben Bestätigungscode.', status: 'yes' },
    { title: 'Wiederherstellung', detail: 'Richte eine Wiederherstellungsphrase aus 24 Wörtern ein; sie holt dein Realm und alles Synchronisierte zurück, wenn du alle Geräte verlierst.', status: 'yes' },
    { title: 'App-Sperre', detail: 'Control fragt nach Fingerabdruck, Gesicht oder Displaysperre, bevor es sich öffnet.', status: 'yes' },
    { title: 'Speicher und Pläne', detail: 'Sieh unter „Dienste“, wie viel du nutzt, und wechsle deinen Plan.', status: 'yes' },
    { title: 'Deine Freigabe-Links', detail: 'Sieh und widerrufe die Links, die du aus Tenshu Drive geteilt hast.', status: 'yes' },
    { title: 'Nur im WLAN', detail: 'Lege fest, ob Synchronisation im Hintergrund und Fotosicherung auf WLAN warten.', status: 'yes' },
    { title: 'Jedes Gerät', detail: 'Heute Android. iPhone, iPad, Mac und das Web folgen bald.', status: 'soon' },
  ],
  availableLabel: 'Verfügbar',
  notYetLabel: 'Noch nicht',
  notPartLabel: 'Nicht Teil von Control',
  differentHeading: 'Was wir anders machen.',
  differentLede: 'Bei vielen Diensten kann der Anbieter deine Daten öffnen. Bei Tenshu können das nur deine Geräte.',
  differences: [
    {
      kicker: 'Ende-zu-Ende-verschlüsselt',
      heading: 'Nur deine Geräte können deine Daten öffnen.',
      body: 'Deine Daten werden auf deinen Geräten verschlüsselt, bevor sie irgendwohin gehen, und nur deine Geräte und deine Wiederherstellungsphrase können sie öffnen. Die Server von Tenshu können deine Daten nicht lesen: Sie leiten nur verschlüsselte Daten weiter und speichern nur solche. Nur was du teilst, verlässt diesen Kreis.',
      widget: {
        kind: 'sees',
        seesHeading: 'Unser Server sieht',
        sees: ['Wie viel du speicherst', 'Wie viele Geräte', 'Wann sie synchronisieren'],
        neverSeesHeading: 'Unser Server sieht nie',
        neverSees: ['Deine Daten', 'Was sie öffnet', 'Deine Wiederherstellungsphrase'],
      },
    },
    {
      kicker: 'Ein Gerät hinzufügen',
      heading: 'Ein neues Gerät kommt rein. Unser Server nicht.',
      body: 'Wenn du ein Gerät hinzufügst, gibt ihm dein vorhandenes Gerät, was es zum Öffnen deiner Daten braucht, verschlüsselt nur für dieses Gerät, sodass unser Server es unterwegs nicht lesen kann.',
      widget: { kind: 'flow', steps: ['Dieses Handy', 'verschlüsselt', 'Unser Server', 'Dein neues Gerät'] },
    },
    {
      kicker: 'Kein Konto nötig',
      heading: 'Ein Konto nur, wenn du eins willst.',
      body: 'Auf einem einzelnen Gerät funktioniert Tenshu ohne Konto und sendet nichts an unsere Server. Deine Wiederherstellungsphrase gehört nur dir: Niemand sonst hat eine Kopie, deshalb kann niemand, auch wir nicht, dein Realm ohne sie wiederherstellen.',
      widget: {
        kind: 'table',
        heading: 'Was du brauchst',
        rows: [
          { label: 'Tenshu auf einem Gerät', value: 'Nichts', good: true },
          { label: 'Synchronisation zwischen Geräten', value: 'Eine E-Mail-Adresse', good: false },
          { label: 'Wer deine Wiederherstellungsphrase hat', value: 'Nur du', good: true },
        ],
      },
    },
  ],
  worksWith: [
    { heading: 'Tenshu Drive', body: 'Deine Dateien, Ende-zu-Ende-verschlüsselt. Braucht Control auf demselben Handy.', link: 'Über Drive', href: '/apps/drive' },
    { heading: 'Tenshu Photos', body: 'Deine Fotomediathek, Ende-zu-Ende-verschlüsselt. Braucht Control auf demselben Handy.', link: 'Über Photos', href: '/apps/photos' },
  ],
  faqHeading: 'Fragen',
  faq: [
    {
      question: 'Was kostet es?',
      answer: 'Control und jede Tenshu-App sind kostenlos. Um zwischen deinen Geräten zu synchronisieren, brauchst du einen Speicherplan, und die ersten 5 GB sind kostenlos.',
      pricing: true,
    },
    {
      question: 'Brauche ich ein Konto?',
      answer: 'Auf einem Gerät nicht: Tenshu funktioniert ohne Konto und sendet nichts an unsere Server. Um zwischen Geräten zu synchronisieren, gib deine E-Mail-Adresse ein, und ein neues Konto startet mit dem kostenlosen Plan.',
    },
    {
      question: 'Was, wenn ich mein Handy verliere?',
      answer: 'Wenn du ein weiteres Gerät hast, ist dein Realm noch da: Füge damit dein neues Handy hinzu. War das Handy dein einziges Gerät, installiere Control auf einem neuen und stelle dein Realm mit deiner Wiederherstellungsphrase wieder her. Alles, was du synchronisiert hast, kommt zurück; was nur auf dem verlorenen Handy war, nicht. Ohne die Phrase kann es niemand wiederherstellen, auch wir nicht.',
    },
    {
      question: 'Fragt der Support nach meiner Wiederherstellungsphrase?',
      answer: 'Nie. Wer sie hat, kann alles öffnen, also bewahre sie offline auf und gib sie niemandem.',
    },
  ],
  pricingLink: 'Pläne und Preise ansehen',
  helpLink: 'Hilfe zu Control und häufige Probleme',
  downloadHeading: 'Herunterladen bei:',
}

const controlFr: AppPage = {
  metaTitle: 'Tenshu Control : vos données, vos appareils et votre synchronisation dans une seule app',
  description:
    'Control est l’app dont les autres apps Tenshu ont besoin. Elle fait en sorte que seuls vos appareils puissent ouvrir vos données, les relie et garde vos apps synchronisées. Sur un seul appareil, sans compte.',
  reviewed: false,
  eyebrow: 'Tenshu Control',
  heading: ['Vous contrôlez vos données.', 'Vos apps restent synchronisées.'],
  lede: 'Control est l’app dont les autres apps Tenshu ont besoin. Elle relie vos appareils et garde vos apps synchronisées. La différence : seuls vos appareils peuvent ouvrir vos données, jamais nos serveurs.',
  targets: { android: 'Google Play', ios: 'App Store', macos: 'Mac', web: 'Web' },
  comingSoon: 'Bientôt',
  detectedReady: 'Vous semblez utiliser {platform}.',
  detectedSoon: 'Vous semblez utiliser {platform}. Tenshu Control y arrive bientôt.',
  shotAlts: ['Configuration : protégé par votre appareil, sans mot de passe à retenir', 'Ajouter un appareil avec un code QR', 'Votre phrase de récupération, 24 mots'],
  parityHeading: 'Vos appareils et votre synchronisation au même endroit.',
  parity: [
    { title: 'Un realm pour toutes les apps', detail: 'Drive, Photos, Calendar et les autres apps Tenshu utilisent toutes le realm que Control configure.', status: 'yes' },
    { title: 'Connexion par e-mail', detail: 'Seulement si vous voulez synchroniser. Un nouveau compte démarre avec l’offre gratuite.', status: 'yes' },
    { title: 'Ajouter vos autres appareils', detail: 'Un appareil affiche un code, l’autre le scanne, et les deux affichent le même code de confirmation.', status: 'yes' },
    { title: 'Récupération', detail: 'Configurez une phrase de récupération de 24 mots : elle restaure votre realm et tout ce que vous avez synchronisé si vous perdez tous vos appareils.', status: 'yes' },
    { title: 'Verrouillage', detail: 'Control demande votre empreinte, votre visage ou le verrouillage de l’écran avant de s’ouvrir.', status: 'yes' },
    { title: 'Espace et offres', detail: 'Voyez votre utilisation et changez d’offre sous « Services ».', status: 'yes' },
    { title: 'Vos liens de partage', detail: 'Voyez et révoquez les liens partagés depuis Tenshu Drive.', status: 'yes' },
    { title: 'Wi-Fi uniquement', detail: 'Choisissez si la synchronisation en arrière-plan et la sauvegarde des photos attendent le Wi-Fi.', status: 'yes' },
    { title: 'Tous vos appareils', detail: 'Android aujourd’hui. iPhone, iPad, Mac et le web arrivent bientôt.', status: 'soon' },
  ],
  availableLabel: 'Disponible',
  notYetLabel: 'Pas encore',
  notPartLabel: 'Hors de Control',
  differentHeading: 'Ce que nous faisons autrement.',
  differentLede: 'Chez beaucoup de services, le fournisseur peut ouvrir vos données. Avec Tenshu, seuls vos appareils le peuvent.',
  differences: [
    {
      kicker: 'Chiffré de bout en bout',
      heading: 'Seuls vos appareils peuvent ouvrir vos données.',
      body: 'Vos données sont chiffrées sur vos appareils avant d’aller où que ce soit, et seuls vos appareils, et votre phrase de récupération, peuvent les ouvrir. Les serveurs de Tenshu ne peuvent pas lire vos données : ils ne transmettent et ne stockent que des données chiffrées. Seul ce que vous partagez sort de ce cercle.',
      widget: {
        kind: 'sees',
        seesHeading: 'Notre serveur voit',
        sees: ['Combien vous stockez', 'Combien d’appareils', 'Quand ils se synchronisent'],
        neverSeesHeading: 'Notre serveur ne voit jamais',
        neverSees: ['Vos données', 'Ce qui les ouvre', 'Votre phrase de récupération'],
      },
    },
    {
      kicker: 'Ajouter un appareil',
      heading: 'Un nouvel appareil entre. Notre serveur, non.',
      body: 'Quand vous ajoutez un appareil, votre appareil existant lui transmet de quoi ouvrir vos données, chiffré pour cet appareil seul, pour que notre serveur ne puisse pas le lire en chemin.',
      widget: { kind: 'flow', steps: ['Ce téléphone', 'chiffré', 'Notre serveur', 'Votre nouvel appareil'] },
    },
    {
      kicker: 'Pas de compte requis',
      heading: 'Un compte seulement si vous le voulez.',
      body: 'Sur un seul appareil, Tenshu fonctionne sans compte et n’envoie rien à nos serveurs. Votre phrase de récupération n’appartient qu’à vous : personne d’autre n’en a de copie, donc personne, pas même nous, ne peut restaurer votre realm sans elle.',
      widget: {
        kind: 'table',
        heading: 'Ce qu’il vous faut',
        rows: [
          { label: 'Tenshu sur un appareil', value: 'Rien', good: true },
          { label: 'Synchroniser vos appareils', value: 'Une adresse e-mail', good: false },
          { label: 'Qui détient votre phrase de récupération', value: 'Vous seul', good: true },
        ],
      },
    },
  ],
  worksWith: [
    { heading: 'Tenshu Drive', body: 'Vos fichiers, chiffrés de bout en bout. Nécessite Control sur le même téléphone.', link: 'À propos de Drive', href: '/apps/drive' },
    { heading: 'Tenshu Photos', body: 'Votre photothèque, chiffrée de bout en bout. Nécessite Control sur le même téléphone.', link: 'À propos de Photos', href: '/apps/photos' },
  ],
  faqHeading: 'Questions',
  faq: [
    {
      question: 'Combien ça coûte ?',
      answer: 'Control et toutes les apps Tenshu sont gratuites. Pour synchroniser vos appareils, il vous faut une offre de stockage, et les 5 premiers Go sont gratuits.',
      pricing: true,
    },
    {
      question: 'Faut-il un compte ?',
      answer: 'Pas sur un seul appareil : Tenshu fonctionne sans compte et n’envoie rien à nos serveurs. Pour synchroniser vos appareils, saisissez votre adresse e-mail, et un nouveau compte démarre avec l’offre gratuite.',
    },
    {
      question: 'Et si je perds mon téléphone ?',
      answer: 'Si vous avez un autre appareil, votre realm y est toujours : utilisez-le pour ajouter votre nouveau téléphone. Si le téléphone était votre seul appareil, installez Control sur un nouveau et restaurez votre realm avec votre phrase de récupération. Tout ce que vous avez synchronisé revient ; ce qui n’était que sur le téléphone perdu, non. Sans elle, personne ne peut le restaurer, pas même nous.',
    },
    {
      question: 'Le support me demandera-t-il ma phrase de récupération ?',
      answer: 'Jamais. Quiconque la possède peut tout ouvrir : gardez-la hors ligne et ne la donnez à personne.',
    },
  ],
  pricingLink: 'Voir les offres et les prix',
  helpLink: 'Aide sur Control et problèmes fréquents',
  downloadHeading: 'Télécharger sur :',
}

// Calendar's copy has no canvas board either: its privacy sentences are store/calendar/claims.md's,
// and its features follow its Play description.
const calendarEn: AppPage = {
  metaTitle: 'Tenshu Calendar: your events on every device, end-to-end encrypted',
  description:
    'Month, week, day and agenda, reminders, repeating events and subscriptions, like the calendar you use today. The difference: your events are end-to-end encrypted.',
  reviewed: true,
  eyebrow: 'Tenshu Calendar',
  heading: ['Your days, in sync.', 'Readable only by you.'],
  lede: 'Month, week, day and agenda, reminders, repeating events and subscriptions, just like the calendar you use today. The difference: your events are end-to-end encrypted.',
  targets: { android: 'Google Play', ios: 'App Store', macos: 'Mac', web: 'Web' },
  comingSoon: 'Coming soon',
  detectedReady: 'We think you’re on {platform}.',
  detectedSoon: 'We think you’re on {platform}. Tenshu Calendar for it is coming soon.',
  shotAlts: ['The month view with the day’s events below', 'The agenda: what is coming up', 'The week view'],
  parityHeading: 'Everything you expect from a calendar.',
  parity: [
    { title: 'Month, week, day and agenda', detail: 'See your month, week or day, or a list of what is coming up.', status: 'yes' },
    { title: 'Events that repeat', detail: 'All-day or timed, with a location, notes and a repeat. Change one occurrence, or all of them.', status: 'yes' },
    { title: 'Reminders', detail: 'Even when Calendar is closed. Snooze or dismiss them from the notification.', status: 'yes' },
    { title: 'Several calendars', detail: 'Each with its own colour. Show or hide each one on this phone.', status: 'yes' },
    { title: 'Subscriptions', detail: 'Public holidays or a team schedule, by web address. They update on their own, and you can pause them.', status: 'yes' },
    { title: 'Import and export', detail: 'Import an .ics file, export a calendar as one, and open .ics files from other apps.', status: 'yes' },
    { title: 'Home screen widget', detail: 'See what is next without opening the app.', status: 'yes' },
    { title: 'Every device', detail: 'Android today. iPhone, iPad, Mac and the web are coming soon.', status: 'soon' },
  ],
  availableLabel: 'Available',
  notYetLabel: 'Not yet',
  notPartLabel: 'Not part of Calendar',
  differentHeading: 'What we do differently.',
  differentLede: 'A calendar says where you will be and with whom. Tenshu Calendar is built so that we can’t read it.',
  differences: [
    {
      kicker: 'End-to-end encrypted',
      heading: 'We sync your events. We can’t read them.',
      body: 'Your events and calendars are encrypted on your device before they are uploaded. Tenshu’s servers store only encrypted data, and they don’t have the keys to read it.',
      widget: {
        kind: 'sees',
        seesHeading: 'Our server sees',
        sees: ['How much you store', 'How many devices', 'When they sync'],
        neverSeesHeading: 'Our server never sees',
        neverSees: ['Your events', 'Who and where', 'Your calendars'],
      },
    },
    {
      kicker: 'Subscriptions',
      heading: 'Straight from the publisher.',
      body: 'When you subscribe to a calendar, your device downloads it directly from its publisher, and the publisher sees your IP address. Each of your devices downloads it for itself.',
      widget: {
        kind: 'table',
        heading: 'A subscribed calendar',
        rows: [
          { label: 'Who downloads it', value: 'Your device', good: true },
          { label: 'Downloaded by our server', value: 'Never', good: true },
          { label: 'Who sees your IP address', value: 'The publisher', good: false },
        ],
      },
    },
    {
      kicker: 'Only what you add',
      heading: 'Your other calendars stay where they are.',
      body: 'Calendar does not read the other calendars on your device. Without a storage plan, your events stay on this device. For a calendar you choose, reminders can hide the event’s title on the lock screen.',
      widget: {
        kind: 'table',
        heading: 'What Calendar reads',
        rows: [
          { label: 'Events you add or import', value: 'Yes', good: true },
          { label: 'Other calendars on your phone', value: 'Never', good: true },
          { label: 'Titles on the lock screen', value: 'You choose', good: true },
        ],
      },
    },
  ],
  worksWith: [{ heading: 'Calendar works with Tenshu Control.', body: 'Control makes sure only your devices can open your data, and connects them. Install it first.', link: 'About Control', href: '/apps/control' }],
  faqHeading: 'Questions',
  faq: [
    {
      question: 'What does it cost?',
      answer: 'The app is free. To sync your calendars between your devices, you need a storage plan, and the first 5 GB are free. Without a plan, your events stay on this device.',
      pricing: true,
    },
    {
      question: 'Can I bring my events from another calendar?',
      answer: 'Yes: export your calendar as an .ics file and import it into Tenshu Calendar. A calendar with a web address, like public holidays, you can subscribe to instead.',
    },
    {
      question: 'Does Calendar read the calendars already on my phone?',
      answer: 'No. Calendar shows only the events you add, import or subscribe to.',
    },
    {
      question: 'What if I lose my phone?',
      answer: 'Synced events are still on your other devices. If the phone was your only device, they come back when you restore your realm with your recovery phrase, if you set one up. Changes that hadn’t synced yet were only on that phone.',
    },
  ],
  pricingLink: 'See plans and prices',
  helpLink: 'Calendar help and common problems',
  downloadHeading: 'Download from:',
}

const calendarDe: AppPage = {
  metaTitle: 'Tenshu Calendar: deine Termine auf jedem Gerät, Ende-zu-Ende-verschlüsselt',
  description:
    'Monat, Woche, Tag und Agenda, Erinnerungen, wiederkehrende Termine und Abos, wie bei dem Kalender, den du heute nutzt. Der Unterschied: Deine Termine sind Ende-zu-Ende-verschlüsselt.',
  reviewed: false,
  eyebrow: 'Tenshu Calendar',
  heading: ['Deine Tage, synchron.', 'Lesbar nur für dich.'],
  lede: 'Monat, Woche, Tag und Agenda, Erinnerungen, wiederkehrende Termine und Abos, genau wie bei dem Kalender, den du heute nutzt. Der Unterschied: Deine Termine sind Ende-zu-Ende-verschlüsselt.',
  targets: { android: 'Google Play', ios: 'App Store', macos: 'Mac', web: 'Web' },
  comingSoon: 'Demnächst',
  detectedReady: 'Du nutzt wohl {platform}.',
  detectedSoon: 'Du nutzt wohl {platform}. Tenshu Calendar dafür kommt bald.',
  shotAlts: ['Die Monatsansicht mit den Terminen des Tages', 'Die Agenda: was als Nächstes kommt', 'Die Wochenansicht'],
  parityHeading: 'Alles, was du von einem Kalender erwartest.',
  parity: [
    { title: 'Monat, Woche, Tag und Agenda', detail: 'Sieh deinen Monat, deine Woche oder deinen Tag, oder eine Liste dessen, was kommt.', status: 'yes' },
    { title: 'Wiederkehrende Termine', detail: 'Ganztägig oder mit Uhrzeit, mit Ort, Notizen und Wiederholung. Ändere ein Vorkommen oder alle.', status: 'yes' },
    { title: 'Erinnerungen', detail: 'Auch wenn Calendar geschlossen ist. Schlummern oder schließen direkt in der Benachrichtigung.', status: 'yes' },
    { title: 'Mehrere Kalender', detail: 'Jeder mit eigener Farbe. Blende jeden auf diesem Handy ein oder aus.', status: 'yes' },
    { title: 'Abos', detail: 'Feiertage oder ein Teamplan, per Webadresse. Sie aktualisieren sich von selbst, und du kannst sie pausieren.', status: 'yes' },
    { title: 'Import und Export', detail: 'Importiere eine .ics-Datei, exportiere einen Kalender als solche und öffne .ics-Dateien aus anderen Apps.', status: 'yes' },
    { title: 'Widget', detail: 'Sieh auf dem Startbildschirm, was als Nächstes kommt.', status: 'yes' },
    { title: 'Jedes Gerät', detail: 'Heute Android. iPhone, iPad, Mac und das Web folgen bald.', status: 'soon' },
  ],
  availableLabel: 'Verfügbar',
  notYetLabel: 'Noch nicht',
  notPartLabel: 'Nicht Teil von Calendar',
  differentHeading: 'Was wir anders machen.',
  differentLede: 'Ein Kalender verrät, wo du sein wirst und mit wem. Tenshu Calendar ist so gebaut, dass wir ihn nicht lesen können.',
  differences: [
    {
      kicker: 'Ende-zu-Ende-verschlüsselt',
      heading: 'Wir synchronisieren deine Termine. Lesen können wir sie nicht.',
      body: 'Deine Termine und Kalender werden auf deinem Gerät verschlüsselt, bevor sie hochgeladen werden. Die Server von Tenshu speichern nur verschlüsselte Daten und haben nicht die Schlüssel, um sie zu lesen.',
      widget: {
        kind: 'sees',
        seesHeading: 'Unser Server sieht',
        sees: ['Wie viel du speicherst', 'Wie viele Geräte', 'Wann sie synchronisieren'],
        neverSeesHeading: 'Unser Server sieht nie',
        neverSees: ['Deine Termine', 'Mit wem und wo', 'Deine Kalender'],
      },
    },
    {
      kicker: 'Abos',
      heading: 'Direkt vom Herausgeber.',
      body: 'Wenn du einen Kalender abonnierst, lädt dein Gerät ihn direkt von seinem Herausgeber, und der Herausgeber sieht deine IP-Adresse. Jedes deiner Geräte lädt ihn selbst.',
      widget: {
        kind: 'table',
        heading: 'Ein abonnierter Kalender',
        rows: [
          { label: 'Wer ihn lädt', value: 'Dein Gerät', good: true },
          { label: 'Von unserem Server geladen', value: 'Nie', good: true },
          { label: 'Wer deine IP-Adresse sieht', value: 'Der Herausgeber', good: false },
        ],
      },
    },
    {
      kicker: 'Nur was du hinzufügst',
      heading: 'Deine anderen Kalender bleiben, wo sie sind.',
      body: 'Calendar liest die anderen Kalender auf deinem Gerät nicht. Ohne Speicherplan bleiben deine Termine auf diesem Gerät. Für einen Kalender deiner Wahl können Erinnerungen den Titel auf dem Sperrbildschirm ausblenden.',
      widget: {
        kind: 'table',
        heading: 'Was Calendar liest',
        rows: [
          { label: 'Termine, die du hinzufügst oder importierst', value: 'Ja', good: true },
          { label: 'Andere Kalender auf deinem Handy', value: 'Nie', good: true },
          { label: 'Titel auf dem Sperrbildschirm', value: 'Du entscheidest', good: true },
        ],
      },
    },
  ],
  worksWith: [{ heading: 'Calendar funktioniert mit Tenshu Control.', body: 'Control sorgt dafür, dass nur deine Geräte deine Daten öffnen können, und verbindet sie. Installiere es zuerst.', link: 'Über Control', href: '/apps/control' }],
  faqHeading: 'Fragen',
  faq: [
    {
      question: 'Was kostet es?',
      answer: 'Die App ist kostenlos. Um deine Kalender zwischen deinen Geräten zu synchronisieren, brauchst du einen Speicherplan, und die ersten 5 GB sind kostenlos. Ohne Plan bleiben deine Termine auf diesem Gerät.',
      pricing: true,
    },
    {
      question: 'Kann ich meine Termine aus einem anderen Kalender mitnehmen?',
      answer: 'Ja: Exportiere deinen Kalender als .ics-Datei und importiere sie in Tenshu Calendar. Einen Kalender mit Webadresse, etwa Feiertage, kannst du stattdessen abonnieren.',
    },
    {
      question: 'Liest Calendar die Kalender, die schon auf meinem Handy sind?',
      answer: 'Nein. Calendar zeigt nur die Termine, die du hinzufügst, importierst oder abonnierst.',
    },
    {
      question: 'Was, wenn ich mein Handy verliere?',
      answer: 'Synchronisierte Termine sind noch auf deinen anderen Geräten. War das Handy dein einziges Gerät, kommen sie zurück, wenn du dein Realm mit deiner Wiederherstellungsphrase wiederherstellst, sofern du eine eingerichtet hast. Änderungen, die noch nicht synchronisiert waren, waren nur auf diesem Handy.',
    },
  ],
  pricingLink: 'Pläne und Preise ansehen',
  helpLink: 'Hilfe zu Calendar und häufige Probleme',
  downloadHeading: 'Herunterladen bei:',
}

const calendarFr: AppPage = {
  metaTitle: 'Tenshu Calendar : vos événements sur tous vos appareils, chiffrés de bout en bout',
  description:
    'Mois, semaine, jour et agenda, rappels, événements récurrents et abonnements, comme l’agenda que vous utilisez aujourd’hui. La différence : vos événements sont chiffrés de bout en bout.',
  reviewed: false,
  eyebrow: 'Tenshu Calendar',
  heading: ['Vos journées, synchronisées.', 'Lisibles par vous seul.'],
  lede: 'Mois, semaine, jour et agenda, rappels, événements récurrents et abonnements, comme l’agenda que vous utilisez aujourd’hui. La différence : vos événements sont chiffrés de bout en bout.',
  targets: { android: 'Google Play', ios: 'App Store', macos: 'Mac', web: 'Web' },
  comingSoon: 'Bientôt',
  detectedReady: 'Vous semblez utiliser {platform}.',
  detectedSoon: 'Vous semblez utiliser {platform}. Tenshu Calendar y arrive bientôt.',
  shotAlts: ['La vue mois avec les événements du jour', 'L’agenda : ce qui arrive', 'La vue semaine'],
  parityHeading: 'Tout ce que vous attendez d’un agenda.',
  parity: [
    { title: 'Mois, semaine, jour et agenda', detail: 'Voyez votre mois, votre semaine ou votre journée, ou la liste de ce qui arrive.', status: 'yes' },
    { title: 'Événements récurrents', detail: 'Toute la journée ou à heure fixe, avec un lieu, des notes et une répétition. Modifiez une occurrence, ou toutes.', status: 'yes' },
    { title: 'Rappels', detail: 'Même quand Calendar est fermé. Reportez-les ou ignorez-les depuis la notification.', status: 'yes' },
    { title: 'Plusieurs agendas', detail: 'Chacun avec sa couleur. Affichez ou masquez chacun sur ce téléphone.', status: 'yes' },
    { title: 'Abonnements', detail: 'Jours fériés ou planning d’équipe, par adresse web. Ils se mettent à jour tout seuls, et vous pouvez les suspendre.', status: 'yes' },
    { title: 'Import et export', detail: 'Importez un fichier .ics, exportez un agenda dans ce format, et ouvrez les fichiers .ics d’autres apps.', status: 'yes' },
    { title: 'Widget', detail: 'Voyez ce qui arrive sur l’écran d’accueil, sans ouvrir l’app.', status: 'yes' },
    { title: 'Tous vos appareils', detail: 'Android aujourd’hui. iPhone, iPad, Mac et le web arrivent bientôt.', status: 'soon' },
  ],
  availableLabel: 'Disponible',
  notYetLabel: 'Pas encore',
  notPartLabel: 'Hors de Calendar',
  differentHeading: 'Ce que nous faisons autrement.',
  differentLede: 'Un agenda dit où vous serez et avec qui. Tenshu Calendar est conçu pour que nous ne puissions pas le lire.',
  differences: [
    {
      kicker: 'Chiffré de bout en bout',
      heading: 'Nous synchronisons vos événements. Nous ne pouvons pas les lire.',
      body: 'Vos événements et vos agendas sont chiffrés sur votre appareil avant d’être envoyés. Les serveurs de Tenshu ne stockent que des données chiffrées et n’ont pas les clés pour les lire.',
      widget: {
        kind: 'sees',
        seesHeading: 'Notre serveur voit',
        sees: ['Combien vous stockez', 'Combien d’appareils', 'Quand ils se synchronisent'],
        neverSeesHeading: 'Notre serveur ne voit jamais',
        neverSees: ['Vos événements', 'Avec qui et où', 'Vos agendas'],
      },
    },
    {
      kicker: 'Abonnements',
      heading: 'Directement depuis l’éditeur.',
      body: 'Quand vous vous abonnez à un agenda, votre appareil le télécharge directement auprès de son éditeur, qui voit votre adresse IP. Chacun de vos appareils le télécharge lui-même.',
      widget: {
        kind: 'table',
        heading: 'Un abonnement à un agenda',
        rows: [
          { label: 'Qui le télécharge', value: 'Votre appareil', good: true },
          { label: 'Téléchargé par notre serveur', value: 'Jamais', good: true },
          { label: 'Qui voit votre adresse IP', value: 'L’éditeur', good: false },
        ],
      },
    },
    {
      kicker: 'Seulement ce que vous ajoutez',
      heading: 'Vos autres agendas restent où ils sont.',
      body: 'Calendar ne lit pas les autres agendas de votre appareil. Sans offre de stockage, vos événements restent sur cet appareil. Pour un agenda de votre choix, les rappels peuvent masquer le titre sur l’écran de verrouillage.',
      widget: {
        kind: 'table',
        heading: 'Ce que Calendar lit',
        rows: [
          { label: 'Les événements que vous ajoutez ou importez', value: 'Oui', good: true },
          { label: 'Les autres agendas du téléphone', value: 'Jamais', good: true },
          { label: 'Les titres sur l’écran de verrouillage', value: 'À vous de choisir', good: true },
        ],
      },
    },
  ],
  worksWith: [{ heading: 'Calendar fonctionne avec Tenshu Control.', body: 'Control fait en sorte que seuls vos appareils puissent ouvrir vos données, et les relie. Installez-le d’abord.', link: 'À propos de Control', href: '/apps/control' }],
  faqHeading: 'Questions',
  faq: [
    {
      question: 'Combien ça coûte ?',
      answer: 'L’app est gratuite. Pour synchroniser vos agendas entre vos appareils, il vous faut une offre de stockage, et les 5 premiers Go sont gratuits. Sans offre, vos événements restent sur cet appareil.',
      pricing: true,
    },
    {
      question: 'Puis-je reprendre mes événements d’un autre agenda ?',
      answer: 'Oui : exportez votre agenda en fichier .ics et importez-le dans Tenshu Calendar. Un agenda doté d’une adresse web, comme les jours fériés, peut aussi être suivi par abonnement.',
    },
    {
      question: 'Calendar lit-il les agendas déjà présents sur mon téléphone ?',
      answer: 'Non. Calendar n’affiche que les événements que vous ajoutez, importez ou auxquels vous vous abonnez.',
    },
    {
      question: 'Et si je perds mon téléphone ?',
      answer: 'Les événements synchronisés sont encore sur vos autres appareils. Si le téléphone était votre seul appareil, ils reviennent quand vous restaurez votre realm avec votre phrase de récupération, si vous en avez configuré une. Les modifications pas encore synchronisées n’étaient que sur ce téléphone.',
    },
  ],
  pricingLink: 'Voir les offres et les prix',
  helpLink: 'Aide sur Calendar et problèmes fréquents',
  downloadHeading: 'Télécharger sur :',
}

// Contacts' copy, like Calendar's: privacy sentences from store/contacts/claims.md (the Android
// address-book sentence), features from its Play description.
const contactsEn: AppPage = {
  metaTitle: 'Tenshu Contacts: your contacts on every device, end-to-end encrypted',
  description:
    'Numbers, email addresses, search, duplicates and import from your phone, like the address book you use today. The difference: your contacts are end-to-end encrypted, and nobody can look you up.',
  reviewed: true,
  eyebrow: 'Tenshu Contacts',
  heading: ['Everyone you know, on every device.', 'Readable only by you.'],
  lede: 'Numbers, email addresses, search, duplicates and import from your phone, just like the address book you use today. The difference: your contacts are end-to-end encrypted, and nobody can look you up.',
  targets: { android: 'Google Play', ios: 'App Store', macos: 'Mac', web: 'Web' },
  comingSoon: 'Coming soon',
  detectedReady: 'We think you’re on {platform}.',
  detectedSoon: 'We think you’re on {platform}. Tenshu Contacts for it is coming soon.',
  shotAlts: ['Your contacts, with possible duplicates at the top', 'A contact’s numbers, email addresses and notes', 'Inviting someone with a QR code or a link'],
  parityHeading: 'Everything you expect from an address book.',
  parity: [
    { title: 'Everything about a person', detail: 'Phone numbers, email addresses, organisation and notes.', status: 'yes' },
    { title: 'Search', detail: 'Find a contact by name, phone number, email address or organisation.', status: 'yes' },
    { title: 'Import from your phone', detail: 'Pick one contact, import a .vcf file, or import your whole address book.', status: 'yes' },
    { title: 'Duplicates', detail: 'Find possible duplicates, choose the one to keep, and merge them.', status: 'yes' },
    { title: 'Your own name', detail: 'Set the name the other Tenshu apps show for you.', status: 'yes' },
    { title: 'Every device', detail: 'Android today. iPhone, iPad, Mac and the web are coming soon.', status: 'soon' },
    { title: 'Writing to your phone’s address book', detail: 'Not part of Contacts: it can import from it, and never writes back.', status: 'no' },
  ],
  availableLabel: 'Available',
  notYetLabel: 'Not yet',
  notPartLabel: 'Not part of Contacts',
  differentHeading: 'What we do differently.',
  differentLede: 'An address book is a map of who you know. Tenshu Contacts is built so that we can’t read it, and nobody can find you through it.',
  differences: [
    {
      kicker: 'End-to-end encrypted',
      heading: 'We sync your contacts. We can’t read them.',
      body: 'Your contacts are encrypted on your device before they are uploaded. Tenshu’s servers store only encrypted data, and they don’t have the keys to read it.',
      widget: {
        kind: 'sees',
        seesHeading: 'Our server sees',
        sees: ['How much you store', 'How many devices', 'When they sync'],
        neverSeesHeading: 'Our server never sees',
        neverSees: ['Your contacts', 'Their numbers and addresses', 'Your contact list'],
      },
    },
    {
      kicker: 'No directory',
      heading: 'Nobody can look you up.',
      body: 'Tenshu has no directory, so nobody can look you up. You connect with someone only when you both accept an invitation, and you can compare a six-digit code to confirm it is them.',
      widget: {
        kind: 'table',
        heading: 'Finding you on Tenshu',
        rows: [
          { label: 'By your name, number or email', value: 'Nobody', good: true },
          { label: 'Connecting with you', value: 'Only by invitation', good: true },
          { label: 'Confirming it is them', value: 'A six-digit code', good: true },
        ],
      },
    },
    {
      kicker: 'Your phone’s address book',
      heading: 'Read only when you ask. Never changed.',
      body: 'Contacts reads your address book only when you choose to import from it, and it never writes to it. Without a storage plan, your contacts stay on this device.',
      widget: {
        kind: 'table',
        heading: 'Your phone’s address book',
        rows: [
          { label: 'Read', value: 'Only when you import', good: true },
          { label: 'Written to', value: 'Never', good: true },
        ],
      },
    },
  ],
  worksWith: [{ heading: 'Contacts works with Tenshu Control.', body: 'Control makes sure only your devices can open your data, and connects them. Install it first.', link: 'About Control', href: '/apps/control' }],
  faqHeading: 'Questions',
  faq: [
    {
      question: 'What does it cost?',
      answer: 'The app is free. To sync your contacts between your devices, you need a storage plan, and the first 5 GB are free. Without a plan, your contacts stay on this device.',
      pricing: true,
    },
    {
      question: 'Can I bring the contacts from my phone?',
      answer: 'Yes: pick one contact, import a .vcf file, or import your whole address book. Contacts reads your address book only then, and never writes to it.',
    },
    {
      question: 'Can people find me on Tenshu?',
      answer: 'No. Tenshu has no directory. Someone can connect with you only if you send them an invitation, or accept theirs.',
    },
    {
      question: 'What if I lose my phone?',
      answer: 'Synced contacts are still on your other devices. If the phone was your only device, they come back when you restore your realm with your recovery phrase, if you set one up. Changes that hadn’t synced yet were only on that phone.',
    },
  ],
  pricingLink: 'See plans and prices',
  helpLink: 'Contacts help and common problems',
  downloadHeading: 'Download from:',
}

const contactsDe: AppPage = {
  metaTitle: 'Tenshu Contacts: deine Kontakte auf jedem Gerät, Ende-zu-Ende-verschlüsselt',
  description:
    'Nummern, E-Mail-Adressen, Suche, Duplikate und Import von deinem Handy, wie beim Adressbuch, das du heute nutzt. Der Unterschied: Deine Kontakte sind Ende-zu-Ende-verschlüsselt, und niemand kann dich nachschlagen.',
  reviewed: false,
  eyebrow: 'Tenshu Contacts',
  heading: ['Alle, die du kennst, auf jedem Gerät.', 'Lesbar nur für dich.'],
  lede: 'Nummern, E-Mail-Adressen, Suche, Duplikate und Import von deinem Handy, genau wie beim Adressbuch, das du heute nutzt. Der Unterschied: Deine Kontakte sind Ende-zu-Ende-verschlüsselt, und niemand kann dich nachschlagen.',
  targets: { android: 'Google Play', ios: 'App Store', macos: 'Mac', web: 'Web' },
  comingSoon: 'Demnächst',
  detectedReady: 'Du nutzt wohl {platform}.',
  detectedSoon: 'Du nutzt wohl {platform}. Tenshu Contacts dafür kommt bald.',
  shotAlts: ['Deine Kontakte, mit möglichen Duplikaten oben', 'Nummern, E-Mail-Adressen und Notizen eines Kontakts', 'Jemanden per QR-Code oder Link einladen'],
  parityHeading: 'Alles, was du von einem Adressbuch erwartest.',
  parity: [
    { title: 'Alles über eine Person', detail: 'Telefonnummern, E-Mail-Adressen, Organisation und Notizen.', status: 'yes' },
    { title: 'Suche', detail: 'Finde einen Kontakt über Name, Telefonnummer, E-Mail-Adresse oder Organisation.', status: 'yes' },
    { title: 'Import von deinem Handy', detail: 'Wähle einen Kontakt, importiere eine .vcf-Datei oder dein ganzes Adressbuch.', status: 'yes' },
    { title: 'Duplikate', detail: 'Finde mögliche Duplikate, wähle, welches bleibt, und führe sie zusammen.', status: 'yes' },
    { title: 'Dein eigener Name', detail: 'Lege fest, welchen Namen die anderen Tenshu-Apps für dich zeigen.', status: 'yes' },
    { title: 'Jedes Gerät', detail: 'Heute Android. iPhone, iPad, Mac und das Web folgen bald.', status: 'soon' },
    { title: 'In das Adressbuch deines Handys schreiben', detail: 'Nicht Teil von Contacts: Es kann daraus importieren und schreibt nie zurück.', status: 'no' },
  ],
  availableLabel: 'Verfügbar',
  notYetLabel: 'Noch nicht',
  notPartLabel: 'Nicht Teil von Contacts',
  differentHeading: 'Was wir anders machen.',
  differentLede: 'Ein Adressbuch zeigt, wen du kennst. Tenshu Contacts ist so gebaut, dass wir es nicht lesen können und niemand dich darüber findet.',
  differences: [
    {
      kicker: 'Ende-zu-Ende-verschlüsselt',
      heading: 'Wir synchronisieren deine Kontakte. Lesen können wir sie nicht.',
      body: 'Deine Kontakte werden auf deinem Gerät verschlüsselt, bevor sie hochgeladen werden. Die Server von Tenshu speichern nur verschlüsselte Daten und haben nicht die Schlüssel, um sie zu lesen.',
      widget: {
        kind: 'sees',
        seesHeading: 'Unser Server sieht',
        sees: ['Wie viel du speicherst', 'Wie viele Geräte', 'Wann sie synchronisieren'],
        neverSeesHeading: 'Unser Server sieht nie',
        neverSees: ['Deine Kontakte', 'Ihre Nummern und Adressen', 'Deine Kontaktliste'],
      },
    },
    {
      kicker: 'Kein Verzeichnis',
      heading: 'Niemand kann dich nachschlagen.',
      body: 'Tenshu hat kein Verzeichnis, deshalb kann niemand dich nachschlagen. Du verbindest dich mit jemandem nur, wenn ihr beide eine Einladung annehmt, und ihr könnt einen sechsstelligen Code vergleichen, um sicherzugehen, dass es die richtige Person ist.',
      widget: {
        kind: 'table',
        heading: 'Dich auf Tenshu finden',
        rows: [
          { label: 'Über Name, Nummer oder E-Mail', value: 'Niemand', good: true },
          { label: 'Sich mit dir verbinden', value: 'Nur per Einladung', good: true },
          { label: 'Bestätigen, dass es die Person ist', value: 'Ein sechsstelliger Code', good: true },
        ],
      },
    },
    {
      kicker: 'Das Adressbuch deines Handys',
      heading: 'Gelesen nur, wenn du willst. Nie verändert.',
      body: 'Contacts liest dein Adressbuch nur, wenn du einen Import daraus startest, und schreibt nie hinein. Ohne Speicherplan bleiben deine Kontakte auf diesem Gerät.',
      widget: {
        kind: 'table',
        heading: 'Das Adressbuch deines Handys',
        rows: [
          { label: 'Gelesen', value: 'Nur beim Import', good: true },
          { label: 'Verändert', value: 'Nie', good: true },
        ],
      },
    },
  ],
  worksWith: [{ heading: 'Contacts funktioniert mit Tenshu Control.', body: 'Control sorgt dafür, dass nur deine Geräte deine Daten öffnen können, und verbindet sie. Installiere es zuerst.', link: 'Über Control', href: '/apps/control' }],
  faqHeading: 'Fragen',
  faq: [
    {
      question: 'Was kostet es?',
      answer: 'Die App ist kostenlos. Um deine Kontakte zwischen deinen Geräten zu synchronisieren, brauchst du einen Speicherplan, und die ersten 5 GB sind kostenlos. Ohne Plan bleiben deine Kontakte auf diesem Gerät.',
      pricing: true,
    },
    {
      question: 'Kann ich die Kontakte von meinem Handy übernehmen?',
      answer: 'Ja: Wähle einen Kontakt, importiere eine .vcf-Datei oder dein ganzes Adressbuch. Nur dann liest Contacts dein Adressbuch, und es schreibt nie hinein.',
    },
    {
      question: 'Können andere mich auf Tenshu finden?',
      answer: 'Nein. Tenshu hat kein Verzeichnis. Jemand kann sich nur mit dir verbinden, wenn du eine Einladung schickst oder seine annimmst.',
    },
    {
      question: 'Was, wenn ich mein Handy verliere?',
      answer: 'Synchronisierte Kontakte sind noch auf deinen anderen Geräten. War das Handy dein einziges Gerät, kommen sie zurück, wenn du dein Realm mit deiner Wiederherstellungsphrase wiederherstellst, sofern du eine eingerichtet hast. Änderungen, die noch nicht synchronisiert waren, waren nur auf diesem Handy.',
    },
  ],
  pricingLink: 'Pläne und Preise ansehen',
  helpLink: 'Hilfe zu Contacts und häufige Probleme',
  downloadHeading: 'Herunterladen bei:',
}

const contactsFr: AppPage = {
  metaTitle: 'Tenshu Contacts : vos contacts sur tous vos appareils, chiffrés de bout en bout',
  description:
    'Numéros, adresses e-mail, recherche, doublons et import depuis votre téléphone, comme le carnet d’adresses que vous utilisez aujourd’hui. La différence : vos contacts sont chiffrés de bout en bout, et personne ne peut vous rechercher.',
  reviewed: false,
  eyebrow: 'Tenshu Contacts',
  heading: ['Tous ceux que vous connaissez, sur tous vos appareils.', 'Lisibles par vous seul.'],
  lede: 'Numéros, adresses e-mail, recherche, doublons et import depuis votre téléphone, comme le carnet d’adresses que vous utilisez aujourd’hui. La différence : vos contacts sont chiffrés de bout en bout, et personne ne peut vous rechercher.',
  targets: { android: 'Google Play', ios: 'App Store', macos: 'Mac', web: 'Web' },
  comingSoon: 'Bientôt',
  detectedReady: 'Vous semblez utiliser {platform}.',
  detectedSoon: 'Vous semblez utiliser {platform}. Tenshu Contacts y arrive bientôt.',
  shotAlts: ['Vos contacts, avec les doublons possibles en haut', 'Les numéros, adresses e-mail et notes d’un contact', 'Inviter quelqu’un avec un code QR ou un lien'],
  parityHeading: 'Tout ce que vous attendez d’un carnet d’adresses.',
  parity: [
    { title: 'Tout sur une personne', detail: 'Numéros de téléphone, adresses e-mail, organisation et notes.', status: 'yes' },
    { title: 'Recherche', detail: 'Trouvez un contact par nom, numéro, adresse e-mail ou organisation.', status: 'yes' },
    { title: 'Import depuis votre téléphone', detail: 'Choisissez un contact, importez un fichier .vcf, ou tout votre carnet d’adresses.', status: 'yes' },
    { title: 'Doublons', detail: 'Trouvez les doublons possibles, choisissez celui à garder et fusionnez-les.', status: 'yes' },
    { title: 'Votre propre nom', detail: 'Choisissez le nom que les autres apps Tenshu affichent pour vous.', status: 'yes' },
    { title: 'Tous vos appareils', detail: 'Android aujourd’hui. iPhone, iPad, Mac et le web arrivent bientôt.', status: 'soon' },
    { title: 'Écrire dans le carnet d’adresses du téléphone', detail: 'Hors de Contacts : l’app peut l’importer, et n’y écrit jamais.', status: 'no' },
  ],
  availableLabel: 'Disponible',
  notYetLabel: 'Pas encore',
  notPartLabel: 'Hors de Contacts',
  differentHeading: 'Ce que nous faisons autrement.',
  differentLede: 'Un carnet d’adresses dit qui vous connaissez. Tenshu Contacts est conçu pour que nous ne puissions pas le lire, et que personne ne vous y trouve.',
  differences: [
    {
      kicker: 'Chiffré de bout en bout',
      heading: 'Nous synchronisons vos contacts. Nous ne pouvons pas les lire.',
      body: 'Vos contacts sont chiffrés sur votre appareil avant d’être envoyés. Les serveurs de Tenshu ne stockent que des données chiffrées et n’ont pas les clés pour les lire.',
      widget: {
        kind: 'sees',
        seesHeading: 'Notre serveur voit',
        sees: ['Combien vous stockez', 'Combien d’appareils', 'Quand ils se synchronisent'],
        neverSeesHeading: 'Notre serveur ne voit jamais',
        neverSees: ['Vos contacts', 'Leurs numéros et adresses', 'Votre liste de contacts'],
      },
    },
    {
      kicker: 'Pas d’annuaire',
      heading: 'Personne ne peut vous rechercher.',
      body: 'Tenshu n’a pas d’annuaire : personne ne peut vous rechercher. Vous ne vous connectez avec quelqu’un que si vous acceptez tous les deux une invitation, et vous pouvez comparer un code à six chiffres pour vérifier que c’est bien lui.',
      widget: {
        kind: 'table',
        heading: 'Vous trouver sur Tenshu',
        rows: [
          { label: 'Par votre nom, numéro ou e-mail', value: 'Personne', good: true },
          { label: 'Se connecter avec vous', value: 'Seulement sur invitation', good: true },
          { label: 'Vérifier que c’est bien la personne', value: 'Un code à six chiffres', good: true },
        ],
      },
    },
    {
      kicker: 'Le carnet d’adresses du téléphone',
      heading: 'Lu seulement à votre demande. Jamais modifié.',
      body: 'Contacts ne lit votre carnet d’adresses que lorsque vous choisissez d’en importer, et n’y écrit jamais. Sans offre de stockage, vos contacts restent sur cet appareil.',
      widget: {
        kind: 'table',
        heading: 'Le carnet d’adresses du téléphone',
        rows: [
          { label: 'Lu', value: 'Seulement à l’import', good: true },
          { label: 'Modifié', value: 'Jamais', good: true },
        ],
      },
    },
  ],
  worksWith: [{ heading: 'Contacts fonctionne avec Tenshu Control.', body: 'Control fait en sorte que seuls vos appareils puissent ouvrir vos données, et les relie. Installez-le d’abord.', link: 'À propos de Control', href: '/apps/control' }],
  faqHeading: 'Questions',
  faq: [
    {
      question: 'Combien ça coûte ?',
      answer: 'L’app est gratuite. Pour synchroniser vos contacts entre vos appareils, il vous faut une offre de stockage, et les 5 premiers Go sont gratuits. Sans offre, vos contacts restent sur cet appareil.',
      pricing: true,
    },
    {
      question: 'Puis-je reprendre les contacts de mon téléphone ?',
      answer: 'Oui : choisissez un contact, importez un fichier .vcf, ou tout votre carnet d’adresses. Contacts ne lit votre carnet qu’à ce moment-là, et n’y écrit jamais.',
    },
    {
      question: 'Peut-on me trouver sur Tenshu ?',
      answer: 'Non. Tenshu n’a pas d’annuaire. Quelqu’un ne peut se connecter avec vous que si vous lui envoyez une invitation, ou acceptez la sienne.',
    },
    {
      question: 'Et si je perds mon téléphone ?',
      answer: 'Les contacts synchronisés sont encore sur vos autres appareils. Si le téléphone était votre seul appareil, ils reviennent quand vous restaurez votre realm avec votre phrase de récupération, si vous en avez configuré une. Les modifications pas encore synchronisées n’étaient que sur ce téléphone.',
    },
  ],
  pricingLink: 'Voir les offres et les prix',
  helpLink: 'Aide sur Contacts et problèmes fréquents',
  downloadHeading: 'Télécharger sur :',
}

// Fit's copy, like Calendar's: privacy sentences from store/fit/claims.md (the Android ones),
// features from its Play description.
const fitEn: AppPage = {
  metaTitle: 'Tenshu Fit: your workouts and health data, end-to-end encrypted',
  description:
    'Record runs, rides and walks, bring in your activity from Health Connect, and see your trends, like the fitness app you use today. The difference: your health data is end-to-end encrypted.',
  reviewed: true,
  eyebrow: 'Tenshu Fit',
  heading: ['Your workouts and health data.', 'Readable only by you.'],
  lede: 'Record runs, rides and walks, bring in your activity from Health Connect, and see your trends, just like the fitness app you use today. The difference: your health data is end-to-end encrypted.',
  targets: { android: 'Google Play', ios: 'App Store', macos: 'Mac', web: 'Web' },
  comingSoon: 'Coming soon',
  detectedReady: 'We think you’re on {platform}.',
  detectedSoon: 'We think you’re on {platform}. Tenshu Fit for it is coming soon.',
  shotAlts: ['Today: active minutes, steps, distance and energy', 'A run with its route and heart rate', 'Sleep over the week'],
  parityHeading: 'Everything you expect from a fitness app.',
  parity: [
    { title: 'Record workouts', detail: 'Runs, walks, rides and strength. Time, distance, pace and steps as you go, with the route from GPS.', status: 'yes' },
    { title: 'Screen off', detail: 'Recording continues with the screen off. Pause, resume or stop from the notification.', status: 'yes' },
    { title: 'Heart rate strap', detail: 'Connect a Bluetooth heart rate strap to see your heart rate while you record.', status: 'yes' },
    { title: 'Health Connect', detail: 'Workouts with routes, steps, sleep, weight, heart rate and more, if you allow it.', status: 'yes' },
    { title: 'Import your history', detail: 'From a Google Takeout .zip, or from .gpx and .tcx files.', status: 'yes' },
    { title: 'Look back', detail: 'Each workout with its route, laps and heart rate, and simple trends over time.', status: 'yes' },
    { title: 'Daily goals', detail: 'For active minutes and steps.', status: 'yes' },
    { title: 'Every device', detail: 'Android today. iPhone, iPad, Mac and the web are coming soon.', status: 'soon' },
    { title: 'Medical advice', detail: 'Not part of Fit: it keeps track of your activity, is not a medical device and gives no medical advice.', status: 'no' },
  ],
  availableLabel: 'Available',
  notYetLabel: 'Not yet',
  notPartLabel: 'Not part of Fit',
  differentHeading: 'What we do differently.',
  differentLede: 'Your health data says a lot about you. Tenshu Fit is built so that we can’t read it.',
  differences: [
    {
      kicker: 'End-to-end encrypted',
      heading: 'We sync your health data. We can’t read it.',
      body: 'Your workouts and health data are encrypted on your device before they are uploaded. Tenshu’s servers store only encrypted data, and they don’t have the keys to read it. To draw a workout’s map, the map for that area is downloaded from our map server: it sees the area, never your route.',
      widget: {
        kind: 'sees',
        seesHeading: 'Our server sees',
        sees: ['How much you store', 'How many devices', 'When they sync'],
        neverSeesHeading: 'Our server never sees',
        neverSees: ['Your workouts', 'Your routes', 'Your health data'],
      },
    },
    {
      kicker: 'Health Connect and location',
      heading: 'Only what you allow, only when you record.',
      body: 'Fit reads from Health Connect only the kinds of data you allow, and it writes nothing to Health Connect. Fit uses your location only while you record a workout.',
      widget: {
        kind: 'table',
        heading: 'What Fit uses',
        rows: [
          { label: 'Health Connect data', value: 'Only what you allow', good: true },
          { label: 'Written to Health Connect', value: 'Nothing', good: true },
          { label: 'Your location', value: 'Only while recording', good: true },
        ],
      },
    },
    {
      kicker: 'No ads, no analytics',
      heading: 'Your health data stays yours.',
      body: 'Fit has no ads and no analytics, and it does not share your health data with anyone. Without a storage plan, your data stays on this device.',
      widget: {
        kind: 'table',
        heading: 'In Fit',
        rows: [
          { label: 'Ads', value: 'None', good: true },
          { label: 'Analytics', value: 'None', good: true },
          { label: 'Your health data shared', value: 'With nobody', good: true },
        ],
      },
    },
  ],
  worksWith: [{ heading: 'Fit works with Tenshu Control.', body: 'Control makes sure only your devices can open your data, and connects them. Install it first.', link: 'About Control', href: '/apps/control' }],
  faqHeading: 'Questions',
  faq: [
    {
      question: 'What does it cost?',
      answer: 'The app is free. To sync your data between your devices, you need a storage plan, and the first 5 GB are free. Without a plan, your data stays on this device.',
      pricing: true,
    },
    {
      question: 'Can I bring my history?',
      answer: 'Yes. From Health Connect, Fit imports the last 30 days, or your full history if you allow that too, and imports again when you open it. Workouts also come in from a Google Takeout .zip, or from .gpx and .tcx files.',
    },
    {
      question: 'Is Fit a medical app?',
      answer: 'No. Fit is for keeping track of your activity. It is not a medical device and gives no medical advice.',
    },
    {
      question: 'What if I lose my phone?',
      answer: 'Synced workouts and health data are still on your other devices. If the phone was your only device, they come back when you restore your realm with your recovery phrase, if you set one up. What hadn’t synced yet was only on that phone.',
    },
  ],
  pricingLink: 'See plans and prices',
  helpLink: 'Fit help and common problems',
  downloadHeading: 'Download from:',
}

const fitDe: AppPage = {
  metaTitle: 'Tenshu Fit: deine Trainings und Gesundheitsdaten, Ende-zu-Ende-verschlüsselt',
  description:
    'Läufe, Radtouren und Spaziergänge aufzeichnen, Aktivität aus Health Connect übernehmen und Trends sehen, wie bei der Fitness-App, die du heute nutzt. Der Unterschied: Deine Gesundheitsdaten sind Ende-zu-Ende-verschlüsselt.',
  reviewed: false,
  eyebrow: 'Tenshu Fit',
  heading: ['Deine Trainings und Gesundheitsdaten.', 'Lesbar nur für dich.'],
  lede: 'Läufe, Radtouren und Spaziergänge aufzeichnen, deine Aktivität aus Health Connect übernehmen und Trends sehen, genau wie bei der Fitness-App, die du heute nutzt. Der Unterschied: Deine Gesundheitsdaten sind Ende-zu-Ende-verschlüsselt.',
  targets: { android: 'Google Play', ios: 'App Store', macos: 'Mac', web: 'Web' },
  comingSoon: 'Demnächst',
  detectedReady: 'Du nutzt wohl {platform}.',
  detectedSoon: 'Du nutzt wohl {platform}. Tenshu Fit dafür kommt bald.',
  shotAlts: ['Heute: aktive Minuten, Schritte, Strecke und Energie', 'Ein Lauf mit Route und Herzfrequenz', 'Schlaf über die Woche'],
  parityHeading: 'Alles, was du von einer Fitness-App erwartest.',
  parity: [
    { title: 'Trainings aufzeichnen', detail: 'Laufen, Gehen, Radfahren und Krafttraining. Zeit, Strecke, Tempo und Schritte unterwegs, mit der Route per GPS.', status: 'yes' },
    { title: 'Bildschirm aus', detail: 'Die Aufzeichnung läuft mit ausgeschaltetem Bildschirm weiter. Über die Benachrichtigung pausieren, fortsetzen oder beenden.', status: 'yes' },
    { title: 'Brustgurt', detail: 'Verbinde einen Bluetooth-Herzfrequenzgurt, um beim Aufzeichnen deine Herzfrequenz zu sehen.', status: 'yes' },
    { title: 'Health Connect', detail: 'Trainings mit Routen, Schritte, Schlaf, Gewicht, Herzfrequenz und mehr, wenn du es erlaubst.', status: 'yes' },
    { title: 'Deinen Verlauf importieren', detail: 'Aus einer Google-Takeout-ZIP-Datei oder aus .gpx- und .tcx-Dateien.', status: 'yes' },
    { title: 'Zurückblicken', detail: 'Jedes Training mit Route, Runden und Herzfrequenz und einfache Trends über die Zeit.', status: 'yes' },
    { title: 'Tagesziele', detail: 'Für aktive Minuten und Schritte.', status: 'yes' },
    { title: 'Jedes Gerät', detail: 'Heute Android. iPhone, iPad, Mac und das Web folgen bald.', status: 'soon' },
    { title: 'Medizinischer Rat', detail: 'Nicht Teil von Fit: Es hält deine Aktivität fest, ist kein Medizinprodukt und gibt keinen medizinischen Rat.', status: 'no' },
  ],
  availableLabel: 'Verfügbar',
  notYetLabel: 'Noch nicht',
  notPartLabel: 'Nicht Teil von Fit',
  differentHeading: 'Was wir anders machen.',
  differentLede: 'Deine Gesundheitsdaten sagen viel über dich. Tenshu Fit ist so gebaut, dass wir sie nicht lesen können.',
  differences: [
    {
      kicker: 'Ende-zu-Ende-verschlüsselt',
      heading: 'Wir synchronisieren deine Gesundheitsdaten. Lesen können wir sie nicht.',
      body: 'Deine Trainings und Gesundheitsdaten werden auf deinem Gerät verschlüsselt, bevor sie hochgeladen werden. Die Server von Tenshu speichern nur verschlüsselte Daten und haben nicht die Schlüssel, um sie zu lesen. Um die Karte eines Trainings zu zeichnen, wird die Karte dieser Gegend von unserem Kartenserver geladen: Er sieht die Gegend, nie deine Route.',
      widget: {
        kind: 'sees',
        seesHeading: 'Unser Server sieht',
        sees: ['Wie viel du speicherst', 'Wie viele Geräte', 'Wann sie synchronisieren'],
        neverSeesHeading: 'Unser Server sieht nie',
        neverSees: ['Deine Trainings', 'Deine Routen', 'Deine Gesundheitsdaten'],
      },
    },
    {
      kicker: 'Health Connect und Standort',
      heading: 'Nur was du erlaubst, nur beim Aufzeichnen.',
      body: 'Fit liest aus Health Connect nur die Datenarten, die du erlaubst, und schreibt nichts in Health Connect. Fit nutzt deinen Standort nur, während du ein Training aufzeichnest.',
      widget: {
        kind: 'table',
        heading: 'Was Fit nutzt',
        rows: [
          { label: 'Daten aus Health Connect', value: 'Nur was du erlaubst', good: true },
          { label: 'In Health Connect geschrieben', value: 'Nichts', good: true },
          { label: 'Dein Standort', value: 'Nur beim Aufzeichnen', good: true },
        ],
      },
    },
    {
      kicker: 'Keine Werbung, keine Analyse',
      heading: 'Deine Gesundheitsdaten bleiben deine.',
      body: 'Fit hat keine Werbung und keine Analyse-Tools, und es gibt deine Gesundheitsdaten an niemanden weiter. Ohne Speicherplan bleiben deine Daten auf diesem Gerät.',
      widget: {
        kind: 'table',
        heading: 'In Fit',
        rows: [
          { label: 'Werbung', value: 'Keine', good: true },
          { label: 'Analyse-Tools', value: 'Keine', good: true },
          { label: 'Gesundheitsdaten weitergegeben', value: 'An niemanden', good: true },
        ],
      },
    },
  ],
  worksWith: [{ heading: 'Fit funktioniert mit Tenshu Control.', body: 'Control sorgt dafür, dass nur deine Geräte deine Daten öffnen können, und verbindet sie. Installiere es zuerst.', link: 'Über Control', href: '/apps/control' }],
  faqHeading: 'Fragen',
  faq: [
    {
      question: 'Was kostet es?',
      answer: 'Die App ist kostenlos. Um deine Daten zwischen deinen Geräten zu synchronisieren, brauchst du einen Speicherplan, und die ersten 5 GB sind kostenlos. Ohne Plan bleiben deine Daten auf diesem Gerät.',
      pricing: true,
    },
    {
      question: 'Kann ich meinen Verlauf mitnehmen?',
      answer: 'Ja. Aus Health Connect importiert Fit die letzten 30 Tage, oder deinen ganzen Verlauf, wenn du auch das erlaubst, und importiert erneut, wenn du die App öffnest. Trainings kommen außerdem aus einer Google-Takeout-ZIP-Datei oder aus .gpx- und .tcx-Dateien.',
    },
    {
      question: 'Ist Fit eine medizinische App?',
      answer: 'Nein. Fit hält deine Aktivität fest. Es ist kein Medizinprodukt und gibt keinen medizinischen Rat.',
    },
    {
      question: 'Was, wenn ich mein Handy verliere?',
      answer: 'Synchronisierte Trainings und Gesundheitsdaten sind noch auf deinen anderen Geräten. War das Handy dein einziges Gerät, kommen sie zurück, wenn du dein Realm mit deiner Wiederherstellungsphrase wiederherstellst, sofern du eine eingerichtet hast. Was noch nicht synchronisiert war, war nur auf diesem Handy.',
    },
  ],
  pricingLink: 'Pläne und Preise ansehen',
  helpLink: 'Hilfe zu Fit und häufige Probleme',
  downloadHeading: 'Herunterladen bei:',
}

const fitFr: AppPage = {
  metaTitle: 'Tenshu Fit : vos entraînements et données de santé, chiffrés de bout en bout',
  description:
    'Enregistrez courses, sorties à vélo et marches, reprenez votre activité depuis Health Connect et suivez vos tendances, comme l’app de fitness que vous utilisez aujourd’hui. La différence : vos données de santé sont chiffrées de bout en bout.',
  reviewed: false,
  eyebrow: 'Tenshu Fit',
  heading: ['Vos entraînements et données de santé.', 'Lisibles par vous seul.'],
  lede: 'Enregistrez courses, sorties à vélo et marches, reprenez votre activité depuis Health Connect et suivez vos tendances, comme l’app de fitness que vous utilisez aujourd’hui. La différence : vos données de santé sont chiffrées de bout en bout.',
  targets: { android: 'Google Play', ios: 'App Store', macos: 'Mac', web: 'Web' },
  comingSoon: 'Bientôt',
  detectedReady: 'Vous semblez utiliser {platform}.',
  detectedSoon: 'Vous semblez utiliser {platform}. Tenshu Fit y arrive bientôt.',
  shotAlts: ['Aujourd’hui : minutes actives, pas, distance et énergie', 'Une course avec son parcours et sa fréquence cardiaque', 'Le sommeil sur la semaine'],
  parityHeading: 'Tout ce que vous attendez d’une app de fitness.',
  parity: [
    { title: 'Enregistrer vos entraînements', detail: 'Course, marche, vélo et musculation. Temps, distance, allure et pas en direct, avec le parcours GPS.', status: 'yes' },
    { title: 'Écran éteint', detail: 'L’enregistrement continue écran éteint. Mettez en pause, reprenez ou arrêtez depuis la notification.', status: 'yes' },
    { title: 'Ceinture cardio', detail: 'Connectez une ceinture cardio Bluetooth pour voir votre fréquence cardiaque pendant l’enregistrement.', status: 'yes' },
    { title: 'Health Connect', detail: 'Entraînements avec parcours, pas, sommeil, poids, fréquence cardiaque et plus, si vous l’autorisez.', status: 'yes' },
    { title: 'Importer votre historique', detail: 'Depuis un .zip Google Takeout, ou depuis des fichiers .gpx et .tcx.', status: 'yes' },
    { title: 'Revoir', detail: 'Chaque entraînement avec son parcours, ses tours et sa fréquence cardiaque, et des tendances simples dans le temps.', status: 'yes' },
    { title: 'Objectifs quotidiens', detail: 'Pour les minutes actives et les pas.', status: 'yes' },
    { title: 'Tous vos appareils', detail: 'Android aujourd’hui. iPhone, iPad, Mac et le web arrivent bientôt.', status: 'soon' },
    { title: 'Conseil médical', detail: 'Hors de Fit : l’app suit votre activité, n’est pas un dispositif médical et ne donne aucun conseil médical.', status: 'no' },
  ],
  availableLabel: 'Disponible',
  notYetLabel: 'Pas encore',
  notPartLabel: 'Hors de Fit',
  differentHeading: 'Ce que nous faisons autrement.',
  differentLede: 'Vos données de santé en disent long sur vous. Tenshu Fit est conçu pour que nous ne puissions pas les lire.',
  differences: [
    {
      kicker: 'Chiffré de bout en bout',
      heading: 'Nous synchronisons vos données de santé. Nous ne pouvons pas les lire.',
      body: 'Vos entraînements et vos données de santé sont chiffrés sur votre appareil avant d’être envoyés. Les serveurs de Tenshu ne stockent que des données chiffrées et n’ont pas les clés pour les lire. Pour dessiner la carte d’un entraînement, la carte de la zone est téléchargée depuis notre serveur de cartes : il voit la zone, jamais votre parcours.',
      widget: {
        kind: 'sees',
        seesHeading: 'Notre serveur voit',
        sees: ['Combien vous stockez', 'Combien d’appareils', 'Quand ils se synchronisent'],
        neverSeesHeading: 'Notre serveur ne voit jamais',
        neverSees: ['Vos entraînements', 'Vos parcours', 'Vos données de santé'],
      },
    },
    {
      kicker: 'Health Connect et position',
      heading: 'Seulement ce que vous autorisez, seulement pendant l’enregistrement.',
      body: 'Fit ne lit dans Health Connect que les types de données que vous autorisez, et n’y écrit rien. Fit n’utilise votre position que pendant l’enregistrement d’un entraînement.',
      widget: {
        kind: 'table',
        heading: 'Ce que Fit utilise',
        rows: [
          { label: 'Données de Health Connect', value: 'Ce que vous autorisez', good: true },
          { label: 'Écrit dans Health Connect', value: 'Rien', good: true },
          { label: 'Votre position', value: 'Pendant l’enregistrement', good: true },
        ],
      },
    },
    {
      kicker: 'Ni publicité, ni statistiques',
      heading: 'Vos données de santé restent à vous.',
      body: 'Fit n’a ni publicité ni outils de statistiques, et ne partage vos données de santé avec personne. Sans offre de stockage, vos données restent sur cet appareil.',
      widget: {
        kind: 'table',
        heading: 'Dans Fit',
        rows: [
          { label: 'Publicité', value: 'Aucune', good: true },
          { label: 'Statistiques', value: 'Aucune', good: true },
          { label: 'Données de santé partagées', value: 'Avec personne', good: true },
        ],
      },
    },
  ],
  worksWith: [{ heading: 'Fit fonctionne avec Tenshu Control.', body: 'Control fait en sorte que seuls vos appareils puissent ouvrir vos données, et les relie. Installez-le d’abord.', link: 'À propos de Control', href: '/apps/control' }],
  faqHeading: 'Questions',
  faq: [
    {
      question: 'Combien ça coûte ?',
      answer: 'L’app est gratuite. Pour synchroniser vos données entre vos appareils, il vous faut une offre de stockage, et les 5 premiers Go sont gratuits. Sans offre, vos données restent sur cet appareil.',
      pricing: true,
    },
    {
      question: 'Puis-je reprendre mon historique ?',
      answer: 'Oui. Depuis Health Connect, Fit importe les 30 derniers jours, ou tout votre historique si vous l’autorisez aussi, et importe à nouveau à chaque ouverture. Les entraînements arrivent aussi depuis un .zip Google Takeout, ou depuis des fichiers .gpx et .tcx.',
    },
    {
      question: 'Fit est-elle une app médicale ?',
      answer: 'Non. Fit sert à suivre votre activité. Ce n’est pas un dispositif médical et elle ne donne aucun conseil médical.',
    },
    {
      question: 'Et si je perds mon téléphone ?',
      answer: 'Les entraînements et données de santé synchronisés sont encore sur vos autres appareils. Si le téléphone était votre seul appareil, ils reviennent quand vous restaurez votre realm avec votre phrase de récupération, si vous en avez configuré une. Ce qui n’était pas encore synchronisé n’était que sur ce téléphone.',
    },
  ],
  pricingLink: 'Voir les offres et les prix',
  helpLink: 'Aide sur Fit et problèmes fréquents',
  downloadHeading: 'Télécharger sur :',
}

export const DRIVE_PAGES: AppPageSet = {
  app: 'drive',
  downloads: [
    { target: 'android', href: playUrl('app.tenshu.drive.android') },
    { target: 'ios', href: null },
    { target: 'macos', href: macosDownloadNeutralUrl('drive') },
    { target: 'web', href: null },
  ],
  screens: ['01-files', '02-photos', '03-preview'],
  device: 'phonelanding',
  pages: { en: driveEn, de: driveDe, fr: driveFr },
}

export const PHOTOS_PAGES: AppPageSet = {
  app: 'photos',
  downloads: [
    { target: 'android', href: playUrl('app.tenshu.photos.android') },
    { target: 'ios', href: null },
    { target: 'macos', href: macosDownloadNeutralUrl('photos') },
    { target: 'web', href: null },
  ],
  screens: ['01-library', '02-album', '03-viewer'],
  device: 'phonelanding',
  pages: { en: photosEn, de: photosDe, fr: photosFr },
}

export const CONTROL_PAGES: AppPageSet = {
  app: 'control',
  downloads: [
    { target: 'android', href: playUrl('app.tenshu.control.android') },
    { target: 'ios', href: null },
    { target: 'macos', href: macosDownloadNeutralUrl('control') },
    { target: 'web', href: null },
  ],
  // Not 10-services: it shows prices, and only the pricing page names one.
  screens: ['02-protected', '09-add-device', '03-recovery-phrase'],
  device: 'phone',
  pages: { en: controlEn, de: controlDe, fr: controlFr },
}

export const CALENDAR_PAGES: AppPageSet = {
  app: 'calendar',
  downloads: [
    { target: 'android', href: playUrl('app.tenshu.calendar.android') },
    { target: 'ios', href: null },
    { target: 'macos', href: macosDownloadNeutralUrl('calendar') },
    { target: 'web', href: null },
  ],
  screens: ['01-month', '02-agenda', '07-week'],
  device: 'phone',
  pages: { en: calendarEn, de: calendarDe, fr: calendarFr },
}

export const CONTACTS_PAGES: AppPageSet = {
  app: 'contacts',
  downloads: [
    { target: 'android', href: playUrl('app.tenshu.contacts.android') },
    { target: 'ios', href: null },
    { target: 'macos', href: macosDownloadNeutralUrl('contacts') },
    { target: 'web', href: null },
  ],
  screens: ['01-contacts', '02-contact', '07-invite'],
  device: 'phone',
  pages: { en: contactsEn, de: contactsDe, fr: contactsFr },
}

export const FIT_PAGES: AppPageSet = {
  app: 'fit',
  downloads: [
    { target: 'android', href: playUrl('app.tenshu.fit.android') },
    { target: 'ios', href: null },
    { target: 'macos', href: macosDownloadNeutralUrl('fit') },
    { target: 'web', href: null },
  ],
  screens: ['01-home', '03-workout', '06-sleep'],
  device: 'phone',
  pages: { en: fitEn, de: fitDe, fr: fitFr },
}

/** Every app with a marketing page. landingApps.ts APPS_WITH_PAGES and the Caddyfile list the same ids. */
export const APP_PAGES: readonly AppPageSet[] = [CONTROL_PAGES, DRIVE_PAGES, PHOTOS_PAGES, CALENDAR_PAGES, CONTACTS_PAGES, FIT_PAGES]

/** The pages' languages, in the order the language picker lists them (English first). */
export const APP_PAGE_LANGUAGES = ['en', 'de', 'fr'] as const

export function appPageUrl(app: string, lang: string): string {
  return `/apps/${app}/${lang}`
}

/** `/apps/<app>`, the language-neutral URL: the carousel's link, x-default, and Caddy's redirect. */
export function appPageNeutralUrl(app: string): string {
  return `/apps/${app}`
}

const escape = (text: string) =>
  text.replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;').replaceAll('"', '&quot;')

const BUTTON = 'inline-flex min-h-11 items-center justify-center gap-2 rounded-lg px-5 text-sm font-semibold'
/** The detected (or, with JavaScript off, the first) target's button. */
export const BUTTON_PRIMARY = `${BUTTON} bg-primary text-on-primary hover:opacity-90`
export const BUTTON_SECONDARY = `${BUTTON} border border-line bg-surface text-text hover:bg-surface-2`
const BUTTON_SOON = `${BUTTON} border border-dashed border-line text-text-3`

/**
 * The download buttons, in the set's order. [primaryFirst]: the hero's row, where the first
 * available target is the primary button until the page's script (generate.ts appDownloadScript)
 * moves the detected one first. The closing row has no primary button. A target that is not
 * available yet is a `<span>`, never a link.
 */
function downloadRow(set: AppPageSet, page: AppPage, primaryFirst: boolean): string {
  const firstAvailable = set.downloads.find((d) => d.href)?.target
  return set.downloads.map(({ target, href }) => {
    const label = escape(page.targets[target])
    if (!href) {
      return `<li data-os="${target}" data-soon><span class="${BUTTON_SOON}">${label} <span class="font-normal">· ${escape(page.comingSoon)}</span></span></li>`
    }
    const cls = primaryFirst && target === firstAvailable ? BUTTON_PRIMARY : BUTTON_SECONDARY
    return `<li data-os="${target}"><a class="${cls}" href="${escape(href)}" rel="noopener">${label}</a></li>`
  }).join('')
}

const CHECK =
  '<svg class="mt-0.5 h-5 w-5 flex-none text-success" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round" role="img" aria-label="{label}"><path d="M5 12.5l4.5 4.5L19 7.5"/></svg>'
const CLOCK =
  '<svg class="mt-0.5 h-5 w-5 flex-none text-warning" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" role="img" aria-label="{label}"><circle cx="12" cy="12" r="8"/><path d="M12 8v4.5l3 2"/></svg>'
const DASH =
  '<svg class="mt-0.5 h-5 w-5 flex-none text-text-3" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" role="img" aria-label="{label}"><path d="M6 12h12"/></svg>'
const ICONS: Record<ParityRow['status'], string> = { yes: CHECK, soon: CLOCK, no: DASH }

/** The picture beside a difference. */
function widgetHtml(widget: DifferenceWidget): string {
  const chip = 'rounded-lg bg-surface-2 px-3 py-2'
  switch (widget.kind) {
    case 'sees':
      return [
        '<div class="mt-5 grid grid-cols-2 gap-3 text-sm md:mt-0">',
        `<div class="rounded-xl bg-surface-2 p-4"><p class="font-semibold text-text">${escape(widget.seesHeading)}</p><ul class="mt-2 space-y-1 text-text-2">${widget.sees.map((s) => `<li>${escape(s)}</li>`).join('')}</ul></div>`,
        `<div class="rounded-xl border border-primary/40 p-4"><p class="font-semibold text-text">${escape(widget.neverSeesHeading)}</p><ul class="mt-2 space-y-1 text-text-2">${widget.neverSees.map((s) => `<li>${escape(s)}</li>`).join('')}</ul></div>`,
        '</div>',
      ].join('')
    case 'shareLink':
      return (
        `<p class="mt-5 overflow-hidden rounded-lg bg-surface-2 px-4 py-3 font-mono text-sm"><span class="text-text-2">${escape(widget.url)}</span><span class="text-primary">${escape(widget.key)}</span></p>` +
        `<p class="mt-2 text-sm text-text-3">${escape(widget.note)}</p>`
      )
    case 'flow': {
      const [from, how, through, to] = widget.steps
      return (
        `<p class="mt-5 flex flex-wrap items-center gap-2 text-sm"><span class="${chip}">${escape(from)}</span><span class="text-text-3">→ ${escape(how)} →</span>` +
        `<span class="${chip}">${escape(through)}</span><span class="text-text-3">→</span><span class="${chip}">${escape(to)}</span></p>`
      )
    }
    case 'table':
      return (
        `<div class="mt-5 rounded-xl bg-surface-2 p-4 text-sm"><p class="font-semibold text-text">${escape(widget.heading)}</p><dl class="mt-2 space-y-1">` +
        widget.rows.map((r) => `<div class="flex justify-between gap-4"><dt class="text-text-2">${escape(r.label)}</dt><dd class="font-semibold ${r.good ? 'text-success' : 'text-text'}">${escape(r.value)}</dd></div>`).join('') +
        '</dl></div>'
      )
    case 'code':
      return (
        `<figure class="mt-5"><pre class="overflow-x-auto rounded-lg bg-surface-2 px-4 py-3 font-mono text-xs leading-relaxed text-text-2"><code>${widget.lines.map(escape).join('\n')}</code></pre>` +
        `<figcaption class="mt-2 text-sm text-text-3">${escape(widget.caption)}</figcaption></figure>`
      )
  }
}

/**
 * The page body as HTML. The hero is two columns from 64rem: the app's icon and text on the left,
 * [carousel] on the right — the landing page's app carousel with this one app (generate.ts
 * buildAppPages; support.css `.app-hero` hides its list of names). [icon] is the app's light and
 * dark icon (hub.ts appIcon); [lang] picks the links; [platformNames] fills the detected-system line.
 * ⚠ Every <h2> carries `class` after its id, so generate.ts's table-of-contents match skips them.
 */
export function appPageHtml(
  set: AppPageSet,
  page: AppPage,
  lang: string,
  hero: { readonly carousel: string; readonly icon: { readonly light: string; readonly dark: string } | null; readonly frameNote: string },
  platformNames: Record<string, string>,
): string {
  const link = 'text-primary underline underline-offset-4'
  const card = 'rounded-2xl border border-line bg-surface p-6 sm:p-8'
  const kicker = (text: string) => `<p class="text-sm font-semibold uppercase tracking-wide text-primary">${escape(text)}</p>`
  const text = (d: Difference) =>
    `${kicker(d.kicker)}<h3 class="mt-2 text-2xl font-semibold tracking-tight">${escape(d.heading)}</h3><p class="mt-3 text-text-2">${escape(d.body)}</p>`
  const names = Object.fromEntries(set.downloads.map(({ target }) => [target, platformNames[target] ?? page.targets[target]]))
  const pricingHref = `${PRICING_NEUTRAL_URL}/${lang}`
  const [first, ...rest] = page.differences
  return [
    // Hero
    '<section class="app-hero grid items-center gap-10 lg:grid-cols-[minmax(0,1fr)_24rem] lg:gap-12" aria-labelledby="app-heading">',
    // As wide as the icon and the headline together; the lede and the download row fill that width
    // and never widen it (support.css .app-hero__fill), so they end where the headline does.
    '<div class="w-fit max-w-full">',
    // The app's icon with its name under it, beside the headline.
    '<div class="flex items-center gap-6 lg:gap-10">',
    '<div class="flex flex-none flex-col items-center">',
    hero.icon
      ? `<img class="hub-icon hub-icon--light app-hero__icon" src="${hero.icon.light}" alt="" width="128" height="128" decoding="async">` +
        `<img class="hub-icon hub-icon--dark app-hero__icon" src="${hero.icon.dark}" alt="" width="128" height="128" decoding="async">`
      : '',
    `<p class="${hero.icon ? 'mt-3 ' : ''}text-center text-base font-semibold whitespace-nowrap text-primary lg:text-lg">${escape(page.eyebrow)}</p>`,
    '</div>',
    `<h1 id="app-heading" class="min-w-0 text-4xl font-semibold tracking-tight sm:text-5xl lg:text-6xl">${escape(page.heading[0])}<br>${escape(page.heading[1])}</h1>`,
    '</div>',
    `<p class="app-hero__fill mt-6 text-lg text-text-2">${escape(page.lede)}</p>`,
    `<div class="app-download app-hero__fill mt-8" data-download data-names="${escape(JSON.stringify(names))}" data-ready="${escape(page.detectedReady)}" data-soon-text="${escape(page.detectedSoon)}">`,
    `<ul class="flex flex-wrap gap-3">${downloadRow(set, page, true)}</ul>`,
    '<p class="mt-3 min-h-6 text-sm text-text-3" data-download-hint></p>',
    '</div>',
    '</div>',
    `<div class="app-hero__shots">${hero.carousel}</div>`,
    '</section>',
    // Parity
    '<section class="mt-24" aria-labelledby="parity-heading">',
    `<h2 id="parity-heading" class="text-center text-3xl font-semibold tracking-tight">${escape(page.parityHeading)}</h2>`,
    '<ul class="mt-10 grid gap-3 sm:grid-cols-2">',
    ...page.parity.map(
      (row) =>
        `<li class="flex gap-3 rounded-xl border border-line bg-surface p-5">${ICONS[row.status].replace('{label}', escape(row.status === 'yes' ? page.availableLabel : row.status === 'soon' ? page.notYetLabel : page.notPartLabel))}` +
        `<div><p class="font-semibold text-text">${escape(row.title)}</p><p class="mt-1 text-sm text-text-2">${escape(row.detail)}</p></div></li>`,
    ),
    '</ul>',
    '</section>',
    // Different
    '<section class="mt-24" aria-labelledby="different-heading">',
    `<h2 id="different-heading" class="text-center text-3xl font-semibold tracking-tight">${escape(page.differentHeading)}</h2>`,
    `<p class="mx-auto mt-4 max-w-2xl text-center text-lg text-text-2">${escape(page.differentLede)}</p>`,
    '<div class="mt-12 space-y-6">',
    first ? `<div class="${card} grid gap-6 md:grid-cols-2 md:items-center"><div>${text(first)}</div>${widgetHtml(first.widget)}</div>` : '',
    rest.length ? `<div class="grid gap-6 md:grid-cols-2">${rest.map((d) => `<div class="${card}">${text(d)}${widgetHtml(d.widget)}</div>`).join('')}</div>` : '',
    `<div class="grid gap-4 ${page.worksWith.length > 1 ? 'md:grid-cols-2' : ''}">`,
    // One companion: a single row, the link on the right. Several: cards side by side.
    ...page.worksWith.map((w) =>
      page.worksWith.length === 1
        ? `<div class="rounded-2xl bg-surface-2 p-6 sm:flex sm:items-center sm:justify-between sm:gap-6"><div><p class="font-semibold text-text">${escape(w.heading)}</p><p class="mt-1 text-text-2">${escape(w.body)}</p></div>` +
          `<a class="mt-3 inline-block ${link} sm:mt-0" href="${escape(w.href)}">${escape(w.link)}</a></div>`
        : `<div class="rounded-2xl bg-surface-2 p-6"><p class="font-semibold text-text">${escape(w.heading)}</p><p class="mt-1 text-text-2">${escape(w.body)}</p>` +
          `<a class="mt-3 inline-block ${link}" href="${escape(w.href)}">${escape(w.link)}</a></div>`,
    ),
    '</div>',
    '</div>',
    '</section>',
    // Questions
    '<section class="mx-auto mt-24 max-w-3xl" aria-labelledby="questions-heading">',
    `<h2 id="questions-heading" class="text-3xl font-semibold tracking-tight">${escape(page.faqHeading)}</h2>`,
    ...page.faq.map((f) =>
      [
        `<h3 class="mt-8 text-lg font-semibold text-text">${escape(f.question)}</h3>`,
        `<p class="mt-2 text-text-2">${escape(f.answer)}${f.pricing ? ` <a class="${link}" href="${pricingHref}">${escape(page.pricingLink)}</a>` : ''}</p>`,
      ].join('\n'),
    ),
    `<p class="mt-10"><a class="${link}" href="/apps/${set.app}/support">${escape(page.helpLink)} →</a></p>`,
    '</section>',
    // Download
    '<section class="mt-24 border-t border-line pt-12 text-center" aria-labelledby="download-heading">',
    `<h2 id="download-heading" class="text-2xl font-semibold tracking-tight">${escape(page.downloadHeading)}</h2>`,
    `<ul class="mt-6 flex flex-wrap justify-center gap-3">${downloadRow(set, page, false)}</ul>`,
    // The hero's device frames are drawn: the trademark line the carousel carries everywhere.
    `<p class="mt-10 text-xs text-text-3">${escape(hero.frameNote)}</p>`,
    '</section>',
  ]
    .filter(Boolean)
    .join('\n')
}
