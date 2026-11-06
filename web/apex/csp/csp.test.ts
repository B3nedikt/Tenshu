import { createHash } from 'node:crypto'
import { describe, expect, it } from 'vitest'
import { THEME_BOOT_SCRIPT } from '../../shared/theme.ts'
import { buildSupportSite } from '../support-pages/generate.ts'
import { loadSupportChrome } from '../support-pages/loadChrome.ts'
import {
  applyCsp,
  connectOrigins,
  contentSecurityPolicy,
  hostedAppOrigins,
  inlineScriptHashes,
  originOf,
  PROD_COMMERCE_URL,
  PROD_RELAY_URL,
  screenshotOrigins,
  TILES_HOST_URL,
  withCspMeta,
} from './csp.ts'

const sha = (body: string) => `'sha256-${createHash('sha256').update(body, 'utf8').digest('base64')}'`

/** The policy in a page's CSP <meta>, as `directive → sources`. */
function metaPolicy(html: string): Map<string, string[]> {
  const content = /<meta http-equiv="Content-Security-Policy" content="([^"]*)">/.exec(html)?.[1]
  if (content === undefined) throw new Error('no CSP <meta>')
  return new Map(
    content
      .replaceAll('&quot;', '"')
      .split(';')
      .map((d) => d.trim().split(/\s+/))
      .map(([name, ...sources]) => [name!, sources]),
  )
}

describe('inlineScriptHashes', () => {
  it('hashes the exact bytes of every executable inline script, and nothing else', () => {
    const html = [
      `<script>${THEME_BOOT_SCRIPT}</script>`,
      '<script type="module">  import "/x.js"\n</script>',
      '<script type="module" src="/assets/index.js"></script>',
      '<script type="application/json">{"a":1}</script>',
      '<SCRIPT>a()</SCRIPT >',
    ].join('\n')
    expect(inlineScriptHashes(html)).toEqual([sha(THEME_BOOT_SCRIPT), sha('  import "/x.js"\n'), sha('a()')])
  })
})

describe('withCspMeta', () => {
  it('goes above every script, leaving only <meta charset> ahead of it', () => {
    const html = withCspMeta('<!doctype html><html><head><script>x()</script><meta charset="UTF-8" /><title>t</title></head></html>', 'P')
    expect(html).toBe(
      '<!doctype html><html><head>\n<meta charset="UTF-8" />\n<meta http-equiv="Content-Security-Policy" content="P"><script>x()</script><title>t</title></head></html>',
    )
  })

  it('refuses a page that already has one, or has no <head>', () => {
    expect(() => withCspMeta('<html><head><meta http-equiv="content-security-policy" content="x"></head></html>', 'P')).toThrow()
    expect(() => withCspMeta('<p>no head</p>', 'P')).toThrow()
  })
})

describe('contentSecurityPolicy', () => {
  const policy = new Map(
    contentSecurityPolicy({ scriptHashes: [sha('x')], connect: ['https://relay.example'], frame: ['https://drive.example'] })
      .split('; ')
      .map((d) => d.split(' '))
      .map(([name, ...sources]) => [name!, sources]),
  )

  it('carries the hardening directives', () => {
    expect(policy.get('object-src')).toEqual(["'none'"])
    expect(policy.get('base-uri')).toEqual(["'none'"])
    expect(policy.get('form-action')).toEqual(["'self'"])
    expect(policy.get('default-src')).toEqual(["'self'"])
  })

  it('runs only same-origin and hashed scripts — no unsafe-inline, no eval', () => {
    expect(policy.get('script-src')).toEqual(["'self'", sha('x')])
  })

  it('names the configured origins and blob: where the share viewer needs them', () => {
    expect(policy.get('connect-src')).toEqual(["'self'", 'blob:', 'https://relay.example'])
    expect(policy.get('frame-src')).toEqual(["'self'", 'blob:', 'https://drive.example'])
    expect(policy.get('img-src')).toEqual(["'self'", 'data:', 'blob:'])
  })
})

describe('origins', () => {
  it('reduces a configured URL to its origin, and fails the build on a malformed one', () => {
    expect(originOf('https://relay.example:8443/path?q')).toBe('https://relay.example:8443')
    expect(() => originOf('relay.example')).toThrow()
  })

  it('falls back to production, or to the dev servers in a dev build', () => {
    expect(connectOrigins({ PROD: true, DEV: false })).toEqual([PROD_RELAY_URL, PROD_COMMERCE_URL, TILES_HOST_URL])
    expect(connectOrigins({ PROD: false, DEV: true })).toEqual(['http://localhost:8080', 'http://localhost:8081', TILES_HOST_URL])
    expect(connectOrigins({ PROD: true, VITE_TENSHU_RELAY_URL: 'https://relay.tenshu.xyz/', VITE_TENSHU_COMMERCE_URL: '' })).toEqual([
      'https://relay.tenshu.xyz',
      PROD_COMMERCE_URL,
      TILES_HOST_URL,
    ])
  })

  it('resolves each hosted app like appOrigin(): configured, else production in a production build', () => {
    expect(hostedAppOrigins({ PROD: true, VITE_DRIVE_ORIGIN: 'https://drive.tenshu.xyz' }, ['drive', 'contacts', 'my-app'])).toEqual([
      'https://drive.tenshu.xyz',
      'https://contacts.tenshu.app',
      'https://my-app.tenshu.app',
    ])
    expect(hostedAppOrigins({ PROD: true, VITE_MY_APP_ORIGIN: 'https://m.example' }, ['my-app'])).toEqual(['https://m.example'])
    expect(hostedAppOrigins({ PROD: false, DEV: true }, ['drive'])).toEqual([])
  })
})

describe('screenshotOrigins', () => {
  it('is the screenshot host the committed store manifests name', () => {
    expect(screenshotOrigins()).toEqual(['https://assets.tenshu.app'])
  })
})

describe('the support pages', async () => {
  const site = await buildSupportSite({ chrome: await loadSupportChrome('test') })
  const pages = site.files.filter((f) => f.file.endsWith('.html'))

  it('every page carries the policy, above its first script, hashing each of its inline scripts', () => {
    expect(pages.length).toBeGreaterThan(10)
    for (const { file, content } of pages) {
      const meta = content.indexOf('<meta http-equiv="Content-Security-Policy"')
      expect(meta, file).toBeGreaterThan(0)
      const firstScript = content.indexOf('<script')
      if (firstScript >= 0) expect(meta, file).toBeLessThan(firstScript)
      const scriptSrc = metaPolicy(content).get('script-src')!
      expect(scriptSrc[0], file).toBe("'self'")
      for (const hash of inlineScriptHashes(content)) expect(scriptSrc, file).toContain(hash)
      expect(scriptSrc.join(' '), file).not.toMatch(/unsafe/)
    }
  })

  it('a static page talks to nobody and frames nothing beyond its own origin', () => {
    for (const { file, content } of pages) {
      const policy = metaPolicy(content)
      expect(policy.get('connect-src'), file).toEqual(["'self'", 'blob:'])
      expect(policy.get('frame-src'), file).toEqual(["'self'", 'blob:'])
    }
  })

  it('every image a page points at is one its img-src allows', () => {
    let absolute = 0
    for (const { file, content } of pages) {
      const imgSrc = metaPolicy(content).get('img-src')!
      for (const [, attr] of content.matchAll(/\s(?:src|srcset)="([^"]*)"/g)) {
        for (const url of attr!.split(',').map((c) => c.trim().split(/\s+/)[0]!)) {
          if (!/^https?:/.test(url)) continue
          absolute++
          expect(imgSrc, `${file}: ${url}`).toContain(new URL(url).origin)
        }
      }
    }
    // The fixture really has off-origin screenshots, or this test proves nothing.
    expect(absolute).toBeGreaterThan(0)
  })

  it('applyCsp on an already-finished page is refused, so a page is never double-stamped', () => {
    expect(() => applyCsp(pages[0]!.content)).toThrow()
  })
})
