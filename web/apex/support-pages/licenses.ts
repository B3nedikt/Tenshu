// The third-party licences page, `/legal/licenses/<lang>`: the notices for the third-party code
// the site serves, built by the support-page generator (generate.ts buildLicenses) into static
// pages with the landing page's header and footer, beside the imprint — so it reads with
// JavaScript off and every page's footer links it (src/landing/MarketingFooter.tsx).
//
// This file is the page's copy and its URL. The data is read at build time: thirdParty.ts walks
// node_modules for the npm packages, sdkBundle.ts lists what the Kotlin/JS SDK bundle carries,
// and licensesPage.ts renders both. ⚖️ The licence texts are reproduced as the packages ship
// them and are never translated; only the frame around them is.
//
// ⚠ No "open source" in any copy here — not in the title, not in the footer link (landing.json
// footer.link.licenses), not about Tenshu: the site makes no open-source claim while the repository
// is private (store-presence-and-support-pages.md §4.7; generate.test.ts and hub.test.ts check
// the footers). The page names the licences of the software it uses, nothing more.
//
// ⚠ Pure: no imports. The footer (browser code) imports licensesUrl from here, and plain Node runs
// it for the support-page build.

export interface LicensesCopy {
  /** The page's <h1> and the start of its <title>; also the footer link's text (landing.json). */
  readonly title: string
  readonly description: string
  /** ⚠ false = machine-translated, not yet read by a native speaker. */
  readonly reviewed: boolean
  /** The paragraphs above the list, as HTML: trusted, written here. */
  readonly intro: string
  readonly webHeading: string
  readonly webIntro: string
  readonly sdkHeading: string
  readonly sdkIntro: string
  readonly assetsHeading: string
  readonly assetsText: string
  readonly package: string
  readonly version: string
  readonly license: string
  readonly source: string
  /** Shown above the standard text when a package ships no licence file of its own. */
  readonly noLicenseFile: string
  readonly count: (n: number) => string
}

export const LICENSES: Record<string, LicensesCopy> = {
  en: {
    title: 'Third-party licences',
    description: 'The third-party software in the Tenshu website and web app, with the full text of each licence.',
    reviewed: true,
    intro:
      '<p>The Tenshu website and web app use software by third parties. This page lists every third-party package whose code the site sends to your browser, with its licence as the package ships it.</p>',
    webHeading: 'Web app',
    webIntro: 'JavaScript and CSS packages bundled into the pages of this site.',
    sdkHeading: 'Tenshu SDK runtime',
    sdkIntro:
      'The web app runs Tenshu’s sync and encryption core, written in Kotlin and compiled to JavaScript. That bundle contains these libraries.',
    assetsHeading: 'Fonts, images and maps',
    assetsText:
      'The site uses your system’s own fonts and loads no web fonts. Its icons and illustrations are Tenshu’s own. It shows no maps.',
    package: 'Package',
    version: 'Version',
    license: 'Licence',
    source: 'Source',
    noLicenseFile: 'The package ships no licence file of its own. Its package metadata declares the licence below; the standard text follows.',
    count: (n) => `${n} packages`,
  },
  // ⚠ Machine-translated, pending native review — like the imprint's and the support pages' translations.
  de: {
    title: 'Lizenzen von Drittanbietern',
    description: 'Die Software von Drittanbietern in der Tenshu-Website und Web-App, mit dem vollständigen Text jeder Lizenz.',
    reviewed: false,
    intro:
      '<p>Die Tenshu-Website und Web-App verwenden Software von Drittanbietern. Diese Seite listet jedes Paket Dritter, dessen Code die Website an deinen Browser sendet, mit seiner Lizenz so, wie das Paket sie mitliefert.</p>',
    webHeading: 'Web-App',
    webIntro: 'JavaScript- und CSS-Pakete, die in die Seiten dieser Website gebündelt sind.',
    sdkHeading: 'Tenshu-SDK-Laufzeit',
    sdkIntro:
      'Die Web-App führt Tenshus Synchronisations- und Verschlüsselungskern aus, in Kotlin geschrieben und nach JavaScript übersetzt. Dieses Bundle enthält diese Bibliotheken.',
    assetsHeading: 'Schriften, Bilder und Karten',
    assetsText:
      'Die Website verwendet die Schriften deines Systems und lädt keine Webfonts. Ihre Icons und Illustrationen sind Tenshus eigene. Sie zeigt keine Karten.',
    package: 'Paket',
    version: 'Version',
    license: 'Lizenz',
    source: 'Quelle',
    noLicenseFile: 'Das Paket liefert keine eigene Lizenzdatei mit. Seine Paket-Metadaten nennen die folgende Lizenz; es folgt ihr Standardtext.',
    count: (n) => `${n} Pakete`,
  },
  fr: {
    title: 'Licences tierces',
    description: 'Les logiciels tiers du site et de l’application web Tenshu, avec le texte intégral de chaque licence.',
    reviewed: false,
    intro:
      '<p>Le site et l’application web Tenshu utilisent des logiciels de tiers. Cette page liste chaque paquet tiers dont le site envoie le code à votre navigateur, avec sa licence telle que le paquet la fournit.</p>',
    webHeading: 'Application web',
    webIntro: 'Les paquets JavaScript et CSS intégrés aux pages de ce site.',
    sdkHeading: 'Environnement d’exécution du SDK Tenshu',
    sdkIntro:
      'L’application web exécute le cœur de synchronisation et de chiffrement de Tenshu, écrit en Kotlin et compilé en JavaScript. Ce bundle contient ces bibliothèques.',
    assetsHeading: 'Polices, images et cartes',
    assetsText:
      'Le site utilise les polices de votre système et ne charge aucune police web. Ses icônes et illustrations sont celles de Tenshu. Il n’affiche aucune carte.',
    package: 'Paquet',
    version: 'Version',
    license: 'Licence',
    source: 'Source',
    noLicenseFile: 'Le paquet ne fournit pas son propre fichier de licence. Ses métadonnées déclarent la licence ci-dessous ; son texte standard suit.',
    count: (n) => `${n} paquets`,
  },
}

/** The page's languages, in the order the language picker lists them (English first). */
export const LICENSES_LANGUAGES = ['en', 'de', 'fr'] as const

/** The licences page in [lang] (`/legal/licenses/de`); a language it is not in gets the English one. */
export function licensesUrl(lang: string): string {
  return `/legal/licenses/${(LICENSES_LANGUAGES as readonly string[]).includes(lang) ? lang : 'en'}`
}
