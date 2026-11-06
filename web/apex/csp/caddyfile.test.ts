// The header half of the apex CSP (web/apex/Caddyfile; csp.ts has the <meta> half). No Caddy here,
// so this reads the file: the directives that only a header can carry, and that every hosted app's
// site is allowed to frame the apex (its hidden bridge iframe) — a missing one breaks that app
// opened on its own, and nothing else would notice.
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'
import { APEX_DIR } from '../support-pages/generate.ts'
import { APP_PAGES } from '../support-pages/appPages.ts'
import { ANDROID_APPS } from '../support-pages/androidDownload.ts'
import { MACOS_APPS } from '../support-pages/macosDownload.ts'

const caddyfile = readFileSync(join(APEX_DIR, 'Caddyfile'), 'utf8')
const live = caddyfile
  .split('\n')
  .filter((line) => !line.trim().startsWith('#'))
  .join('\n')

function policyOf(header: string): Map<string, string[]> {
  return new Map(
    header
      .split(';')
      .map((d) => d.trim().split(/\s+/))
      .map(([name, ...sources]) => [name!, sources]),
  )
}

/** Every `header Content-Security-Policy "…"` value outside comments. */
const headers = [...live.matchAll(/header Content-Security-Policy "([^"]*)"/g)].map((m) => m[1]!)
/** The policies handed to the one site body, in file order: `hosted_app`'s, then `hosted_map_app`'s. */
const sitePolicies = [...live.matchAll(/import hosted_app_site \{args\[0\]\} "([^"]*)"/g)].map((m) => m[1]!)
/** A snippet's body, comments dropped. */
function snippet(name: string): string {
  const body = new RegExp(`^\\(${name}\\) \\{\\n([\\s\\S]*?)^\\}`, 'm').exec(live)?.[1]
  if (body === undefined) throw new Error(`no (${name}) snippet`)
  return body
}
/** The hosted apps' site blocks: `{$DRIVE_SITE} { import hosted_app drive … }` (or `hosted_map_app`). */
const appSites = [...live.matchAll(/^\{\$([A-Z_]+_SITE)\} \{\s*import hosted_(?:map_)?app /gm)].map((m) => m[1]!)
/** The sites on the map snippet. */
const mapSites = [...live.matchAll(/^\{\$([A-Z_]+_SITE)\} \{\s*import hosted_map_app /gm)].map((m) => m[1]!)

describe('the Caddyfile CSP header', () => {
  it('is set once on the apex and once in the one hosted-app site body, which takes it as an argument', () => {
    expect(headers).toEqual([expect.any(String), '{args[1]}'])
    expect(sitePolicies).toHaveLength(2)
    expect(appSites.length).toBeGreaterThanOrEqual(7)
    expect(mapSites).toEqual(expect.arrayContaining(['TIMELINE_SITE', 'MAPS_SITE', 'FIT_SITE']))
  })

  // Home (Wave 4) is read-only, draws no map and uses no shared Dialog: the plain frame-ancestors
  // policy, deliberately — no `style-src-elem 'unsafe-inline'` it does not need.
  it('Home is a plain hosted_app site, not on the map policy', () => {
    expect(appSites).toContain('HOME_SITE')
    expect(mapSites).not.toContain('HOME_SITE')
  })

  // Mia draws no map and opens no shared Dialog (no scroll-lock <style>), so it stays on the
  // frame-ancestors-only `hosted_app` policy. A switch to `hosted_map_app` must be a decision, not drift.
  it('Mia is a hosted app on the plain policy, not the map one', () => {
    expect(appSites).toContain('MIA_SITE')
    expect(mapSites).not.toContain('MIA_SITE')
  })

  // ⚠⚠ The two kinds of app share every non-CSP line: each snippet is the one import and nothing else,
  // so a header, cache or root change made for one cannot miss the other.
  it('hosted_app and hosted_map_app are each ONE import of hosted_app_site — nothing else to drift', () => {
    for (const name of ['hosted_app', 'hosted_map_app']) {
      expect(snippet(name).trim()).toMatch(/^import hosted_app_site \{args\[0\]\} "[^"]*"$/)
    }
    const site = snippet('hosted_app_site')
    for (const line of ['encode zstd gzip', 'root * /srv-apps/{args[0]}', 'try_files {path} /index.html', 'file_server', 'import access_log {args[0]}']) {
      expect(site).toContain(line)
    }
  })

  const apex = policyOf(headers[0]!)
  const [app, mapApp] = sitePolicies.map(policyOf)

  it('lets the apex be framed only by itself and every hosted app', () => {
    expect(apex!.get('frame-ancestors')).toEqual(["'self'", ...appSites.map((site) => `{$${site}}`)])
  })

  it('a hosted app stays frameable by its configured ancestors only', () => {
    expect(app!.get('frame-ancestors')).toEqual(['{args[1]}'])
    expect(mapApp!.get('frame-ancestors')).toEqual(['{args[1]}'])
  })

  // how-to new-web-app.md §2b, the complete set: the map lines (blob: workers, data:/blob: images),
  // the apex as the only frame it dials, and never a wider connect-src (tiles come over the bridge).
  it('a map app gets the how-to map policy, and nothing wider', () => {
    expect(Object.fromEntries(mapApp!)).toEqual({
      'default-src': ["'self'"],
      'script-src': ["'self'"],
      'style-src': ["'self'"],
      // The shared Radix Dialog's scroll lock injects a <style> element whose CSS embeds the
      // measured scrollbar gap, so no hash can match it. Only elements get 'unsafe-inline';
      // style attributes stay 'self' (React's style={} goes through the CSSOM, which CSP allows).
      'style-src-elem': ["'self'", "'unsafe-inline'"],
      'connect-src': ["'self'"],
      'img-src': ["'self'", 'data:', 'blob:'],
      'worker-src': ['blob:'],
      'child-src': ['blob:'],
      'frame-src': ['{args[1]}'],
      'frame-ancestors': ['{args[1]}'],
      'object-src': ["'none'"],
      'base-uri': ["'none'"],
      'form-action': ["'self'"],
    })
  })

  it('a non-map app gets exactly its four directives', () => {
    expect(Object.fromEntries(app!)).toEqual({
      'frame-ancestors': ['{args[1]}'],
      'object-src': ["'none'"],
      'base-uri': ["'none'"],
      'form-action': ["'self'"],
    })
  })

  it('a map app admits inline <style> elements only, never inline style attributes', () => {
    expect(mapApp!.get('style-src')).toEqual(["'self'"])
    expect(mapApp!.has('style-src-attr')).toBe(false)
    expect(mapApp!.get('style-src-elem')).toContain("'unsafe-inline'")
  })

  it('carries the origin-independent hardening on all three', () => {
    for (const policy of [apex!, app!, mapApp!]) {
      expect(policy.get('object-src')).toEqual(["'none'"])
      expect(policy.get('base-uri')).toEqual(["'none'"])
      expect(policy.get('form-action')).toEqual(["'self'"])
    }
  })

  it('never contains a comma — in a CSP header that starts a second policy', () => {
    for (const header of [...headers, ...sitePolicies]) expect(header).not.toContain(',')
  })
})

// `/roadmap` (support-pages/roadmap.ts) is the header's link on every page: without this redirect
// Caddy would serve the build's static language list there instead of the reader's language.
describe('the Caddyfile language redirects', () => {
  it('sends /roadmap to /roadmap/<lang> through the legal pages\' Accept-Language map, uncached', () => {
    expect(live).toMatch(/@roadmapNeutral path_regexp roadmap \^\/roadmap\/\?\$/)
    expect(live).toContain('redir @roadmapNeutral /roadmap/{legal_lang} 302')
    expect(live).toMatch(/header @roadmapNeutral \{\s*Vary Accept-Language\s*Cache-Control no-store\s*\}/)
  })

  it('sends /pricing to /pricing/<lang> the same way (support-pages/pricing.ts, the header\'s Pricing link)', () => {
    expect(live).toMatch(/@pricingNeutral path_regexp pricing \^\/pricing\/\?\$/)
    expect(live).toContain('redir @pricingNeutral /pricing/{legal_lang} 302')
    expect(live).toMatch(/header @pricingNeutral \{\s*Vary Accept-Language\s*Cache-Control no-store\s*\}/)
  })

  it('sends /apps/<app> to /apps/<app>/<lang> for exactly the apps with a page (support-pages/appPages.ts)', () => {
    const ids = APP_PAGES.map((p) => p.app).join('|')
    expect(live).toContain(`@appPageNeutral path_regexp apppage ^/apps/(${ids})/?$`)
    expect(live).toContain('redir @appPageNeutral /apps/{re.apppage.1}/{legal_lang} 302')
    expect(live).toMatch(/header @appPageNeutral \{\s*Vary Accept-Language\s*Cache-Control no-store\s*\}/)
  })

  // ⚠ /apps/control/download/android is compiled into the Android apps
  // (CONTROL_ANDROID_DOWNLOAD_URL): it must 302 to a page, never fall through to the SPA.
  it('sends /apps/<app>/download/android to its language for exactly the Android apps (support-pages/androidDownload.ts)', () => {
    expect(ANDROID_APPS).toContain('control')
    expect(live).toContain(`@androidDownloadNeutral path_regexp androiddl ^/apps/(${ANDROID_APPS.join('|')})/download/android/?$`)
    expect(live).toContain('redir @androidDownloadNeutral /apps/{re.androiddl.1}/download/android/{legal_lang} 302')
    expect(live).toMatch(/header @androidDownloadNeutral \{\s*Vary Accept-Language\s*Cache-Control no-store\s*\}/)
    const re = new RegExp(/@androidDownloadNeutral path_regexp androiddl (\S+)/.exec(live)![1]!)
    expect(re.test('/apps/control/download/android')).toBe(true)
    expect(re.test('/apps/control/download/android/')).toBe(true)
    expect(re.test('/apps/control/download/android/en')).toBe(false)
    expect(re.test('/apps/control/download')).toBe(false)
  })

  it('sends /apps/<app>/download/macos to its language for exactly the macOS apps, never macos.json (support-pages/macosDownload.ts)', () => {
    expect(live).toContain(`@macosDownloadNeutral path_regexp macosdl ^/apps/(${MACOS_APPS.join('|')})/download/macos/?$`)
    expect(live).toContain('redir @macosDownloadNeutral /apps/{re.macosdl.1}/download/macos/{legal_lang} 302')
    expect(live).toMatch(/header @macosDownloadNeutral \{\s*Vary Accept-Language\s*Cache-Control no-store\s*\}/)
    const re = new RegExp(/@macosDownloadNeutral path_regexp macosdl (\S+)/.exec(live)![1]!)
    expect(re.test('/apps/photos/download/macos')).toBe(true)
    expect(re.test('/apps/photos/download/macos/')).toBe(true)
    expect(re.test('/apps/photos/download/macos/en')).toBe(false)
    // The casks' livecheck reads this file; a redirect would hand brew an HTML page.
    expect(re.test('/apps/photos/download/macos.json')).toBe(false)
  })
})
