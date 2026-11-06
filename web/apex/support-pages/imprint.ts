// The site's imprint (Impressum), `/legal/imprint/<lang>`: built by the support-page generator
// (generate.ts buildImprint) into static pages with the landing page's header and footer, so it
// reads the same with JavaScript off and is reachable from every page's footer — the landing
// page's and the support pages'. The privacy policy sits beside it at `/legal/privacy/<lang>` (privacy.ts).
//
// ⚖️ The German text is the company's own imprint (cajetan.company/impressum), copied verbatim,
// plus two additions for this site: the § 18 Abs. 2 MStV line (the site carries editorial content —
// docs, support pages) and a Marken section: the brand line, the third-party trademark notices in
// the wording Apple and Google ask for, and a non-affiliation line. No EU online-dispute-resolution (ODR) link: that
// platform was discontinued in July 2025. English and French are translations, marked `reviewed:
// false` like the site's other machine-made ones, and say at the top that the German is binding.
// Counsel should read all three before launch.
//
// ⚠ Pure: no imports. Plain Node runs it for the support-page build.

/** A legal page in one language: the imprint here, the privacy policy in privacy.ts. */
export interface LegalPage {
  /** The page's <h1> and the start of its <title>; also the footer link's text (landing.json). */
  readonly title: string
  readonly description: string
  /** A translation's first line: the German version is the binding one. Absent in German. */
  readonly translationNotice?: string
  /**
   * true = the text was read by a native speaker (a language check), NOT a counsel review: the
   * German imprint and privacy policy are `true` and are still awaiting counsel. ⚠ false = a
   * translation not yet read by a native speaker.
   */
  readonly reviewed: boolean
  /** The body, as HTML: trusted, written here, never user input. An `<h2 id=…>` enters the table of contents. */
  readonly html: string
}

const EMAIL = '<a href="mailto:contact@cajetan.company">contact@cajetan.company</a>'

/** The postal address, shared by every language except the country's name. */
const address = (country: string) =>
  `<p>Cajetan UG (haftungsbeschränkt)<br>Lucas-Cranach-Straße 5<br>84453 Mühldorf am Inn<br>${country}</p>`

// TODO(imprint): business phone number goes here — owner is getting one; don't use the mobile from
// cajetan.company. When it exists, add a "Telefon: <a href="tel:+49…">+49 …</a>" line above the
// e-mail in each language's contact section.

export const IMPRINT: Record<string, LegalPage> = {
  de: {
    title: 'Impressum',
    description: 'Impressum von Tenshu: Angaben gemäß § 5 DDG zur Cajetan UG (haftungsbeschränkt).',
    reviewed: true,
    html: [
      '<h2>Angaben gemäß § 5 DDG</h2>',
      address('Deutschland'),
      '<p>Vertreten durch: Benedikt Löbbecke</p>',
      '<h2>Kontakt</h2>',
      `<p>E-Mail: ${EMAIL}</p>`,
      '<h2>Registereintrag</h2>',
      '<p>Registergericht: Amtsgericht Traunstein<br>Registernummer: HRB 35668</p>',
      '<h2>Umsatzsteuer-Identifikationsnummer</h2>',
      '<p>Umsatzsteuer-Identifikationsnummer gemäß § 27a Umsatzsteuergesetz: DE465138089</p>',
      '<h2>Verbraucherstreitbeilegung</h2>',
      '<p>Wir sind nicht bereit und nicht verpflichtet, an Streitbeilegungsverfahren vor einer Verbraucherschlichtungsstelle teilzunehmen.</p>',
      '<h2>Verantwortlich für den Inhalt nach § 18 Abs. 2 MStV</h2>',
      '<p>Benedikt Löbbecke, Anschrift wie oben.</p>',
      '<h2>Haftung für Inhalte</h2>',
      '<p>Als Diensteanbieter sind wir für eigene Inhalte auf diesen Seiten nach den allgemeinen Gesetzen verantwortlich. Wir sind jedoch nicht verpflichtet, übermittelte oder gespeicherte fremde Informationen zu überwachen oder nach Umständen zu forschen, die auf eine rechtswidrige Tätigkeit hinweisen.</p>',
      '<h2>Haftung für Links</h2>',
      '<p>Unser Angebot enthält Links zu externen Websites Dritter, auf deren Inhalte wir keinen Einfluss haben. Für die Inhalte der verlinkten Seiten ist stets der jeweilige Anbieter oder Betreiber der Seiten verantwortlich.</p>',
      '<h2>Marken</h2>',
      '<p>Tenshu ist eine Marke von Benedikt Löbbecke.</p>',
      '<p>Apple, das Apple Logo, iPhone, iPad und Mac sind Marken der Apple Inc., die in den USA und weiteren Ländern und Regionen eingetragen sind. App Store ist eine Dienstleistungsmarke der Apple Inc., die in den USA und weiteren Ländern und Regionen eingetragen ist. IOS ist eine Marke oder eingetragene Marke von Cisco in den USA und weiteren Ländern und wird unter Lizenz verwendet.</p>',
      '<p>Google Play und das Google Play-Logo sind Marken von Google LLC. Android ist eine Marke von Google LLC.</p>',
      '<p>Windows ist eine Marke der Microsoft-Unternehmensgruppe. Linux® ist eine eingetragene Marke von Linus Torvalds in den USA und weiteren Ländern.</p>',
      '<p>Tenshu steht in keiner Verbindung zu Apple, Google oder Microsoft und wird von diesen weder unterstützt noch gesponsert.</p>',
    ].join('\n'),
  },
  en: {
    title: 'Imprint',
    description: 'Imprint of Tenshu: legal notice pursuant to § 5 DDG for Cajetan UG (haftungsbeschränkt).',
    translationNotice: 'This is a translation; the German version is legally binding.',
    reviewed: false,
    html: [
      '<h2>Information pursuant to § 5 DDG</h2>',
      address('Germany'),
      '<p>Represented by: Benedikt Löbbecke</p>',
      '<h2>Contact</h2>',
      `<p>Email: ${EMAIL}</p>`,
      '<h2>Register entry</h2>',
      '<p>Register court: Amtsgericht Traunstein<br>Register number: HRB 35668</p>',
      '<h2>VAT identification number</h2>',
      '<p>VAT identification number pursuant to § 27a of the German VAT Act (Umsatzsteuergesetz): DE465138089</p>',
      '<h2>Consumer dispute resolution</h2>',
      '<p>We are neither willing nor obliged to take part in dispute resolution proceedings before a consumer arbitration board.</p>',
      '<h2>Responsible for the content pursuant to § 18(2) MStV</h2>',
      '<p>Benedikt Löbbecke, address as above.</p>',
      '<h2>Liability for content</h2>',
      '<p>As a service provider, we are responsible for our own content on these pages under the general laws. However, we are not obliged to monitor third-party information that is transmitted or stored, or to investigate circumstances that indicate unlawful activity.</p>',
      '<h2>Liability for links</h2>',
      '<p>Our website contains links to external third-party websites whose content we have no influence over. The respective provider or operator of the linked pages is always responsible for their content.</p>',
      '<h2>Trademarks</h2>',
      '<p>Tenshu is a brand of Benedikt Löbbecke.</p>',
      '<p>Apple, the Apple logo, iPhone, iPad and Mac are trademarks of Apple Inc., registered in the U.S. and other countries and regions. App Store is a service mark of Apple Inc., registered in the U.S. and other countries and regions. IOS is a trademark or registered trademark of Cisco in the U.S. and other countries and is used under license.</p>',
      '<p>Google Play and the Google Play logo are trademarks of Google LLC. Android is a trademark of Google LLC.</p>',
      '<p>Windows is a trademark of the Microsoft group of companies. Linux® is the registered trademark of Linus Torvalds in the U.S. and other countries.</p>',
      '<p>Tenshu is not affiliated with, endorsed by or sponsored by Apple, Google or Microsoft.</p>',
    ].join('\n'),
  },
  fr: {
    title: 'Mentions légales',
    description: 'Mentions légales de Tenshu : informations conformément au § 5 DDG sur Cajetan UG (haftungsbeschränkt).',
    translationNotice: 'Ceci est une traduction ; seule la version allemande fait foi.',
    reviewed: false,
    html: [
      '<h2>Informations conformément au § 5 DDG</h2>',
      address('Allemagne'),
      '<p>Représentée par : Benedikt Löbbecke</p>',
      '<h2>Contact</h2>',
      `<p>E-mail : ${EMAIL}</p>`,
      '<h2>Inscription au registre</h2>',
      '<p>Tribunal d’enregistrement : Amtsgericht Traunstein<br>Numéro d’immatriculation : HRB 35668</p>',
      '<h2>Numéro de TVA intracommunautaire</h2>',
      '<p>Numéro d’identification à la TVA conformément au § 27a de la loi allemande relative à la TVA (Umsatzsteuergesetz) : DE465138089</p>',
      '<h2>Règlement des litiges de consommation</h2>',
      '<p>Nous ne sommes ni disposés ni tenus à participer à une procédure de règlement des litiges devant un organisme de médiation de la consommation.</p>',
      '<h2>Responsable du contenu conformément au § 18, al. 2 MStV</h2>',
      '<p>Benedikt Löbbecke, adresse ci-dessus.</p>',
      '<h2>Responsabilité quant au contenu</h2>',
      '<p>En tant que prestataire de services, nous sommes responsables de nos propres contenus sur ces pages conformément aux lois générales. Nous ne sommes toutefois pas tenus de surveiller les informations de tiers transmises ou stockées, ni de rechercher des circonstances indiquant une activité illicite.</p>',
      '<h2>Responsabilité quant aux liens</h2>',
      '<p>Notre site contient des liens vers des sites web externes de tiers, sur le contenu desquels nous n’avons aucune influence. Le fournisseur ou l’exploitant des pages liées est toujours responsable de leur contenu.</p>',
      '<h2>Marques</h2>',
      '<p>Tenshu est une marque de Benedikt Löbbecke.</p>',
      '<p>Apple, le logo Apple, iPhone, iPad et Mac sont des marques d’Apple Inc., déposées aux États-Unis et dans d’autres pays et régions. App Store est une marque de service d’Apple Inc., déposée aux États-Unis et dans d’autres pays et régions. IOS est une marque ou une marque déposée de Cisco aux États-Unis et dans d’autres pays, utilisée sous licence.</p>',
      '<p>Google Play et le logo Google Play sont des marques de Google LLC. Android est une marque de Google LLC.</p>',
      '<p>Windows est une marque du groupe de sociétés Microsoft. Linux® est une marque déposée de Linus Torvalds aux États-Unis et dans d’autres pays.</p>',
      '<p>Tenshu n’est ni affilié à Apple, Google ou Microsoft, ni approuvé ou sponsorisé par ces sociétés.</p>',
    ].join('\n'),
  },
}

/** The imprint's languages, in the order the language picker lists them (English first). */
export const IMPRINT_LANGUAGES = ['en', 'de', 'fr'] as const

export function imprintUrl(lang: string): string {
  return `/legal/imprint/${lang}`
}
