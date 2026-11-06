// The apex origin's Content-Security-Policy — every page the build emits carries it as a
// `<meta http-equiv>` in <head>, and Caddy adds the header-only half (web/apex/Caddyfile).
// Policy, reasons and how to extend it: docs/web/architecture.md §5.3.
//
// ⚠⚠ Why most of it is a build-time <meta> and not the Caddy header: the origins the SPA talks to
// (the relay, commerce, every hosted app it frames) are INLINED by Vite at build time
// (`VITE_TENSHU_RELAY_URL`, `VITE_TENSHU_COMMERCE_URL`, `VITE_<ID>_ORIGIN`). Caddy's environment
// never sees them, and a runtime copy would drift from the bundle without anything noticing — the
// dev lab builds the same image against a different relay. Computed here from the same env, the
// policy is always the one for the bundle beside it, and the inline-script hashes are taken from the
// emitted HTML itself, so a changed script cannot outrun its hash.
//
// ⚠ What a <meta> policy cannot carry is `frame-ancestors` (and reporting): that is the Caddy
// header's job. Both policies are enforced; a request must pass each.
//
// ⚠ Pure Node (node:crypto), build-time only: vite.config.ts and support-pages/generate.ts import
// it, the SPA never does.
import { createHash } from 'node:crypto'
import { existsSync, readdirSync, readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

/** `store/` at the repo root — where the screenshot manifests live (support-pages/generate.ts STORE_DIR). */
export const STORE_DIR = join(dirname(fileURLToPath(import.meta.url)), '..', '..', '..', 'store')

/**
 * The production defaults the SPA falls back to when the build sets no override. ⚠ Must equal
 * `src/lib/relayUrl.ts` and `src/control/services/commerceApi.ts` — csp.origins.test.ts loads both
 * as a production build would and fails on drift.
 */
export const PROD_RELAY_URL = 'https://relay.tenshu.app'
export const PROD_COMMERCE_URL = 'https://commerce.service.tenshu.app'
/**
 * The tile host the web runtime reads basemap tiles from (`FetchTileTransport`). The runtime
 * runs in the APEX page, so the apex's own `connect-src` must name it: without it every map in every
 * app fails with `error_map_transport_failed`, however correct the bucket's CORS rule is. One host for
 * every build (dev lab included), because the Kotlin side hard-codes it: `TILES_HOST_URL` in
 * `map-region-service-proxy/…/TilesManifestKey.kt` (origins.test.ts reads it from there).
 */
export const TILES_HOST_URL = 'https://tiles.service.tenshu.app'
const DEV_RELAY_URL = 'http://localhost:8080'
const DEV_COMMERCE_URL = 'http://localhost:8081'

/** The subset of Vite's resolved `config.env` this module reads. */
export type BuildEnv = Readonly<Record<string, string | boolean | undefined>>

/** A configured URL's origin. Throws on a malformed one, so a typo fails the build, not the page. */
export function originOf(url: string): string {
  const origin = new URL(url).origin
  if (origin === 'null') throw new Error(`csp: "${url}" has no origin`)
  return origin
}

function configured(env: BuildEnv, name: string): string | undefined {
  const value = env[name]
  return typeof value === 'string' && value !== '' ? value : undefined
}

/**
 * The hosted app origins the SPA frames (`AppEmbed`) and that frame it back (the apps' hidden apex
 * iframe — that half is `frame-ancestors`, in the Caddyfile). Same resolution as `appOrigin()` in
 * src/apps/registry/types.ts: `VITE_<ID>_ORIGIN`, else `https://<id>.tenshu.app` in a production
 * build and the app's dev server otherwise. A dev-server build (`vite build --mode development`)
 * gets no app origins beyond what is configured — it is never served by Caddy.
 */
export function hostedAppOrigins(env: BuildEnv, appIds: readonly string[]): string[] {
  return appIds.flatMap((id) => {
    const value = configured(env, `VITE_${id.toUpperCase().replace(/-/g, '_')}_ORIGIN`) ?? (env.PROD ? `https://${id}.tenshu.app` : undefined)
    return value ? [originOf(value)] : []
  })
}

/** Where the SPA's own requests go: the relay (Kotlin SDK, share viewer), commerce and the tile host. */
export function connectOrigins(env: BuildEnv): string[] {
  const relay = configured(env, 'VITE_TENSHU_RELAY_URL') ?? (env.DEV ? DEV_RELAY_URL : PROD_RELAY_URL)
  const commerce = configured(env, 'VITE_TENSHU_COMMERCE_URL') ?? (env.DEV ? DEV_COMMERCE_URL : PROD_COMMERCE_URL)
  return [...new Set([originOf(relay), originOf(commerce), originOf(TILES_HOST_URL)])]
}

/**
 * Where the app screenshots are served from: the origin of every `base` in the committed
 * `store/<app>/screenshots.{manifest,webp}.json` (assets.tenshu.app, an R2 bucket). The landing
 * carousel and the support pages read the same files at build time, so this is exactly the set they
 * can point an <img> at.
 */
export function screenshotOrigins(storeDir: string = STORE_DIR): string[] {
  if (!existsSync(storeDir)) return []
  const origins = new Set<string>()
  for (const app of readdirSync(storeDir, { withFileTypes: true })) {
    if (!app.isDirectory()) continue
    for (const name of ['screenshots.manifest.json', 'screenshots.webp.json']) {
      const path = join(storeDir, app.name, name)
      if (!existsSync(path)) continue
      const base = (JSON.parse(readFileSync(path, 'utf8')) as { base?: unknown }).base
      if (typeof base === 'string') origins.add(originOf(base))
    }
  }
  return [...origins].sort()
}

/**
 * The executable inline `<script>` bodies in a page, as CSP hash sources. A script with `src` is
 * covered by `'self'`; a data block (`type="application/json"`, …) never executes and needs none.
 * ⚠ Hashes the exact bytes between the tags — what the browser hashes — so it must run on the
 * FINAL html (after every other transform).
 */
export function inlineScriptHashes(html: string): string[] {
  const hashes: string[] = []
  for (const match of html.matchAll(/<script\b([^>]*)>([\s\S]*?)<\/script\s*>/gi)) {
    const attrs = match[1] ?? ''
    const body = match[2] ?? ''
    if (/\ssrc\s*=/i.test(attrs)) continue
    const type = /\stype\s*=\s*["']?([^"'\s>]+)/i.exec(attrs)?.[1]?.toLowerCase()
    if (type && type !== 'module' && type !== 'text/javascript' && type !== 'application/javascript') continue
    hashes.push(`'sha256-${createHash('sha256').update(body, 'utf8').digest('base64')}'`)
  }
  return [...new Set(hashes)]
}

export interface PagePolicy {
  /** `inlineScriptHashes` of the page. */
  readonly scriptHashes: readonly string[]
  /** Extra `connect-src` origins (the SPA's relay + commerce; none for a static page). */
  readonly connect?: readonly string[]
  /** Extra `frame-src` origins (the hosted apps; none for a static page). */
  readonly frame?: readonly string[]
  /** Extra `img-src` origins (`screenshotOrigins()`). */
  readonly images?: readonly string[]
}

/**
 * The policy for one page. Every directive is spelled out rather than left to `default-src`, so a
 * reader sees what each kind of load may do. Why each one: docs/web/architecture.md §5.3.
 */
export function contentSecurityPolicy({ scriptHashes, connect = [], frame = [], images = [] }: PagePolicy): string {
  const directives: [string, ...string[]][] = [
    ['default-src', "'self'"],
    ['script-src', "'self'", ...scriptHashes],
    // React `style={…}` and the support pages' `style="aspect-ratio…"` are inline style ATTRIBUTES;
    // only 'unsafe-inline' admits those (a hash cannot name an attribute without 'unsafe-hashes').
    ['style-src', "'self'", "'unsafe-inline'"],
    // data: — contact avatars arrive as data URLs; blob: — decrypted previews and thumbnails;
    // the screenshot host — the landing carousel and the support pages.
    ['img-src', "'self'", 'data:', 'blob:', ...images],
    ['font-src', "'self'"],
    ['media-src', "'self'", 'blob:'],
    // blob: — the share viewer reads its own text blob back with fetch().
    ['connect-src', "'self'", 'blob:', ...connect],
    // blob: — the share viewer's PDF preview; the apps — AppEmbed.
    ['frame-src', "'self'", 'blob:', ...frame],
    ['worker-src', "'self'"],
    ['manifest-src', "'self'"],
    ['object-src', "'none'"],
    ['base-uri', "'none'"],
    ['form-action', "'self'"],
  ]
  return directives.map((d) => d.join(' ')).join('; ')
}

/**
 * The page with the policy at the top of <head>: a <meta> policy governs only what the parser meets
 * after it, so anything above it — the inline theme script — would run unchecked. Only the
 * `<meta charset>` goes first (moved there if a transform put a script above it): it loads nothing,
 * and it must sit in the first 1024 bytes, which a policy with hashes could otherwise push it past.
 */
export function withCspMeta(html: string, policy: string): string {
  if (/http-equiv\s*=\s*["']?content-security-policy/i.test(html)) throw new Error('csp: page already carries a CSP <meta>')
  const charset = /<meta\s+charset\s*=\s*["']?[\w-]+["']?\s*\/?>/i.exec(html)
  const rest = charset ? html.slice(0, charset.index) + html.slice(charset.index + charset[0].length) : html
  const head = /<head\b[^>]*>/i.exec(rest)
  if (!head) throw new Error('csp: page has no <head>')
  const at = head.index + head[0].length
  const meta = `<meta http-equiv="Content-Security-Policy" content="${policy.replaceAll('"', '&quot;')}">`
  return `${rest.slice(0, at)}\n${charset ? `${charset[0]}\n` : ''}${meta}${rest.slice(at)}`
}

/** `withCspMeta(html, contentSecurityPolicy({ … }))` with the page's own inline-script hashes. */
export function applyCsp(html: string, extra: Omit<PagePolicy, 'scriptHashes'> = {}): string {
  return withCspMeta(html, contentSecurityPolicy({ ...extra, scriptHashes: inlineScriptHashes(html) }))
}
