// @vitest-environment node
//
// The support pages' Light / Dark / Auto switch, light/dark screenshots and step layout:
//   - the theme scripts are on every page: the head script before the stylesheet, the switch
//     hidden until its script runs; with JavaScript off nothing is set and the page follows the OS
//     (what the scripts DO in a browser: src/theme/theme.test.ts, under jsdom);
//   - each slot shows the capture matching the effective theme, and the light one in both themes
//     when no dark one is published;
//   - every key the committed manifests hold (the published light captures) still resolves.
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'
import { THEME_BOOT_SCRIPT, THEME_STORAGE_KEY, parseThemeChoice } from '../../shared/theme.ts'
import {
  MANIFEST_KEY_RE,
  SUPPORT_DEVICE,
  buildSupportSite,
  loadShotManifest,
  resolveShot,
  shotHtml,
  shotKey,
  wrapSteps,
  type ShotSlot,
} from './generate.ts'
import { THEME_SWITCH_SCRIPT } from './themeSwitch.ts'
import { loadSupportChrome } from './loadChrome.ts'

// The chrome loaded exactly as the build loads it (Vite's module runner), not a test double.
const chrome = await loadSupportChrome('test')
const site = await buildSupportSite({ chrome })
const page = site.pages.find((p) => p.url === '/apps/drive/support/ios/en')!
const css = site.files.find((f) => f.file.startsWith('assets/support-'))!.content
/** The landing footer's switch in its static form, as the pages in [lang] carry it. */
const switchHtmlIn = (lang: string) =>
  /<fieldset id="theme-switch"[\s\S]*?<\/fieldset>/.exec(
    chrome({ lang, url: '/apps/drive/support', supportHref: '/apps/drive/support', languages: [] }).footer,
  )![0]
const switchHtml = switchHtmlIn('en')

describe('theme switch on the support pages', () => {
  it('every page carries the shared boot script verbatim in <head>, before the stylesheet', () => {
    for (const file of site.files.filter((f) => f.file.endsWith('.html'))) {
      const head = file.content.slice(0, file.content.indexOf('</head>'))
      const script = head.indexOf(`<script>${THEME_BOOT_SCRIPT}</script>`)
      expect(script, file.file).toBeGreaterThan(0)
      expect(script, file.file).toBeLessThan(head.indexOf('rel="stylesheet"'))
    }
  })

  it('every page carries the switch (hidden) and its wiring script, after the markup it wires', () => {
    for (const file of site.files.filter((f) => f.file.endsWith('.html'))) {
      const markup = file.content.indexOf(switchHtmlIn(/<html lang="(\w+)">/.exec(file.content)![1]!))
      expect(markup, file.file).toBeGreaterThan(0)
      expect(file.content.indexOf(`<script>${THEME_SWITCH_SCRIPT}</script>`), file.file).toBeGreaterThan(markup)
    }
    expect(THEME_SWITCH_SCRIPT).toContain(JSON.stringify(THEME_STORAGE_KEY))
  })

  it('ships no data-theme: without JavaScript the tokens follow the OS (Auto) and the switch is hidden', () => {
    expect(page.html).toContain('<html lang="en">')
    expect(page.html).not.toMatch(/<html[^>]*data-theme/)
    expect(page.html).toMatch(/<fieldset id="theme-switch" hidden=""/)
    // Auto is the checked option in the markup itself.
    expect(page.html).toMatch(/<input type="radio" [^>]*name="theme" checked="" value="auto"\/>/)
    expect([...page.html.matchAll(/<input [^>]*checked/g)]).toHaveLength(1)
    // The stylesheet decides by media query when the attribute is absent, and the attribute wins.
    expect(css).toMatch(/@media \(prefers-color-scheme: dark\)\s*\{\s*:root:not\(\[data-theme="light"\]\)/)
    expect(css).toMatch(/:root\[data-theme="dark"\]\s*\{\s*color-scheme: dark;/)
    expect(css).toMatch(/:root\[data-theme="light"\]\s*\{\s*color-scheme: light;/)
    // Tailwind's preflight keeps a [hidden] element hidden against the switch's own `flex`.
    expect(css).toMatch(/\[hidden\]:where\(:not\(\[hidden="until-found"\]\)\)\s*\{\s*display: none !important/)
  })

  it('is a labelled radio group: a legend and three named options', () => {
    const html = switchHtml
    expect(html).toMatch(/^<fieldset[^>]*><legend[^>]*>Theme<\/legend>/)
    expect([...html.matchAll(/<input type="radio" [^>]*name="theme"[^>]* value="(\w+)"\/><span[^>]*>(\w+)<\/span>/g)].map((m) => [m[1], m[2]])).toEqual([
      ['light', 'Light'],
      ['dark', 'Dark'],
      ['auto', 'Auto'],
    ])
    expect(parseThemeChoice('dark')).toBe('dark')
    expect(parseThemeChoice(null)).toBe('auto')
    expect(parseThemeChoice('auto')).toBe('auto')
  })
})

describe('light and dark screenshots', () => {
  const BASE = 'https://assets.tenshu.app/sha256/'
  const SHA = (c: string) => c.repeat(64)
  const entry = (c: string) => ({ sha256: SHA(c), width: 1320, height: 2868, bytes: 1000 })
  const image = (c: string) => ({ ...entry(c), url: `${BASE}${SHA(c)}.png` })
  const slot = (light: string | null, dark: string | null): ShotSlot => ({
    app: 'control',
    key: 'ios/iphone69/en/01-welcome',
    darkKey: 'ios/iphone69/en/dark/01-welcome',
    image: light ? image(light) : null,
    dark: dark ? image(dark) : null,
  })

  it('the dark key adds a `dark` segment after the language; the light key is the published form', () => {
    expect(shotKey('ios', 'iphone69', 'en', '01-welcome', 'light')).toBe('ios/iphone69/en/01-welcome')
    expect(shotKey('ios', 'iphone69', 'en', '01-welcome', 'dark')).toBe('ios/iphone69/en/dark/01-welcome')
    expect(MANIFEST_KEY_RE.test('ios/iphone69/en/01-welcome')).toBe(true)
    expect(MANIFEST_KEY_RE.test('ios/iphone69/en/dark/01-welcome')).toBe(true)
    expect(MANIFEST_KEY_RE.test('ios/iphone69/de-AT/dark/01-welcome')).toBe(true)
    // One spelling per image: no explicit light segment, no appearance anywhere else.
    expect(MANIFEST_KEY_RE.test('ios/iphone69/en/light/01-welcome')).toBe(false)
    expect(MANIFEST_KEY_RE.test('ios/iphone69/dark/en/01-welcome')).toBe(false)
  })

  it('both published: both images, each marked with its theme, both lazy with their size', () => {
    const html = shotHtml(slot('a', 'b'), 'Welcome', 'pending')
    expect(html).toBe(
      '<span class="shot-frame">' +
        `<img class="shot shot--light" src="${BASE}${SHA('a')}.png" alt="Welcome" width="1320" height="2868" loading="lazy" decoding="async">` +
        `<img class="shot shot--dark" src="${BASE}${SHA('b')}.png" alt="Welcome" width="1320" height="2868" loading="lazy" decoding="async">` +
        '</span>',
    )
  })

  it('dark missing: the light image alone, unmarked, so it shows in both themes', () => {
    const html = shotHtml(slot('a', null), 'Welcome', 'pending')
    expect(html).toBe(`<span class="shot-frame"><img class="shot" src="${BASE}${SHA('a')}.png" alt="Welcome" width="1320" height="2868" loading="lazy" decoding="async"></span>`)
    expect(html).not.toMatch(/shot--(light|dark)|shot--pending/)
  })

  it('light missing: the dark image alone in both themes; neither: a placeholder', () => {
    expect(shotHtml(slot(null, 'b'), 'W', 'p')).toBe(
      `<span class="shot-frame"><img class="shot" src="${BASE}${SHA('b')}.png" alt="W" width="1320" height="2868" loading="lazy" decoding="async"></span>`,
    )
    expect(shotHtml(slot(null, null), 'W', 'Soon')).toContain('<span class="shot-frame"><span class="shot shot--pending" role="img" aria-label="W"')
  })

  it('the stylesheet shows the capture of the effective theme: OS by default, data-theme over it', () => {
    expect(css).toMatch(/\.shot--dark\s*\{\s*display: none;/)
    expect(css).toMatch(/@media \(prefers-color-scheme: dark\)\s*\{\s*:root:not\(\[data-theme="light"\]\) \.shot--light\s*\{\s*display: none;/)
    expect(css).toMatch(/:root\[data-theme="dark"\] \.shot--light\s*\{\s*display: none;/)
    expect(css).toMatch(/:root\[data-theme="dark"\] \.shot--dark\s*\{\s*display: block;/)
  })

  it('a page built from a manifest with a dark capture for one slot shows both for it, light alone elsewhere', async () => {
    const dir = mkdtempSync(join(tmpdir(), 'support-store-'))
    try {
      mkdirSync(join(dir, 'control'))
      writeFileSync(
        join(dir, 'control', 'screenshots.manifest.json'),
        JSON.stringify({
          version: 1,
          base: BASE,
          screens: {
            'ios/iphone69/en/01-welcome': entry('a'),
            'ios/iphone69/en/dark/01-welcome': entry('b'),
            'ios/iphone69/en/03-recovery-phrase': entry('c'),
          },
        }),
      )
      const built = await buildSupportSite({ chrome, storeDir: dir })
      const ios = built.pages.find((p) => p.url === '/apps/control/support/ios/en')!
      expect(ios.html).toContain(`<img class="shot shot--light" src="${BASE}${SHA('a')}.png"`)
      expect(ios.html).toContain(`<img class="shot shot--dark" src="${BASE}${SHA('b')}.png"`)
      expect(ios.html).toContain(`<img class="shot" src="${BASE}${SHA('c')}.png"`)
      // Both are in the markup, in pairs; the stylesheet picks one.
      expect(ios.html.split('shot--light').length).toBe(ios.html.split('shot--dark').length)
    } finally {
      rmSync(dir, { recursive: true, force: true })
    }
  })

  it('every key the committed manifests hold still resolves to the same image (no republish needed)', () => {
    let checked = 0
    for (const app of ['control', 'drive']) {
      const manifest = loadShotManifest(app)
      expect(manifest, app).not.toBeNull()
      for (const [key, published] of Object.entries(manifest!.screens)) {
        const [platform, device, lang, ...rest] = key.split('/')
        const dark = rest[0] === 'dark'
        const screen = rest.at(-1)!
        if (SUPPORT_DEVICE[platform as 'ios'] !== device) continue // e.g. the iPad captures
        const resolved = resolveShot((a) => (a === app ? manifest : null), app, platform as 'ios', lang!, undefined, screen)
        expect((dark ? resolved.dark : resolved.image)?.sha256, key).toBe(published.sha256)
        checked++
      }
    }
    // 16 iPhone captures were published light-only before dark existed (9 Control + 7 Drive).
    expect(checked).toBeGreaterThanOrEqual(16)
  })

  it('the published pages today: every published slot carries its light and its dark capture', () => {
    // Every published iPhone slot has a dark capture too, so a slot renders
    // as a light/dark pair; a plain single <img class="shot"> would mean a slot lost its dark half.
    const published = page.screenshotSlots.filter((s) => s.image !== null)
    expect(published.length).toBeGreaterThan(0)
    for (const slot of published) expect(slot.dark, slot.key ?? undefined).not.toBeNull()
    expect(page.html).toContain('<img class="shot shot--light" src="https://assets.tenshu.app/sha256/')
    expect(page.html).toContain('<img class="shot shot--dark" src="https://assets.tenshu.app/sha256/')
    expect(page.html).not.toContain('<img class="shot" src=')
  })

  it('with WebP copies: their srcset sized to the step frame, never the PNG as a candidate, the PNG still the src', () => {
    const copies = { ...image('a'), variants: [{ url: `${BASE}${SHA('w')}.webp`, width: 540 }, { url: `${BASE}${SHA('x')}.webp`, width: 960 }] }
    const html = shotHtml({ ...slot(null, null), image: copies }, 'W', 'p')
    expect(html).toBe(
      `<span class="shot-frame"><img class="shot" src="${BASE}${SHA('a')}.png" srcset="${BASE}${SHA('w')}.webp 540w, ${BASE}${SHA('x')}.webp 960w" ` +
        `sizes="(min-width: 52rem) 15rem, 17.5rem" style="aspect-ratio: 1320 / 2868" alt="W" width="1320" height="2868" loading="lazy" decoding="async"></span>`,
    )
  })

  it("a page's first slot is eager in both themes (the markup cannot know which shows); every later one lazy", () => {
    expect(shotHtml(slot('a', 'b'), 'W', 'p', true).match(/loading="eager"/g)).toHaveLength(2)
    expect(shotHtml(slot('a', 'b'), 'W', 'p', true)).not.toContain('loading="lazy"')
    const eager = [...page.html.matchAll(/<img [^>]*loading="eager"/g)]
    expect(eager).toHaveLength(2)
    expect(page.html.indexOf('loading="eager"')).toBeLessThan(page.html.indexOf('loading="lazy"'))
  })

  it('the built pages name the committed WebP copies (store/<app>/screenshots.webp.json)', () => {
    expect(page.html).toMatch(/<img class="shot shot--light" src="[^"]+\.png" srcset="[^"]+\.webp 540w, [^"]+\.webp 960w" sizes="/)
  })

  it('reads the sidecar into the manifest; a malformed one, or one on another base, fails the build', () => {
    const dir = mkdtempSync(join(tmpdir(), 'support-store-'))
    try {
      mkdirSync(join(dir, 'control'))
      writeFileSync(join(dir, 'control', 'screenshots.manifest.json'), JSON.stringify({ version: 1, base: BASE, screens: { 'ios/iphone69/en/01-welcome': entry('a') } }))
      const sidecar = (doc: unknown) => writeFileSync(join(dir, 'control', 'screenshots.webp.json'), JSON.stringify(doc))
      const d = (c: string, width: number) => ({ width, height: 2 * width, sha256: SHA(c), bytes: 10 })
      sidecar({ version: 1, base: BASE, derivatives: { [SHA('a')]: [d('d', 960), d('c', 540)] } })
      const manifest = loadShotManifest('control', dir)!
      const resolved = resolveShot(() => manifest, 'control', 'ios', 'en', undefined, '01-welcome')
      expect(resolved.image!.variants).toEqual([
        { url: `${BASE}${SHA('c')}.webp`, width: 540 },
        { url: `${BASE}${SHA('d')}.webp`, width: 960 },
      ])
      sidecar({ version: 1, base: 'https://elsewhere.example/sha256/', derivatives: {} })
      expect(() => loadShotManifest('control', dir)).toThrow(/is not the manifest's/)
      sidecar({ version: 1, base: BASE, derivatives: { [SHA('a')]: [{ width: 540 }] } })
      expect(() => loadShotManifest('control', dir)).toThrow(/bad derivatives/)
    } finally {
      rmSync(dir, { recursive: true, force: true })
    }
  })
})

describe('steps (text beside the screenshot)', () => {
  const MEDIA = '<!--step-media--><img class="shot" src="x" alt="a" width="1" height="2"><!--/step-media-->'

  it('a list item ending in a screenshot becomes a step; one without stays a plain item', () => {
    const html = wrapSteps(`<ol>\n<li><strong>One</strong> text\n${MEDIA}</li>\n<li>Two, no picture</li>\n</ol>`)
    expect(html).toBe(
      '<ol>\n<li class="step"><div class="step__body"><div class="step__text"><strong>One</strong> text</div>' +
        '<span class="step__media"><img class="shot" src="x" alt="a" width="1" height="2"></span></div></li>\n' +
        '<li>Two, no picture</li>\n</ol>',
    )
  })

  it('handles a loose item (text in <p>) and never spans two items', () => {
    expect(wrapSteps(`<li><p>Loose\n${MEDIA}</p>\n</li>`)).toBe(
      '<li class="step"><div class="step__body"><div class="step__text"><p>Loose</p></div>' +
        '<span class="step__media"><img class="shot" src="x" alt="a" width="1" height="2"></span></div></li>',
    )
    const two = wrapSteps(`<li>plain</li><li>pic ${MEDIA}</li>`)
    expect(two.startsWith('<li>plain</li><li class="step">')).toBe(true)
  })

  it('a screenshot outside a list item keeps its place, without the markers', () => {
    expect(wrapSteps(`<p>Look: ${MEDIA}</p>`)).toBe('<p>Look: <span class="step__media"><img class="shot" src="x" alt="a" width="1" height="2"></span></p>')
  })

  it('the iOS Get started steps are steps on the real pages, and no marker leaks', () => {
    for (const p of site.pages) expect(p.html, p.url).not.toContain('step-media-->')
    const getStarted = page.html.slice(page.html.indexOf('id="get-started"'), page.html.indexOf('id="privacy"'))
    expect([...getStarted.matchAll(/<li class="step">/g)]).toHaveLength(5)
  })

  it('wide screens put text and screenshot side by side; screenshots are capped in height', () => {
    // Lightning CSS (buildCss) may reorder a rule's declarations, so each one is matched on its own.
    const wide = /@media \(min-width: 52rem\) \{\s*\.step__body \{([^}]*)\}/.exec(css)?.[1] ?? ''
    expect(wide).toMatch(/display: grid;/)
    expect(wide).toMatch(/grid-template-columns: minmax\(0, 1fr\) var\(--shot-w\);/)
    // 15rem wide × 2868/1320 ≈ 521px tall — inside the 480–560px band.
    expect(css).toMatch(/--shot-w: 15rem;/)
    expect(css).toMatch(/\.shot--pending\s*\{\s*aspect-ratio: 1320 \/ 2868;/)
  })
})

describe('the boot script itself', () => {
  function run(stored: string | null, opts: { throws?: boolean } = {}) {
    const attrs: Record<string, string> = {}
    const document = { documentElement: { setAttribute: (k: string, v: string) => (attrs[k] = v) } }
    const localStorage = {
      getItem: (k: string) => {
        if (opts.throws) throw new Error('SecurityError')
        return k === THEME_STORAGE_KEY ? stored : null
      },
    }
    new Function('localStorage', 'document', THEME_BOOT_SCRIPT)(localStorage, document)
    return attrs['data-theme'] ?? null
  }

  it('sets data-theme only for light or dark, and survives blocked storage', () => {
    expect(run('light')).toBe('light')
    expect(run('dark')).toBe('dark')
    expect(run(null)).toBeNull()
    expect(run('auto')).toBeNull()
    expect(run('<script>')).toBeNull()
    expect(run('dark', { throws: true })).toBeNull()
  })
})
