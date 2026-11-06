// @vitest-environment node
//
// The support-page generator (store-presence-and-support-pages.md §4). Pins three promises:
// every page the content declares is generated, every privacy claim cites a source (§4.4),
// and every cited source is a file in this repo — so a doc move cannot silently orphan a
// public claim.
import { existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
// @ts-expect-error jsdom ships no types and @types/jsdom is not a dependency; the test needs only JSDOM.
import { JSDOM } from 'jsdom'
import { describe, expect, it } from 'vitest'
import {
  APEX_DIR,
  REQUIRED_SECTIONS,
  SUPPORT_DEVICE,
  buildSupportSite,
  loadContent,
  loadShotManifest,
  privacyClaimsOf,
  redirectLogic,
  resolveShot,
  selectPlatform,
  slugify,
} from './generate.ts'
import { loadSupportChrome } from './loadChrome.ts'
import { IMPRINT } from './imprint.ts'
import { PRIVACY, PRIVACY_POLICY_DATE } from './privacy.ts'
import { TERMS, TERMS_DATE } from './terms.ts'
import { ROADMAP, ROADMAP_DATE } from './roadmap.ts'
import { PRICING, START_HREF, planStorage } from './pricing.ts'
import { APP_PAGES, BUTTON_PRIMARY } from './appPages.ts'
import { HUB_SCRIPT_BODY } from './hub.ts'
import { PLANS, formatEuro } from '../src/control/services/plans.ts'

const REPO_ROOT = join(APEX_DIR, '..', '..')
// The chrome loaded exactly as the build loads it (Vite's module runner), not a test double.
const chrome = await loadSupportChrome('test')
const site = await buildSupportSite({ chrome })

/**
 * The pages S1 ships. Written out rather than derived from the content, so dropping a platform
 * from a front matter is a visible test change, not a quietly missing store support URL.
 */
const PLATFORMS: Record<string, readonly string[]> = {
  control: ['android', 'ios', 'macos', 'web'],
  drive: ['android', 'ios', 'macos', 'web'],
  // Calendar, Contacts, Fit and Photos are not distributed on macOS or the web yet.
  calendar: ['android', 'ios'],
  contacts: ['android', 'ios'],
  fit: ['android', 'ios'],
  photos: ['android', 'ios'],
}
const EXPECTED = Object.entries(PLATFORMS)
  .flatMap(([app, platforms]) => platforms.flatMap((platform) => ['de', 'en', 'fr'].map((lang) => `apps/${app}/support/${platform}/${lang}/index.html`)))
  .sort()

/** Every app's support page and redirect page: the HTML under `apps/<app>/`, not the hub's. */
/** The support pages under `/apps/<app>/support…`: not the hub, not the app marketing pages (`/apps/<app>/<lang>`). */
const appFiles = () => site.files.filter((f) => /^apps\/[a-z]+\/support(\/|$)/.test(f.file) && !f.file.startsWith('apps/support/') && f.file.endsWith('.html'))

/** Each language's own name, as the language pickers print it. */
const LANGUAGE_NAMES: Record<string, string> = { en: 'English', de: 'Deutsch', fr: 'Français' }

/** What the language pickers on [page] link to: the page's siblings in each language. */
const languagesOf = (page: { app: string; platform: string; lang: string }) =>
  site.pages
    .filter((p) => p.app === page.app && p.platform === page.platform)
    .map((p) => ({ lang: p.lang, name: LANGUAGE_NAMES[p.lang]!, href: p.url, current: p.lang === page.lang }))

/** Each language's <title> pattern: `<title> — <platform>`. */
const TITLE: Record<string, RegExp> = {
  en: /<title>Tenshu (Drive|Control|Calendar|Contacts|Fit|Photos) support — [^<]+<\/title>/,
  de: /<title>Tenshu (Drive|Control|Calendar|Contacts|Fit|Photos) – Support — [^<]+<\/title>/,
  fr: /<title>Assistance Tenshu (Drive|Control|Calendar|Contacts|Fit|Photos) — [^<]+<\/title>/,
}

describe('support pages', () => {
  it('generates every expected page, and nothing else', () => {
    expect(site.pages.map((p) => p.file).sort()).toEqual(EXPECTED)
  })

  it('generates the redirect pages for every app and platform', () => {
    const files = new Set(site.files.map((f) => f.file))
    for (const [app, platforms] of Object.entries(PLATFORMS)) {
      expect(files).toContain(`apps/${app}/support/index.html`)
      for (const platform of platforms) {
        expect(files).toContain(`apps/${app}/support/${platform}/index.html`)
      }
    }
  })

  it.each(site.pages.map((p) => [p.url, p] as const))('%s has title, description, lang and both switchers', (_url, page) => {
    expect(page.html).toMatch(new RegExp(`<html lang="${page.lang}">`))
    expect(page.html).toMatch(TITLE[page.lang]!)
    expect(page.html).toMatch(/<meta name="description" content="[^"]{40,}">/)
    expect(page.html).toContain(`<a href="${page.url}" aria-current="page">`)
    // Each platform the app ships on is linked from the switcher, as a plain link.
    for (const other of site.pages.filter((p) => p.app === page.app && p.lang === page.lang)) {
      expect(page.html).toContain(`href="${other.url}"`)
    }
    // Only the two theme scripts and the language one, all inline: the page must read the same
    // with JavaScript off.
    expect([...page.html.matchAll(/<script>/g)]).toHaveLength(3)
    expect(page.html).not.toMatch(/<script [^>]*src=/)
    // The five sections, in the proposal's order.
    const ids = [...page.html.matchAll(/<h2 id="([^"]+)"/g)].map((m) => m[1])
    expect(ids).toEqual([...REQUIRED_SECTIONS])
    expect(page.html).toContain('mailto:support@tenshu.app')
  })

  it('every page — support page and redirect page — carries the landing page\'s own header and footer', () => {
    for (const file of appFiles()) {
      const url = file.file.replace(/\/index\.html$/, '').replace(/^/, '/')
      // A support page's chrome is in its language and switches between its siblings; a redirect
      // page has no language of its own (English chrome, no switch). Support leads to the hub, in
      // the page's language where it has one (§4.7).
      const page = site.pages.find((p) => p.url === url)
      const lang = page?.lang ?? 'en'
      const supportHref = page ? `/apps/support/${lang}` : '/apps/support'
      const shared = chrome({ lang, url, supportHref, languages: page ? languagesOf(page) : [] })
      // Byte for byte what the components render: one header, one footer, no fork.
      expect(file.content, file.file).toContain(shared.header)
      expect(file.content, file.file).toContain(shared.footer)
      if (page) expect(file.content, file.file).toContain(shared.languagePicker)
      expect(file.content, file.file).toContain(`<body class="${shared.bodyClass}">`)
      // The line-drawn cat of BrandLockup, not the old square icon.
      expect(shared.header).toContain('<span class="text-base font-semibold tracking-tight">Tenshu</span>')
      expect(shared.header).toMatch(/<svg [^>]*aria-label="Tenshu" class="h-8 w-8"/)
      expect(file.content).not.toContain('class="site-header')
      expect(file.content).not.toContain('class="site-footer')
      if (lang !== 'en') continue // the copy checks below are the English chrome's
      // Docs and Support in the bar; Support is the current section and leads to the hub.
      expect(shared.header).toContain('href="https://github.com/B3nedikt/Tenshu/tree/master/docs"')
      expect(shared.header).toMatch(new RegExp(`<a href="${supportHref}" aria-current="page"[^>]*>Support</a>`))
      // The CTA as a logged-out visitor sees it: a plain link to /start.
      expect(shared.header).toMatch(/<a [^>]*href="\/start"[^>]*>(Get started|Coming soon)<\/a>/)
      // The phone menu opens without JavaScript.
      expect(shared.header).toMatch(/<details[^>]*><summary aria-label="Menu"/)
    }
  })

  it('has no eyebrow: the title is the app\'s name, and <title> still says support and platform', () => {
    for (const file of appFiles()) {
      expect(file.content, file.file).not.toContain('eyebrow')
      expect(file.content, file.file).toMatch(/<h1 class="[^"]*">Tenshu (Drive|Control|Calendar|Contacts|Fit|Photos)<\/h1>/)
      expect(file.content, file.file).toMatch(/<title>(Tenshu (Drive|Control|Calendar|Contacts|Fit|Photos) (support — [^<]+|— Support|– Support — [^<]+)|Assistance Tenshu (Drive|Control|Calendar|Contacts|Fit|Photos) — [^<]+)<\/title>/)
    }
    const ios = site.pages.find((p) => p.url === '/apps/drive/support/ios/en')!
    expect(ios.html).toContain('<title>Tenshu Drive support — iPhone and iPad</title>')
    expect(ios.html).toMatch(/<meta name="description" content="Help for Tenshu Drive on iPhone and iPad:/)
    expect(ios.html).not.toContain('Support · iPhone and iPad')
  })

  it('lays the switchers out platform first, language last (at the trailing edge on wide screens)', () => {
    for (const page of site.pages) {
      const platform = page.html.indexOf('class="switcher switcher--platform"')
      const language = page.html.indexOf('class="switcher switcher--language"')
      expect(platform, page.url).toBeGreaterThan(0)
      expect(language, page.url).toBeGreaterThan(platform)
    }
  })

  // Both pickers are one component (src/landing/LanguagePicker.tsx), and the pages are never
  // hydrated: with JavaScript off, a <details> must open and every language must be a real link
  // to this page in that language. A <select> would need a script to navigate.
  it('both language pickers work without JavaScript: a <details> of real links to this page in each language', () => {
    const pickersOf = (html: string) => [...html.matchAll(/<details class="group relative">[\s\S]*?<\/details>/g)].map((m) => m[0])
    for (const page of site.pages) {
      const head = page.html.slice(page.html.indexOf('class="switcher switcher--language"'), page.html.indexOf('<nav class="toc"'))
      const footer = page.html.slice(page.html.lastIndexOf('<footer'))
      const pickers = [...pickersOf(head), ...pickersOf(footer)]
      expect(pickers, page.url).toHaveLength(2)
      // The page head's opens downwards at the trailing edge; the footer's opens upwards.
      expect(pickers[0], page.url).toMatch(/<ul class="[^"]*top-full[^"]*right-0/)
      expect(pickers[1], page.url).toMatch(/<ul class="[^"]*bottom-full[^"]*left-0/)
      for (const picker of pickers) {
        // The summary is labelled ("Language" in the page's language, then the current one's name)
        // and shows the current language in its own name. No aria-expanded: <summary> reports it,
        // and a static `false` would be wrong once it is open.
        const summary = /<summary ([^>]*)>([\s\S]*?)<\/summary>/.exec(picker)!
        expect(summary[1], page.url).toMatch(/aria-labelledby="[^" ]+ [^"]+"/)
        expect(summary[1], page.url).not.toContain('aria-expanded')
        expect(summary[2], page.url).toContain(`lang="${page.lang}">${LANGUAGE_NAMES[page.lang]}</span>`)
        const links = [...picker.matchAll(/<a ([^>]*)>([^<]*)/g)].map((m) => ({
          href: /href="([^"]+)"/.exec(m[1]!)?.[1],
          hreflang: /hreflang="(\w+)"/i.exec(m[1]!)?.[1],
          lang: / lang="(\w+)"/.exec(` ${m[1]}`)?.[1],
          choice: /data-lang-choice="(\w+)"/.exec(m[1]!)?.[1],
          current: m[1]!.includes('aria-current="page"'),
          name: m[2],
        }))
        // Every language, each in its own name, linking this page (never the landing page), the
        // current one marked, and each recording the pick (LANGUAGE_SWITCH_SCRIPT).
        expect(links, page.url).toEqual(
          languagesOf(page).map((l) => ({ href: l.href, hreflang: l.lang, lang: l.lang, choice: l.lang, current: l.current, name: l.name })),
        )
        expect(links.filter((l) => l.current).map((l) => l.href), page.url).toEqual([page.url])
        expect(picker, page.url).not.toMatch(/<select|<script|onclick/i)
      }
    }
    // The redirect pages have no language of their own: no picker.
    for (const file of site.files.filter((f) => /^apps\/[^/]+\/support\/(\w+\/)?index\.html$/.test(f.file))) {
      expect(file.content, file.file).not.toContain('data-lang-choice="')
      expect(file.content, file.file).not.toContain('switcher--language')
    }
  })

  it('the pills are the platform\'s alone: four platforms stay pills, the language is the picker', () => {
    for (const page of site.pages) {
      const platform = /<nav class="switcher switcher--platform"[\s\S]*?<\/nav>/.exec(page.html)![0]
      expect(platform, page.url).not.toContain('data-lang-choice')
      expect(platform, page.url).not.toContain('hreflang')
      // Every React id on the page is unique: header, picker and footer are separate renders.
      const ids = [...page.html.matchAll(/ id="([^"]+)"/g)].map((m) => m[1])
      expect(new Set(ids).size, page.url).toBe(ids.length)
    }
  })

  // The imprint (imprint.ts): static like the support pages, so it works with JavaScript off and
  // every footer — the landing page's and the support pages' — can link it.
  it('builds the imprint in every language, in the site chrome, with no phone number yet', () => {
    const imprints = ['en', 'de', 'fr'].map((lang) => {
      const file = site.files.find((f) => f.file === `legal/imprint/${lang}/index.html`)
      expect(file, lang).toBeDefined()
      return { lang, html: file!.content }
    })
    for (const { lang, html } of imprints) {
      expect(html).toContain(`<html lang="${lang}">`)
      const shared = chrome({ lang, url: `/legal/imprint/${lang}`, supportHref: `/apps/support/${lang}`, languages: [], current: null })
      expect(html, lang).toContain(shared.header)
      // Nothing in the header is current on the imprint.
      expect(shared.header, lang).not.toContain('aria-current')
      for (const l of ['en', 'de', 'fr']) {
        expect(html, lang).toContain(`<link rel="alternate" hreflang="${l}" href="https://tenshu.app/legal/imprint/${l}">`)
        // The footer's picker switches between the imprint's own siblings.
        expect(html, lang).toMatch(new RegExp(`<a [^>]*hreflang="${l}"[^>]*href="/legal/imprint/${l}"`, 'i'))
      }
      expect(html, lang).toContain('Cajetan UG (haftungsbeschränkt)')
      expect(html, lang).toContain('HRB 35668')
      expect(html, lang).toContain('DE465138089')
      expect(html, lang).toContain('§ 18')
      expect(html, lang).toContain('<a href="mailto:contact@cajetan.company">contact@cajetan.company</a>')
      // TODO(imprint) in imprint.ts: no phone until the business number exists.
      expect(html, lang).not.toMatch(/tel:|95489543|\bTelefon\b|\bPhone\b|Téléphone/)
      // No EU ODR platform link: discontinued in July 2025.
      expect(html, lang).not.toContain('ec.europa.eu/consumers/odr')
      expect([...html.matchAll(/<script>/g)], lang).toHaveLength(3)
      expect(html, lang).not.toMatch(/<script [^>]*src=/)
      // German is the binding version; the translations say so first.
      expect(html.includes('class="translation-notice"'), lang).toBe(lang !== 'de')
    }
    expect(imprints.find((i) => i.lang === 'de')!.html).toContain('Tenshu ist eine Marke von Benedikt Löbbecke.')
    // Every language names the third-party marks and says we are not affiliated.
    for (const [lang, heading, apple, notAffiliated] of [
      ['de', '<h2>Marken</h2>', 'Marken der Apple Inc.', 'steht in keiner Verbindung zu Apple, Google oder Microsoft'],
      ['en', '<h2>Trademarks</h2>', 'trademarks of Apple Inc.', 'not affiliated with, endorsed by or sponsored by Apple, Google or Microsoft'],
      ['fr', '<h2>Marques</h2>', 'marques d’Apple Inc.', 'ni affilié à Apple, Google ou Microsoft'],
    ] as const) {
      const html = imprints.find((i) => i.lang === lang)!.html
      expect(html, lang).toContain(heading)
      expect(html, lang).toContain(apple)
      expect(html, lang).toContain('Google Play')
      expect(html, lang).toContain(notAffiliated)
    }
    expect(imprints.find((i) => i.lang === 'en')!.html).toContain('This is a translation; the German version is legally binding.')
  })

  // The privacy policy (privacy.ts): built like the imprint. ⚠ The language-neutral `/legal/privacy`
  // is the URL App Store Connect and Play carry (store/shared/en-US/privacy_url.txt; Caddy 302s it
  // to a language), and the per-language paths stay stable, so both are pinned here.
  it('builds the privacy policy in every language, in the site chrome, dated, with a contents list', () => {
    const policies = ['en', 'de', 'fr'].map((lang) => {
      const file = site.files.find((f) => f.file === `legal/privacy/${lang}/index.html`)
      expect(file, lang).toBeDefined()
      return { lang, html: file!.content }
    })
    expect(readFileSync(join(REPO_ROOT, 'store/shared/en-US/privacy_url.txt'), 'utf8').trim()).toBe('https://tenshu.app/legal/privacy')
    for (const { lang, html } of policies) {
      expect(html).toContain(`<html lang="${lang}">`)
      const shared = chrome({ lang, url: `/legal/privacy/${lang}`, supportHref: `/apps/support/${lang}`, languages: [], current: null })
      expect(html, lang).toContain(shared.header)
      expect(shared.header, lang).not.toContain('aria-current')
      for (const l of ['en', 'de', 'fr']) {
        expect(html, lang).toContain(`<link rel="alternate" hreflang="${l}" href="https://tenshu.app/legal/privacy/${l}">`)
        expect(html, lang).toMatch(new RegExp(`<a [^>]*hreflang="${l}"[^>]*href="/legal/privacy/${l}"`, 'i'))
      }
      // The controller, as in the imprint, and the date the text took effect.
      expect(html, lang).toContain('Cajetan UG (haftungsbeschränkt)')
      expect(html, lang).toContain('Lucas-Cranach-Straße 5')
      expect(html, lang).toContain(PRIVACY_POLICY_DATE)
      expect(html, lang).toContain('mailto:privacy@tenshu.app')
      // The supervisory authority for a controller in Bavaria's private sector.
      expect(html, lang).toContain('lda.bayern.de')
      // Every <h2> has an id, and the contents list links each one.
      const h2s = [...html.matchAll(/<h2( id="([a-z0-9-]+)")?>/g)]
      expect(h2s.length, lang).toBeGreaterThan(10)
      for (const h of h2s) {
        expect(h[2], `${lang}: an <h2> without an id`).toBeDefined()
        expect(html, lang).toContain(`<a href="#${h[2]}">`)
      }
      expect([...html.matchAll(/<script>/g)], lang).toHaveLength(3)
      expect(html, lang).not.toMatch(/<script [^>]*src=/)
      expect(html.includes('class="translation-notice"'), lang).toBe(lang !== 'de')
      // ⚠ The repo is private: the policy itself links no source file and calls nothing public.
      const article = html.slice(html.indexOf('<article'), html.indexOf('</article>'))
      expect(article, lang).not.toContain('github.com/B3nedikt')
      expect(article, lang).not.toMatch(/Bedrohungsmodell|threat model|modèle de menace|Quellcode|source code|code source/i)
    }
    // The three versions carry the same sections, in the same order: a translation drops nothing.
    const ids = (html: string) => [...html.matchAll(/<h2 id="([a-z0-9-]+)">/g)].map((m) => m[1])
    const de = ids(policies.find((p) => p.lang === 'de')!.html)
    for (const { lang, html } of policies) expect(ids(html), lang).toEqual(de)
    expect(policies.find((p) => p.lang === 'en')!.html).toContain('This is a translation; the German version is legally binding.')
  })

  // The terms of use (terms.ts): built like the privacy policy, linked from the sign-up screens.
  it('builds the terms of use in every language, in the site chrome, dated, with a contents list', () => {
    const pages = ['en', 'de', 'fr'].map((lang) => {
      const file = site.files.find((f) => f.file === `legal/terms/${lang}/index.html`)
      expect(file, lang).toBeDefined()
      return { lang, html: file!.content }
    })
    for (const { lang, html } of pages) {
      expect(html).toContain(`<html lang="${lang}">`)
      const shared = chrome({ lang, url: `/legal/terms/${lang}`, supportHref: `/apps/support/${lang}`, languages: [], current: null })
      expect(html, lang).toContain(shared.header)
      for (const l of ['en', 'de', 'fr']) {
        expect(html, lang).toContain(`<link rel="alternate" hreflang="${l}" href="https://tenshu.app/legal/terms/${l}">`)
        expect(html, lang).toMatch(new RegExp(`<a [^>]*hreflang="${l}"[^>]*href="/legal/terms/${l}"`, 'i'))
      }
      // The operator, as in the imprint and the privacy policy, and the date = TOS_VERSION.
      expect(html, lang).toContain('Cajetan UG (haftungsbeschränkt)')
      expect(html, lang).toContain('Lucas-Cranach-Straße 5')
      expect(html, lang).toContain(TERMS_DATE)
      expect(html, lang).toContain('mailto:support@tenshu.app')
      // It links its siblings in its own language.
      expect(html, lang).toContain(`href="/legal/privacy/${lang}"`)
      expect(html, lang).toContain(`href="/legal/imprint/${lang}"`)
      const h2s = [...html.matchAll(/<h2( id="([a-z0-9-]+)")?>/g)]
      expect(h2s.length, lang).toBeGreaterThan(10)
      for (const h of h2s) {
        expect(h[2], `${lang}: an <h2> without an id`).toBeDefined()
        expect(html, lang).toContain(`<a href="#${h[2]}">`)
      }
      expect(html.includes('class="translation-notice"'), lang).toBe(lang !== 'de')
      const article = html.slice(html.indexOf('<article'), html.indexOf('</article>'))
      expect(article, lang).not.toContain('github.com/B3nedikt')
      // The minimum age the privacy policy states, and the § 36 VSBG statement the imprint makes.
      expect(article, lang).toContain('16')
    }
    const ids = (html: string) => [...html.matchAll(/<h2 id="([a-z0-9-]+)">/g)].map((m) => m[1])
    const de = ids(pages.find((p) => p.lang === 'de')!.html)
    for (const { lang, html } of pages) expect(ids(html), lang).toEqual(de)
    expect(pages.find((p) => p.lang === 'de')!.html).toContain('Wir sind nicht bereit und nicht verpflichtet, an Streitbeilegungsverfahren vor einer Verbraucherschlichtungsstelle teilzunehmen.')
    // The checkouts link `#withdrawal` (TermsOfUse.withdrawalUrl, termsWithdrawalUrl): it must exist.
    for (const { lang, html } of pages) expect(html, lang).toContain('<h2 id="withdrawal">')
    // ⚠⚠ Closure deletes from LIVE storage; the backup copy may outlive it (privacy policy). Never
    // promise permanent, unrecoverable deletion while the mirror is unpruned.
    expect(pages.find((p) => p.lang === 'de')!.html).toContain('aus unserem laufenden Speicher')
    for (const { html } of pages) expect(html).not.toMatch(/endgültig|permanently delete|définitivement/)
    // ⚠ The statutory model withdrawal notice keeps its own "Sie" form: rewording it leaves the model.
    expect(pages.find((p) => p.lang === 'de')!.html).toContain('Sie haben das Recht, binnen vierzehn Tagen ohne Angabe von Gründen diesen Vertrag zu widerrufen.')
  })

  it('the terms of use are unreviewed in every language, German included', () => {
    expect(Object.fromEntries(Object.entries(TERMS).map(([lang, p]) => [lang, p.reviewed]))).toEqual({ de: false, en: false, fr: false })
  })

  it('the table of contents and the translation notice share the prose column\'s measure', () => {
    // Both sit above the <article>, in the page column (max-w-5xl), so without the prose's 70ch
    // they ran to the column's edge, wider than the text below them (maintainer, 2026-10-24).
    const css = readFileSync(join(APEX_DIR, 'support-pages', 'support.css'), 'utf8')
    const rules = [...css.matchAll(/([^{}]+)\{([^{}]*)\}/g)].map((m) => ({
      selectors: m[1]!.replace(/\/\*[\s\S]*?\*\//g, '').split(',').map((s) => s.trim()),
      body: m[2]!,
    }))
    const measure = rules.find((r) => r.selectors.includes('.prose p') && /max-width:\s*70ch/.test(r.body))
    expect(measure, 'the prose measure rule').toBeDefined()
    expect(measure!.selectors).toContain('.toc')
    // The notice's own font is 0.875rem, so its 70ch is scaled back to the body copy's.
    const notice = rules.find((r) => r.selectors.includes('.translation-notice'))
    expect(notice!.body).toMatch(/max-width:\s*calc\(70ch \/ 0\.875\)/)
    expect(notice!.body).toMatch(/font-size:\s*0\.875rem/)
    // And the legal pages render both outside the <article>, which is why they need the rule.
    const en = site.files.find((f) => f.file === 'legal/privacy/en/index.html')!.content
    expect(en.indexOf('<nav class="toc"')).toBeLessThan(en.indexOf('<article'))
  })

  // `/legal/privacy` and `/legal/imprint`: Caddy redirects them by Accept-Language, so the page is
  // only what a host without that rule shows — but it must never fall through to the SPA.
  it('the language-neutral legal URLs are a static list of the languages, and every language names it as x-default', () => {
    const titles: Record<string, Record<string, { title: string }>> = { privacy: PRIVACY, imprint: IMPRINT, terms: TERMS }
    for (const slug of ['privacy', 'imprint', 'terms']) {
      const neutral = site.files.find((f) => f.file === `legal/${slug}/index.html`)
      expect(neutral, slug).toBeDefined()
      const html = neutral!.content
      expect(html, slug).toContain('<html lang="en">')
      expect(html, slug).not.toContain('location.replace') // no redirect script: plain links work with JavaScript off
      expect(html, slug).not.toContain('http-equiv="refresh"')
      expect(html, slug).not.toContain('rel="canonical"')
      for (const l of ['en', 'de', 'fr']) {
        expect(html, `${slug} ${l}`).toContain(`<link rel="alternate" hreflang="${l}" href="https://tenshu.app/legal/${slug}/${l}">`)
        const title = titles[slug]![l]!.title.replaceAll('&', '&amp;')
        expect(html, `${slug} ${l}`).toContain(`<a href="/legal/${slug}/${l}" hreflang="${l}" lang="${l}">${title} (${LANGUAGE_NAMES[l]})</a>`)
      }
      expect(html, slug).toContain(`<link rel="alternate" hreflang="x-default" href="https://tenshu.app/legal/${slug}">`)
      for (const l of ['en', 'de', 'fr']) {
        const page = site.files.find((f) => f.file === `legal/${slug}/${l}/index.html`)!.content
        for (const a of ['en', 'de', 'fr']) expect(page, `${slug} ${l}`).toContain(`<link rel="alternate" hreflang="${a}" href="https://tenshu.app/legal/${slug}/${a}">`)
        expect(page, `${slug} ${l}`).toContain(`<link rel="alternate" hreflang="x-default" href="https://tenshu.app/legal/${slug}">`)
        expect(page, `${slug} ${l}`).toContain(`<link rel="canonical" href="https://tenshu.app/legal/${slug}/${l}">`)
      }
    }
  })

  // The public roadmap (roadmap.ts): a text page like the legal ones, with "Roadmap" current in
  // the header. ⚠ It is a public promise surface: no dates, no "open source", no link into the
  // private repository, and a translation drops no item.
  it('builds the roadmap in every language, in the site chrome with Roadmap current, dated, in three stages', () => {
    for (const lang of ['en', 'de', 'fr']) {
      const file = site.files.find((f) => f.file === `roadmap/${lang}/index.html`)
      expect(file, lang).toBeDefined()
      const html = file!.content
      expect(html).toContain(`<html lang="${lang}">`)
      expect(html).toContain(`<title>${ROADMAP[lang]!.title} — Tenshu</title>`)
      expect(html).toMatch(/<meta name="description" content="[^"]{40,}">/)
      const shared = chrome({ lang, url: `/roadmap/${lang}`, supportHref: `/apps/support/${lang}`, languages: [], current: 'roadmap' })
      expect(html, lang).toContain(shared.header)
      expect(shared.header).toMatch(/<a href="\/roadmap" aria-current="page"/)
      expect(html, lang).toContain(`<link rel="canonical" href="https://tenshu.app/roadmap/${lang}">`)
      for (const l of ['en', 'de', 'fr']) {
        expect(html, lang).toContain(`<link rel="alternate" hreflang="${l}" href="https://tenshu.app/roadmap/${l}">`)
        expect(html, lang).toMatch(new RegExp(`<a [^>]*hreflang="${l}"[^>]*href="/roadmap/${l}"`, 'i'))
      }
      expect(html, lang).toContain('<link rel="alternate" hreflang="x-default" href="https://tenshu.app/roadmap">')
      expect(html, lang).toContain(`<time datetime="${ROADMAP_DATE}">`)
      // The three stages in order, and no table of contents for them.
      expect([...html.matchAll(/<section id="([a-z]+)"/g)].map((m) => m[1]), lang).toEqual(['testing', 'next', 'later'])
      expect(html, lang).not.toContain('<nav class="toc"')
      // Only the theme and language scripts: the page reads the same with JavaScript off.
      expect([...html.matchAll(/<script>/g)], lang).toHaveLength(3)
      const article = html.slice(html.indexOf('<article'), html.indexOf('</article>'))
      expect(article, lang).not.toMatch(/open.source|quelloffen|code source ouvert/i)
      expect(article, lang).not.toContain('github.com')
      // No dates in the items: the one date is the "last updated" <time>.
      expect(article.replace(/<time [^>]*>[^<]*<\/time>/, ''), lang).not.toMatch(/\b20\d\d\b/)
      expect(article, lang).toContain('mailto:hello@tenshu.app')
    }
    const neutral = site.files.find((f) => f.file === 'roadmap/index.html')!.content
    expect(neutral).not.toContain('location.replace')
    for (const l of ['en', 'de', 'fr']) expect(neutral, l).toContain(`<a href="/roadmap/${l}" hreflang="${l}" lang="${l}">`)
  })

  it('a roadmap translation keeps every stage, area and item; the translations are unreviewed', () => {
    const shape = (lang: string) => ROADMAP[lang]!.stages.map((s) => [s.id, s.areas.map((a) => a.items.length)])
    for (const lang of ['de', 'fr']) expect(shape(lang), lang).toEqual(shape('en'))
    expect(Object.fromEntries(Object.entries(ROADMAP).map(([lang, p]) => [lang, p.reviewed]))).toEqual({ en: true, de: false, fr: false })
  })

  // The public pricing page (pricing.ts): the one page that names a price. Its plans come from
  // plans.ts, its answers from the Terms sections it links, and it carries the roadmap's rules:
  // no "open source" while the repository is private, no link into it, no em dash.
  it('builds the pricing page in every language, in the site chrome with Pricing current, one card per plan', () => {
    for (const lang of ['en', 'de', 'fr']) {
      const file = site.files.find((f) => f.file === `pricing/${lang}/index.html`)
      expect(file, lang).toBeDefined()
      const html = file!.content
      expect(html).toContain(`<html lang="${lang}">`)
      expect(html).toContain(`<title>${PRICING[lang]!.title} — Tenshu</title>`)
      expect(html).toMatch(/<meta name="description" content="[^"]{40,}">/)
      const shared = chrome({ lang, url: `/pricing/${lang}`, supportHref: `/apps/support/${lang}`, languages: [], current: 'pricing' })
      expect(html, lang).toContain(shared.header)
      expect(shared.header).toMatch(/<a href="\/pricing" aria-current="page"/)
      expect(html, lang).toContain(`<link rel="canonical" href="https://tenshu.app/pricing/${lang}">`)
      for (const l of ['en', 'de', 'fr']) {
        expect(html, lang).toContain(`<link rel="alternate" hreflang="${l}" href="https://tenshu.app/pricing/${l}">`)
        expect(html, lang).toMatch(new RegExp(`<a [^>]*hreflang="${l}"[^>]*href="/pricing/${l}"`, 'i'))
      }
      expect(html, lang).toContain('<link rel="alternate" hreflang="x-default" href="https://tenshu.app/pricing">')
      // One card per plan, smallest first, each with its storage, its price and its button.
      expect([...html.matchAll(/<section id="([a-z]+)" aria-labelledby="\1-heading" class="flex flex-col/g)].map((m) => m[1]), lang).toEqual(PLANS.map((p) => p.id))
      for (const plan of PLANS) {
        const card = html.slice(html.indexOf(`<section id="${plan.id}"`), html.indexOf('</section>', html.indexOf(`<section id="${plan.id}"`)))
        expect(card, `${lang} ${plan.id}`).toContain(planStorage(plan.limitBytes, lang))
        if (plan.monthlyEuroCents > 0) expect(card, `${lang} ${plan.id}`).toContain(formatEuro(plan.monthlyEuroCents, lang))
        // Every card leads to the way in, never into ControlShell (pricing.ts START_HREF).
        expect(card, `${lang} ${plan.id}`).toContain(`href="${START_HREF}"`)
        expect(card, `${lang} ${plan.id}`).not.toContain('/control/')
      }
      expect(html, lang).not.toContain('<nav class="toc"')
      // Only the theme and language scripts: the page reads the same with JavaScript off.
      expect([...html.matchAll(/<script>/g)], lang).toHaveLength(3)
      const article = html.slice(html.indexOf('<article'), html.indexOf('</article>'))
      expect(article, lang).not.toMatch(/open.source|quelloffen|code source ouvert/i)
      expect(article, lang).not.toContain('github.com')
      expect(article, lang).not.toContain('—')
      expect(article, lang).toContain('mailto:support@tenshu.app')
      // Every answer links a Terms section that exists in the same language.
      const terms = site.files.find((f) => f.file === `legal/terms/${lang}/index.html`)!.content
      const anchors = [...article.matchAll(new RegExp(`href="/legal/terms/${lang}#([a-z-]+)"`, 'g'))].map((m) => m[1]!)
      expect(anchors, lang).toHaveLength(PRICING[lang]!.faq.length)
      for (const id of anchors) expect(terms, `${lang} #${id}`).toContain(`<h2 id="${id}">`)
    }
    const neutral = site.files.find((f) => f.file === 'pricing/index.html')!.content
    expect(neutral).not.toContain('location.replace')
    for (const l of ['en', 'de', 'fr']) expect(neutral, l).toContain(`<a href="/pricing/${l}" hreflang="${l}" lang="${l}">`)
  })

  // The app marketing pages (appPages.ts). Rules: no price (they link the pricing page), a store
  // button only where the app is, no "open source", no em dash, and the page works without JavaScript.
  it('builds every app page in every language, with its store links, its screenshots and the download script', () => {
    for (const set of APP_PAGES) {
      for (const lang of ['en', 'de', 'fr']) {
        const where = `${set.app} ${lang}`
        const file = site.files.find((f) => f.file === `apps/${set.app}/${lang}/index.html`)
        expect(file, where).toBeDefined()
        const html = file!.content
        const page = set.pages[lang]!
        expect(html).toContain(`<html lang="${lang}">`)
        expect(html).toContain(`<title>${page.metaTitle}</title>`)
        expect(html, where).toContain(`<link rel="canonical" href="https://tenshu.app/apps/${set.app}/${lang}">`)
        expect(html, where).toContain(`<link rel="alternate" hreflang="x-default" href="https://tenshu.app/apps/${set.app}">`)
        expect(html, where).toContain('<h1 id="app-heading"')
        // Each store link twice (hero and closing row); every target without one links nowhere.
        for (const { href } of set.downloads.filter((d) => d.href)) expect(html.split(`href="${href}"`).length - 1, where).toBe(2)
        expect([...html.matchAll(/<li data-os="[a-z]+" data-soon><span /g)], where).toHaveLength(2 * set.downloads.filter((d) => !d.href).length)
        expect(html, where).not.toContain('apps.apple.com')
        // The hero's carousel: one app, its Android viewer the page's three captures, published ones
        // (no placeholder): a light and dark pair each, or one image where only one appearance was
        // captured (Control's German set is light only). Then an iPhone viewer, and the hub's script.
        expect(html, where).not.toContain('shot--pending')
        const hero = html.slice(html.indexOf('<div class="app-hero__shots">'), html.indexOf('</section>', html.indexOf('<div class="app-hero__shots">')))
        expect(hero.match(/data-hub-panel="/g), where).toHaveLength(1)
        expect(hero, where).not.toContain('hub-placeholder')
        const android = hero.slice(hero.indexOf('data-hub-platform="android"'), hero.indexOf('data-hub-platform="ios"'))
        const frames = android.split('data-hub-slide=""').slice(1)
        expect(frames, where).toHaveLength(3)
        expect(hero, where).toContain('data-hub-platform="ios"')
        expect(html, where).toContain(HUB_SCRIPT_BODY)
        for (const frame of frames) expect([1, 2], where).toContain([...frame.matchAll(/<img class="shot/g)].length)
        // Where the manifest has an English dark capture, the English page shows the pair: a lost dark one must fail.
        const manifest = loadShotManifest(set.app)!
        set.screens.forEach((screen, i) => {
          if (lang === 'en' && manifest.screens[`android/${set.device}/en/dark/${screen}`]) {
            expect([...frames[i]!.matchAll(/<img class="shot shot--(light|dark)"/g)], `${where} ${screen}`).toHaveLength(2)
          }
        })
        // Links: pricing in this language, the app's support, every app it works with.
        expect(html, where).toContain(`href="/pricing/${lang}"`)
        expect(html, where).toContain(`href="/apps/${set.app}/support"`)
        for (const w of page.worksWith) expect(html, where).toContain(`href="${w.href}"`)
        // The theme and language scripts plus the download and carousel scripts, all hashed into the CSP.
        expect([...html.matchAll(/<script>/g)], where).toHaveLength(5)
        const article = html.slice(html.indexOf('<article'), html.indexOf('</article>'))
        expect(article, where).not.toMatch(/open.source|quelloffen|code source ouvert/i)
        expect(article, where).not.toMatch(/€|\d[.,]\d\d/)
        expect(article, where).not.toContain('aria-disabled')
        expect(html, where).not.toContain('—')
      }
      const neutral = site.files.find((f) => f.file === `apps/${set.app}/index.html`)!.content
      for (const l of ['en', 'de', 'fr']) expect(neutral, `${set.app} ${l}`).toContain(`<a href="/apps/${set.app}/${l}" hreflang="${l}" lang="${l}">`)
    }
  })

  // Runs the page's own inline script against its own markup, with the user agent stubbed.
  it('the download script moves an available system first as primary, only hints at a coming one, and leaves the closing row alone', () => {
    const html = site.files.find((f) => f.file === 'apps/drive/en/index.html')!.content
    const script = [...html.matchAll(/<script>([\s\S]*?)<\/script>/g)].map((m) => m[1]!).find((body) => body.includes('data-download'))!
    const main = html.slice(html.indexOf('<main'), html.indexOf('</main>') + '</main>'.length)
    const run = (userAgent: string, touch = 0) => {
      const dom = new JSDOM(`<!doctype html><body>${main}</body>`, { runScripts: 'outside-only' })
      Object.defineProperty(dom.window.navigator, 'userAgent', { get: () => userAgent })
      Object.defineProperty(dom.window.navigator, 'maxTouchPoints', { get: () => touch })
      dom.window.eval(script)
      const document = dom.window.document
      const [hero, closing] = [...document.querySelectorAll('main ul.flex')] as unknown as HTMLElement[]
      const order = (ul: HTMLElement) => [...ul.querySelectorAll('li')].map((li) => li.dataset.os)
      const primary = (ul: HTMLElement) => [...ul.querySelectorAll('a')].filter((a) => a.className === BUTTON_PRIMARY).map((a) => a.closest('li')!.dataset.os)
      return { hero: order(hero!), primary: primary(hero!), closing: order(closing!), closingPrimary: primary(closing!), hint: document.querySelector('[data-download-hint]')!.textContent }
    }
    const built = ['android', 'ios', 'macos', 'web']
    const android = run('Mozilla/5.0 (Linux; Android 15; Pixel 8)')
    expect(android).toEqual({ hero: built, primary: ['android'], closing: built, closingPrimary: [], hint: 'We think you’re on Android.' })
    const iphone = run('Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X)')
    expect(iphone).toEqual({ hero: built, primary: ['android'], closing: built, closingPrimary: [], hint: 'We think you’re on iPhone and iPad. Tenshu Drive for it is coming soon.' })
    // iPadOS reports a Mac; touch points give it away.
    expect(run('Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7)', 5).hint).toContain('iPhone and iPad')
    expect(run('Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7)').hint).toContain('on Mac.')
    // No target for Windows: nothing moves, no hint.
    expect(run('Mozilla/5.0 (Windows NT 10.0; Win64; x64)')).toEqual({ hero: built, primary: ['android'], closing: built, closingPrimary: [], hint: '' })
    // Photos' Mac target is the macOS download page (macosDownload.ts): a Mac visitor gets it first, as the primary button.
    const photos = site.files.find((f) => f.file === 'apps/photos/en/index.html')!.content
    const photosMain = photos.slice(photos.indexOf('<main'), photos.indexOf('</main>') + '</main>'.length)
    const dom = new JSDOM(`<!doctype html><body>${photosMain}</body>`, { runScripts: 'outside-only' })
    Object.defineProperty(dom.window.navigator, 'userAgent', { get: () => 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7)' })
    Object.defineProperty(dom.window.navigator, 'maxTouchPoints', { get: () => 0 })
    dom.window.eval(script)
    expect([...dom.window.document.querySelectorAll('[data-download] li')].map((li: { dataset: { os: string } }) => li.dataset.os)).toEqual(['macos', 'android', 'ios', 'web'])
    expect(dom.window.document.querySelector('[data-download] li[data-os="macos"] a').className).toBe(BUTTON_PRIMARY)
    expect(dom.window.document.querySelector('[data-download] li[data-os="macos"] a').getAttribute('href')).toBe('/apps/photos/download/macos')
    expect(dom.window.document.querySelector('[data-download-hint]').textContent).toContain('on Mac.')
  })

  it('an app page translation keeps every row and question; the translations are unreviewed', () => {
    for (const set of APP_PAGES) {
      const shape = (lang: string) => {
        const p = set.pages[lang]!
        return [p.parity.map((r) => r.status), p.faq.map((f) => !!f.pricing), p.differences.map((d) => d.widget.kind), p.worksWith.map((w) => w.href)]
      }
      for (const lang of ['de', 'fr']) expect(shape(lang), `${set.app} ${lang}`).toEqual(shape('en'))
      expect(Object.fromEntries(Object.entries(set.pages).map(([lang, p]) => [lang, p.reviewed])), set.app).toEqual({ en: true, de: false, fr: false })
    }
  })

  it('a pricing translation keeps every feature and question; the translations are unreviewed', () => {
    const shape = (lang: string) => {
      const p = PRICING[lang]!
      return [p.freeFeatures.length, p.paidFeatures.length, p.alwaysFree.length, p.faq.map((f) => f.terms)]
    }
    for (const lang of ['de', 'fr']) expect(shape(lang), lang).toEqual(shape('en'))
    expect(Object.fromEntries(Object.entries(PRICING).map(([lang, p]) => [lang, p.reviewed]))).toEqual({ en: true, de: false, fr: false })
  })

  // ⚠ The Terms state the prices too (the law wants them in the contract). Both read plans.ts's
  // figures here, so a price change that misses one of them fails.
  it('the Terms name the same plans, storage and prices as plans.ts, in every language', () => {
    for (const [lang, terms] of Object.entries(TERMS)) {
      const section = terms.html.slice(terms.html.indexOf('<h2 id="plans">'), terms.html.indexOf('</ul>', terms.html.indexOf('<h2 id="plans">')))
      for (const plan of PLANS) {
        expect(section, `${lang} ${plan.id}`).toContain(planStorage(plan.limitBytes, lang))
        if (plan.monthlyEuroCents > 0) expect(section, `${lang} ${plan.id}`).toContain(formatEuro(plan.monthlyEuroCents, lang))
      }
    }
  })

  it('the privacy policy\'s translations are marked unreviewed; German is the binding text', () => {
    expect(Object.fromEntries(Object.entries(PRIVACY).map(([lang, p]) => [lang, p.reviewed]))).toEqual({ de: true, en: false, fr: false })
  })

  it('every page\'s footer links the imprint, the privacy policy and the terms in its language, and names the copyright holder', () => {
    for (const page of site.pages) {
      const footer = page.html.slice(page.html.lastIndexOf('<footer'))
      expect(footer, page.url).toContain(`href="/legal/imprint/${page.lang}"`)
      expect(footer, page.url).toContain(`href="/legal/privacy/${page.lang}"`)
      expect(footer, page.url).toContain(`href="/legal/terms/${page.lang}"`)
      expect(footer, page.url).toMatch(/© \d{4} Benedikt Löbbecke/)
      expect(footer, page.url).not.toMatch(/open source/i)
    }
  })

  it('the imprint\'s translations are marked unreviewed', () => {
    expect(Object.fromEntries(Object.entries(IMPRINT).map(([lang, i]) => [lang, i.reviewed]))).toEqual({ de: true, en: false, fr: false })
  })

  it('every translation is marked as machine-translated, pending review', () => {
    for (const { meta } of loadContent()) expect(meta.reviewed, `${meta.app}/${meta.lang}`).toBe(meta.lang === 'en')
  })

  it('a translation keeps the original\'s shape: same sections, slots, sources and platform blocks', () => {
    const content = loadContent()
    const shape = (body: string) => ({
      fences: [...body.matchAll(/^\s*:::.*$/gm)].map((m) => m[0].trim()),
      slots: [...body.matchAll(/\]\((shot:[^)]+)\)/g)].map((m) => m[1]),
      sources: [...body.matchAll(/\{source:\s*([^|}]+?)\s*\|/g)].map((m) => m[1]),
      sections: [...body.matchAll(/\{#([a-z-]+)\}/g)].map((m) => m[1]),
      links: [...body.matchAll(/\]\(((?:\/|mailto:)[^)]*)\)/g)].map((m) => m[1]),
    })
    for (const source of content.filter((c) => c.meta.lang !== 'en')) {
      const original = content.find((c) => c.meta.app === source.meta.app && c.meta.lang === 'en')!
      expect(shape(source.body), source.where).toEqual(shape(original.body))
      expect(source.meta.platforms, source.where).toEqual(original.meta.platforms)
    }
  })

  it.each(site.pages.map((p) => [p.url, p] as const))('%s: every privacy claim carries a source', (_url, page) => {
    expect(page.privacyClaims.length).toBeGreaterThanOrEqual(3)
    expect(page.privacyClaims.length).toBeLessThanOrEqual(5)
    for (const claim of page.privacyClaims) expect(claim.sources.length, claim.text).toBeGreaterThan(0)
  })

  it('every cited source exists in the repo', () => {
    const paths = new Set(site.pages.flatMap((p) => p.sources.map((s) => s.path.split('#')[0]!)))
    expect(paths.size).toBeGreaterThan(0)
    for (const path of paths) expect(existsSync(join(REPO_ROOT, path)), path).toBe(true)
  })

  it('names no competitor (D6)', () => {
    const names = /Dropbox|Google Drive|OneDrive|iCloud Drive|Proton Drive|Tresorit|\bMEGA\b|\bBox\.com/
    for (const page of site.pages) expect(page.html).not.toMatch(names)
  })

  it('every screenshot slot is a published R2 image or a placeholder — never a local path', () => {
    for (const page of site.pages) {
      expect(page.screenshotSlots.length).toBeGreaterThan(0)
      for (const slot of page.screenshotSlots) {
        if (slot.image) expect(slot.image.url).toMatch(/^https:\/\/[^/]+\/.+\/[0-9a-f]{64}\.png$/)
      }
      expect(page.html).not.toContain('/support/img/')
    }
  })

  it('the content files declare what the expected list says', () => {
    const declared = loadContent().flatMap((c) => c.meta.platforms.map((p) => `apps/${c.meta.app}/support/${p}/${c.meta.lang}/index.html`))
    expect(declared.sort()).toEqual(EXPECTED)
  })
})

describe('generator rules', () => {
  it('keeps only the blocks for the platform', () => {
    const body = 'all\n::: ios android\nphone\n:::\n::: web\nbrowser\n:::\nend'
    expect(selectPlatform(body, 'ios', 't')).toBe('all\nphone\nend')
    expect(selectPlatform(body, 'web', 't')).toBe('all\nbrowser\nend')
    expect(() => selectPlatform('::: ios\nx', 'ios', 't')).toThrow(/never closed/)
    expect(() => selectPlatform('::: amiga\nx\n:::', 'ios', 't')).toThrow(/unknown platform/)
  })

  it('slugs an h3 from its text, without entity debris', () => {
    expect(slugify('Drive says &quot;Set up Tenshu Control&quot;')).toBe('drive-says-set-up-tenshu-control')
    expect(slugify('Tenshu doesn&#39;t sync')).toBe('tenshu-doesnt-sync')
    const page = site.pages.find((p) => p.url === '/apps/drive/support/android/en')!
    expect(page.html).toContain('<h3 id="drive-says-set-up-tenshu-control">')
  })

  it('rejects a privacy claim without a source', () => {
    const ok = '<span class="source">Source: <a data-source="docs/a.md" href="x">A</a></span>'
    expect(() => privacyClaimsOf(`<h2 id="privacy">P</h2><ul><li>one ${ok}</li><li>two ${ok}</li><li>three</li></ul>`, 't')).toThrow(
      /without a source: "three"/,
    )
    expect(privacyClaimsOf(`<h2 id="privacy">P</h2><ul><li>1 ${ok}</li><li>2 ${ok}</li><li>3 ${ok}</li></ul>`, 't')).toHaveLength(3)
  })

  it('the stylesheet is compiled: apex tokens, no Tailwind directive left, no nesting', () => {
    const css = site.files.find((f) => f.file.startsWith('assets/support-'))!.content
    expect(css).toContain('--surface:')
    expect(css).toContain('prefers-color-scheme: dark')
    expect(css).not.toMatch(/@theme|@source|@import|@apply|@tailwind/)
    // Lightning CSS flattened Tailwind's nested variants, as the Vite build does.
    expect(css).not.toMatch(/&:hover|&\s*\{/)
  })

  it('the stylesheet holds every utility the shared header and footer use', () => {
    const css = site.files.find((f) => f.file.startsWith('assets/support-'))!.content
    // A sample across the chrome: the grid bar, the brand mark's size, the md: breakpoint that
    // swaps the menu for the links, the CTA's colours, the theme switch's checked pill.
    for (const selector of [
      '.grid-cols-\\[1fr_auto_1fr\\]',
      '.h-14',
      '.max-w-5xl',
      '.h-8',
      '.md\\:flex',
      '.md\\:hidden',
      '.bg-primary',
      '.text-on-primary',
      '.peer-checked\\:bg-primary',
      '.underline-offset-8',
      '.col-start-3',
    ]) {
      expect(css, selector).toContain(selector)
    }
    // The support-only rules sit in the components layer, under the chrome's utilities.
    expect(css.indexOf('@layer components')).toBeGreaterThan(0)
    expect(css.indexOf('.switcher--platform a')).toBeGreaterThan(css.indexOf('@layer components'))
    expect(css.indexOf('.switcher--platform a')).toBeLessThan(css.indexOf('@layer utilities'))
    // The picker's popover is drawn by its own utilities, in both surfaces.
    for (const selector of ['.group-open\\:rotate-180', '.bottom-full', '.top-full', '.max-w-\\[calc\\(100vw-3rem\\)\\]', '.shadow-lg']) {
      expect(css, selector).toContain(selector)
    }
  })
})

describe('redirect logic (/apps/<app>/support)', () => {
  const { detect, pick } = redirectLogic()

  it('detects the platform from the user agent', () => {
    expect(detect('Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X)', 5)).toBe('ios')
    // iPadOS asks for the desktop site and reports a Mac; touch gives it away.
    expect(detect('Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7)', 5)).toBe('ios')
    expect(detect('Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7)', 0)).toBe('macos')
    expect(detect('Mozilla/5.0 (Linux; Android 14; Pixel 8)', 5)).toBe('android')
    expect(detect('Mozilla/5.0 (Windows NT 10.0; Win64; x64)', 0)).toBe('windows')
    expect(detect('curl/8.0', 0)).toBeNull()
  })

  it('picks a language we have, else English', () => {
    expect(pick(['de-DE', 'en'], ['en', 'de'])).toBe('de')
    expect(pick(['de-DE'], ['en'])).toBe('en')
    expect(pick(['fr', 'EN-gb'], ['en'])).toBe('en')
    expect(pick(['fr-CA', 'en'], ['en', 'de', 'fr'])).toBe('fr')
  })

  it('a language picked from a switch wins over the browser\'s list; blocked storage is skipped', () => {
    const page = site.files.find((f) => f.file === 'apps/drive/support/index.html')!.content
    const script = /<script>(\(function\(\)\{var t=[\s\S]*?)<\/script>/.exec(page)![1]!
    const run = (stored: string | null, languages: string[], opts: { throws?: boolean } = {}) => {
      let target = ''
      const localStorage = {
        getItem: (k: string) => {
          if (opts.throws) throw new Error('SecurityError')
          return k === 'tenshu.lang' ? stored : null
        },
      }
      const navigator = { userAgent: 'Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X)', maxTouchPoints: 5, languages, language: languages[0] }
      const location = { hash: '', replace: (u: string) => void (target = u) }
      new Function('localStorage', 'navigator', 'location', script)(localStorage, navigator, location)
      return target
    }
    expect(run(null, ['de-DE', 'en'])).toBe('/apps/drive/support/ios/de')
    expect(run('fr', ['de-DE', 'en'])).toBe('/apps/drive/support/ios/fr')
    expect(run('xx', ['de-DE'])).toBe('/apps/drive/support/ios/de')
    expect(run('fr', ['de-DE'], { throws: true })).toBe('/apps/drive/support/ios/de')
  })

  it('falls back to the default platform for one the app does not ship', () => {
    const page = site.files.find((f) => f.file === 'apps/drive/support/index.html')!.content
    expect(page).toContain('<meta name="robots" content="noindex">')
    expect(page).toMatch(/d="web"/)
    // Without JavaScript the page is a list of links to every page.
    for (const p of site.pages.filter((p) => p.app === 'drive')) expect(page).toContain(`href="${p.url}"`)
  })
})

describe('screenshot manifests (§3.3)', () => {
  const SHA = (c: string) => c.repeat(64)
  const entry = (c: string, width = 1320, height = 2868) => ({ sha256: SHA(c), width, height, bytes: 1000 })

  async function withStore(manifests: Record<string, unknown>, run: (dir: string) => void | Promise<void>) {
    const dir = mkdtempSync(join(tmpdir(), 'support-store-'))
    try {
      for (const [app, m] of Object.entries(manifests)) {
        mkdirSync(join(dir, app), { recursive: true })
        writeFileSync(join(dir, app, 'screenshots.manifest.json'), typeof m === 'string' ? m : JSON.stringify(m))
      }
      await run(dir)
    } finally {
      rmSync(dir, { recursive: true, force: true })
    }
  }

  const BASE = 'https://assets.tenshu.app/sha256/'

  it('the iOS pages show the 6.9" iPhone capture, Android the phone; other platforms have no device yet', () => {
    expect(SUPPORT_DEVICE.ios).toBe('iphone69')
    expect(SUPPORT_DEVICE.android).toBe('phone')
    for (const p of ['macos', 'windows', 'linux', 'web'] as const) expect(SUPPORT_DEVICE[p]).toBeNull()
  })

  it('renders a listed screen as an <img> on R2, with its size, and the rest as placeholders', async () => {
    const control = {
      version: 1,
      base: BASE,
      screens: {
        'ios/iphone69/en/01-welcome': entry('a'),
        // The iPad capture of a screen the page uses is NOT what the page shows.
        'ios/ipad13/en/03-recovery-phrase': entry('b', 2064, 2752),
      },
    }
    await withStore({ control }, async (dir) => {
      const built = await buildSupportSite({ chrome, storeDir: dir })
      const ios = built.pages.find((p) => p.url === '/apps/control/support/ios/en')!
      // Light only: one plain <img>, shown in both themes.
      expect(ios.html).toContain(`<img class="shot" src="${BASE}${SHA('a')}.png" alt="Tenshu Control's welcome screen on iPhone`)
      expect(ios.html).not.toContain('shot--dark')
      expect(ios.html).toMatch(new RegExp(`src="${BASE}${SHA('a')}\\.png"[^>]* width="1320" height="2868"`))
      expect(ios.html).not.toContain(SHA('b'))
      expect(ios.html).toContain('data-slot="control:ios/iphone69/en/03-recovery-phrase"')
      // The same slot name on Android is not the iOS capture: its own key, a placeholder until published.
      const android = built.pages.find((p) => p.url === '/apps/control/support/android/en')!
      expect(android.html).not.toContain(BASE)
      expect(android.screenshotSlots.every((s) => s.image === null && s.key?.startsWith('android/phone/en/'))).toBe(true)
      // And a macOS page, which has no pipeline, has no key at all.
      const macos = built.pages.find((p) => p.url === '/apps/control/support/macos/en')!
      expect(macos.screenshotSlots.every((s) => s.image === null && s.key === null)).toBe(true)
      // Drive publishes nothing here: its own slots stay placeholders, while its iOS page borrows
      // Control's welcome (shot:control/01-welcome).
      // (A German or French page with no capture of its own borrows the English one.)
      for (const page of built.pages.filter((p) => p.app === 'drive')) {
        for (const slot of page.screenshotSlots) {
          expect(slot.image !== null, slot.key ?? '').toBe(slot.app === 'control' && slot.key === `ios/iphone69/${page.lang}/01-welcome`)
        }
      }
      const driveIos = built.pages.find((p) => p.url === '/apps/drive/support/ios/en')!
      expect(driveIos.html).toContain(`${BASE}${SHA('a')}.png`)
    })
  })

  it('a missing manifest means placeholders; a malformed one fails the build', async () => {
    await withStore({}, async (dir) => {
      expect(loadShotManifest('control', dir)).toBeNull()
      for (const page of (await buildSupportSite({ chrome, storeDir: dir })).pages) expect(page.html).not.toContain('<img class="shot"')
    })
    await withStore({ control: '{ not json' }, (dir) => expect(buildSupportSite({ chrome, storeDir: dir })).rejects.toThrow(/not JSON/))
    await withStore({ control: { version: 1, base: 'http://x/', screens: {} } }, (dir) =>
      expect(() => loadShotManifest('control', dir)).toThrow(/https URL/),
    )
    await withStore({ control: { version: 1, base: BASE, screens: { 'ios/iphone69/en/01-welcome': { sha256: 'zz', width: 1, height: 1 } } } }, (dir) =>
      expect(() => loadShotManifest('control', dir)).toThrow(/bad entry/),
    )
  })

  it('a slot can name another app (shot:control/NN-screen) and resolves in that app\'s manifest', () => {
    const control = { base: BASE, screens: { 'ios/iphone69/en/01-welcome': entry('c') } }
    const manifests = (app: string) => (app === 'control' ? control : null)
    expect(resolveShot(manifests, 'drive', 'ios', 'en', 'control', '01-welcome').image?.url).toBe(`${BASE}${SHA('c')}.png`)
    expect(resolveShot(manifests, 'drive', 'ios', 'en', undefined, '01-welcome').image).toBeNull()
    // No German capture of the screen: the English one stands in, under the page's own keys, so a
    // German capture published later takes over with no content change.
    const de = resolveShot(manifests, 'drive', 'ios', 'de', 'control', '01-welcome')
    expect(de.image?.url).toBe(`${BASE}${SHA('c')}.png`)
    expect(de.key).toBe('ios/iphone69/de/01-welcome')
    const withGerman = { base: BASE, screens: { ...control.screens, 'ios/iphone69/de/01-welcome': entry('d') } }
    expect(resolveShot((a) => (a === 'control' ? withGerman : null), 'drive', 'ios', 'de', 'control', '01-welcome').image?.url).toBe(`${BASE}${SHA('d')}.png`)
    // …and a language never mixes in the other's dark capture: German light + English dark is not a pair.
    const mixed = { base: BASE, screens: { 'ios/iphone69/de/01-welcome': entry('d'), 'ios/iphone69/en/dark/01-welcome': entry('e') } }
    expect(resolveShot((a) => (a === 'control' ? mixed : null), 'drive', 'ios', 'de', 'control', '01-welcome').dark).toBeNull()
    expect(resolveShot(manifests, 'drive', 'web', 'en', 'control', '01-welcome')).toEqual({
      app: 'control',
      key: null,
      darkKey: null,
      image: null,
      dark: null,
    })
  })
})
