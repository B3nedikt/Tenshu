/**
 * Framed store screenshots: a capture in its device's bezel, a caption above it, written where
 * scripts/store-publish.sh picks it up as its own device id (`<device>framed`, frames.ts).
 */
import { existsSync, mkdirSync, readFileSync, readdirSync, renameSync, writeFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import type { Browser } from 'playwright-core'
import { renderPng } from './browser.ts'
import { FRAMED_SUFFIX, FrameError, SCREENSHOT_CACHE, STORE_DIR, parseCaptions, pngSize, sha256, type ReadyFrame } from './frames.ts'
import { frameHtml } from './template.ts'

export type Platform = 'ios' | 'android'

export interface RenderOne {
  frame: ReadyFrame
  bezel: Uint8Array
  capture: Uint8Array
  caption: string | null
  tokensCss?: string
}

export async function renderFrame(browser: Browser, r: RenderOne): Promise<Uint8Array> {
  const bezelSize = pngSize(r.bezel)
  const { screen } = r.frame
  if (screen.x + screen.width > bezelSize.width || screen.y + screen.height > bezelSize.height) {
    throw new FrameError(`frame '${r.frame.id}': the screen area lies outside the ${bezelSize.width}×${bezelSize.height} bezel — re-run inspect`)
  }
  const capture = pngSize(r.capture)
  const want = screen.width / screen.height
  const got = capture.width / capture.height
  if (Math.abs(want - got) / want > 0.02) {
    throw new FrameError(
      `frame '${r.frame.id}': the capture is ${capture.width}×${capture.height} but the screen area ` +
        `${screen.width}×${screen.height} — a different aspect ratio would stretch the app. Wrong device?`,
    )
  }
  const html = frameHtml({ output: r.frame.output, bezel: { png: r.bezel, ...bezelSize }, screen, capture: r.capture, caption: r.caption, tokensCss: r.tokensCss })
  const png = await renderPng(browser, html, r.frame.output.width, r.frame.output.height)
  const size = pngSize(png)
  if (size.width !== r.frame.output.width || size.height !== r.frame.output.height) {
    throw new FrameError(`rendered ${size.width}×${size.height}, expected ${r.frame.output.width}×${r.frame.output.height}`)
  }
  return png
}

interface ScreenshotsManifest {
  base: string
  screens: Record<string, { sha256: string; width: number; height: number }>
}

/**
 * The plain capture of one screen: the local one store-screenshots.sh wrote, else the published one,
 * fetched by its manifest sha256 into the cache scripts/store-listing.py shares, and verified.
 */
export async function captureFor(app: string, device: string, lang: string, screen: string, platform: Platform = 'ios'): Promise<{ bytes: Uint8Array; from: string }> {
  const local = join(STORE_DIR, app, 'screenshots', platform, device, lang, `${screen}.png`)
  if (existsSync(local)) return { bytes: new Uint8Array(readFileSync(local)), from: local }
  const manifest = JSON.parse(readFileSync(join(STORE_DIR, app, 'screenshots.manifest.json'), 'utf8')) as ScreenshotsManifest
  const key = `${platform}/${device}/${lang}/${screen}`
  const entry = manifest.screens[key]
  if (!entry) throw new FrameError(`no capture for ${app} ${key}: neither ${local} nor a manifest entry`)
  const cached = join(SCREENSHOT_CACHE, `${entry.sha256}.png`)
  if (existsSync(cached)) {
    const bytes = new Uint8Array(readFileSync(cached))
    if (sha256(bytes) === entry.sha256) return { bytes, from: cached }
  }
  const url = `${manifest.base}${entry.sha256}.png`
  const response = await fetch(url)
  if (!response.ok) throw new FrameError(`${url}: HTTP ${response.status}`)
  const bytes = new Uint8Array(await response.arrayBuffer())
  if (sha256(bytes) !== entry.sha256) throw new FrameError(`${url} does NOT hash to its manifest entry — nothing rendered`)
  mkdirSync(SCREENSHOT_CACHE, { recursive: true })
  writeFileSync(`${cached}.part`, bytes)
  renameSync(`${cached}.part`, cached)
  return { bytes, from: url }
}

/** store/<app>/<locale>/captions.txt for the store locale whose manifest language is `lang`. */
export function captionsFor(app: string, lang: string): { locale: string; captions: Map<string, string> } {
  const listing = JSON.parse(readFileSync(join(STORE_DIR, app, 'listing.json'), 'utf8')) as { ios: { languages: Record<string, string> } }
  const locale = Object.entries(listing.ios.languages).find(([, l]) => l === lang)?.[0]
  if (!locale) throw new FrameError(`store/${app}/listing.json has no store locale for language '${lang}'`)
  const path = join(STORE_DIR, app, locale, 'captions.txt')
  if (!existsSync(path)) throw new FrameError(`${path} is missing — write the captions, or pass --no-caption`)
  return { locale, captions: parseCaptions(readFileSync(path, 'utf8')) }
}

/** The screens that have a plain capture locally or in the manifest, in screen order. */
export function availableScreens(app: string, device: string, lang: string, platform: Platform = 'ios'): string[] {
  const names = new Set<string>()
  const local = join(STORE_DIR, app, 'screenshots', platform, device, lang)
  if (existsSync(local)) for (const f of readdirSync(local)) if (/^\d\d-[a-z0-9-]+\.png$/.test(f)) names.add(f.slice(0, -4))
  const manifestPath = join(STORE_DIR, app, 'screenshots.manifest.json')
  if (existsSync(manifestPath)) {
    const manifest = JSON.parse(readFileSync(manifestPath, 'utf8')) as ScreenshotsManifest
    const prefix = `${platform}/${device}/${lang}/`
    for (const key of Object.keys(manifest.screens)) {
      const rest = key.slice(prefix.length)
      if (key.startsWith(prefix) && /^\d\d-[a-z0-9-]+$/.test(rest)) names.add(rest)
    }
  }
  return [...names].sort()
}

/** store/<app>/screenshots/<platform>/<device>framed/<lang>/NN-screen.png — gitignored; store-publish.sh publishes it. */
export function framedPath(app: string, device: string, lang: string, screen: string, platform: Platform = 'ios'): string {
  return join(STORE_DIR, app, 'screenshots', platform, `${device}${FRAMED_SUFFIX}`, lang, `${screen}.png`)
}

/**
 * The Play listing's captions: store/<app>/play/<locale>/captions.txt, beside that locale's Play copy
 * (store-listings.md §8) and apart from the App Store's — the two stores' captions differ.
 */
export function playCaptionsFor(app: string, lang: string): { locale: string; captions: Map<string, string> } {
  const base = join(STORE_DIR, app, 'play')
  const locales = existsSync(base) ? readdirSync(base).filter((d) => d.split('-')[0] === lang && existsSync(join(base, d, 'title.txt'))) : []
  // en-US for en, de-DE for de: never whichever regional variant sorts first (en-GB).
  const preferred = lang === 'en' ? 'en-US' : `${lang}-${lang.toUpperCase()}`
  const locale = locales.includes(preferred) ? preferred : locales.length === 1 ? locales[0] : undefined
  if (locales.length > 1 && !locale) throw new FrameError(`store/${app}/play has several '${lang}' locales (${locales.join(', ')}) and none is ${preferred}`)
  if (!locale) throw new FrameError(`store/${app}/play has no Play locale for language '${lang}'`)
  const path = join(base, locale, 'captions.txt')
  if (!existsSync(path)) throw new FrameError(`${path} is missing — write the captions, or pass --no-caption`)
  return { locale, captions: parseCaptions(readFileSync(path, 'utf8')) }
}

export function writePng(path: string, png: Uint8Array): void {
  mkdirSync(dirname(path), { recursive: true })
  writeFileSync(path, png)
}
