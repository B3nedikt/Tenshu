// @vitest-environment node
//
// The third-party licences page (licenses.ts, thirdParty.ts, sdkBundle.ts): it is built in every
// language from the installed packages, carries their licence texts in full, is linked from every
// footer — and holds the line on which licences the site may serve at all.
import { existsSync, readFileSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'
import { APEX_DIR, buildSupportSite } from './generate.ts'
import { loadSupportChrome } from './loadChrome.ts'
import { LICENSES, LICENSES_LANGUAGES, licensesUrl } from './licenses.ts'
import { SDK_BUNDLE_LIBRARIES, SDK_BUNDLE_NOT_BUNDLED } from './sdkBundle.ts'
import { BUILD_ONLY, collectThirdParty, uncoveredBundleModules } from './thirdParty.ts'

const REPO_ROOT = join(APEX_DIR, '..', '..')
const chrome = await loadSupportChrome('test')
const site = await buildSupportSite({ chrome })
const packages = collectThirdParty(APEX_DIR)
const page = (lang: string) => site.files.find((f) => f.file === `legal/licenses/${lang}/index.html`)?.content ?? ''

/**
 * Licences the site may serve without a decision. ⚠ A package outside this list fails the test on
 * purpose: copyleft (GPL/LGPL/AGPL/MPL/EPL/CC-BY-SA), SSPL, a custom or a missing licence needs a
 * maintainer to decide, not a lockfile bump to slip it in.
 */
const PERMISSIVE = new Set(['MIT', 'ISC', '0BSD', 'BSD-2-Clause', 'BSD-3-Clause', 'Apache-2.0', 'CC0-1.0', 'Unlicense', 'BlueOak-1.0.0', 'Zlib', 'Python-2.0'])
const permissive = (spdx: string) => spdx.replace(/[()]/g, '').split(/ OR /).some((part) => PERMISSIVE.has(part.trim()))

describe('third-party licences page', () => {
  it('is built in every language, at /legal/licenses/<lang>, plus a redirect at /legal/licenses', () => {
    for (const lang of LICENSES_LANGUAGES) {
      const html = page(lang)
      expect(html, lang).toContain(`<html lang="${lang}">`)
      expect(html, lang).toContain(`<title>${LICENSES[lang]!.title} — Tenshu</title>`)
      expect(html, lang).toContain(`<link rel="canonical" href="https://tenshu.app/legal/licenses/${lang}">`)
    }
    const redirect = site.files.find((f) => f.file === 'legal/licenses/index.html')!.content
    expect(redirect).toContain('content="0; url=/legal/licenses/en"')
    expect(redirect).toContain('<meta name="robots" content="noindex">')
    expect(redirect).toContain('<footer') // the site chrome, like every other static page
  })

  it('makes no open-source claim of its own — only the licence texts, as shipped, may say it', () => {
    for (const lang of LICENSES_LANGUAGES) {
      const frame = page(lang).replace(/<pre>[\s\S]*?<\/pre>/g, '').replace(/<summary>[\s\S]*?<\/summary>/g, '')
      expect(frame, lang).not.toMatch(/open[- ]source|code source ouvert/i)
    }
  })

  it('lists known dependencies with their licence names and full licence texts', () => {
    const html = page('en')
    for (const [name, license] of [['react', 'MIT'], ['react-dom', 'MIT'], ['i18next', 'MIT'], ['intl-messageformat', 'BSD-3-Clause'], ['tslib', '0BSD']]) {
      const version = packages.find((p) => p.name === name)!.version
      expect(html, name).toContain(`<span class="licenses__name">${name}</span> <span class="licenses__version">${version}</span> — <span class="licenses__license">${license}</span>`)
    }
    expect(html).toContain('Copyright (c) Meta Platforms, Inc. and affiliates.') // react's LICENSE, as shipped
    expect(html).toContain('Permission is hereby granted, free of charge')
    // The SDK bundle's Kotlin libraries, with the Apache-2.0 text in full.
    expect(html).toContain('<span class="licenses__name">org.jetbrains.kotlin:kotlin-stdlib, kotlin-dom-api-compat, kotlinx-atomicfu-runtime</span>')
    expect(html).toContain('<span class="licenses__name">@js-joda/core</span> <span class="licenses__version">3.2.0</span> — <span class="licenses__license">BSD-3-Clause</span>')
    expect(html).toContain('Copyright (c) 2016, Philipp Thürwächter &amp; Pattrick Hüper') // its LICENSE, verbatim
    expect(html).toContain('TERMS AND CONDITIONS FOR USE, REPRODUCTION, AND DISTRIBUTION')
  })

  it('every page\'s footer links the licences page in its language', () => {
    const htmlPages = site.files.filter((f) => f.file.endsWith('/index.html') && f.file !== 'legal/licenses/index.html' && f.content.includes('<footer'))
    expect(htmlPages.length).toBeGreaterThan(50)
    for (const { file, content } of htmlPages) {
      const lang = /<html lang="([a-z]+)">/.exec(content)![1]!
      const footer = content.slice(content.indexOf('<footer'))
      expect(footer, file).toContain(`href="${licensesUrl(lang)}"`)
    }
    expect(page('de')).toMatch(/<a [^>]*href="\/legal\/licenses\/de"[^>]*>Lizenzen<\/a>/)
  })

  it('walks the production graph only: no build plugin, no dev tool, no workspace package', () => {
    const names = new Set(packages.map((p) => p.name))
    for (const name of [...BUILD_ONLY, 'lightningcss', 'jiti', 'typescript', 'vitest', 'eslint', '@tenshu/sdk', '@tenshu/web-tokens']) {
      expect(names, name).not.toContain(name)
    }
    // Workspace packages' own dependencies are in (web-tokens' Radix), and so is the bundler runtime.
    for (const name of ['@radix-ui/react-dialog', 'react', 'scheduler', 'tailwindcss', 'vite', 'rolldown']) expect(names, name).toContain(name)
  })

  it('serves permissive licences only — a new one is a decision, not a lockfile bump', () => {
    const offending = [...packages, ...SDK_BUNDLE_LIBRARIES].filter((p) => !permissive(p.license)).map((p) => `${p.name}@${p.version} ${p.license}`)
    expect(offending).toEqual([])
  })

  it('flags a bundle module from a package the page does not cover', () => {
    const react = packages.find((p) => p.name === 'react')!
    const covered = join(react.dir, 'index.js')
    // Vite's own dependency (MPL-2.0, build-time only): installed beside it, deliberately not listed.
    const lightningcss = join(packages.find((p) => p.name === 'vite')!.dir, '..', 'lightningcss', 'node', 'index.js')
    expect(uncoveredBundleModules(['\0vite/preload-helper.js', covered, `${covered}?commonjs-proxy`, join(APEX_DIR, 'src', 'main.tsx')], packages)).toEqual([])
    expect(existsSync(lightningcss), lightningcss).toBe(true)
    expect(uncoveredBundleModules([lightningcss], packages)).toHaveLength(1)
  })

  // sdkBundle.ts is hand-maintained; the version catalogue is what Gradle builds the bundle from.
  it.skipIf(!existsSync(join(REPO_ROOT, 'gradle', 'libs.versions.toml')))('lists the SDK bundle\'s Kotlin libraries at the versions Gradle builds', () => {
    const toml = readFileSync(join(REPO_ROOT, 'gradle', 'libs.versions.toml'), 'utf8')
    const catalogue = (key: string) => new RegExp(`^${key}\\s*=\\s*"([^"]+)"`, 'm').exec(toml)?.[1]
    for (const lib of SDK_BUNDLE_LIBRARIES) {
      if (!lib.catalogVersion) continue
      const pinned = catalogue(lib.catalogVersion)
      expect(pinned, lib.catalogVersion).toBeDefined()
      // Equal, or the resolved variant of it (kotlinx-datetime 0.8.0 → 0.8.0-0.6.x-compat).
      expect(lib.version === pinned || lib.version.startsWith(`${pinned}-`), `${lib.name} ${lib.version} vs libs.versions.toml ${lib.catalogVersion} = ${pinned}`).toBe(true)
    }
  })

  // After a Gradle build, the Kotlin plugin's generated package.json names every npm package the
  // SDK's Kotlin graph declares — the bundle's npm side. Each must be listed (or excluded, with why).
  // `Drive-` is the Gradle root project's name (settings.gradle.kts), which prefixes every package.
  const kgpPackageJson = join(REPO_ROOT, 'build', 'js', 'packages', 'Drive-tenshu-sdk-js', 'package.json')
  it.skipIf(!existsSync(kgpPackageJson))('lists every npm package the SDK\'s Kotlin graph declares', () => {
    const declared = (JSON.parse(readFileSync(kgpPackageJson, 'utf8')) as { dependencies: Record<string, string> }).dependencies
    for (const [name, version] of Object.entries(declared)) {
      if (SDK_BUNDLE_NOT_BUNDLED[name]) continue
      expect(SDK_BUNDLE_LIBRARIES.find((l) => l.name === name)?.version, name).toBe(version)
    }
  })
})
