// The public pricing page, `/pricing/<lang>`: built by the support-page generator (generate.ts
// buildPricing) into static pages with the landing page's header and footer, like the roadmap.
// Caddy serves them as files before the SPA loads, so the pre-launch curtain never covers them;
// `/pricing` 302s by Accept-Language (web/apex/Caddyfile). The header's "Pricing" entry, the
// landing FAQ and the app pages link here, so no other page has to name a price.
//
// What goes on it — the rules, so the page stays honest:
//   - The plans and prices come from src/control/services/plans.ts, the apex's one copy, never
//     typed here. generate.test.ts also holds the Terms to the same prices.
//   - Every FAQ answer says no more than the Terms section it links (support-pages/terms.ts):
//     storage, cancellation, withdrawal, plans, closure. A change there changes the answer here.
//   - Every card leads to `/start`: a visitor here has no realm yet, and the plan picker's
//     checkout path needs one (it sits inside ControlShell). Everyone starts on Free and upgrades
//     in Control → Services, and the page says so under the cards.
//   - No "most popular" badge or pre-selected plan: the three cards look alike
//     (control-subscription-ux.md §13, no dark patterns).
//   - No "open source" claim while the repository is private (generate.test.ts checks it), and
//     nothing promised as available that is not: "later" items are planned, not built.
//
// English is the source; German and French are translations nobody has read yet (`reviewed: false`).
import { PLANS, formatEuro, type Plan } from '../src/control/services/plans.ts'
import { SUPPORT_MAIL } from './privacy.ts'
import { termsUrl } from './terms.ts'

/** A Terms section the FAQ links, by its `<h2 id>` in terms.ts. */
type TermsSection = 'plans' | 'storage' | 'cancellation' | 'withdrawal' | 'closure'

export interface PricingFaq {
  readonly question: string
  readonly answer: string
  /** The Terms section that makes the answer true. */
  readonly terms: TermsSection
}

export interface PricingPage {
  /** The page's <h1> and the start of its <title>. */
  readonly title: string
  readonly description: string
  /** true = read by a native speaker. ⚠ false = not yet. */
  readonly reviewed: boolean
  readonly lede: string
  readonly planNames: Record<Plan['id'], string>
  readonly planBlurbs: Record<Plan['id'], string>
  readonly freeFeatures: readonly string[]
  readonly paidFeatures: readonly string[]
  readonly perMonth: string
  /** Every card's button: all lead to `/start`. */
  readonly cta: string
  /** Under the cards: everyone starts on Free, and where to upgrade. */
  readonly upgradeNote: string
  readonly priceNote: string
  readonly alwaysFreeHeading: string
  readonly alwaysFree: readonly string[]
  readonly laterHeading: string
  readonly later: string
  readonly faqHeading: string
  readonly faq: readonly PricingFaq[]
  /** Each answer's link to its Terms section. */
  readonly termsLink: string
  /** The closing line, as HTML (it holds the mail link). */
  readonly closing: string
}

const en: PricingPage = {
  title: 'Pricing',
  description: 'Every Tenshu app is free. Syncing between your devices is free up to 5 GB, and Basic and Premium give you more storage. Prices include VAT.',
  reviewed: true,
  lede: 'Every Tenshu app is free to use. You pay only if you want more storage on our servers, for syncing your devices and sharing.',
  planNames: { free: 'Free', basic: 'Basic', premium: 'Premium' },
  planBlurbs: {
    free: 'Sync your apps across all your devices.',
    basic: 'For your photo library and your files.',
    premium: 'For everything, with room to spare.',
  },
  freeFeatures: ['A free account with your email address', 'Every Tenshu app', 'End-to-end encrypted sync between your devices', 'Share links'],
  paidFeatures: ['Everything in Free', 'Cancel any time'],
  perMonth: 'per month',
  cta: 'Get started',
  upgradeNote: 'Everyone starts on Free. Move to Basic or Premium whenever you need more, in Tenshu Control under Services.',
  priceNote: 'Prices include VAT. A paid plan renews every month until you cancel. In the App Store and Google Play, prices may differ by country.',
  alwaysFreeHeading: 'What never costs anything',
  alwaysFree: [
    'Every Tenshu app, on all your devices.',
    'Using the apps on one device, with no account at all.',
    'Moving to a smaller plan, or back to Free. Nothing you’ve stored is deleted.',
  ],
  laterHeading: 'Coming later',
  later: 'Yearly plans, and paying as you go for AI features.',
  faqHeading: 'Questions',
  faq: [
    {
      question: 'What counts towards my storage?',
      answer: 'Everything you store on our servers, end-to-end encrypted: your files and photos, your apps’ data, and the separate encrypted copy a share link or a shared folder keeps. What stays only on your device doesn’t count.',
      terms: 'storage',
    },
    {
      question: 'What happens if I go over my limit?',
      answer: 'We accept no new uploads until you’re back under it, by deleting something or choosing a bigger plan. We never delete what you’ve already stored.',
      terms: 'storage',
    },
    {
      question: 'Can I cancel at any time?',
      answer: 'Yes, without a reason and without a fee. Your plan runs to the end of the month you’ve paid for, then moves to Free. Nothing is deleted.',
      terms: 'cancellation',
    },
    {
      question: 'Can I withdraw after buying?',
      answer: 'If you buy from us, yes: within 14 days, without giving a reason. You pay only for the time up to your withdrawal, and we refund the rest. For purchases in the App Store or Google Play, their rules apply.',
      terms: 'withdrawal',
    },
    {
      question: 'Where can I buy a plan?',
      answer: 'On the web, in the desktop apps and in Android apps from outside Google Play, you buy from us and pay by card. In the apps from Google Play, you buy through Google, and on iPhone and iPad through Apple once that’s available. Your plan belongs to your account, so it works on all your devices, wherever you bought it.',
      terms: 'plans',
    },
    {
      question: 'What happens if I delete my account?',
      answer: '30 days later, we delete all of its encrypted content from our live storage. Save what you want to keep before you delete it.',
      terms: 'closure',
    },
  ],
  termsLink: 'Details in the terms of use',
  closing: `Questions about plans or billing? Write to us at ${SUPPORT_MAIL}.`,
}

const de: PricingPage = {
  title: 'Preise',
  description: 'Jede Tenshu-App ist kostenlos. Die Synchronisation zwischen deinen Geräten ist bis 5 GB kostenlos, Basic und Premium bieten mehr Speicher. Preise inklusive MwSt.',
  reviewed: false,
  lede: 'Jede Tenshu-App ist kostenlos. Du zahlst nur, wenn du mehr Speicher auf unseren Servern möchtest, um deine Geräte zu synchronisieren und Inhalte zu teilen.',
  planNames: { free: 'Free', basic: 'Basic', premium: 'Premium' },
  planBlurbs: {
    free: 'Synchronisiere deine Apps auf all deinen Geräten.',
    basic: 'Für deine Fotomediathek und deine Dateien.',
    premium: 'Für alles, mit viel Luft nach oben.',
  },
  freeFeatures: ['Ein kostenloses Konto mit deiner E-Mail-Adresse', 'Jede Tenshu-App', 'Ende-zu-Ende-verschlüsselte Synchronisation zwischen deinen Geräten', 'Freigabe-Links'],
  paidFeatures: ['Alles aus Free', 'Jederzeit kündbar'],
  perMonth: 'pro Monat',
  cta: 'Loslegen',
  upgradeNote: 'Alle starten mit Free. Zu Basic oder Premium wechselst du, wann immer du mehr brauchst, in Tenshu Control unter „Services“.',
  priceNote: 'Preise inklusive MwSt. Ein kostenpflichtiger Plan verlängert sich jeden Monat, bis du kündigst. Im App Store und bei Google Play können die Preise je nach Land abweichen.',
  alwaysFreeHeading: 'Was immer kostenlos bleibt',
  alwaysFree: [
    'Jede Tenshu-App, auf all deinen Geräten.',
    'Die Apps auf einem Gerät nutzen, ganz ohne Konto.',
    'Der Wechsel zu einem kleineren Plan oder zurück zu Free. Was du gespeichert hast, wird nicht gelöscht.',
  ],
  laterHeading: 'Später',
  later: 'Jahrespläne und Bezahlung nach Verbrauch für KI-Funktionen.',
  faqHeading: 'Fragen',
  faq: [
    {
      question: 'Was zählt zu meinem Speicher?',
      answer: 'Alles, was du auf unseren Servern speicherst, Ende-zu-Ende-verschlüsselt: deine Dateien und Fotos, die Daten deiner Apps und die separate verschlüsselte Kopie, die ein Freigabe-Link oder ein geteilter Ordner hält. Was nur auf deinem Gerät liegt, zählt nicht.',
      terms: 'storage',
    },
    {
      question: 'Was passiert, wenn ich mein Limit überschreite?',
      answer: 'Wir nehmen keine neuen Uploads an, bis du wieder darunter bist, indem du etwas löschst oder einen größeren Plan wählst. Was du schon gespeichert hast, löschen wir nie.',
      terms: 'storage',
    },
    {
      question: 'Kann ich jederzeit kündigen?',
      answer: 'Ja, ohne Angabe von Gründen und ohne Gebühr. Dein Plan läuft bis zum Ende des bezahlten Monats und wechselt dann zu Free. Es wird nichts gelöscht.',
      terms: 'cancellation',
    },
    {
      question: 'Kann ich nach dem Kauf widerrufen?',
      answer: 'Wenn du bei uns kaufst, ja: innerhalb von 14 Tagen, ohne Angabe von Gründen. Du zahlst nur für die Zeit bis zu deinem Widerruf, den Rest erstatten wir. Für Käufe im App Store oder bei Google Play gelten deren Regeln.',
      terms: 'withdrawal',
    },
    {
      question: 'Wo kann ich einen Plan kaufen?',
      answer: 'Im Web, in den Desktop-Apps und in Android-Apps, die du außerhalb von Google Play installiert hast, kaufst du bei uns und zahlst per Karte. In den Apps aus Google Play kaufst du über Google, auf iPhone und iPad über Apple, sobald das möglich ist. Dein Plan gehört zu deinem Konto und gilt deshalb auf all deinen Geräten, egal wo du ihn gekauft hast.',
      terms: 'plans',
    },
    {
      question: 'Was passiert, wenn ich mein Konto lösche?',
      answer: '30 Tage später löschen wir alle verschlüsselten Inhalte des Kontos aus unserem Live-Speicher. Sichere vorher, was du behalten möchtest.',
      terms: 'closure',
    },
  ],
  termsLink: 'Details in den Nutzungsbedingungen',
  closing: `Fragen zu Plänen oder zur Abrechnung? Schreib uns an ${SUPPORT_MAIL}.`,
}

const fr: PricingPage = {
  title: 'Tarifs',
  description: 'Toutes les apps Tenshu sont gratuites. La synchronisation entre vos appareils est gratuite jusqu’à 5 Go, et Basic et Premium vous donnent plus d’espace. Prix TTC.',
  reviewed: false,
  lede: 'Toutes les apps Tenshu sont gratuites. Vous ne payez que si vous voulez plus d’espace sur nos serveurs, pour synchroniser vos appareils et partager.',
  planNames: { free: 'Free', basic: 'Basic', premium: 'Premium' },
  planBlurbs: {
    free: 'Synchronisez vos apps sur tous vos appareils.',
    basic: 'Pour votre photothèque et vos fichiers.',
    premium: 'Pour tout, avec de la marge.',
  },
  freeFeatures: ['Un compte gratuit avec votre adresse e-mail', 'Toutes les apps Tenshu', 'Synchronisation chiffrée de bout en bout entre vos appareils', 'Liens de partage'],
  paidFeatures: ['Tout ce qu’offre Free', 'Résiliable à tout moment'],
  perMonth: 'par mois',
  cta: 'Commencer',
  upgradeNote: 'Tout le monde commence avec Free. Passez à Basic ou Premium dès que vous avez besoin de plus, dans Tenshu Control, sous « Services ».',
  priceNote: 'Prix TTC. Une offre payante se renouvelle chaque mois jusqu’à ce que vous la résiliiez. Sur l’App Store et Google Play, les prix peuvent varier selon le pays.',
  alwaysFreeHeading: 'Ce qui reste toujours gratuit',
  alwaysFree: [
    'Toutes les apps Tenshu, sur tous vos appareils.',
    'Utiliser les apps sur un seul appareil, sans aucun compte.',
    'Passer à une offre inférieure, ou revenir à Free. Rien de ce que vous avez stocké n’est supprimé.',
  ],
  laterHeading: 'Plus tard',
  later: 'Des offres annuelles, et le paiement à l’usage pour les fonctions d’IA.',
  faqHeading: 'Questions',
  faq: [
    {
      question: 'Qu’est-ce qui compte dans mon espace ?',
      answer: 'Tout ce que vous stockez sur nos serveurs, chiffré de bout en bout : vos fichiers et photos, les données de vos apps, et la copie chiffrée distincte que conserve un lien de partage ou un dossier partagé. Ce qui reste uniquement sur votre appareil ne compte pas.',
      terms: 'storage',
    },
    {
      question: 'Que se passe-t-il si je dépasse ma limite ?',
      answer: 'Nous n’acceptons plus de nouveaux envois tant que vous n’êtes pas repassé en dessous, en supprimant quelque chose ou en choisissant une offre supérieure. Nous ne supprimons jamais ce que vous avez déjà stocké.',
      terms: 'storage',
    },
    {
      question: 'Puis-je résilier à tout moment ?',
      answer: 'Oui, sans motif et sans frais. Votre offre court jusqu’à la fin du mois payé, puis passe à Free. Rien n’est supprimé.',
      terms: 'cancellation',
    },
    {
      question: 'Puis-je me rétracter après un achat ?',
      answer: 'Si vous achetez chez nous, oui : dans les 14 jours, sans motif. Vous ne payez que la période jusqu’à votre rétractation, et nous remboursons le reste. Pour les achats sur l’App Store ou Google Play, leurs règles s’appliquent.',
      terms: 'withdrawal',
    },
    {
      question: 'Où souscrire une offre ?',
      answer: 'Sur le web, dans les apps de bureau et dans les apps Android installées hors de Google Play, vous achetez chez nous et payez par carte. Dans les apps de Google Play, vous achetez via Google, et sur iPhone et iPad via Apple dès que ce sera possible. Votre offre appartient à votre compte : elle fonctionne sur tous vos appareils, où que vous l’ayez achetée.',
      terms: 'plans',
    },
    {
      question: 'Que se passe-t-il si je supprime mon compte ?',
      answer: '30 jours plus tard, nous supprimons tout son contenu chiffré de notre stockage actif. Enregistrez d’abord ce que vous voulez garder.',
      terms: 'closure',
    },
  ],
  termsLink: 'Détails dans les conditions d’utilisation',
  closing: `Des questions sur les offres ou la facturation ? Écrivez-nous à ${SUPPORT_MAIL}.`,
}

export const PRICING: Record<string, PricingPage> = { en, de, fr }

/** The pricing page's languages, in the order the language picker lists them (English first). */
export const PRICING_LANGUAGES = ['en', 'de', 'fr'] as const

export function pricingUrl(lang: string): string {
  return `/pricing/${lang}`
}

/** `/pricing`, the language-neutral URL: the header's link, x-default, and Caddy's redirect. */
export const PRICING_NEUTRAL_URL = '/pricing'

/**
 * Where every card's button leads: the site's way in. ⚠ Not the plan picker's checkout path
 * (tiers.ts purchasePath): that sits inside ControlShell, which sends a browser without a realm,
 * every visitor of this page, back to `/`.
 */
export const START_HREF = '/start'

/** "5 GB" / "2 TB"; French writes "Go" / "To". Whole binary units only, as every plan is one. */
export function planStorage(bytes: number, lang: string): string {
  const tib = 1024 ** 4
  const [value, unit] = bytes >= tib ? [bytes / tib, 'TB'] : [bytes / 1024 ** 3, 'GB']
  return `${value} ${lang === 'fr' ? unit.replace('B', 'o') : unit}`
}

const escape = (text: string) =>
  text.replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;').replaceAll('"', '&quot;')

/**
 * The page body as HTML: the lede, a card per plan, the price note, what stays free, what comes
 * later, the questions and the closing line. Plain text throughout except [PricingPage.closing].
 * ⚠ Every <h2> carries `class` after its id, so generate.ts's table-of-contents match (a bare
 * `<h2 id="…">`) skips them: the page needs no contents list.
 */
export function pricingHtml(page: PricingPage, lang: string): string {
  const card = (plan: Plan) => {
    const name = page.planNames[plan.id]
    const features = plan.monthlyEuroCents === 0 ? page.freeFeatures : page.paidFeatures
    // Free too shows its price, "€0 per month": the three cards read alike, and its name is not
    // repeated as a price.
    const amount = plan.monthlyEuroCents === 0 ? formatEuro(0, lang).replace(/[.,]00/, '') : formatEuro(plan.monthlyEuroCents, lang)
    const price = `<span class="text-2xl font-semibold text-text">${escape(amount)}</span> <span class="text-text-2">${escape(page.perMonth)}</span>`
    return [
      `<section id="${plan.id}" aria-labelledby="${plan.id}-heading" class="flex flex-col rounded-2xl border border-line bg-surface p-6">`,
      `<h2 id="${plan.id}-heading" class="text-lg font-semibold text-text">${escape(name)}</h2>`,
      `<p class="mt-4 text-4xl font-semibold tracking-tight text-text">${escape(planStorage(plan.limitBytes, lang))}</p>`,
      `<p class="mt-2">${price}</p>`,
      `<p class="mt-3 text-text-2">${escape(page.planBlurbs[plan.id])}</p>`,
      '<ul class="mt-4 flex-1 list-disc space-y-1.5 pl-5 text-sm text-text-2 marker:text-text-3">',
      ...features.map((f) => `<li>${escape(f)}</li>`),
      '</ul>',
      `<a href="${START_HREF}" class="mt-6 inline-flex min-h-11 items-center justify-center rounded-lg bg-primary px-4 text-sm font-semibold text-on-primary hover:opacity-90">${escape(page.cta)}</a>`,
      '</section>',
    ].join('\n')
  }
  const termsHref = (section: TermsSection) => `${termsUrl(lang)}#${section}`
  const linkClass = 'text-primary underline underline-offset-4'
  return [
    `<p class="max-w-3xl text-lg text-text-2">${escape(page.lede)}</p>`,
    '<div class="mt-10 grid gap-4 md:grid-cols-3">',
    ...PLANS.map(card),
    '</div>',
    `<p class="mt-6 text-text-2">${escape(page.upgradeNote)}</p>`,
    `<p class="mt-2 text-sm text-text-3">${escape(page.priceNote)}</p>`,
    '<div class="mt-14 grid gap-4 md:grid-cols-2">',
    '<section class="rounded-2xl border border-line bg-surface-2 p-6" aria-labelledby="always-free-heading">',
    `<h2 id="always-free-heading" class="text-lg font-semibold text-text">${escape(page.alwaysFreeHeading)}</h2>`,
    '<ul class="mt-3 list-disc space-y-2 pl-5 text-text-2 marker:text-text-3">',
    ...page.alwaysFree.map((item) => `<li>${escape(item)}</li>`),
    '</ul>',
    '</section>',
    '<section class="rounded-2xl border border-line p-6" aria-labelledby="later-heading">',
    `<h2 id="later-heading" class="text-lg font-semibold text-text">${escape(page.laterHeading)}</h2>`,
    `<p class="mt-3 text-text-2">${escape(page.later)}</p>`,
    '</section>',
    '</div>',
    `<section id="questions" aria-labelledby="questions-heading" class="mt-14 max-w-3xl">`,
    `<h2 id="questions-heading" class="text-2xl font-semibold tracking-tight">${escape(page.faqHeading)}</h2>`,
    ...page.faq.map((f) =>
      [
        `<h3 class="mt-8 text-lg font-semibold text-text">${escape(f.question)}</h3>`,
        `<p class="mt-2 text-text-2">${escape(f.answer)} <a href="${termsHref(f.terms)}" class="${linkClass}">${escape(page.termsLink)}</a></p>`,
      ].join('\n'),
    ),
    '</section>',
    `<p class="mt-14 border-t border-line pt-8 text-text-2 [&_a]:text-primary [&_a]:underline [&_a]:underline-offset-4">${page.closing}</p>`,
  ].join('\n')
}
