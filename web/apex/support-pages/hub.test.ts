// @vitest-environment node
//
// The support hub (`/apps/support/<lang>`) and Getting started (store-presence-and-support-pages.md
// §4.7): the static markup is the product — the carousel script only rearranges it — so these pin
// what a crawler, App Review and a visitor without JavaScript get. What the script does in a
// browser: hub-script.test.ts.
import { existsSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'
import { APEX_DIR, buildSupportSite, loadContent, loadShotManifest } from './generate.ts'
import { HUB_APPS, HUB_COPY, HUB_LANGUAGES, HUB_SCRIPT_BODY, defaultShotPlatform } from './hub.ts'
import { ADVANCE_MS } from '../src/landing/carousel/carouselModel.ts'
import { loadSupportChrome } from './loadChrome.ts'

const REPO_ROOT = join(APEX_DIR, '..', '..')
const chrome = await loadSupportChrome('test')
const site = await buildSupportSite({ chrome })
const hubs = site.hubPages.filter((p) => p.kind === 'hub')
const guides = site.hubPages.filter((p) => p.kind === 'getting-started')
const fileNames = site.files.map((f) => f.file)

/** The `<li class="hub-entry…">` for [id] on [html]. */
const entryOf = (html: string, id: string) =>
  new RegExp(`<li class="hub-entry[^"]*"><(?:a|span) [^>]*data-hub-name="${id}"[\\s\\S]*?</li>`).exec(html)?.[0]

describe('the support hub', () => {
  it('exists in every language, plus a language-picking entry point; /support is NOT generated', () => {
    expect(hubs.map((h) => h.url)).toEqual(HUB_LANGUAGES.map((l) => `/apps/support/${l}`))
    expect(fileNames).toContain('apps/support/index.html')
    expect(fileNames).toContain('apps/support/getting-started/index.html')
    // ⚠ Reserved for a possible support app: nothing written there.
    expect(fileNames.filter((f) => f === 'support' || f.startsWith('support/'))).toEqual([])
    for (const file of site.files) expect(file.content, file.file).not.toMatch(/href="\/support["/#?]/)
  })

  it('lists all ten apps and Getting started, in order', () => {
    for (const hub of hubs) {
      const ids = [...hub.html.matchAll(/data-hub-name="([^"]+)"/g)].map((m) => m[1])
      expect(ids, hub.url).toEqual(['getting-started', ...HUB_APPS.map((a) => a.app)])
      expect(ids).toHaveLength(11)
    }
  })

  it('every app with a support page is a real link to it, in the hub\'s language', () => {
    const enabled = HUB_APPS.filter((a) => a.support)
    expect(enabled.map((a) => a.app)).toEqual(['control', 'drive', 'photos', 'calendar', 'contacts', 'fit'])
    for (const hub of hubs) {
      for (const app of enabled) {
        const entry = entryOf(hub.html, app.app)!
        expect(entry, `${hub.url} ${app.app}`).toBeDefined()
        // The name, every screenshot and the button all link the same page, which the build made.
        const hrefs = [...entry.matchAll(/href="([^"]+)"/g)].map((m) => m[1]!)
        expect(hrefs, hub.url).toHaveLength(2 + (entry.match(/data-hub-slide=""/g) ?? []).length)
        expect(new Set(hrefs).size, hub.url).toBe(1)
        expect(hrefs[0]).toMatch(new RegExp(`^/apps/${app.app}/support/(ios|android|macos|web)/${hub.lang}$`))
        expect(fileNames).toContain(`${hrefs[0]!.slice(1)}/index.html`)
        expect(entry).toContain(`>${HUB_COPY[hub.lang]!.open(app.name)}</a>`)
        expect(entry).not.toContain('hub-entry--soon')
      }
      const gs = entryOf(hub.html, 'getting-started')!
      expect(gs).toContain(`href="/apps/support/getting-started/${hub.lang}"`)
      expect(gs).toContain(`>${HUB_COPY[hub.lang]!.gettingStarted}</a>`)
    }
  })

  it('a coming-soon app is greyed out, marked "Coming soon", and has no link', () => {
    for (const hub of hubs) {
      for (const app of HUB_APPS.filter((a) => !a.support)) {
        const entry = entryOf(hub.html, app.app)!
        expect(entry, `${hub.url} ${app.app}`).toContain('class="hub-entry hub-entry--soon"')
        // Kept for screen readers; visually it is one shared note under the lists.
        expect(entry).toContain(`<span class="hub-soon">${HUB_COPY[hub.lang]!.comingSoon}</span>`)
        expect(entry).not.toMatch(/<a[\s>]/)
        expect(entry).not.toContain('href=')
        // No screenshots: a neutral placeholder card, never a broken image. (Its name's icon is
        // the one image it has.)
        expect(entry).not.toMatch(/<img [^>]*class="shot/)
        expect(entry).toContain('class="hub-placeholder"')
      }
    }
  })

  it('every name carries its icon, light and dark (Getting started: Tenshu\'s mark), and every icon file exists', () => {
    for (const hub of hubs) {
      for (const id of ['getting-started', ...HUB_APPS.map((a) => a.app)]) {
        const name = new RegExp(`<(?:a|span) [^>]*data-hub-name="${id}"[^>]*>([\\s\\S]*?)</(?:a|span)>`).exec(hub.html)![1]!
        const icons = [...name.matchAll(/<img class="hub-icon hub-icon--(light|dark) hub-name__icon" src="([^"]+)"/g)].map((m) => [m[1], m[2]])
        const file = id === 'getting-started' ? ['/icons/tenshu.svg', '/icons/tenshu.svg'] : [`/icons/apps/${id}.webp`, `/icons/apps/${id}-dark.webp`]
        expect(icons, `${hub.url} ${id}`).toEqual([['light', file[0]], ['dark', file[1]]])
        for (const [, src] of icons) expect(existsSync(join(APEX_DIR, 'public', src!)), src).toBe(true)
      }
    }
  })

  // ⚠ The one rule for which device the carousels show first; the hub script runs its source.
  it('defaultShotPlatform: an iPhone on macOS, iOS and iPadOS; Android on Windows, Linux, Android and ChromeOS', () => {
    const ua = {
      mac: 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Safari/605.1.15',
      iphone: 'Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148',
      windows: 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Safari/537.36',
      linux: 'Mozilla/5.0 (X11; Linux x86_64; rv:133.0) Gecko/20100101 Firefox/133.0',
      android: 'Mozilla/5.0 (Linux; Android 15; Pixel 9) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Mobile Safari/537.36',
      chromeos: 'Mozilla/5.0 (X11; CrOS x86_64 14541.0.0) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Safari/537.36',
    }
    // User agent string only (Safari, Firefox): iPadOS reports the Mac one.
    expect(Object.fromEntries(Object.entries(ua).map(([k, v]) => [k, defaultShotPlatform('', v)]))).toEqual({
      mac: 'ios', iphone: 'ios', windows: 'android', linux: 'android', android: 'android', chromeos: 'android',
    })
    // userAgentData.platform (Chromium) wins over the string.
    for (const p of ['macOS', 'iOS', 'iPadOS']) expect(defaultShotPlatform(p, ua.windows), p).toBe('ios')
    for (const p of ['Windows', 'Linux', 'Android', 'Chrome OS', 'Unknown']) expect(defaultShotPlatform(p, ua.mac), p).toBe('android')
    expect(defaultShotPlatform()).toBe('android')
    // One copy: the hub's script carries this function's own source.
    expect(HUB_SCRIPT_BODY).toContain(defaultShotPlatform.toString())
  })

  it('carries the device-frame trademark note under the carousel, in every language', () => {
    for (const hub of hubs) expect(hub.html).toContain(`<p class="hub-footnote">${HUB_COPY[hub.lang]!.frameNote}</p>`)
    expect(HUB_COPY.en!.frameNote).toBe('Device frames are illustrations. iPhone is a trademark of Apple Inc., Android is a trademark of Google LLC. Tenshu is not affiliated with or endorsed by Apple or Google.')
  })

  it('one shared note explains the greyed-out apps', () => {
    for (const hub of hubs) {
      expect(hub.html.match(/class="hub-note"/g) ?? []).toHaveLength(1)
      expect(hub.html).toContain(HUB_COPY[hub.lang]!.soonNote)
    }
  })

  it('the flag and the content agree: an app is linked exactly when it has support pages', () => {
    const withContent = new Set(loadContent().map((c) => c.meta.app))
    for (const app of HUB_APPS) expect(app.support, app.app).toBe(withContent.has(app.app))
    for (const app of HUB_APPS) expect(existsSync(join(APEX_DIR, 'content', 'apps', app.app, 'support')), app.app).toBe(app.support)
  })

  // A language shows its own captures when it has any (resolveShot): German has light ones only
  // (its store listings are light), so its hub shows that one capture in both themes rather
  // than an English dark one. French has none and borrows English, both themes.
  it('shows published screenshots as R2 images: both themes where the language has both', () => {
    const R2 = 'src="https:\\/\\/assets\\.tenshu\\.app\\/sha256\\/[0-9a-f]{64}\\.png"'
    const pair = [new RegExp(`<img class="shot shot--light" ${R2}`), new RegExp(`<img class="shot shot--dark" ${R2}`)]
    const single = new RegExp(`<img class="shot" ${R2}`)
    for (const hub of hubs) {
      for (const id of ['getting-started', 'control', 'drive']) {
        const entry = entryOf(hub.html, id)!
        if (hub.lang === 'de') {
          expect(entry, `${hub.lang} ${id}`).toMatch(single)
          expect(entry, `${hub.lang} ${id}`).not.toMatch(/shot--dark/)
        } else {
          for (const re of pair) expect(entry, `${hub.lang} ${id}`).toMatch(re)
        }
      }
      // Three per platform: the Android viewer first (what no-JS shows), then the iPhone one —
      // every one of them a pair, or (German) every one a single own capture.
      const drive = entryOf(hub.html, 'drive')!
      expect(drive.match(/data-hub-slide=/g), hub.lang).toHaveLength(6)
      if (hub.lang === 'de') {
        expect(drive.match(/<img class="shot" /g), hub.lang).toHaveLength(6)
        expect(drive, hub.lang).not.toMatch(/shot--dark/)
      } else {
        expect(drive.match(/<img class="shot shot--dark" /g), hub.lang).toHaveLength(6)
      }
    }
  })

  it('without JavaScript: every screenshot side by side, the rotation controls hidden, no carousel semantics', () => {
    for (const hub of hubs) {
      const entry = entryOf(hub.html, 'drive')!
      // The Android viewer is the first, the one appCarousel.css shows without the script.
      expect(entry, hub.url).toMatch(/<div class="hub-views"><div class="hub-viewer" data-hub-viewer="" data-hub-platform="android">/)
      const drive = /data-hub-platform="android">[\s\S]*?(?=data-hub-platform="ios")/.exec(entry)![0]
      expect(drive.match(/data-hub-slide=""/g), hub.url).toHaveLength(3)
      expect(drive).toContain(`aria-label="${HUB_COPY[hub.lang]!.screenshot(2, 3, 'Drive')}"`)
      // Dots and the pause button are in the markup for the script, hidden until it runs.
      expect(drive).toMatch(/<div class="hub-controls" hidden="">/)
      expect(drive.match(/<button type="button" class="hub-dot"/g)).toHaveLength(3)
      expect(drive).toContain(`data-label-play="${HUB_COPY[hub.lang]!.playShots}"`)
      const markup = hub.html.replace(/<script>[\s\S]*?<\/script>/g, '')
      expect(markup).not.toContain('aria-roledescription')
      expect(markup).not.toContain('is-current')
      expect(hub.html).not.toContain('data-stage-count')
      // The switch is in the markup for the script, hidden until it runs.
      expect(entry).toContain(`<div class="hub-platform" hidden="">`)
      // One screenshot: no controls at all.
      expect(entryOf(hub.html, 'getting-started')).not.toContain('hub-controls')
    }
  })

  it('is static first: the only scripts are inline, and none loads the app bundle', () => {
    for (const hub of hubs) {
      expect(hub.html, hub.url).not.toMatch(/<script [^>]*src=/)
      expect(hub.html, hub.url).not.toMatch(/type="module"|\/assets\/index-/)
      // Theme boot, the carousel, theme switch, language switch.
      expect([...hub.html.matchAll(/<script>/g)], hub.url).toHaveLength(4)
      expect(hub.html).toContain(HUB_SCRIPT_BODY)
      // One timer, for the screenshots inside the frame; the SELECTION never changes on its own
      // (hub-script.test.ts pins that). The interval is the landing page's. The narrow row's
      // indicator reads the scroll position once per frame (requestAnimationFrame, with a
      // setTimeout stand-in where there is none) — never a timer of its own.
      expect(HUB_SCRIPT_BODY.match(/setTimeout|setInterval|requestAnimationFrame/g)).toEqual(['setTimeout', 'requestAnimationFrame', 'setTimeout'])
      expect(HUB_SCRIPT_BODY).toContain('window.requestAnimationFrame||function(f){return setTimeout(f,16)}')
      expect(HUB_SCRIPT_BODY).toContain(`ADV=${ADVANCE_MS}`)
    }
  })

  it('the header\'s Support entry is the hub, marked current, and the language pickers link the hub\'s siblings', () => {
    for (const hub of hubs) {
      expect(hub.html).toMatch(new RegExp(`<a href="/apps/support/${hub.lang}" aria-current="page"[^>]*>${HUB_COPY[hub.lang]!.heading === 'Assistance' ? 'Assistance' : 'Support'}</a>`))
      for (const l of HUB_LANGUAGES) {
        expect(hub.html).toMatch(new RegExp(`<a [^>]*hreflang="${l}"[^>]*href="/apps/support/${l}"`, 'i'))
        expect(hub.html).toContain(`<link rel="alternate" hreflang="${l}" href="https://tenshu.app/apps/support/${l}">`)
      }
    }
  })
})

describe('navigation back to the hub', () => {
  it('every app support page has the breadcrumb Support › App › Platform', () => {
    expect(site.pages.length).toBeGreaterThan(0)
    for (const page of site.pages) {
      const crumb = /<nav class="breadcrumb" aria-label="([^"]+)"><ol>([\s\S]*?)<\/ol><\/nav>/.exec(page.html)
      expect(crumb, page.url).not.toBeNull()
      expect(crumb![1]).toBe(HUB_COPY[page.lang]!.breadcrumb)
      const items = [...crumb![2]!.matchAll(/<li>([\s\S]*?)<\/li>/g)].map((m) => m[1])
      const name = HUB_APPS.find((a) => a.app === page.app)!.name
      expect(items, page.url).toHaveLength(3)
      expect(items[0]).toMatch(new RegExp(`^<a href="/apps/support/${page.lang}">(Support|Assistance)</a>$`))
      expect(items[1]).toBe(`<a href="/apps/${page.app}/support">${name}</a>`)
      expect(items[2]).toMatch(/^<span aria-current="page">[^<]+<\/span>$/)
      // Above the title.
      expect(page.html.indexOf('class="breadcrumb"')).toBeLessThan(page.html.indexOf('<h1'))
    }
    const android = site.pages.find((p) => p.url === '/apps/drive/support/android/en')!
    expect(android.html).toContain(
      '<nav class="breadcrumb" aria-label="Breadcrumb"><ol><li><a href="/apps/support/en">Support</a></li><li><a href="/apps/drive/support">Drive</a></li><li><span aria-current="page">Android</span></li></ol></nav>',
    )
  })

  it('every page\'s header Support link goes to the hub — support pages, redirects, imprint, privacy policy, Getting started', () => {
    for (const file of site.files.filter((f) => f.file.endsWith('.html'))) {
      const header = file.content.slice(file.content.indexOf('<header'), file.content.indexOf('</header>'))
      const supports = [...header.matchAll(/<a href="([^"]+)"[^>]*>(Support|Assistance)<\/a>/g)].map((m) => m[1])
      // The bar's link and the phone menu's.
      expect(supports.length, file.file).toBeGreaterThanOrEqual(2)
      for (const href of supports) expect(href, file.file).toMatch(/^\/apps\/support(\/(en|de|fr))?$/)
      expect(header, file.file).not.toContain('/apps/drive/support"')
    }
  })

  it('the language redirect pages pick a stored or browser language and list the languages without JavaScript', () => {
    for (const [file, path] of [
      ['apps/support/index.html', '/apps/support/'],
      ['apps/support/getting-started/index.html', '/apps/support/getting-started/'],
    ] as const) {
      const html = site.files.find((f) => f.file === file)!.content
      expect(html).toContain('<meta name="robots" content="noindex">')
      expect(html).toContain('localStorage.getItem("tenshu.lang")')
      expect(html).toContain(`location.replace(${JSON.stringify(path)}+l+""+location.hash)`)
      for (const l of HUB_LANGUAGES) expect(html).toContain(`<a href="${path}${l}" hreflang="${l}" lang="${l}">`)
    }
  })
})

describe('Getting started', () => {
  it('exists in every language, with a breadcrumb, the hub as Support, and its siblings in the pickers', () => {
    expect(guides.map((g) => g.url)).toEqual(HUB_LANGUAGES.map((l) => `/apps/support/getting-started/${l}`))
    for (const page of guides) {
      const copy = HUB_COPY[page.lang]!
      expect(page.html).toContain(`<html lang="${page.lang}">`)
      expect(page.html).toContain(
        `<nav class="breadcrumb" aria-label="${copy.breadcrumb}"><ol><li><a href="/apps/support/${page.lang}">`,
      )
      expect(page.html).toContain(`<li><span aria-current="page">${copy.gettingStarted}</span></li>`)
      expect(page.html).toContain(`<h1 class="text-3xl font-semibold tracking-tight sm:text-4xl">${copy.gettingStarted}</h1>`)
      for (const l of HUB_LANGUAGES) expect(page.html).toMatch(new RegExp(`<a [^>]*hreflang="${l}"[^>]*href="/apps/support/getting-started/${l}"`, 'i'))
      expect(page.html).not.toMatch(/<script [^>]*src=/)
    }
  })

  it('sends people to Control first, links its steps in the page\'s language, and names the support address', () => {
    for (const page of guides) {
      const control = page.html.indexOf('id="control"')
      expect(control, page.url).toBeGreaterThan(0)
      expect(control).toBeLessThan(page.html.indexOf('id="apps-and-devices"'))
      for (const platform of ['ios', 'android', 'macos', 'web']) {
        expect(page.html).toContain(`href="/apps/control/support/${platform}/${page.lang}"`)
        expect(page.html).toContain(`href="/apps/drive/support/${platform}/${page.lang}"`)
      }
      expect(page.html).toContain('mailto:support@tenshu.app')
      // Control's welcome screen, from the manifest.
      expect(page.screenshotSlots.map((s) => `${s.app}:${s.key}`)).toEqual(['control:android/phone/' + page.lang + '/01-welcome'])
      // A pair where the language has both themes. German has only its light capture, and
      // it must be the German one — its sha from the manifest — not English's light half.
      if (page.lang === 'de') {
        const german = loadShotManifest('control')!.screens['android/phone/de/01-welcome']!
        expect(page.html, page.url).toContain(`<img class="shot" src="https://assets.tenshu.app/sha256/${german.sha256}.png"`)
        expect(page.html, page.url).not.toMatch(/shot--dark/)
      } else {
        expect(page.html, page.url).toMatch(/<img class="shot shot--light" src="https:\/\/assets\.tenshu\.app\//)
        expect(page.html, page.url).toMatch(/<img class="shot shot--dark" src="https:\/\/assets\.tenshu\.app\//)
      }
    }
  })

  it('every link on it reaches a generated page', () => {
    for (const page of guides) {
      for (const [, href] of page.html.matchAll(/<article[\s\S]*?<\/article>/g).next().value![0].matchAll(/href="(\/[^"#]+)"/g)) {
        expect(fileNames, `${page.url} → ${href}`).toContain(`${href!.slice(1)}/index.html`)
      }
    }
  })

  it('cites a source for every privacy claim, each one a file in the repo, and claims no "open source"', () => {
    for (const page of guides) {
      expect(page.privacyClaims.length, page.url).toBe(3)
      for (const claim of page.privacyClaims) {
        expect(claim.sources.length, claim.text).toBeGreaterThan(0)
        for (const source of claim.sources) expect(existsSync(join(REPO_ROOT, source.path)), source.path).toBe(true)
      }
      expect(page.html, page.url).not.toMatch(/open[- ]source|Open Source|code source ouvert/i)
    }
    for (const hub of hubs) expect(hub.html, hub.url).not.toMatch(/open[- ]source/i)
  })

  it('the translations are marked unreviewed', async () => {
    const { readFileSync } = await import('node:fs')
    for (const lang of ['de', 'fr']) {
      const md = readFileSync(join(APEX_DIR, 'content', 'support', 'getting-started', `${lang}.md`), 'utf8')
      expect(md, lang).toMatch(/^reviewed: false$/m)
    }
  })
})
