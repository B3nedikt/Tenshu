// The landing page's Apps section (Block7Apps): which apps the carousel shows, which captures
// represent each, which are not usable yet, and where each one links.
// docs/proposals/web-about-tenshu-public.md §9.
//
// The roster and its order are the support hub's (support-pages/hub.ts HUB_APPS), so the two
// carousels list the same apps the same way. Where an app links: landingAppHref, the one place.
import { APP_ICON_IDS, HUB_APPS, HUB_SHOT_PLATFORM, appIcon, type HubApp } from '../../../support-pages/hub.ts'
import type { CarouselIcon, CarouselImage, CarouselShot, CarouselVariant, ShotPlatform } from './carouselModel'

/**
 * The captures each app's card shows, per platform, in order: `NN-screen` keys of
 * `store/<app>/screenshots.manifest.json` on that platform's device (DEVICE below). The carousel's
 * "iPhone | Android" switch picks the set; a platform with none published shows the other's, with
 * the switch disabled for that app. An app with none at all shows a neutral placeholder card.
 *
 * Add an app's keys here once its captures are published, and nothing else changes. Keys that are
 * not in the manifest are skipped, never a broken image.
 *
 * A key may be `{ screen, landing: true }`: a capture of the landing page's own content set — a
 * separate realm full of stock photos (store/fixtures/landing/, docs/how-to/store-screenshots.md
 * §9), published under the device `<device>landing` (`android/phonelanding/<lang>/NN-screen`), so
 * it never replaces the support pages' plain capture of the same number. On iOS the same scheme is
 * `ios/iphone69landing/<lang>/NN-screen`.
 *
 * A key may be `{ screen, framed: true }`: a "pre-framed" capture that already carries a real
 * device bezel (scripts/store-frame, D7), published under `…/<lang>/framed/NN-screen` (and
 * `…/<lang>/dark/framed/NN-screen`). The carousel then draws no frame of its own. Nothing uses it
 * yet — the bezels' licences are checked first.
 */
export type ScreenRef = string | { readonly screen: string; readonly framed?: true; readonly landing?: true }

const landing = (screen: string): ScreenRef => ({ screen, landing: true })

export interface PlatformScreens {
  readonly android: readonly ScreenRef[]
  readonly ios: readonly ScreenRef[]
}

/**
 * Drive's and Photos' `ios` entries name the iOS landing set (`iphone69landing`, English only, so
 * other languages fall back to it). ⚠ A key missing from the manifest is skipped, not an error: an
 * unpublished capture silently disables that platform's switch rather than failing the build.
 */
export const LANDING_SCREENS: Readonly<Record<string, PlatformScreens>> = {
  control: { android: ['01-welcome', '06-settings'], ios: ['01-welcome', '06-settings'] },
  drive: {
    android: [landing('01-files'), landing('02-photos'), landing('03-preview')],
    ios: [landing('01-files'), landing('02-photos'), landing('03-preview')],
  },
  photos: {
    android: [landing('01-library'), landing('02-album'), landing('03-viewer')],
    ios: [landing('01-library'), landing('02-album'), landing('03-viewer')],
  },
  fit: { android: ['01-home', '03-workout', '06-sleep'], ios: ['01-home', '03-workout', '06-sleep'] },
  calendar: { android: ['01-month', '07-week', '02-agenda'], ios: ['01-month', '02-agenda', '03-event'] },
  contacts: { android: ['01-contacts', '02-contact', '04-duplicates'], ios: ['01-contacts', '02-contact', '04-duplicates'] },
}

const NO_SCREENS: PlatformScreens = { android: [], ios: [] }

/**
 * Apps that are not usable yet: their card says so ("Coming later"), quietly. Nothing marks them in
 * the list of names, and no other app carries a status at all.
 */
export const COMING_LATER: ReadonlySet<string> = new Set(['home', 'mia'])

/** Re-exported for the landing tests; the list itself is hub.ts's, which both carousels read. */
export { APP_ICON_IDS }

/**
 * ⚠ THE place the landing carousel decides where an app leads — its name in the list, its name on
 * the card and its phone all carry this one href (the landing card has no "Open X support" button:
 * web-about-tenshu-public.md §9).
 *
 * An app with a marketing page (support-pages/appPages.ts) leads there, `/apps/<app>`, which Caddy
 * sends on to `/apps/<app>/<lang>`; that page links its support. Any other app leads to its support
 * entry, `/apps/<app>/support`, which picks the visitor's platform and language
 * (support-pages/generate.ts), if it has support pages; no link otherwise. Apps switch one at a
 * time, each in the change that ships its page.
 */
export function landingAppHref(app: HubApp): string | null {
  if (APPS_WITH_PAGES.has(app.app)) return `/apps/${app.app}`
  return app.support ? `/apps/${app.app}/support` : null
}

/**
 * The apps with a marketing page: appPages.ts APP_PAGES's ids, spelled out here so the SPA bundle
 * does not pull in the page copy (landingSections.test.tsx holds the two together).
 */
export const APPS_WITH_PAGES: ReadonlySet<string> = new Set(['control', 'drive', 'photos', 'calendar', 'contacts', 'fit'])

export interface LandingApp {
  readonly id: string
  /** Where the app leads (landingAppHref), or none. */
  readonly href: string | null
  readonly comingLater: boolean
  readonly icon: CarouselIcon | null
  readonly screens: PlatformScreens
}

export const LANDING_APPS: readonly LandingApp[] = HUB_APPS.map((app) => ({
  id: app.app,
  href: landingAppHref(app),
  comingLater: COMING_LATER.has(app.app),
  icon: appIcon(app.app),
  screens: LANDING_SCREENS[app.app] ?? NO_SCREENS,
}))

// ── captures ─────────────────────────────────────────────────────────────

export interface ShotManifest {
  readonly base: string
  readonly screens: Readonly<Record<string, { readonly sha256: string; readonly width: number; readonly height: number }>>
  /**
   * The website's WebP copies, by the source PNG's sha256 (`store/<app>/screenshots.webp.json`,
   * scripts/lib/store_webp.py), each at `base + sha256 + '.webp'`. A PNG with none is shown as is.
   */
  readonly derivatives?: Readonly<Record<string, readonly { readonly width: number; readonly sha256: string }[]>>
}

interface WebpSidecar {
  readonly base: string
  readonly derivatives: NonNullable<ShotManifest['derivatives']>
}

// Every published manifest, by app. Read at build time; the images themselves stay on R2
// (assets.tenshu.app), like the support pages'. The Docker build copies store/ for the same reason.
const byApp = <T,>(files: Record<string, T>): Record<string, T> =>
  Object.fromEntries(Object.entries(files).map(([path, file]) => [path.split('/').at(-2)!, file]))
const SIDECARS = byApp(
  import.meta.glob<WebpSidecar>('../../../../../store/*/screenshots.webp.json', { eager: true, import: 'default' }),
)
const MANIFESTS: Readonly<Record<string, ShotManifest>> = Object.fromEntries(
  Object.entries(
    byApp(import.meta.glob<ShotManifest>('../../../../../store/*/screenshots.manifest.json', { eager: true, import: 'default' })),
  ).map(([app, manifest]) => [app, SIDECARS[app] ? { ...manifest, derivatives: SIDECARS[app].derivatives } : manifest]),
)

/** The device the captures come from on each platform (support-pages/generate.ts SUPPORT_DEVICE). */
const DEVICE: Readonly<Record<ShotPlatform, string>> = { android: 'phone', ios: 'iphone69' }
/** Whose bezel a pre-framed capture on that platform carries. */
const VENDOR: Readonly<Record<ShotPlatform, 'apple' | 'google'>> = { android: 'google', ios: 'apple' }

/** Captures in [lang], or English where that language has none of a screen (as on the support pages). */
export function landingShots(
  app: string,
  screens: readonly ScreenRef[],
  lang: string,
  alt: string,
  manifests: Readonly<Record<string, ShotManifest>> = MANIFESTS,
  platform: ShotPlatform = HUB_SHOT_PLATFORM,
): CarouselShot[] {
  const manifest = manifests[app]
  const platformDevice = DEVICE[platform]
  if (!manifest || !platformDevice) return []
  const image = (key: string): CarouselImage | null => {
    const entry = manifest.screens[key]
    if (!entry) return null
    const variants: CarouselVariant[] = (manifest.derivatives?.[entry.sha256] ?? [])
      .map((d) => ({ url: `${manifest.base}${d.sha256}.webp`, width: d.width }))
      .sort((a, b) => a.width - b.width)
    const png = { url: `${manifest.base}${entry.sha256}.png`, width: entry.width, height: entry.height }
    return variants.length ? { ...png, variants } : png
  }
  return screens.flatMap((ref) => {
    const { screen, framed = false, landing = false } = typeof ref === 'string' ? { screen: ref } : ref
    const device = landing ? `${platformDevice}landing` : platformDevice
    const slot = (l: string, tail: string) => ({
      light: image(`${platform}/${device}/${l}/${tail}`),
      dark: image(`${platform}/${device}/${l}/dark/${tail}`),
    })
    const resolve = (tail: string) => {
      const s = slot(lang, tail)
      return s.light || s.dark ? s : slot('en', tail)
    }
    const bare = resolve(screen)
    if (framed) {
      const s = resolve(`framed/${screen}`)
      const fallback = bare.light || bare.dark ? bare : null
      if (s.light || s.dark) return [{ ...s, alt, framed: { vendor: VENDOR[platform], bare: fallback } }]
      return fallback ? [{ ...fallback, alt }] : []
    }
    return bare.light || bare.dark ? [{ ...bare, alt }] : []
  })
}
