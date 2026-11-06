// The public roadmap, `/roadmap/<lang>`: built by the support-page generator (generate.ts
// buildTextPages) into static pages with the landing page's header and footer, like the legal
// pages. Caddy serves them as files before the SPA loads, so the pre-launch curtain never covers
// them; `/roadmap` 302s by Accept-Language (web/apex/Caddyfile). The header's "Roadmap" entry and
// the FAQ's roadmap link both point here.
//
// What goes on it — the rules, so the page stays honest:
//   - Features a user would notice, in plain words. Never internal names, phases or tickets, and
//     never a bug fix: pre-release fixes live in docs/proposals/pre-release-checklist.md.
//   - No dates and no promises. "Next" = being built or designed to be built soon; "Later" =
//     planned, not started. Something not built is "planned", never described as if it exists.
//   - Promise nothing a pending decision could take back: paid plans stay off "In testing" until
//     Play billing is proven (pre-release-checklist.md §5).
//   - Name no competitor, and make no "open source" claim while the repository is private
//     (store-presence-and-support-pages.md §4.7; generate.test.ts checks the second).
//   - Sourced from the 2026-10-29 sweep of docs/proposals/ (docs/milestones.md is the internal
//     view). When an item ships, move it up to "In testing" or drop it, and bump ROADMAP_DATE.
//
// English is the source; German and French are translations nobody has read yet (`reviewed: false`).
//
// ⚠ Pure: no imports. Plain Node runs it for the support-page build.

/** One stage of the roadmap: what is in testing, what comes next, what comes later. */
export interface RoadmapStage {
  /** The section's anchor: `/roadmap/<lang>#next`. */
  readonly id: 'testing' | 'next' | 'later'
  readonly heading: string
  readonly lede: string
  readonly areas: readonly RoadmapArea[]
}

/** A group of items inside a stage: an app ("Drive") or a theme ("Your account"). */
export interface RoadmapArea {
  readonly name: string
  readonly items: readonly string[]
}

export interface RoadmapPage {
  /** The page's <h1> and the start of its <title>. */
  readonly title: string
  readonly description: string
  /** true = read by a native speaker. ⚠ false = not yet. */
  readonly reviewed: boolean
  readonly intro: string
  /** "Last updated", followed by the formatted ROADMAP_DATE. */
  readonly updated: string
  readonly stages: readonly RoadmapStage[]
  /** The closing line, as HTML (it holds the mail link). */
  readonly closing: string
}

/** When the roadmap was last revised, ISO. Bump it with every content change. */
export const ROADMAP_DATE = '2026-10-29'

const HELLO = '<a href="mailto:hello@tenshu.app">hello@tenshu.app</a>'

const en: RoadmapPage = {
  title: 'Roadmap',
  description: 'What Tenshu is testing now, what comes next and what comes later: the apps, platforms and features we are working on, without dates.',
  reviewed: true,
  intro: 'Where Tenshu is headed: what we are testing now, what we are building next, and what comes after. There are no dates here on purpose. We ship a feature when it works, and this page changes as our plans do.',
  updated: 'Last updated',
  stages: [
    {
      id: 'testing',
      heading: 'In testing',
      lede: 'Being tested before release.',
      areas: [
        {
          name: 'Android',
          items: [
            'All ten Tenshu apps, going into an open test on Google Play: Control, Drive, Photos, Fit, Timeline, Maps, Calendar, Contacts, Home and Mia.',
            'End-to-end encrypted sync between your devices, with 5 GB of free storage.',
          ],
        },
      ],
    },
    {
      id: 'next',
      heading: 'Next',
      lede: 'What we are building now or soon.',
      areas: [
        {
          name: 'iPhone, iPad and Mac',
          items: [
            'Tenshu in the App Store, starting with Control and Drive, then Photos, Fit, Timeline, Maps, Calendar and Contacts.',
            'Faster, sturdier storage on iPhone that all Tenshu apps share.',
            'Signed Mac apps to download: Drive in the Finder and Control in the menu bar.',
          ],
        },
        {
          name: 'Your account',
          items: [
            'Word suggestions as you type your recovery phrase, and clearer reminders to keep it safe.',
            'A clear "update" prompt when an app and Control are out of step, and installing an older version no longer erasing what is on your device.',
            'A confirmation e-mail for every purchase, and a cancellation page that works without signing in.',
            'Your Google Play subscription shown and managed inside Control.',
          ],
        },
        {
          name: 'Drive',
          items: [
            'Reliable backup of large videos from your phone.',
            'Backup status that always tells you when backup is off or paused.',
          ],
        },
        {
          name: 'Photos',
          items: [
            'Rename and merge people, and fewer pets and dolls mistaken for people.',
            'Play videos right inside Photos.',
          ],
        },
        {
          name: 'Timeline and Maps',
          items: [
            'Better recognition of train, metro and bike journeys.',
            'Station names without turning on online place lookup.',
            'Google Takeout import, tested on large location histories.',
            'More map regions, and a choice between streaming maps and downloading them.',
          ],
        },
        {
          name: 'Fit, Calendar and Contacts',
          items: [
            'Step counts for walks and runs that Fit detects on its own.',
            'Import and export calendars as .ics files.',
          ],
        },
      ],
    },
    {
      id: 'later',
      heading: 'Later',
      lede: 'Planned, further out.',
      areas: [
        {
          name: 'Files and sharing',
          items: [
            'Share a whole folder with a link, with an optional password, and let people upload files to you through a link.',
            'A "recently deleted" safety net for files you delete by mistake.',
            'Uploads that resume where they stopped, and large ones that wait for Wi-Fi.',
            'Open and save Drive files from Android’s file picker, "Share to Drive", and Drive in the iPhone Files app.',
          ],
        },
        {
          name: 'Sync',
          items: [
            'Changes from your other devices that arrive almost instantly.',
            'Optional extra encryption of the app data stored on your device.',
          ],
        },
        {
          name: 'More places to use Tenshu',
          items: [
            'Tenshu apps in your browser.',
            'Desktop apps for Windows and Linux.',
            'Buying more storage inside the iPhone app, and yearly plans.',
            'Your recovery phrase in your own language.',
          ],
        },
        {
          name: 'Smarter, still private',
          items: [
            'Search your photos by what is in them, worked out on your own device.',
            'Mia answering questions in plain language, and later by voice.',
          ],
        },
        {
          name: 'Beyond our own apps',
          items: [
            'Tenshu Hub for your smart home, and a Tenshu speaker.',
            'Apps from other developers, with the same permission controls as ours.',
            'Paid storage designed so that even we cannot link it to your files.',
          ],
        },
      ],
    },
  ],
  closing: `Missing something, or want something sooner? Write to ${HELLO}.`,
}

const de: RoadmapPage = {
  title: 'Roadmap',
  description: 'Was Tenshu gerade testet, was als Nächstes kommt und was später: die Apps, Plattformen und Funktionen, an denen wir arbeiten – ohne Termine.',
  reviewed: false,
  intro: 'Wohin sich Tenshu entwickelt: was wir gerade testen, was wir als Nächstes bauen und was danach kommt. Termine stehen hier mit Absicht nicht. Wir veröffentlichen eine Funktion, wenn sie funktioniert, und diese Seite ändert sich mit unseren Plänen.',
  updated: 'Zuletzt aktualisiert',
  stages: [
    {
      id: 'testing',
      heading: 'Im Test',
      lede: 'Wird vor der Veröffentlichung getestet.',
      areas: [
        {
          name: 'Android',
          items: [
            'Alle zehn Tenshu-Apps, auf dem Weg in den offenen Test bei Google Play: Control, Drive, Photos, Fit, Timeline, Maps, Calendar, Contacts, Home und Mia.',
            'Ende-zu-Ende-verschlüsselte Synchronisation zwischen deinen Geräten, mit 5 GB Speicherplatz kostenlos.',
          ],
        },
      ],
    },
    {
      id: 'next',
      heading: 'Als Nächstes',
      lede: 'Woran wir gerade arbeiten oder bald arbeiten.',
      areas: [
        {
          name: 'iPhone, iPad und Mac',
          items: [
            'Tenshu im App Store, zuerst Control und Drive, danach Photos, Fit, Timeline, Maps, Calendar und Contacts.',
            'Schnellerer, robusterer Speicher auf dem iPhone, den alle Tenshu-Apps gemeinsam nutzen.',
            'Signierte Mac-Apps zum Herunterladen: Drive im Finder und Control in der Menüleiste.',
          ],
        },
        {
          name: 'Dein Konto',
          items: [
            'Wortvorschläge beim Eingeben deiner Wiederherstellungsphrase und klarere Erinnerungen, sie sicher aufzubewahren.',
            'Ein klarer Hinweis zum Aktualisieren, wenn eine App und Control nicht zusammenpassen, und eine ältere Version zu installieren löscht nicht mehr, was auf deinem Gerät ist.',
            'Eine Bestätigungs-E-Mail für jeden Kauf und eine Kündigungsseite, die ohne Anmeldung funktioniert.',
            'Dein Google-Play-Abo direkt in Control sehen und verwalten.',
          ],
        },
        {
          name: 'Drive',
          items: [
            'Zuverlässige Sicherung großer Videos von deinem Handy.',
            'Ein Sicherungsstatus, der immer zeigt, wenn die Sicherung aus oder pausiert ist.',
          ],
        },
        {
          name: 'Photos',
          items: [
            'Personen umbenennen und zusammenführen, und weniger Haustiere und Puppen, die für Personen gehalten werden.',
            'Videos direkt in Photos abspielen.',
          ],
        },
        {
          name: 'Timeline und Maps',
          items: [
            'Bessere Erkennung von Zug-, U-Bahn- und Radfahrten.',
            'Haltestellennamen, ohne die Online-Ortssuche einzuschalten.',
            'Import aus Google Takeout, getestet mit großen Standortverläufen.',
            'Mehr Kartenregionen und die Wahl zwischen gestreamten und heruntergeladenen Karten.',
          ],
        },
        {
          name: 'Fit, Calendar und Contacts',
          items: [
            'Schrittzahlen für Spaziergänge und Läufe, die Fit selbst erkennt.',
            'Kalender als .ics-Dateien importieren und exportieren.',
          ],
        },
      ],
    },
    {
      id: 'later',
      heading: 'Später',
      lede: 'Geplant, für später.',
      areas: [
        {
          name: 'Dateien und Teilen',
          items: [
            'Einen ganzen Ordner per Link teilen, auf Wunsch mit Passwort, und andere über einen Link Dateien für dich hochladen lassen.',
            'Ein Papierkorb „Zuletzt gelöscht“ für versehentlich gelöschte Dateien.',
            'Uploads, die nach einer Unterbrechung weiterlaufen, und große Uploads, die auf WLAN warten.',
            'Drive-Dateien aus der Dateiauswahl von Android öffnen und speichern, „Mit Drive teilen“ und Drive in der Dateien-App des iPhones.',
          ],
        },
        {
          name: 'Synchronisation',
          items: [
            'Änderungen von deinen anderen Geräten, die fast sofort ankommen.',
            'Optional eine zusätzliche Verschlüsselung der App-Daten auf deinem Gerät.',
          ],
        },
        {
          name: 'Mehr Orte für Tenshu',
          items: [
            'Tenshu-Apps im Browser.',
            'Desktop-Apps für Windows und Linux.',
            'Mehr Speicherplatz direkt in der iPhone-App kaufen, und Jahrespläne.',
            'Deine Wiederherstellungsphrase in deiner eigenen Sprache.',
          ],
        },
        {
          name: 'Klüger, weiterhin privat',
          items: [
            'Deine Fotos nach ihrem Inhalt durchsuchen, ausgewertet auf deinem eigenen Gerät.',
            'Mia beantwortet Fragen in normaler Sprache und später auch per Stimme.',
          ],
        },
        {
          name: 'Über unsere eigenen Apps hinaus',
          items: [
            'Tenshu Hub für dein Smart Home und ein Tenshu-Lautsprecher.',
            'Apps anderer Entwickler, mit denselben Berechtigungseinstellungen wie unsere.',
            'Bezahlter Speicherplatz, der so gebaut ist, dass selbst wir ihn nicht mit deinen Dateien verknüpfen können.',
          ],
        },
      ],
    },
  ],
  closing: `Fehlt dir etwas, oder möchtest du etwas früher? Schreib uns an ${HELLO}.`,
}

const fr: RoadmapPage = {
  title: 'Feuille de route',
  description: 'Ce que Tenshu teste aujourd’hui, ce qui vient ensuite et ce qui viendra plus tard : les apps, plateformes et fonctions sur lesquelles nous travaillons, sans dates.',
  reviewed: false,
  intro: 'La direction que prend Tenshu : ce que nous testons aujourd’hui, ce que nous construisons ensuite et ce qui viendra après. Il n’y a volontairement aucune date ici. Nous publions une fonction quand elle fonctionne, et cette page évolue avec nos projets.',
  updated: 'Dernière mise à jour',
  stages: [
    {
      id: 'testing',
      heading: 'En test',
      lede: 'En cours de test avant la sortie.',
      areas: [
        {
          name: 'Android',
          items: [
            'Les dix apps Tenshu, qui entrent en test ouvert sur Google Play : Control, Drive, Photos, Fit, Timeline, Maps, Calendar, Contacts, Home et Mia.',
            'Une synchronisation chiffrée de bout en bout entre vos appareils, avec 5 Go de stockage gratuit.',
          ],
        },
      ],
    },
    {
      id: 'next',
      heading: 'Ensuite',
      lede: 'Ce que nous construisons maintenant ou bientôt.',
      areas: [
        {
          name: 'iPhone, iPad et Mac',
          items: [
            'Tenshu sur l’App Store, d’abord Control et Drive, puis Photos, Fit, Timeline, Maps, Calendar et Contacts.',
            'Un stockage plus rapide et plus robuste sur iPhone, partagé par toutes les apps Tenshu.',
            'Des apps Mac signées à télécharger : Drive dans le Finder et Control dans la barre des menus.',
          ],
        },
        {
          name: 'Votre compte',
          items: [
            'Des suggestions de mots pendant la saisie de votre phrase de récupération, et des rappels plus clairs pour la conserver en lieu sûr.',
            'Une invitation claire à mettre à jour quand une app et Control ne sont pas accordées, et l’installation d’une version plus ancienne qui n’efface plus ce qui se trouve sur votre appareil.',
            'Un e-mail de confirmation pour chaque achat, et une page de résiliation qui fonctionne sans connexion.',
            'Votre abonnement Google Play affiché et géré dans Control.',
          ],
        },
        {
          name: 'Drive',
          items: [
            'Une sauvegarde fiable des vidéos volumineuses de votre téléphone.',
            'Un état de sauvegarde qui indique toujours quand la sauvegarde est désactivée ou en pause.',
          ],
        },
        {
          name: 'Photos',
          items: [
            'Renommer et fusionner des personnes, et moins d’animaux et de poupées pris pour des personnes.',
            'Lire les vidéos directement dans Photos.',
          ],
        },
        {
          name: 'Timeline et Maps',
          items: [
            'Une meilleure reconnaissance des trajets en train, en métro et à vélo.',
            'Les noms des arrêts sans activer la recherche de lieux en ligne.',
            'L’import depuis Google Takeout, testé sur de longs historiques de localisation.',
            'Plus de régions de cartes, et le choix entre cartes en streaming et cartes téléchargées.',
          ],
        },
        {
          name: 'Fit, Calendar et Contacts',
          items: [
            'Le nombre de pas pour les marches et les courses que Fit détecte tout seul.',
            'Importer et exporter des calendriers au format .ics.',
          ],
        },
      ],
    },
    {
      id: 'later',
      heading: 'Plus tard',
      lede: 'Prévu, pour plus tard.',
      areas: [
        {
          name: 'Fichiers et partage',
          items: [
            'Partager tout un dossier par lien, avec un mot de passe si vous le souhaitez, et laisser d’autres personnes vous envoyer des fichiers par lien.',
            'Une corbeille « Supprimés récemment » pour les fichiers effacés par erreur.',
            'Des envois qui reprennent là où ils se sont arrêtés, et des envois volumineux qui attendent le Wi-Fi.',
            'Ouvrir et enregistrer des fichiers Drive depuis le sélecteur de fichiers d’Android, « Partager vers Drive », et Drive dans l’app Fichiers de l’iPhone.',
          ],
        },
        {
          name: 'Synchronisation',
          items: [
            'Des modifications venues de vos autres appareils qui arrivent presque instantanément.',
            'Un chiffrement supplémentaire, en option, des données d’app stockées sur votre appareil.',
          ],
        },
        {
          name: 'Tenshu ailleurs',
          items: [
            'Les apps Tenshu dans votre navigateur.',
            'Des apps de bureau pour Windows et Linux.',
            'Acheter du stockage directement dans l’app iPhone, et des offres annuelles.',
            'Votre phrase de récupération dans votre propre langue.',
          ],
        },
        {
          name: 'Plus intelligent, toujours privé',
          items: [
            'Rechercher vos photos selon leur contenu, analysé sur votre propre appareil.',
            'Mia qui répond aux questions en langage courant, puis à la voix.',
          ],
        },
        {
          name: 'Au-delà de nos propres apps',
          items: [
            'Tenshu Hub pour votre maison connectée, et une enceinte Tenshu.',
            'Des apps d’autres développeurs, avec les mêmes contrôles d’autorisations que les nôtres.',
            'Un stockage payant conçu pour que même nous ne puissions pas le relier à vos fichiers.',
          ],
        },
      ],
    },
  ],
  closing: `Il vous manque quelque chose, ou vous aimeriez l’avoir plus tôt ? Écrivez-nous à ${HELLO}.`,
}

export const ROADMAP: Record<string, RoadmapPage> = { en, de, fr }

/** The roadmap's languages, in the order the language picker lists them (English first). */
export const ROADMAP_LANGUAGES = ['en', 'de', 'fr'] as const

export function roadmapUrl(lang: string): string {
  return `/roadmap/${lang}`
}

/** `/roadmap`, the language-neutral URL: the header's link, x-default, and Caddy's redirect. */
export const ROADMAP_NEUTRAL_URL = '/roadmap'

const DATE_LOCALE: Record<string, string> = { en: 'en-GB', de: 'de-DE', fr: 'fr-FR' }

/** ROADMAP_DATE as the page prints it ("29 October 2026"), fixed to UTC so the build is reproducible. */
export function roadmapDate(lang: string): string {
  return new Intl.DateTimeFormat(DATE_LOCALE[lang] ?? 'en-GB', { day: 'numeric', month: 'long', year: 'numeric', timeZone: 'UTC' })
    .format(new Date(`${ROADMAP_DATE}T00:00:00Z`))
}

/** The stage marker's colour: filled for what testers can use, outlined for what is planned. */
const MARKER: Record<RoadmapStage['id'], string> = {
  testing: 'bg-success',
  next: 'bg-primary',
  later: 'border-2 border-line-strong',
}

const escape = (text: string) =>
  text.replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;').replaceAll('"', '&quot;')

/**
 * The page body as HTML: the intro, the date, one section per stage holding a card per area, and
 * the closing line. Plain text throughout except [RoadmapPage.closing]. ⚠ The anchor is the
 * <section>'s id. The <h2> carries `class` after its id, so generate.ts's table-of-contents match
 * (a bare `<h2 id="…">`) skips it: three headings need no contents list.
 */
export function roadmapHtml(page: RoadmapPage, lang: string): string {
  const stage = (s: RoadmapStage) =>
    [
      `<section id="${s.id}" aria-labelledby="${s.id}-heading" class="mt-14">`,
      `<h2 id="${s.id}-heading" class="flex items-center gap-3 text-2xl font-semibold tracking-tight">` +
        `<span aria-hidden="true" class="inline-block h-3 w-3 shrink-0 rounded-full ${MARKER[s.id]}"></span>${escape(s.heading)}</h2>`,
      `<p class="mt-2 text-text-2">${escape(s.lede)}</p>`,
      '<div class="mt-6 grid gap-4 md:grid-cols-2">',
      ...s.areas.map(
        (a) =>
          '<div class="rounded-2xl border border-line bg-surface p-6">' +
          `<h3 class="text-lg font-semibold text-text">${escape(a.name)}</h3>` +
          '<ul class="mt-3 list-disc space-y-2 pl-5 text-text-2 marker:text-text-3">' +
          a.items.map((item) => `<li>${escape(item)}</li>`).join('') +
          '</ul></div>',
      ),
      '</div>',
      '</section>',
    ].join('\n')
  return [
    `<p class="max-w-3xl text-lg text-text-2">${escape(page.intro)}</p>`,
    `<p class="mt-4 text-sm text-text-3">${escape(page.updated)}: <time datetime="${ROADMAP_DATE}">${escape(roadmapDate(lang))}</time></p>`,
    ...page.stages.map(stage),
    `<p class="mt-14 border-t border-line pt-8 text-text-2 [&_a]:text-primary [&_a]:underline [&_a]:underline-offset-4">${page.closing}</p>`,
  ].join('\n')
}
