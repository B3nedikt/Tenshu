// Static support pages — docs/proposals/store-presence-and-support-pages.md §4 (phase S1).
//
// Turns `content/apps/<app>/support/<lang>.md` into plain HTML files at
// `apps/<app>/support/<platform>/<lang>/index.html`, plus two tiny redirect pages
// (`apps/<app>/support/index.html`, `apps/<app>/support/<platform>/index.html`).
// It also builds the support hub `/apps/support/<lang>` and Getting started
// `/apps/support/getting-started/<lang>` (buildHub, data and script in hub.ts; §4.7), and the
// imprint (imprint.ts), the privacy policy (privacy.ts) and the terms of use (terms.ts). ⚠ Never anything at `/support`: reserved for a possible support app.
//
// ⚠ Deliberately NOT part of the SPA (§4.2): the SPA's catch-all route sends unknown paths
// to `/`, starts a session probe and needs JavaScript for its first paint. A support page has
// to work for a store reviewer, a crawler and a user whose app just failed to start, so these
// are static files Caddy serves before the SPA fallback (web/apex/Caddyfile).
//
// ⚠ Pure Node + `marked`, no import from `src/` or `@tenshu/sdk`: the SDK is a Gradle-built
// bundle, and these pages must build where Gradle does not (`pnpm -F apex build:support`).
//
// The header and footer are NOT written here: they are the landing page's own MarketingHeader /
// MarketingFooter, rendered to HTML by src/landing/supportChrome.tsx and handed in as a
// ChromeRenderer (loadChrome.ts loads it through Vite's module runner). The stylesheet is apex's
// own src/index.css compiled by Tailwind for exactly the classes these pages use, plus
// support.css for the parts only a support page has (buildCss).
//
// Content format (one file per app and language, shared across platforms):
//   - front matter between `---` lines: `key: value`, one per line (see FrontMatter).
//   - `## Heading {#id}` — the five sections, ids fixed by REQUIRED_SECTIONS, in that order.
//   - `::: ios android` … `:::` — a block kept only on the listed platforms.
//   - `![alt](shot:NN-screen)` — a screenshot slot of this page's app, `![alt](shot:<app>/NN-screen)`
//     one of another app's (Drive's page shows Control's onboarding). Resolved through that app's
//     `store/<app>/screenshots.manifest.json` (§3.3) to its R2 URL, with width and height; a slot
//     the manifest does not list renders as a placeholder. Each slot looks up a light and a dark
//     capture (`…/<lang>/NN-screen` and `…/<lang>/dark/NN-screen`) and the page shows the one that
//     matches the active theme; with only one of the two published, that one shows in both.
//   - A list item that ends in a screenshot is a STEP: its text and its image sit side by side on
//     wide screens and stack on phones (support.css `.step`). No carousel, on purpose — every step
//     stays visible to scanning, find-in-page, printing and screen readers.
//   - `{source: docs/path.md | Label}` — a citation; REQUIRED on every privacy claim (§4.4).
import { createHash } from 'node:crypto'
import { compile, optimize } from '@tailwindcss/node'
import { existsSync, readdirSync, readFileSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { Marked, type Tokens } from 'marked'
// ⚠ Relative, not `@tenshu/web-tokens/theme`: plain Node will not strip types inside node_modules.
import { THEME_BOOT_SCRIPT } from '../../shared/theme.ts'
import { applyCsp, screenshotOrigins } from '../csp/csp.ts'
import type { CarouselEntry, CarouselLabels, CarouselShot, CarouselVariant } from '../src/landing/carousel/carouselModel.ts'
import { shotSrcset } from '../src/landing/carousel/carouselModel.ts'
import { THEME_SWITCH_SCRIPT } from './themeSwitch.ts'
import { LICENSES, LICENSES_LANGUAGES, licensesUrl } from './licenses.ts'
import { licensesHtml } from './licensesPage.ts'
import { SDK_BUNDLE_LIBRARIES } from './sdkBundle.ts'
import { collectThirdParty } from './thirdParty.ts'
import { LANGUAGE_STORAGE_KEY, LANGUAGE_SWITCH_SCRIPT } from './languageSwitch.ts'
import { IMPRINT, IMPRINT_LANGUAGES, imprintUrl, type LegalPage } from './imprint.ts'
import { PRIVACY, PRIVACY_LANGUAGES, privacyUrl } from './privacy.ts'
import { TERMS, TERMS_LANGUAGES, termsUrl } from './terms.ts'
import { ROADMAP, ROADMAP_LANGUAGES, ROADMAP_NEUTRAL_URL, roadmapHtml, roadmapUrl } from './roadmap.ts'
import { PRICING, PRICING_LANGUAGES, PRICING_NEUTRAL_URL, pricingHtml, pricingUrl } from './pricing.ts'
import {
  ANDROID_APPS,
  ANDROID_DOWNLOAD,
  ANDROID_DOWNLOAD_LANGUAGES,
  androidAppName,
  androidDownloadHtml,
  androidDownloadNeutralUrl,
  androidDownloadUrl,
  loadAndroidRelease,
} from './androidDownload.ts'
import {
  MACOS_APPS,
  MACOS_DOWNLOAD,
  MACOS_DOWNLOAD_LANGUAGES,
  loadMacosRelease,
  macosAppName,
  macosDownloadHtml,
  macosDownloadNeutralUrl,
  macosDownloadUrl,
  macosLivecheckFile,
  macosLivecheckJson,
} from './macosDownload.ts'
import { APP_PAGES, APP_PAGE_LANGUAGES, BUTTON_PRIMARY, BUTTON_SECONDARY, appPageHtml, appPageNeutralUrl, appPageUrl, type AppPageSet } from './appPages.ts'
import {
  GETTING_STARTED_ICON,
  GETTING_STARTED_ID,
  GETTING_STARTED_SHOT,
  HUB_APPS,
  HUB_COPY,
  HUB_ENTRY_URL,
  HUB_LANGUAGES,
  HUB_SCRIPT_BODY,
  HUB_SHOT_PLATFORM,
  appIcon,
  gettingStartedUrl,
  hubUrl,
  type HubApp,
} from './hub.ts'

const HERE = dirname(fileURLToPath(import.meta.url))
export const APEX_DIR = resolve(HERE, '..')
export const CONTENT_DIR = join(APEX_DIR, 'content', 'apps')
/** The Getting started page's content, `<lang>.md` (§4.7). Not an app, so not under `apps/`. */
export const GETTING_STARTED_DIR = join(APEX_DIR, 'content', 'support', 'getting-started')
/** Vite copies `public/` into `dist/` verbatim — the icons the pages link. */
export const PUBLIC_DIR = join(APEX_DIR, 'public')
/**
 * The repo's `store/`, where `scripts/store-publish.sh` writes `<app>/screenshots.manifest.json`.
 * ⚠ Read from disk, never fetched: the build runs without network. The apex image's web stage
 * copies `store/` in for this (web/apex/Dockerfile).
 */
export const STORE_DIR = join(APEX_DIR, '..', '..', 'store')
/** The SPA's stylesheet entry: Tailwind, apex's tokens and the base rules (buildCss). */
const APP_CSS = join(APEX_DIR, 'src', 'index.css')
const SUPPORT_CSS = join(HERE, 'support.css')

/** Canonical origin for `<link rel=canonical>`. The dev lab serves the same files. */
export const SITE_ORIGIN = 'https://tenshu.app'
/**
 * Where a `{source: …}` path is linked to. ⚠ Same base the marketing footer uses
 * (src/landing/MarketingFooter.tsx, which these pages render) so the site has one answer; change
 * both together.
 */
export const REPO_BLOB_BASE = 'https://github.com/B3nedikt/Tenshu/blob/master/'

export const PLATFORMS = ['ios', 'android', 'macos', 'windows', 'linux', 'web'] as const
export type Platform = (typeof PLATFORMS)[number]

/** The five sections of §4.3, in order. The privacy one is checked for sources. */
export const REQUIRED_SECTIONS = ['get-started', 'privacy', 'problems', 'requirements', 'contact'] as const

/**
 * The landing page's header and footer as HTML for one page — src/landing/supportChrome.tsx,
 * loaded by loadChrome.ts. Injected rather than imported: it is TSX, which plain Node cannot run.
 */
export type ChromeRenderer = (opts: {
  readonly lang: string
  readonly url: string
  readonly supportHref: string
  /** The language pickers' links: this page in each of its languages; empty for none. */
  readonly languages: readonly { readonly lang: string; readonly name: string; readonly href: string; readonly current: boolean }[]
  /** The header entry marked current: "Support" (the default), "Roadmap", "Pricing", or none (the legal pages). */
  readonly current?: 'support' | 'roadmap' | 'pricing' | null
}) => {
  /** The landing page's root classes, for <body>. */
  readonly bodyClass: string
  readonly header: string
  readonly footer: string
  /** The language picker under the title (src/landing/LanguagePicker.tsx); '' without languages. */
  readonly languagePicker: string
  /**
   * The app carousel (src/landing/carousel/AppCarousel.tsx, the landing page's Apps section) as
   * static markup: the hub's lists of apps, which HUB_SCRIPT_BODY enhances.
   */
  readonly carousel: (entries: readonly CarouselEntry[], listLabel: string, labels: CarouselLabels, footnote?: string) => string
}

/**
 * The support-specific labels per language: the platform pills, the table of contents, the skip link.
 * The header and footer copy is the landing page's (locales/<lang>/landing.json). Body copy lives
 * in the Markdown, never here.
 */
interface Chrome {
  readonly languageName: string
  readonly platformNames: Record<Platform, string>
  readonly support: string
  readonly platform: string
  readonly onThisPage: string
  readonly skip: string
  readonly source: string
  readonly screenshotPending: string
  readonly choose: string
}


const CHROME: Record<string, Chrome> = {
  en: {
    languageName: 'English',
    platformNames: {
      ios: 'iPhone and iPad',
      android: 'Android',
      macos: 'Mac',
      windows: 'Windows',
      linux: 'Linux',
      web: 'Web',
    },
    support: 'Support',
    platform: 'Platform',
    onThisPage: 'On this page',
    skip: 'Skip to content',
    source: 'Source',
    screenshotPending: 'Screenshot coming soon',
    choose: 'Choose your platform',
  },
  // ⚠ Machine-translated, pending native review — like the de.md / fr.md they frame
  // (`reviewed: false` in their front matter).
  de: {
    languageName: 'Deutsch',
    platformNames: {
      ios: 'iPhone und iPad',
      android: 'Android',
      macos: 'Mac',
      windows: 'Windows',
      linux: 'Linux',
      web: 'Web',
    },
    support: 'Support',
    platform: 'Plattform',
    onThisPage: 'Auf dieser Seite',
    skip: 'Zum Inhalt springen',
    source: 'Quelle',
    screenshotPending: 'Screenshot folgt',
    choose: 'Wähle deine Plattform',
  },
  fr: {
    languageName: 'Français',
    platformNames: {
      ios: 'iPhone et iPad',
      android: 'Android',
      macos: 'Mac',
      windows: 'Windows',
      linux: 'Linux',
      web: 'Web',
    },
    support: 'Assistance',
    platform: 'Plateforme',
    onThisPage: 'Sur cette page',
    skip: 'Aller au contenu',
    source: 'Source',
    screenshotPending: 'Capture d’écran à venir',
    choose: 'Choisissez votre plateforme',
  },
}

export interface FrontMatter {
  readonly app: string
  readonly name: string
  readonly lang: string
  readonly title: string
  readonly description: string
  readonly platforms: readonly Platform[]
  /** Where `/apps/<app>/support` lands when the user agent names no platform we ship. */
  readonly defaultPlatform: Platform
  /**
   * `reviewed: false` marks a machine translation no native speaker has read yet. Optional,
   * default true (the English originals). Not shown on the page; it is for whoever reviews.
   */
  readonly reviewed: boolean
}

export interface SourceRef {
  readonly path: string
  readonly label: string
}

export interface Page {
  readonly app: string
  readonly platform: Platform
  readonly lang: string
  /** Path relative to the output root, e.g. `apps/drive/support/ios/en/index.html`. */
  readonly file: string
  readonly url: string
  readonly html: string
  /** Every `<li>`/`<p>` of the privacy section, with the sources each one cites. */
  readonly privacyClaims: readonly { readonly text: string; readonly sources: readonly SourceRef[] }[]
  readonly sources: readonly SourceRef[]
  readonly screenshotSlots: readonly ShotSlot[]
}

export interface Output {
  readonly file: string
  readonly content: string
}

/** The support hub and the Getting started page, one per language (§4.7). */
export interface HubPage {
  readonly kind: 'hub' | 'getting-started'
  readonly lang: string
  readonly file: string
  readonly url: string
  readonly html: string
  /** Getting started only: the privacy items and the sources each cites (§4.4). */
  readonly privacyClaims: readonly { readonly text: string; readonly sources: readonly SourceRef[] }[]
  readonly screenshotSlots: readonly ShotSlot[]
}

export interface SupportSite {
  readonly pages: readonly Page[]
  readonly hubPages: readonly HubPage[]
  /** Every file to write, pages and redirects and the stylesheet, relative to the output root. */
  readonly files: readonly Output[]
}

// ── front matter ─────────────────────────────────────────────────────────

/** The `key: value` lines between the `---`s, and the body after them. */
function readFrontMatter(source: string, where: string): { raw: Record<string, string>; body: string } {
  const match = /^---\n([\s\S]*?)\n---\n/.exec(source)
  if (!match) throw new Error(`${where}: missing front matter`)
  const raw: Record<string, string> = {}
  for (const line of match[1]!.split('\n')) {
    if (!line.trim() || line.trimStart().startsWith('#')) continue
    const sep = line.indexOf(':')
    if (sep < 0) throw new Error(`${where}: bad front matter line "${line}"`)
    raw[line.slice(0, sep).trim()] = line.slice(sep + 1).trim()
  }
  return { raw, body: source.slice(match[0].length) }
}

export function parseFrontMatter(source: string, where: string): { meta: FrontMatter; body: string } {
  const { raw, body } = readFrontMatter(source, where)
  const need = (key: string): string => {
    const value = raw[key]
    if (!value) throw new Error(`${where}: front matter needs "${key}"`)
    return value
  }
  const platforms = need('platforms').split(',').map((p) => asPlatform(p.trim(), where))
  const defaultPlatform = asPlatform(need('defaultPlatform'), where)
  const reviewed = raw['reviewed'] ?? 'true'
  if (reviewed !== 'true' && reviewed !== 'false') throw new Error(`${where}: reviewed must be true or false`)
  if (!platforms.includes(defaultPlatform)) {
    throw new Error(`${where}: defaultPlatform "${defaultPlatform}" is not in platforms`)
  }
  return {
    meta: {
      app: need('app'),
      name: need('name'),
      lang: need('lang'),
      title: need('title'),
      description: need('description'),
      platforms,
      defaultPlatform,
      reviewed: reviewed === 'true',
    },
    body,
  }
}

function asPlatform(value: string, where: string): Platform {
  if ((PLATFORMS as readonly string[]).includes(value)) return value as Platform
  throw new Error(`${where}: unknown platform "${value}" (known: ${PLATFORMS.join(', ')})`)
}

// ── platform blocks ──────────────────────────────────────────────────────

/**
 * Keeps the `::: <platforms>` … `:::` blocks that name [platform] and drops the rest.
 * Blocks do not nest; an unclosed or unknown-platform block is a build error, not a guess.
 */
export function selectPlatform(body: string, platform: Platform, where: string): string {
  const out: string[] = []
  let open: { keep: boolean; line: number } | null = null
  body.split('\n').forEach((line, index) => {
    const fence = /^\s*:::\s*(.*)$/.exec(line)
    if (!fence) {
      if (!open || open.keep) out.push(line)
      return
    }
    const names = fence[1]!.trim()
    if (names === '') {
      if (!open) throw new Error(`${where}:${index + 1}: ":::" closes nothing`)
      open = null
      return
    }
    if (open) throw new Error(`${where}:${index + 1}: platform blocks do not nest`)
    const list = names.split(/\s+/).map((p) => asPlatform(p, `${where}:${index + 1}`))
    open = { keep: list.includes(platform), line: index + 1 }
  })
  if (open) throw new Error(`${where}:${(open as { line: number }).line}: platform block never closed`)
  return out.join('\n')
}

// ── rendering ────────────────────────────────────────────────────────────

const SOURCE_RE = /\{source:\s*([^|}]+?)\s*\|\s*([^}]+?)\s*\}/g
export const SHOT_RE = /^shot:(?:([a-z][a-z0-9-]*)\/)?(\d\d-[a-z0-9-]+)$/

/**
 * Which capture a support page shows, per platform. `null` = no pipeline yet: placeholders.
 *
 * iOS shows the **6.9" iPhone** (`iphone69`, 1320 × 2868), not the 13" iPad: the pages' iOS steps
 * describe the iPhone ("on iPhone" in the alt texts), most iOS users are on one, and a portrait
 * phone capture fits the narrow screenshot column beside a step's text (support.css `.step`) where
 * the near-square iPad capture would be shrunk to an unreadable thumbnail.
 *
 * Android shows a Pixel-class **phone** (`phone`, 1080 × 2400) from
 * `scripts/store-screenshots-android.sh`: one size, as the pages show one phone per platform.
 */
export const SUPPORT_DEVICE: Record<Platform, string | null> = {
  ios: 'iphone69',
  android: 'phone',
  macos: null,
  windows: null,
  linux: null,
  web: null,
}

export interface ShotEntry {
  readonly sha256: string
  readonly width: number
  readonly height: number
  readonly bytes: number
}

export interface ShotManifest {
  /** The public prefix; an image's URL is `base + sha256 + '.png'`. */
  readonly base: string
  /** Keyed `<platform>/<device>/<lang>/NN-screen` (light) or `<platform>/<device>/<lang>/dark/NN-screen`. */
  readonly screens: Readonly<Record<string, ShotEntry>>
  /**
   * The website's WebP copies of those PNGs, by the PNG's sha256 — `store/<app>/screenshots.webp.json`
   * (scripts/lib/store_webp.py), each at `base + sha256 + '.webp'`. A PNG with none is shown as is.
   */
  readonly derivatives?: Readonly<Record<string, readonly ShotDerivative[]>>
}

export interface ShotDerivative {
  readonly width: number
  readonly height: number
  readonly sha256: string
  readonly bytes: number
}

/**
 * The appearance a capture was taken in. ⚠ Light is the key form without an appearance segment —
 * the one every manifest published before dark existed uses — so those keys stay valid with no
 * republish. Dark adds a `dark` segment after the language, the same "new dimension goes after,
 * never between" rule as the page URLs (§4.1). There is no `light` segment: one spelling per image.
 */
export const APPEARANCES = ['light', 'dark'] as const
export type Appearance = (typeof APPEARANCES)[number]

export const MANIFEST_KEY_RE = /^[a-z]+\/[a-z0-9]+\/[a-z]{2}(-[A-Za-z]+)?(\/dark)?\/\d\d-[a-z0-9-]+$/

export function shotKey(platform: string, device: string, lang: string, screen: string, appearance: Appearance): string {
  return appearance === 'light' ? `${platform}/${device}/${lang}/${screen}` : `${platform}/${device}/${lang}/${appearance}/${screen}`
}

/**
 * `store/<app>/screenshots.manifest.json`, or `null` when the app has published nothing yet. A file
 * that exists but is malformed is a build error, never "no screenshots": it is generated, so a
 * broken one means something went wrong upstream.
 */
export function loadShotManifest(app: string, storeDir = STORE_DIR): ShotManifest | null {
  const path = join(storeDir, app, 'screenshots.manifest.json')
  if (!existsSync(path)) return null
  const where = `store/${app}/screenshots.manifest.json`
  let raw: unknown
  try {
    raw = JSON.parse(readFileSync(path, 'utf8'))
  } catch (e) {
    throw new Error(`${where}: not JSON (${(e as Error).message})`, { cause: e })
  }
  const m = raw as { version?: unknown; base?: unknown; screens?: unknown }
  if (m.version !== 1) throw new Error(`${where}: unknown version ${String(m.version)}`)
  if (typeof m.base !== 'string' || !/^https:\/\/[^/]+\/.*\/$/.test(m.base)) {
    throw new Error(`${where}: base must be an https URL ending in "/"`)
  }
  if (!m.screens || typeof m.screens !== 'object') throw new Error(`${where}: no screens`)
  for (const [key, entry] of Object.entries(m.screens as Record<string, Partial<ShotEntry>>)) {
    const ok =
      MANIFEST_KEY_RE.test(key) &&
      /^[0-9a-f]{64}$/.test(String(entry?.sha256)) &&
      Number.isInteger(entry?.width) &&
      entry!.width! > 0 &&
      Number.isInteger(entry?.height) &&
      entry!.height! > 0
    if (!ok) throw new Error(`${where}: bad entry "${key}"`)
  }
  const derivatives = loadWebpSidecar(app, m.base, storeDir)
  return derivatives ? { base: m.base, screens: m.screens as Record<string, ShotEntry>, derivatives } : { base: m.base, screens: m.screens as Record<string, ShotEntry> }
}

/**
 * `store/<app>/screenshots.webp.json`'s derivatives, or `null` when there is none. Like the manifest,
 * a malformed one is a build error: it is generated, and it must name the manifest's own base.
 */
function loadWebpSidecar(app: string, base: string, storeDir: string): Record<string, readonly ShotDerivative[]> | null {
  const path = join(storeDir, app, 'screenshots.webp.json')
  if (!existsSync(path)) return null
  const where = `store/${app}/screenshots.webp.json`
  let raw: unknown
  try {
    raw = JSON.parse(readFileSync(path, 'utf8'))
  } catch (e) {
    throw new Error(`${where}: not JSON (${(e as Error).message})`, { cause: e })
  }
  const m = raw as { version?: unknown; base?: unknown; derivatives?: unknown }
  if (m.version !== 1) throw new Error(`${where}: unknown version ${String(m.version)}`)
  if (m.base !== base) throw new Error(`${where}: base ${String(m.base)} is not the manifest's ${base}`)
  if (!m.derivatives || typeof m.derivatives !== 'object') throw new Error(`${where}: no derivatives`)
  const positive = (n: unknown) => Number.isInteger(n) && (n as number) > 0
  for (const [sha, list] of Object.entries(m.derivatives as Record<string, unknown>)) {
    const ok =
      /^[0-9a-f]{64}$/.test(sha) &&
      Array.isArray(list) &&
      list.every((d: Partial<ShotDerivative>) => /^[0-9a-f]{64}$/.test(String(d?.sha256)) && positive(d?.width) && positive(d?.height) && positive(d?.bytes))
    if (!ok) throw new Error(`${where}: bad derivatives for "${sha}"`)
  }
  return m.derivatives as Record<string, readonly ShotDerivative[]>
}

export type ShotImage = ShotEntry & { readonly url: string; readonly variants?: readonly CarouselVariant[] }

/**
 * A support page's step frame width on the page, for `sizes` (appCarousel.css `.shot-frame`):
 * --shot-w, 15rem from 52rem, else at most 17.5rem.
 */
export const STEP_SHOT_SIZES = '(min-width: 52rem) 15rem, 17.5rem'

export interface ShotSlot {
  /** The app whose capture it is — the page's own, or the one named in `shot:<app>/…`. */
  readonly app: string
  /** The light manifest key, `<platform>/<device>/<lang>/NN-screen`; null if the platform has no device. */
  readonly key: string | null
  /** The dark manifest key, `<platform>/<device>/<lang>/dark/NN-screen`; null if the platform has no device. */
  readonly darkKey: string | null
  /** The published light image, or null. */
  readonly image: ShotImage | null
  /** The published dark image, or null. Neither published: the slot renders as a placeholder. */
  readonly dark: ShotImage | null
}

export type ShotManifests = (app: string) => ShotManifest | null

/** Resolves one slot against the manifests. Pure, so the tests can feed it manifests directly. */
export function resolveShot(
  manifests: ShotManifests,
  pageApp: string,
  platform: Platform,
  lang: string,
  slotApp: string | undefined,
  screen: string,
  /** The capture set; default the support pages' own (SUPPORT_DEVICE). */
  device: string | null = SUPPORT_DEVICE[platform],
): ShotSlot {
  const app = slotApp ?? pageApp
  if (!device) return { app, key: null, darkKey: null, image: null, dark: null }
  const key = shotKey(platform, device, lang, screen, 'light')
  const darkKey = shotKey(platform, device, lang, screen, 'dark')
  const manifest = manifests(app)
  const lookup = (k: string): ShotImage | null => {
    const entry = manifest?.screens[k]
    if (!entry || !manifest) return null
    const variants = (manifest.derivatives?.[entry.sha256] ?? [])
      .map((d) => ({ url: `${manifest.base}${d.sha256}.webp`, width: d.width }))
      .sort((a, b) => a.width - b.width)
    const image = { ...entry, url: `${manifest.base}${entry.sha256}.png` }
    return variants.length ? { ...image, variants } : image
  }
  const own = { app, key, darkKey, image: lookup(key), dark: lookup(darkKey) }
  // A language with no capture of this screen at all shows the English one rather than a
  // placeholder: it is the same screen, and for an app not translated into that language (French,
  // today) it is exactly what the reader's device shows. The keys stay the page's own, so a
  // capture published later in that language takes over with no content change.
  if (own.image || own.dark || lang === SHOT_FALLBACK_LANG) return own
  const fallback = { image: lookup(shotKey(platform, device, SHOT_FALLBACK_LANG, screen, 'light')), dark: lookup(shotKey(platform, device, SHOT_FALLBACK_LANG, screen, 'dark')) }
  return { ...own, ...fallback }
}

/** The language whose captures stand in for a language that has none of a screen. */
export const SHOT_FALLBACK_LANG = 'en'

/**
 * The markup for one slot. Both captures published: both `<img>`s, and CSS shows the one that
 * matches the effective theme (support.css `.shot--light` / `.shot--dark`) — a `<picture>` with a
 * `prefers-color-scheme` source could not follow the manual switch. The hidden one is
 * `loading="lazy"` with its real width/height, and a lazy image without a layout box is not
 * fetched while scripting is on (measured and argued in support.css, "screenshot slots"). One capture
 * published: that one, in both themes. Neither: a placeholder in the same box. Every case sits in
 * a `.shot-frame`, the neutral phone frame the app carousel uses too.
 *
 * Each `<img>` names its WebP copies in `srcset` (store/<app>/screenshots.webp.json), sized to the
 * step frame (STEP_SHOT_SIZES); the PNG stays the `src`, the fallback. [eager] is the page's FIRST
 * slot, likely on screen at load: not lazy, so the preload scanner fetches it — both themes, since
 * the markup cannot know which one shows (two ~20–40 KB copies, not a layout's worth).
 */
/** [alt] arrives HTML-escaped; [pendingLabel] is escaped here. */
export function shotHtml(slot: ShotSlot, alt: string, pendingLabel: string, eager = false): string {
  const img = (image: ShotImage, variant: string) => {
    const srcset = shotSrcset(image)
    // The PNG's ratio pinned: a copy's rounded height (960 × 2134 of 1080 × 2400) must not move the box.
    const responsive = srcset ? ` srcset="${escapeHtml(srcset)}" sizes="${STEP_SHOT_SIZES}" style="aspect-ratio: ${image.width} / ${image.height}"` : ''
    return `<img class="shot${variant}" src="${escapeHtml(image.url)}"${responsive} alt="${alt}" width="${image.width}" height="${image.height}" loading="${eager ? 'eager' : 'lazy'}" decoding="async">`
  }
  // In the site's neutral phone frame (src/landing/carousel/appCarousel.css `.shot-frame`), the
  // same frame as the app carousel's: one look for every screenshot on the site.
  const frame = (inner: string) => `<span class="shot-frame">${inner}</span>`
  if (slot.image && slot.dark) return frame(img(slot.image, ' shot--light') + img(slot.dark, ' shot--dark'))
  const only = slot.image ?? slot.dark
  if (only) return frame(img(only, ''))
  const ref = slot.key ? ` data-slot="${escapeHtml(`${slot.app}:${slot.key}`)}"` : ''
  return frame(
    `<span class="shot shot--pending" role="img" aria-label="${alt}"${ref}>` +
      `<span class="shot__label">${escapeHtml(pendingLabel)}</span>` +
      `<span class="shot__alt">${alt}</span></span>`,
  )
}

const MEDIA_OPEN = '<!--step-media-->'
const MEDIA_CLOSE = '<!--/step-media-->'

/**
 * Turns every list item that ENDS in a screenshot into a step: `<li class="step">` holding the
 * text in `.step__text` and the screenshot in `.step__media` (both in `.step__body`, the grid —
 * the `<li>` itself stays a list item and keeps its number), which support.css lays out side by
 * side on wide screens and stacked (text, then image) on phones. An item without a screenshot
 * stays a plain `<li>` and takes the full width. A screenshot anywhere else keeps its place.
 */
export function wrapSteps(html: string): string {
  const stepped = html.replace(
    /<li>((?:(?!<\/?li[\s>])[\s\S])*?)<!--step-media-->([\s\S]*?)<!--\/step-media-->(\s*<\/p>)?\s*<\/li>/g,
    (_all, text: string, media: string, closeP: string | undefined) =>
      `<li class="step"><div class="step__body"><div class="step__text">${text.trim()}${closeP ? '</p>' : ''}</div>` +
      `<span class="step__media">${media}</span></div></li>`,
  )
  return stepped.replaceAll(MEDIA_OPEN, '<span class="step__media">').replaceAll(MEDIA_CLOSE, '</span>')
}

export function escapeHtml(text: string): string {
  return text
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
}

interface Rendered {
  readonly html: string
  readonly toc: readonly { id: string; text: string }[]
  readonly sources: readonly SourceRef[]
  readonly shots: readonly ShotSlot[]
}

function renderBody(
  markdown: string,
  app: string,
  platform: Platform,
  lang: string,
  manifests: ShotManifests,
  chrome: Chrome,
  where: string,
): Rendered {
  const sources: SourceRef[] = []
  const shots: ShotSlot[] = []
  const toc: { id: string; text: string }[] = []

  // Citations become inline HTML before Markdown sees them; `data-source` keeps the repo path
  // machine-readable for the test that checks every one exists.
  const withSources = markdown.replace(SOURCE_RE, (_all, path: string, label: string) => {
    sources.push({ path, label })
    return (
      `<span class="source">${escapeHtml(chrome.source)}: ` +
      `<a data-source="${escapeHtml(path)}" href="${REPO_BLOB_BASE}${escapeHtml(path)}">${escapeHtml(label)}</a></span>`
    )
  })
  if (/\{source:/.test(withSources)) throw new Error(`${where}: malformed {source: path | label}`)

  const marked = new Marked({ gfm: true })
  marked.use({
    renderer: {
      heading(this: { parser: { parseInline(t: Tokens.Heading['tokens']): string } }, token: Tokens.Heading) {
        const idMatch = /\s*\{#([a-z0-9-]+)\}\s*$/.exec(token.text)
        const inner = this.parser.parseInline(token.tokens).replace(/\s*\{#[a-z0-9-]+\}\s*$/, '')
        if (token.depth === 2) {
          if (!idMatch) throw new Error(`${where}: "## ${token.text}" needs an {#id}`)
          toc.push({ id: idMatch[1]!, text: inner })
        }
        // h3s get an id from their text, so a support reply can link straight to one answer.
        const slug = idMatch?.[1] ?? (token.depth === 3 ? slugify(stripTags(inner)) : '')
        const id = slug ? ` id="${slug}"` : ''
        return `<h${token.depth}${id}>${inner}</h${token.depth}>\n`
      },
      image(token: Tokens.Image) {
        const shot = SHOT_RE.exec(token.href)
        if (!shot) throw new Error(`${where}: images must be screenshot slots (shot:[app/]NN-screen), got "${token.href}"`)
        const slot = resolveShot(manifests, app, platform, lang, shot[1], shot[2]!)
        const first = shots.length === 0
        shots.push(slot)
        // ⚠ An <img> only for a screen the manifest lists. `store-publish.sh` writes the manifest
        // after the upload, and `--verify` checks every URL in it is served — so a listed screen is
        // one R2 has, and an unlisted one is a placeholder rather than a broken image. The markers
        // let wrapSteps() find the screenshot that ends a list item.
        return MEDIA_OPEN + shotHtml(slot, escapeHtml(token.text), chrome.screenshotPending, first) + MEDIA_CLOSE
      },
    },
  })
  const html = wrapSteps(marked.parse(withSources, { async: false }))
  return { html, toc, sources, shots }
}

/** Splits rendered HTML at its `<h2 id>`s. */
function sectionsOf(html: string): Map<string, string> {
  const map = new Map<string, string>()
  const parts = html.split(/(?=<h2 id=")/)
  for (const part of parts) {
    const id = /^<h2 id="([^"]+)"/.exec(part)?.[1]
    if (id) map.set(id, part)
  }
  return map
}

export function slugify(text: string): string {
  return text
    .replace(/&[a-z0-9#]+;/gi, '')
    .toLowerCase()
    .normalize('NFKD')
    .replace(/[\u0300-\u036f]/g, '')
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-|-$/g, '')
}

/** Wraps each `<h2 id>` and what follows it in a `<section>`, so CSS can style one section. */
function wrapSections(html: string): string {
  return html
    .split(/(?=<h2 id=")/)
    .map((part) => {
      const id = /^<h2 id="([^"]+)"/.exec(part)?.[1]
      return id ? `<section class="section section--${id}" aria-labelledby="${id}">\n${part}</section>\n` : part
    })
    .join('')
}

function stripTags(html: string): string {
  return html.replace(/<[^>]+>/g, '').replace(/\s+/g, ' ').trim()
}

/**
 * §4.4: every claim in the privacy section is a list item or paragraph, and each one cites a
 * source. Returns the claims so the test can assert on them; throws at build time if one is bare.
 */
export function privacyClaimsOf(sectionHtml: string, where: string) {
  const claims: { text: string; sources: SourceRef[] }[] = []
  const body = sectionHtml.replace(/^<h2[\s\S]*?<\/h2>/, '')
  const blocks = body.match(/<li>[\s\S]*?<\/li>|<p>[\s\S]*?<\/p>/g) ?? []
  for (const block of blocks) {
    const sources = [...block.matchAll(/data-source="([^"]+)"[^>]*>([^<]*)</g)].map((m) => ({
      path: m[1]!,
      label: m[2]!,
    }))
    const text = stripTags(block)
    if (sources.length === 0) throw new Error(`${where}: privacy claim without a source: "${text}"`)
    claims.push({ text, sources })
  }
  if (claims.length < 3) throw new Error(`${where}: the privacy section needs at least three claims`)
  return claims
}

// ── page shell ───────────────────────────────────────────────────────────

export function pageUrl(app: string, platform: Platform, lang: string): string {
  return `/apps/${app}/support/${platform}/${lang}`
}

/**
 * The platform pills under the title. Only four platforms, so pills; the language beside them is
 * the site's LanguagePicker (rendered by [ChromeRenderer]), which scales to any number.
 */
function platformSwitcher(label: string, items: { href: string; text: string; current: boolean }[]): string {
  const links = items
    .map((item) => `<li><a href="${item.href}"${item.current ? ' aria-current="page"' : ''}>${escapeHtml(item.text)}</a></li>`)
    .join('')
  return `<nav class="switcher switcher--platform" aria-label="${escapeHtml(label)}"><span class="switcher__label" aria-hidden="true">${escapeHtml(label)}</span><ul>${links}</ul></nav>`
}

/**
 * "Support › Drive › Android" at the top of every page under the hub (§4.7): each step but the last
 * a link, the last plain text marked `aria-current="page"`. The separators are CSS
 * (support.css `.breadcrumb`), so a screen reader hears only the names.
 */
export function breadcrumb(label: string, items: readonly { readonly text: string; readonly href?: string }[]): string {
  const steps = items
    .map((item, i) =>
      i === items.length - 1
        ? `<li><span aria-current="page">${escapeHtml(item.text)}</span></li>`
        : `<li><a href="${escapeHtml(item.href!)}">${escapeHtml(item.text)}</a></li>`,
    )
    .join('')
  return `<nav class="breadcrumb" aria-label="${escapeHtml(label)}"><ol>${steps}</ol></nav>`
}

/** The short name the hub and the breadcrumb use ("Drive"); the hub's list is the one source. */
function shortName(app: string): string {
  const entry = HUB_APPS.find((a) => a.app === app)
  if (!entry) throw new Error(`support-pages/hub.ts: "${app}" has support content but is not in HUB_APPS`)
  return entry.name
}

function head(opts: { lang: string; title: string; description?: string; css: string; canonical?: string; extra?: string }) {
  return [
    '<!doctype html>',
    `<html lang="${opts.lang}">`,
    '<head>',
    '<meta charset="utf-8">',
    '<meta name="viewport" content="width=device-width, initial-scale=1">',
    `<title>${escapeHtml(opts.title)}</title>`,
    opts.description ? `<meta name="description" content="${escapeHtml(opts.description)}">` : '',
    opts.canonical ? `<link rel="canonical" href="${SITE_ORIGIN}${opts.canonical}">` : '',
    // Before the stylesheet: the stored Light / Dark choice lands on <html> before first paint.
    `<script>${THEME_BOOT_SCRIPT}</script>`,
    '<link rel="icon" type="image/svg+xml" href="/icons/tenshu.svg">',
    '<link rel="icon" type="image/png" sizes="32x32" href="/icons/favicon-32.png">',
    `<link rel="stylesheet" href="${opts.css}">`,
    opts.extra ?? '',
    '</head>',
  ]
    .filter(Boolean)
    .join('\n')
}

/** The skip link, then the landing page's header (rendered by [chrome]). */
function siteHeader(chrome: Chrome, rendered: ReturnType<ChromeRenderer>): string {
  return `<a class="skip" href="#main">${escapeHtml(chrome.skip)}</a>\n${rendered.header}`
}

/**
 * The landing page's footer, then the scripts that wire its (static) theme switch and remember a
 * language picked from either language picker (the footer's or the one under the title).
 */
function siteFooter(rendered: ReturnType<ChromeRenderer>): string {
  return `${rendered.footer}\n<script>${THEME_SWITCH_SCRIPT}</script>\n<script>${LANGUAGE_SWITCH_SCRIPT}</script>`
}

/** The page column: the landing header's width and gutters (max-w-5xl, px-6). */
const MAIN_CLASSES = 'mx-auto w-full max-w-5xl px-6 pt-10 pb-16'
/** An app page's column: wider, so its hero's text and carousel sit side by side on a laptop. */
const APP_MAIN_CLASSES = 'mx-auto w-full max-w-[90rem] px-6 pt-8 pb-16 lg:px-8'
/** The landing page's section heading (Block2Problem, Block3Answer …). */
const H1_CLASSES = 'text-3xl font-semibold tracking-tight sm:text-4xl'

// ── the stylesheet ───────────────────────────────────────────────────────

/**
 * The SPA's own stylesheet entry (src/index.css: Tailwind, apex's tokens, the base rules),
 * compiled by Tailwind for the classes these pages use — every `class` in [html], which holds
 * the landing header and footer — then support.css in the `components` layer, so the chrome's
 * utilities still win over it, as they do in the SPA. Optimised by Lightning CSS like the Vite
 * build (nesting flattened, prefixes added).
 *
 * ⚠ The class list comes from the rendered pages, not a source scan: a class reaches the CSS
 * only if a page actually renders it, so the file stays small and cannot miss a class the chrome
 * starts using.
 */
export async function buildCss(html: readonly string[]): Promise<string> {
  const input = `${readFileSync(APP_CSS, 'utf8')}\n@layer components {\n${readFileSync(SUPPORT_CSS, 'utf8')}\n}\n`
  const compiler = await compile(input, { base: dirname(APP_CSS), from: APP_CSS, onDependency: () => {} })
  return optimize(compiler.build([...classesOf(html)]), { file: 'support.css', minify: false }).code
}

/** Every class token in the pages' `class` attributes, entity-decoded (React escapes `&`). */
export function classesOf(html: readonly string[]): Set<string> {
  const out = new Set<string>()
  for (const page of html) {
    for (const match of page.matchAll(/\sclass="([^"]*)"/g)) {
      const value = match[1]!.replace(/&amp;/g, '&').replace(/&quot;/g, '"').replace(/&#x27;|&#39;/g, "'").replace(/&gt;/g, '>').replace(/&lt;/g, '<')
      for (const token of value.split(/\s+/)) if (token) out.add(token)
    }
  }
  return out
}

// ── the site ─────────────────────────────────────────────────────────────

interface Source {
  readonly meta: FrontMatter
  readonly body: string
  readonly where: string
}

export function loadContent(contentDir = CONTENT_DIR): Source[] {
  const sources: Source[] = []
  for (const app of readdirSync(contentDir).sort()) {
    const dir = join(contentDir, app, 'support')
    if (!existsSync(dir)) continue
    for (const file of readdirSync(dir).filter((f) => f.endsWith('.md')).sort()) {
      const where = `content/apps/${app}/support/${file}`
      const { meta, body } = parseFrontMatter(readFileSync(join(dir, file), 'utf8'), where)
      if (meta.app !== app) throw new Error(`${where}: front matter app "${meta.app}" ≠ folder "${app}"`)
      if (`${meta.lang}.md` !== file) throw new Error(`${where}: front matter lang "${meta.lang}" ≠ file name`)
      if (!CHROME[meta.lang]) throw new Error(`${where}: no page chrome for language "${meta.lang}"`)
      sources.push({ meta, body, where })
    }
  }
  return sources
}

const langOrder = (lang: string) => (lang === 'en' ? '' : lang)

/** Where `/apps/<app>/support` is: the app's support entry point (the redirect page below). */
export function supportEntryUrl(app: string): string {
  return `/apps/${app}/support`
}

/**
 * Stands in for the stylesheet's URL until the stylesheet exists: its content hash names it, and
 * its content depends on the classes the finished pages use.
 */
const CSS_HREF_PLACEHOLDER = '/assets/__support-css__.css'

export interface BuildOptions {
  readonly chrome: ChromeRenderer
  readonly contentDir?: string
  readonly gettingStartedDir?: string
  readonly storeDir?: string
}

export async function buildSupportSite({
  chrome: renderChrome,
  contentDir = CONTENT_DIR,
  gettingStartedDir = GETTING_STARTED_DIR,
  storeDir = STORE_DIR,
}: BuildOptions): Promise<SupportSite> {
  const content = loadContent(contentDir)
  const loaded = new Map<string, ShotManifest | null>()
  const manifests: ShotManifests = (app) => {
    if (!loaded.has(app)) loaded.set(app, loadShotManifest(app, storeDir))
    return loaded.get(app)!
  }
  const cssHref = CSS_HREF_PLACEHOLDER
  const files: Output[] = []
  const pages: Page[] = []

  const byApp = new Map<string, Source[]>()
  for (const source of content) byApp.set(source.meta.app, [...(byApp.get(source.meta.app) ?? []), source])
  // English first, then the rest by code — the order the switchers list them in, and the landing
  // footer's (src/landing/languages.ts SITE_LANGUAGES: English, Deutsch, Français).
  for (const sources of byApp.values()) sources.sort((a, b) => langOrder(a.meta.lang).localeCompare(langOrder(b.meta.lang)))

  for (const [app, sources] of byApp) {
    const primary = sources.find((s) => s.meta.lang === 'en') ?? sources[0]!
    const { platforms, defaultPlatform } = primary.meta

    for (const { meta, body, where } of sources) {
      const chrome = CHROME[meta.lang]!
      for (const platform of meta.platforms) {
        const rendered = renderBody(
          selectPlatform(body, platform, where),
          app,
          platform,
          meta.lang,
          manifests,
          chrome,
          `${where} [${platform}]`,
        )
        const ids = rendered.toc.map((t) => t.id)
        if (ids.join() !== REQUIRED_SECTIONS.join()) {
          throw new Error(`${where} [${platform}]: sections must be ${REQUIRED_SECTIONS.join(', ')} in order; got ${ids.join(', ')}`)
        }
        const privacy = sectionsOf(rendered.html).get('privacy')!
        const privacyClaims = privacyClaimsOf(privacy, `${where} [${platform}]`)

        const platformName = chrome.platformNames[platform]
        const url = pageUrl(app, platform, meta.lang)
        const title = `${meta.title} — ${platformName}`
        const description = meta.description.replaceAll('{platform}', platformName)
        const alternates = sources
          .filter((s) => s.meta.platforms.includes(platform))
          .map((s) => `<link rel="alternate" hreflang="${s.meta.lang}" href="${SITE_ORIGIN}${pageUrl(app, platform, s.meta.lang)}">`)
          .join('\n')

        // Both language pickers — under the title and in the footer, one component — link this
        // page's siblings: a reader who switches language stays on this help page, never the
        // landing page. The top one is the page's own control, the footer the site-wide one at the
        // opposite end of a long page.
        const languages = sources
          .filter((s) => s.meta.platforms.includes(platform))
          .map((s) => ({
            lang: s.meta.lang,
            name: CHROME[s.meta.lang]!.languageName,
            href: pageUrl(app, platform, s.meta.lang),
            current: s.meta.lang === meta.lang,
          }))
        const shared = renderChrome({ lang: meta.lang, url, supportHref: hubUrl(meta.lang), languages })
        // No eyebrow above the title: "Support" is the header's current nav entry and the
        // platform is the current platform pill. <title> and the description still name both.
        const html = [
          head({ lang: meta.lang, title, description, css: cssHref, canonical: url, extra: alternates }),
          `<body class="${shared.bodyClass}">`,
          siteHeader(chrome, shared),
          `<main id="main" class="${MAIN_CLASSES}">`,
          breadcrumb(HUB_COPY[meta.lang]!.breadcrumb, [
            { text: chrome.support, href: hubUrl(meta.lang) },
            { text: shortName(app), href: supportEntryUrl(app) },
            { text: platformName },
          ]),
          '<div class="page-head">',
          `<h1 class="${H1_CLASSES}">${escapeHtml(meta.name)}</h1>`,
          '<div class="switchers">',
          platformSwitcher(
            chrome.platform,
            meta.platforms.map((p) => ({
              href: pageUrl(app, p, meta.lang),
              text: chrome.platformNames[p],
              current: p === platform,
            })),
          ),
          `<div class="switcher switcher--language">${shared.languagePicker}</div>`,
          '</div>',
          '</div>',
          `<nav class="toc" aria-label="${escapeHtml(chrome.onThisPage)}"><span class="switcher__label">${escapeHtml(chrome.onThisPage)}</span><ol>` +
            rendered.toc.map((t) => `<li><a href="#${t.id}">${t.text}</a></li>`).join('') +
            '</ol></nav>',
          `<article class="prose">\n${wrapSections(rendered.html)}</article>`,
          '</main>',
          siteFooter(shared),
          '</body>',
          '</html>',
          '',
        ].join('\n')

        const file = `apps/${app}/support/${platform}/${meta.lang}/index.html`
        files.push({ file, content: html })
        pages.push({
          app,
          platform,
          lang: meta.lang,
          file,
          url,
          html,
          privacyClaims,
          sources: rendered.sources,
          screenshotSlots: rendered.shots,
        })
      }
    }

    // §4.1: `/apps/<app>/support` picks a platform and a language, then redirects. Done in the
    // browser (navigator.userAgent / navigator.languages — the same preference list the browser
    // sends as Accept-Language) because only the build knows which pages exist; Caddy needs no
    // rule for it. Without JavaScript the page is a list of links, and it is noindex either way.
    const chrome = CHROME[primary.meta.lang]!
    const langsFor = (platform: Platform) => sources.filter((s) => s.meta.platforms.includes(platform)).map((s) => s.meta.lang)
    const table: Record<string, string[]> = Object.fromEntries(platforms.map((p) => [p, langsFor(p)]))
    files.push({
      file: `apps/${app}/support/index.html`,
      content: redirectPage({ app, name: primary.meta.name, chrome, renderChrome, cssHref, table, defaultPlatform, fixedPlatform: null }),
    })
    for (const platform of platforms) {
      files.push({
        file: `apps/${app}/support/${platform}/index.html`,
        content: redirectPage({ app, name: primary.meta.name, chrome, renderChrome, cssHref, table, defaultPlatform, fixedPlatform: platform }),
      })
    }
  }

  files.push(...buildLegalPages(renderChrome, cssHref, 'imprint', IMPRINT, IMPRINT_LANGUAGES, imprintUrl))
  files.push(...buildLegalPages(renderChrome, cssHref, 'privacy', PRIVACY, PRIVACY_LANGUAGES, privacyUrl))
  files.push(...buildLegalPages(renderChrome, cssHref, 'terms', TERMS, TERMS_LANGUAGES, termsUrl))
  files.push(...buildRoadmap(renderChrome, cssHref))
  files.push(...buildPricing(renderChrome, cssHref))
  files.push(...buildAndroidDownloads(renderChrome, cssHref, storeDir))
  files.push(...buildMacosDownloads(renderChrome, cssHref, storeDir))
  for (const set of APP_PAGES) files.push(...buildAppPages(renderChrome, cssHref, set, manifests))

  // The hub lists every app; its switch and the content must agree (hub.ts HubApp.support).
  for (const entry of HUB_APPS) {
    if (entry.support && !byApp.has(entry.app)) throw new Error(`support-pages/hub.ts: ${entry.app} has support: true but no content/apps/${entry.app}/support/`)
    if (!entry.support && byApp.has(entry.app)) throw new Error(`support-pages/hub.ts: ${entry.app} has support content; set support: true`)
  }
  for (const app of byApp.keys()) shortName(app)
  const hub = buildHub({ renderChrome, cssHref, manifests, byApp, gettingStartedDir })
  files.push(...hub.files)
  files.push(...buildLicenses(renderChrome, cssHref))

  // The stylesheet last: it holds the classes the pages use, and its hash names it.
  const css = await buildCss(files.map((f) => f.content))
  const cssFile = `assets/support-${createHash('sha256').update(css).digest('hex').slice(0, 10)}.css`
  // Then the Content-Security-Policy <meta>, hashing each page's own inline scripts (csp/csp.ts). A
  // static page talks to nobody and frames nothing: its only other origin is the screenshot host.
  const link = (html: string) => html.replaceAll(CSS_HREF_PLACEHOLDER, `/${cssFile}`)
  const images = screenshotOrigins(storeDir)
  const finish = (html: string) => applyCsp(link(html), { images })
  return {
    pages: pages.map((page) => ({ ...page, html: finish(page.html) })),
    hubPages: hub.pages.map((page) => ({ ...page, html: finish(page.html) })),
    files: [{ file: cssFile, content: css }, ...files.map((f) => ({ ...f, content: f.file.endsWith('.html') ? finish(f.content) : link(f.content) }))],
  }
}

/** The legal pages' URL segments: `/legal/<slug>/<lang>`. */
type LegalSlug = 'imprint' | 'privacy' | 'terms'

/** A page written in TypeScript rather than Markdown: a legal page, or the roadmap (roadmap.ts). */
interface TextPage {
  readonly title: string
  readonly description: string
  /** A translation's first line, where the original is the binding text. */
  readonly translationNotice?: string
  /** The body, as HTML: trusted, written here, never user input. */
  readonly html: string
}

/** Where a set of text pages lives, and how its header and article are marked. */
interface TextPageSet {
  /** The output directory, without a leading slash: `legal/terms`, `roadmap`. */
  readonly dir: string
  /** The language-neutral URL (`/legal/terms`): Caddy redirects it by language; x-default. */
  readonly neutralUrl: string
  /** The <article>'s classes: `prose <slug>` for a legal text. */
  readonly articleClass: string
  /** The language-neutral list's <article> classes: always `prose`, it is a plain list. */
  readonly listClass: string
  /** The header entry marked current; none for the legal pages. */
  readonly current: 'roadmap' | 'pricing' | null
}

/**
 * The legal pages, `/legal/imprint/<lang>` (imprint.ts), `/legal/privacy/<lang>` (privacy.ts) and
 * `/legal/terms/<lang>` (terms.ts), built by [buildTextPages]. No "Support" entry is current here,
 * and Support leads to the default entry point.
 */
function buildLegalPages(
  renderChrome: ChromeRenderer,
  cssHref: string,
  slug: LegalSlug,
  pages: Record<string, LegalPage>,
  languages: readonly string[],
  urlOf: (lang: string) => string,
): Output[] {
  const set: TextPageSet = { dir: `legal/${slug}`, neutralUrl: legalNeutralUrl(slug), articleClass: `prose ${slug}`, listClass: `prose ${slug}`, current: null }
  return buildTextPages(renderChrome, cssHref, set, pages, languages, urlOf)
}

/**
 * The public roadmap, `/roadmap/<lang>` (roadmap.ts): a text page like the legal ones, with the
 * header's "Roadmap" entry marked current. Its body brings its own layout, so it is no `prose`.
 */
function buildRoadmap(renderChrome: ChromeRenderer, cssHref: string): Output[] {
  const pages: Record<string, TextPage> = Object.fromEntries(
    ROADMAP_LANGUAGES.map((lang) => {
      const page = ROADMAP[lang]!
      return [lang, { title: page.title, description: page.description, html: roadmapHtml(page, lang) }]
    }),
  )
  const set: TextPageSet = { dir: 'roadmap', neutralUrl: ROADMAP_NEUTRAL_URL, articleClass: 'roadmap', listClass: 'prose roadmap', current: 'roadmap' }
  return buildTextPages(renderChrome, cssHref, set, pages, ROADMAP_LANGUAGES, roadmapUrl)
}

/**
 * The public pricing page, `/pricing/<lang>` (pricing.ts): a text page like the roadmap, with the
 * header's "Pricing" entry marked current. Its body brings its own layout, so it is no `prose`.
 */
function buildPricing(renderChrome: ChromeRenderer, cssHref: string): Output[] {
  const pages: Record<string, TextPage> = Object.fromEntries(
    PRICING_LANGUAGES.map((lang) => {
      const page = PRICING[lang]!
      return [lang, { title: page.title, description: page.description, html: pricingHtml(page, lang) }]
    }),
  )
  const set: TextPageSet = { dir: 'pricing', neutralUrl: PRICING_NEUTRAL_URL, articleClass: 'pricing', listClass: 'prose pricing', current: 'pricing' }
  return buildTextPages(renderChrome, cssHref, set, pages, PRICING_LANGUAGES, pricingUrl)
}

/**
 * The Android download pages, `/apps/<app>/download/android/<lang>` (androidDownload.ts), one set
 * per app, from the committed `store/android-release.json` (or "not available yet" without it).
 * Text pages like the pricing page, no header entry current, plus each app's language-neutral list.
 */
function buildAndroidDownloads(renderChrome: ChromeRenderer, cssHref: string, storeDir: string): Output[] {
  const release = loadAndroidRelease(storeDir)
  return ANDROID_APPS.flatMap((app) => {
    const pages: Record<string, TextPage> = Object.fromEntries(
      ANDROID_DOWNLOAD_LANGUAGES.map((lang) => {
        const page = ANDROID_DOWNLOAD[lang]!
        const name = androidAppName(app)
        return [lang, { title: page.title(name), description: page.description(name), html: androidDownloadHtml(page, app, lang, release) }]
      }),
    )
    const set: TextPageSet = {
      dir: `apps/${app}/download/android`,
      neutralUrl: androidDownloadNeutralUrl(app),
      articleClass: 'prose android-download',
      listClass: 'prose',
      current: null,
    }
    return buildTextPages(renderChrome, cssHref, set, pages, ANDROID_DOWNLOAD_LANGUAGES, (lang) => androidDownloadUrl(app, lang))
  })
}

/**
 * The macOS download pages, `/apps/<app>/download/macos/<lang>` (macosDownload.ts), one set per app,
 * from the committed `store/macos-release.json`, plus each published app's `macos.json`, the Homebrew
 * casks' `livecheck` source.
 */
function buildMacosDownloads(renderChrome: ChromeRenderer, cssHref: string, storeDir: string): Output[] {
  const release = loadMacosRelease(storeDir)
  return MACOS_APPS.flatMap((app) => {
    const pages: Record<string, TextPage> = Object.fromEntries(
      MACOS_DOWNLOAD_LANGUAGES.map((lang) => {
        const page = MACOS_DOWNLOAD[lang]!
        const name = macosAppName(app)
        return [lang, { title: page.title(name), description: page.description(name), html: macosDownloadHtml(page, app, lang, release) }]
      }),
    )
    const set: TextPageSet = {
      dir: `apps/${app}/download/macos`,
      neutralUrl: macosDownloadNeutralUrl(app),
      articleClass: 'prose macos-download',
      listClass: 'prose',
      current: null,
    }
    const html = buildTextPages(renderChrome, cssHref, set, pages, MACOS_DOWNLOAD_LANGUAGES, (lang) => macosDownloadUrl(app, lang))
    const json = release ? macosLivecheckJson(release, app) : null
    return json ? [...html, { file: macosLivecheckFile(app), content: json }] : html
  })
}

/**
 * The download block's only logic, inlined into each app page: [REDIRECT_LOGIC]'s `detect`; if the
 * app is on the detected system, its button moves first and becomes the primary one. Either way a
 * line says which system we think it is (and, if the app is not there yet, that it is coming). Without JavaScript the row stays as built: every
 * target, the first available one primary.
 */
function appDownloadScript(): string {
  return `(function(){` +
  REDIRECT_LOGIC +
  `var r=document.querySelector('[data-download]');if(!r)return;` +
  `var p=detect(navigator.userAgent,navigator.maxTouchPoints||0);var i=p&&r.querySelector('[data-os="'+p+'"]');if(!i)return;` +
  `var n=JSON.parse(r.getAttribute('data-names'));var u=i.parentNode;` +
  `if(!i.hasAttribute('data-soon')){Array.prototype.forEach.call(u.querySelectorAll('a'),function(a){a.className=${JSON.stringify(BUTTON_SECONDARY)}});i.querySelector('a').className=${JSON.stringify(BUTTON_PRIMARY)};u.insertBefore(i,u.firstChild);}` +
  `r.querySelector('[data-download-hint]').textContent=r.getAttribute(i.hasAttribute('data-soon')?'data-soon-text':'data-ready').replace('{platform}',n[p]);` +
  `})();`
}

/**
 * An app's marketing pages, `/apps/<app>/<lang>` (appPages.ts), plus the language-neutral list at
 * `/apps/<app>` that Caddy 302s past. The landing page's header and footer, no header entry
 * current. The hero shows three captures from [AppPageSet.device], falling back to English.
 */
function buildAppPages(renderChrome: ChromeRenderer, cssHref: string, set: AppPageSet, manifests: ShotManifests): Output[] {
  const urlOf = (lang: string) => appPageUrl(set.app, lang)
  const neutralUrl = appPageNeutralUrl(set.app)
  const pages = APP_PAGE_LANGUAGES.map((lang) => {
    const page = set.pages[lang]!
    const chrome = CHROME[lang]!
    const url = urlOf(lang)
    const links = APP_PAGE_LANGUAGES.map((l) => ({ lang: l, name: CHROME[l]!.languageName, href: urlOf(l), current: l === lang }))
    const shared = renderChrome({ lang, url, supportHref: hubUrl(lang), languages: links, current: null })
    // The hero's carousel: the landing page's app carousel with this one app, run by the hub's own
    // script. Android: the page's screens from its capture set; iPhone: the same screens where they
    // were captured there, else the hub's iPhone screens for the app.
    const copy = HUB_COPY[lang]!
    const slot = (platform: Platform, screen: string, alt: string, device?: string): CarouselShot[] => {
      const s = resolveShot(manifests, set.app, platform, lang, undefined, screen, device)
      return s.image || s.dark ? [{ light: s.image, dark: s.dark, alt }] : []
    }
    const ownIos = set.screens.flatMap((screen, i) => slot('ios', screen, page.shotAlts[i]!))
    const entry: CarouselEntry = {
      id: set.app,
      name: page.eyebrow,
      href: null,
      shots: set.screens.flatMap((screen, i) => slot('android', screen, page.shotAlts[i]!, set.device)),
      iosShots: ownIos.length ? ownIos : (HUB_APPS.find((a: HubApp) => a.app === set.app)?.screens.ios ?? []).flatMap((screen) => slot('ios', screen, page.eyebrow)),
    }
    const carousel = shared.carousel([entry], page.eyebrow, {
      screenshot: copy.screenshot,
      pause: copy.pauseShots,
      play: copy.playShots,
      show: copy.show,
      platform: { group: copy.device, ios: copy.iphone, android: copy.android, onlyAndroid: copy.onlyAndroid, onlyIos: copy.onlyIos },
    })
    const carouselScript = `(function(){var cfg=${JSON.stringify({ lang, initial: set.app, platforms: {} })};${REDIRECT_LOGIC}${HUB_SCRIPT_BODY}})();`
    const html = [
      head({ lang, title: page.metaTitle, description: page.description, css: cssHref, canonical: url, extra: textAlternates(neutralUrl, APP_PAGE_LANGUAGES, urlOf) }),
      `<body class="${shared.bodyClass}">`,
      siteHeader(chrome, shared),
      `<main id="main" class="${APP_MAIN_CLASSES}">`,
      `<article class="app-page">\n${appPageHtml(set, page, lang, { carousel, icon: appIcon(set.app), frameNote: copy.frameNote }, chrome.platformNames)}\n</article>`,
      '</main>',
      siteFooter(shared),
      `<script>${appDownloadScript()}</script>`,
      `<script>${carouselScript}</script>`,
      '</body>',
      '</html>',
      '',
    ].join('\n')
    return { file: `apps/${set.app}/${lang}/index.html`, content: html }
  })
  const titles: Record<string, TextPage> = Object.fromEntries(
    APP_PAGE_LANGUAGES.map((lang) => [lang, { title: set.pages[lang]!.eyebrow, description: set.pages[lang]!.description, html: '' }]),
  )
  const list: TextPageSet = { dir: `apps/${set.app}`, neutralUrl, articleClass: 'prose', listClass: 'prose', current: null }
  return [...pages, textLanguageList(renderChrome, cssHref, list, titles, APP_PAGE_LANGUAGES, urlOf)]
}

/**
 * One static page per language with the landing page's header and footer — the footer's language
 * picker switching between its siblings — so they work with JavaScript off and every page's footer
 * can link them. A page whose `<h2>`s carry ids gets a table of contents (the privacy policy; the
 * imprint is short enough to need none). Plus the language-neutral URL's static list
 * ([textLanguageList]).
 */
function buildTextPages(
  renderChrome: ChromeRenderer,
  cssHref: string,
  set: TextPageSet,
  pages: Record<string, TextPage>,
  languages: readonly string[],
  urlOf: (lang: string) => string,
): Output[] {
  return languages.map((lang) => {
    const page = pages[lang]!
    const url = urlOf(lang)
    const links = languages.map((l) => ({ lang: l, name: CHROME[l]!.languageName, href: urlOf(l), current: l === lang }))
    const shared = renderChrome({ lang, url, supportHref: hubUrl(lang), languages: links, current: set.current })
    const alternates = textAlternates(set.neutralUrl, languages, urlOf)
    const toc = [...page.html.matchAll(/<h2 id="([a-z0-9-]+)">([\s\S]*?)<\/h2>/g)].map((m) => ({ id: m[1]!, text: m[2]! }))
    const onThisPage = CHROME[lang]!.onThisPage
    const html = [
      head({ lang, title: `${page.title} — Tenshu`, description: page.description, css: cssHref, canonical: url, extra: alternates }),
      `<body class="${shared.bodyClass}">`,
      siteHeader(CHROME[lang]!, shared),
      `<main id="main" class="${MAIN_CLASSES}">`,
      `<div class="page-head"><h1 class="${H1_CLASSES}">${escapeHtml(page.title)}</h1></div>`,
      page.translationNotice ? `<p class="translation-notice">${escapeHtml(page.translationNotice)}</p>` : '',
      toc.length > 0
        ? `<nav class="toc" aria-label="${escapeHtml(onThisPage)}"><span class="switcher__label">${escapeHtml(onThisPage)}</span><ol>` +
          toc.map((t) => `<li><a href="#${t.id}">${t.text}</a></li>`).join('') +
          '</ol></nav>'
        : '',
      `<article class="${set.articleClass}">\n${page.html}\n</article>`,
      '</main>',
      siteFooter(shared),
      '</body>',
      '</html>',
      '',
    ]
      .filter((line, i, all) => line !== '' || i === all.length - 1)
      .join('\n')
    return { file: `${set.dir}/${lang}/index.html`, content: html }
  }).concat(textLanguageList(renderChrome, cssHref, set, pages, languages, urlOf))
}

/** `/legal/<slug>`, the language-neutral URL: the stores' privacy URL (privacy.ts) and x-default. */
export function legalNeutralUrl(slug: LegalSlug): string {
  return `/legal/${slug}`
}

/** Every language of a text page, plus x-default for the language-neutral URL. */
function textAlternates(neutralUrl: string, languages: readonly string[], urlOf: (lang: string) => string): string {
  return [
    ...languages.map((l) => `<link rel="alternate" hreflang="${l}" href="${SITE_ORIGIN}${urlOf(l)}">`),
    `<link rel="alternate" hreflang="x-default" href="${SITE_ORIGIN}${neutralUrl}">`,
  ].join('\n')
}

/**
 * The language-neutral URL as a page: the list of its languages, each linked in its own language.
 * Caddy never serves it in production — it 302s that URL to a language by Accept-Language first
 * (web/apex/Caddyfile) — so this is what a host without that rule shows (vite preview, a plain
 * file server) instead of falling through to the SPA. No script: it works with JavaScript off.
 */
function textLanguageList(
  renderChrome: ChromeRenderer,
  cssHref: string,
  set: TextPageSet,
  pages: Record<string, TextPage>,
  languages: readonly string[],
  urlOf: (lang: string) => string,
): Output {
  const url = set.neutralUrl
  const shared = renderChrome({ lang: 'en', url, supportHref: HUB_ENTRY_URL, languages: [], current: set.current })
  const links = languages
    .map((l) => `<li><a href="${urlOf(l)}" hreflang="${l}" lang="${l}">${escapeHtml(pages[l]!.title)} (${escapeHtml(CHROME[l]!.languageName)})</a></li>`)
    .join('')
  const html = [
    head({ lang: 'en', title: `${pages.en!.title} — Tenshu`, description: pages.en!.description, css: cssHref, extra: textAlternates(set.neutralUrl, languages, urlOf) }),
    `<body class="${shared.bodyClass}">`,
    siteHeader(CHROME.en!, shared),
    `<main id="main" class="${MAIN_CLASSES}">`,
    `<div class="page-head"><h1 class="${H1_CLASSES}">${escapeHtml(pages.en!.title)}</h1></div>`,
    `<article class="${set.listClass}"><ul>${links}</ul></article>`,
    '</main>',
    siteFooter(shared),
    '</body>',
    '</html>',
    '',
  ].join('\n')
  return { file: `${set.dir}/index.html`, content: html }
}

/**
 * The redirect pages' only logic, as browser JavaScript inlined into them verbatim — kept as a
 * string rather than `fn.toString()` so no bundler or type-stripper can change what ships.
 * `redirectLogic()` evaluates the same string for the tests. iPadOS reports a Mac user agent,
 * hence the touch check; a platform we do not ship falls back to the app's `defaultPlatform`.
 */
const REDIRECT_LOGIC = `
function detect(ua, touch) {
  if (/iPhone|iPad|iPod/.test(ua)) return 'ios';
  if (/Macintosh/.test(ua) && touch > 1) return 'ios';
  if (/Android/.test(ua)) return 'android';
  if (/Macintosh|Mac OS X/.test(ua)) return 'macos';
  if (/Windows/.test(ua)) return 'windows';
  if (/Linux|X11|CrOS/.test(ua)) return 'linux';
  return null;
}
function pick(preferred, available) {
  for (var i = 0; i < preferred.length; i++) {
    var base = String(preferred[i]).toLowerCase().split('-')[0];
    if (available.indexOf(base) >= 0) return base;
  }
  return available.indexOf('en') >= 0 ? 'en' : available[0];
}
`

export function redirectLogic(): {
  detect: (userAgent: string, maxTouchPoints: number) => Platform | null
  pick: (preferred: readonly string[], available: readonly string[]) => string
} {
  return new Function(`${REDIRECT_LOGIC}\nreturn { detect: detect, pick: pick };`)()
}

function redirectPage(opts: {
  app: string
  name: string
  chrome: Chrome
  renderChrome: ChromeRenderer
  cssHref: string
  table: Record<string, string[]>
  defaultPlatform: Platform
  fixedPlatform: Platform | null
}): string {
  const { app, name, chrome, renderChrome, cssHref, table, defaultPlatform, fixedPlatform } = opts
  const url = fixedPlatform ? `${supportEntryUrl(app)}/${fixedPlatform}` : supportEntryUrl(app)
  // No language picker: this page has no language of its own, it only picks one.
  const shared = renderChrome({ lang: 'en', url, supportHref: HUB_ENTRY_URL, languages: [] })
  const script =
    `(function(){var t=${JSON.stringify(table)},d=${JSON.stringify(defaultPlatform)},f=${JSON.stringify(fixedPlatform)};` +
    REDIRECT_LOGIC +
    `var p=f||detect(navigator.userAgent,navigator.maxTouchPoints||0);if(!p||!t[p])p=d;` +
    // The language picked from a language picker first (languageSwitch.ts), then the
    // browser's own list. Blocked storage throws; it then simply is not consulted.
    `var s=null;try{s=localStorage.getItem(${JSON.stringify(LANGUAGE_STORAGE_KEY)})}catch(_){}` +
    `var n=navigator.languages&&navigator.languages.length?navigator.languages:[navigator.language||'en'];` +
    `var l=pick(s?[s].concat(Array.prototype.slice.call(n)):n,t[p]);` +
    `location.replace(${JSON.stringify(`/apps/${app}/support/`)}+p+'/'+l+location.hash);})();`
  const links = Object.entries(table)
    .filter(([platform]) => !fixedPlatform || platform === fixedPlatform)
    .flatMap(([platform, langs]) =>
      langs.map(
        (lang) =>
          `<li><a href="${pageUrl(app, platform as Platform, lang)}">${escapeHtml(chrome.platformNames[platform as Platform])}` +
          `${langs.length > 1 ? ` (${escapeHtml(CHROME[lang]!.languageName)})` : ''}</a></li>`,
      ),
    )
    .join('')
  return [
    head({
      lang: 'en',
      title: `${name} — ${chrome.support}`,
      css: cssHref,
      extra: `<meta name="robots" content="noindex">\n<script>${script}</script>`,
    }),
    `<body class="${shared.bodyClass}">`,
    siteHeader(chrome, shared),
    `<main id="main" class="${MAIN_CLASSES}">`,
    breadcrumb(HUB_COPY.en!.breadcrumb, [
      { text: chrome.support, href: HUB_ENTRY_URL },
      fixedPlatform ? { text: shortName(app), href: supportEntryUrl(app) } : { text: shortName(app) },
      ...(fixedPlatform ? [{ text: chrome.platformNames[fixedPlatform] }] : []),
    ]),
    `<div class="page-head"><h1 class="${H1_CLASSES}">${escapeHtml(name)}</h1></div>`,
    `<article class="prose"><h2>${escapeHtml(chrome.choose)}</h2><ul>${links}</ul></article>`,
    '</main>',
    siteFooter(shared),
    '</body>',
    '</html>',
    '',
  ].join('\n')
}

// ── the hub and Getting started (§4.7) ───────────────────────────────────

/**
 * `/apps/support/<lang>` — every app plus Getting started, each with its screenshots and a real
 * link, as plain HTML; HUB_SCRIPT_BODY (hub.ts) makes the carousel on top. `/apps/support` picks a
 * language. `/apps/support/getting-started/<lang>` is the page new users start from, and
 * `/apps/support/getting-started` picks a language too. ⚠ Nothing is written at `/support`: it is
 * reserved (hub.ts).
 */
function buildHub(opts: {
  renderChrome: ChromeRenderer
  cssHref: string
  manifests: ShotManifests
  byApp: ReadonlyMap<string, readonly Source[]>
  gettingStartedDir: string
}): { files: Output[]; pages: HubPage[] } {
  const { renderChrome, cssHref, manifests, byApp, gettingStartedDir } = opts
  const files: Output[] = []
  const pages: HubPage[] = []
  const languagesFor = (url: (lang: string) => string, lang: string) =>
    HUB_LANGUAGES.map((l) => ({ lang: l, name: CHROME[l]!.languageName, href: url(l), current: l === lang }))
  const alternatesFor = (url: (lang: string) => string) =>
    HUB_LANGUAGES.map((l) => `<link rel="alternate" hreflang="${l}" href="${SITE_ORIGIN}${url(l)}">`).join('\n')

  // Which platforms each app has a page for, in which languages: the script's table, and the
  // static link's default (the app's defaultPlatform, like the redirect pages).
  const appMeta = (app: string) => {
    const sources = byApp.get(app)!
    const primary = sources.find((s) => s.meta.lang === 'en') ?? sources[0]!
    return { primary: primary.meta, langs: sources.map((s) => s.meta.lang) }
  }

  for (const lang of HUB_LANGUAGES) {
    const copy = HUB_COPY[lang]!
    const chrome = CHROME[lang]!
    const url = hubUrl(lang)
    const shared = renderChrome({ lang, url, supportHref: url, languages: languagesFor(hubUrl, lang) })
    const slots: ShotSlot[] = []
    const shot = (app: string, screen: string, alt: string, platform: Platform = HUB_SHOT_PLATFORM): CarouselShot[] => {
      const slot = resolveShot(manifests, app, platform, lang, undefined, screen)
      slots.push(slot)
      return slot.image || slot.dark ? [{ light: slot.image, dark: slot.dark, alt }] : []
    }

    // The carousel's entries (src/landing/carousel/carouselModel.ts); the component renders them
    // and the landing page shows the same carousel. Every name carries its icon (Getting started:
    // Tenshu's mark), on the stage side of the name. An app without a page is greyed out, "Coming
    // soon", with no link.
    const soon = (app: HubApp): CarouselEntry => ({ id: app.app, name: app.name, href: null, shots: [], soonLabel: copy.comingSoon, icon: appIcon(app.app) })
    const entries: CarouselEntry[] = [
      {
        id: GETTING_STARTED_ID,
        name: copy.gettingStarted,
        href: gettingStartedUrl(lang),
        openLabel: copy.openGettingStarted,
        icon: GETTING_STARTED_ICON,
        shots: shot(GETTING_STARTED_SHOT.app, GETTING_STARTED_SHOT.screen, `${copy.gettingStarted}: Tenshu Control`),
        iosShots: shot(GETTING_STARTED_SHOT.app, GETTING_STARTED_SHOT.screen, `${copy.gettingStarted}: Tenshu Control`, 'ios'),
      },
      ...HUB_APPS.map((app: HubApp): CarouselEntry => {
        if (!app.support) return soon(app)
        const { primary, langs } = appMeta(app.app)
        const pageLang = langs.includes(lang) ? lang : 'en'
        return {
          id: app.app,
          name: app.name,
          href: pageUrl(app.app, primary.defaultPlatform, pageLang),
          openLabel: copy.open(app.name),
          app: app.app,
          icon: appIcon(app.app),
          shots: app.screens.android.flatMap((screen) => shot(app.app, screen, `Tenshu ${app.name}`, 'android')),
          iosShots: app.screens.ios.flatMap((screen) => shot(app.app, screen, `Tenshu ${app.name}`, 'ios')),
        }
      }),
    ]
    const platforms = Object.fromEntries(
      [...byApp.keys()].map((app) => [app, appMeta(app).primary.platforms.filter((p) => byApp.get(app)!.some((s) => s.meta.lang === lang && s.meta.platforms.includes(p)))]),
    )
    const cfg = { lang, initial: GETTING_STARTED_ID, platforms }
    const script = `(function(){var cfg=${JSON.stringify(cfg)};${REDIRECT_LOGIC}${HUB_SCRIPT_BODY}})();`
    const html = [
      head({ lang, title: copy.title, description: copy.description, css: cssHref, canonical: url, extra: alternatesFor(hubUrl) }),
      `<body class="${shared.bodyClass}">`,
      siteHeader(chrome, shared),
      `<main id="main" class="${MAIN_CLASSES}">`,
      '<div class="page-head hub-head">',
      `<div><h1 class="${H1_CLASSES}">${escapeHtml(copy.heading)}</h1>`,
      `<p class="hub-lede">${escapeHtml(copy.lede)}</p></div>`,
      `<div class="switcher switcher--language">${shared.languagePicker}</div>`,
      '</div>',
      shared.carousel(
        entries,
        copy.apps,
        {
          screenshot: copy.screenshot,
          pause: copy.pauseShots,
          play: copy.playShots,
          show: copy.show,
          platform: { group: copy.device, ios: copy.iphone, android: copy.android, onlyAndroid: copy.onlyAndroid, onlyIos: copy.onlyIos },
        },
        copy.frameNote,
      ),
      HUB_APPS.some((a: HubApp) => !a.support) ? `<p class="hub-note">${escapeHtml(copy.soonNote)}</p>` : '',
      `<script>${script}</script>`,
      '</main>',
      siteFooter(shared),
      '</body>',
      '</html>',
      '',
    ].join('\n')
    const file = `apps/support/${lang}/index.html`
    files.push({ file, content: html })
    pages.push({ kind: 'hub', lang, file, url, html, privacyClaims: [], screenshotSlots: slots })
  }
  files.push({ file: 'apps/support/index.html', content: languageRedirectPage({ renderChrome, cssHref, title: HUB_COPY.en!.title, heading: HUB_COPY.en!.heading, url: hubUrl, entry: HUB_ENTRY_URL, crumbs: [] }) })

  // Getting started: Markdown like a support page, but one page per language, not per platform.
  for (const lang of HUB_LANGUAGES) {
    const where = `content/support/getting-started/${lang}.md`
    const path = join(gettingStartedDir, `${lang}.md`)
    if (!existsSync(path)) throw new Error(`${where}: missing (the hub links Getting started in every language)`)
    const { raw, body } = readFrontMatter(readFileSync(path, 'utf8'), where)
    if (raw['lang'] !== lang) throw new Error(`${where}: front matter lang "${raw['lang']}" ≠ file name`)
    for (const key of ['title', 'description']) if (!raw[key]) throw new Error(`${where}: front matter needs "${key}"`)
    const reviewed = raw['reviewed'] ?? 'true'
    if (reviewed !== 'true' && reviewed !== 'false') throw new Error(`${where}: reviewed must be true or false`)
    const copy = HUB_COPY[lang]!
    const chrome = CHROME[lang]!
    const url = gettingStartedUrl(lang)
    const rendered = renderBody(body, GETTING_STARTED_SHOT.app, HUB_SHOT_PLATFORM, lang, manifests, chrome, where)
    const privacy = sectionsOf(rendered.html).get('privacy')
    if (!privacy) throw new Error(`${where}: needs a "{#privacy}" section`)
    const privacyClaims = privacyClaimsOf(privacy, where)
    const shared = renderChrome({ lang, url, supportHref: hubUrl(lang), languages: languagesFor(gettingStartedUrl, lang) })
    const html = [
      head({ lang, title: raw['title']!, description: raw['description']!, css: cssHref, canonical: url, extra: alternatesFor(gettingStartedUrl) }),
      `<body class="${shared.bodyClass}">`,
      siteHeader(chrome, shared),
      `<main id="main" class="${MAIN_CLASSES}">`,
      breadcrumb(copy.breadcrumb, [{ text: chrome.support, href: hubUrl(lang) }, { text: copy.gettingStarted }]),
      '<div class="page-head">',
      `<h1 class="${H1_CLASSES}">${escapeHtml(copy.gettingStarted)}</h1>`,
      '<div class="switchers">',
      `<div class="switcher switcher--language">${shared.languagePicker}</div>`,
      '</div>',
      '</div>',
      `<nav class="toc" aria-label="${escapeHtml(chrome.onThisPage)}"><span class="switcher__label">${escapeHtml(chrome.onThisPage)}</span><ol>` +
        rendered.toc.map((t) => `<li><a href="#${t.id}">${t.text}</a></li>`).join('') +
        '</ol></nav>',
      `<article class="prose">\n${wrapSections(rendered.html)}</article>`,
      '</main>',
      siteFooter(shared),
      '</body>',
      '</html>',
      '',
    ].join('\n')
    const file = `apps/support/getting-started/${lang}/index.html`
    files.push({ file, content: html })
    pages.push({ kind: 'getting-started', lang, file, url, html, privacyClaims, screenshotSlots: rendered.shots })
  }
  const gsEntry = gettingStartedUrl('').replace(/\/$/, '')
  files.push({
    file: 'apps/support/getting-started/index.html',
    content: languageRedirectPage({
      renderChrome,
      cssHref,
      title: `${HUB_COPY.en!.gettingStarted} — ${CHROME.en!.support}`,
      heading: HUB_COPY.en!.gettingStarted,
      url: gettingStartedUrl,
      entry: gsEntry,
      crumbs: [{ text: CHROME.en!.support, href: HUB_ENTRY_URL }],
    }),
  })
  return { files, pages }
}

/**
 * `/apps/support` and `/apps/support/getting-started`: pick a language — the one stored from a
 * language picker, then the browser's list — and redirect, like the apps' redirect pages. Without
 * JavaScript, a list of the languages. noindex either way.
 */
function languageRedirectPage(opts: {
  renderChrome: ChromeRenderer
  cssHref: string
  title: string
  heading: string
  url: (lang: string) => string
  entry: string
  crumbs: readonly { text: string; href?: string }[]
}): string {
  const { renderChrome, cssHref, title, heading, url, entry, crumbs } = opts
  const shared = renderChrome({ lang: 'en', url: entry, supportHref: HUB_ENTRY_URL, languages: [] })
  const target = url('__LANG__')
  const [before, after] = target.split('__LANG__') as [string, string]
  const script =
    `(function(){var t=${JSON.stringify([...HUB_LANGUAGES])};` +
    REDIRECT_LOGIC +
    `var s=null;try{s=localStorage.getItem(${JSON.stringify(LANGUAGE_STORAGE_KEY)})}catch(_){}` +
    `var n=navigator.languages&&navigator.languages.length?navigator.languages:[navigator.language||'en'];` +
    `var l=pick(s?[s].concat(Array.prototype.slice.call(n)):n,t);` +
    `location.replace(${JSON.stringify(before)}+l+${JSON.stringify(after)}+location.hash);})();`
  const links = HUB_LANGUAGES.map((l) => `<li><a href="${url(l)}" hreflang="${l}" lang="${l}">${escapeHtml(CHROME[l]!.languageName)}</a></li>`).join('')
  return [
    head({ lang: 'en', title, css: cssHref, extra: `<meta name="robots" content="noindex">\n<script>${script}</script>` }),
    `<body class="${shared.bodyClass}">`,
    siteHeader(CHROME.en!, shared),
    `<main id="main" class="${MAIN_CLASSES}">`,
    crumbs.length ? breadcrumb(HUB_COPY.en!.breadcrumb, [...crumbs, { text: heading }]) : '',
    `<div class="page-head"><h1 class="${H1_CLASSES}">${escapeHtml(heading)}</h1></div>`,
    `<article class="prose"><ul>${links}</ul></article>`,
    '</main>',
    siteFooter(shared),
    '</body>',
    '</html>',
    '',
  ]
    .filter((line, i, all) => line !== '' || i === all.length - 1)
    .join('\n')
}

/**
 * The third-party licences page, `/legal/licenses/<lang>` (licenses.ts): built like the imprint —
 * one static page per language in the site chrome — from the packages the build actually has
 * installed (thirdParty.ts) and the SDK bundle's contents (sdkBundle.ts), so it is regenerated on
 * every build and cannot drift from the lockfile. `/legal/licenses` itself is a short page in the
 * same chrome that sends to English (noindex), so the bare URL never falls through to the SPA.
 */
function buildLicenses(renderChrome: ChromeRenderer, cssHref: string): Output[] {
  const packages = collectThirdParty(APEX_DIR)
  const page = (lang: string, url: string, canonical: string, extra: string, body: string) => {
    const copy = LICENSES[lang]!
    const languages = LICENSES_LANGUAGES.map((l) => ({ lang: l, name: CHROME[l]!.languageName, href: licensesUrl(l), current: l === lang }))
    const shared = renderChrome({ lang, url, supportHref: hubUrl(lang), languages, current: null })
    return [
      head({ lang, title: `${copy.title} — Tenshu`, description: copy.description, css: cssHref, canonical, extra }),
      `<body class="${shared.bodyClass}">`,
      siteHeader(CHROME[lang]!, shared),
      `<main id="main" class="${MAIN_CLASSES}">`,
      `<div class="page-head"><h1 class="${H1_CLASSES}">${escapeHtml(copy.title)}</h1></div>`,
      body,
      '</main>',
      siteFooter(shared),
      '</body>',
      '</html>',
      '',
    ].join('\n')
  }
  const alternates = LICENSES_LANGUAGES.map((l) => `<link rel="alternate" hreflang="${l}" href="${SITE_ORIGIN}${licensesUrl(l)}">`).join('\n')
  const pages = LICENSES_LANGUAGES.map((lang) => ({
    file: `legal/licenses/${lang}/index.html`,
    content: page(lang, licensesUrl(lang), licensesUrl(lang), alternates, `<article class="prose licenses">\n${licensesHtml(LICENSES[lang]!, packages, SDK_BUNDLE_LIBRARIES)}\n</article>`),
  }))
  const target = licensesUrl('en')
  const redirect = page(
    'en',
    '/legal/licenses',
    target,
    `<meta name="robots" content="noindex">\n<meta http-equiv="refresh" content="0; url=${target}">`,
    `<p><a href="${target}">${escapeHtml(LICENSES.en!.title)}</a></p>`,
  )
  return [...pages, { file: 'legal/licenses/index.html', content: redirect }]
}
