// The CSP's origins are computed at build time from Vite's env (csp.ts), the SPA's at runtime from
// import.meta.env. They are two spellings of one rule; this loads the SPA's modules as a production
// build would and fails when the two disagree — a refused relay is a dead site, silently.
import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { connectOrigins, hostedAppOrigins, originOf, STORE_DIR } from './csp.ts'
import { hostedAppIds } from './vitePlugin.ts'

// Non-literal specifiers keep these src/ modules (vite/client typed) out of this node-typed project.
const SRC = '../src/'
const load = <T>(path: string): Promise<T> => import(/* @vite-ignore */ SRC + path) as Promise<T>

interface App {
  readonly id: string
  origin(): string
}

/**
 * The tile host the Kotlin web runtime fetches (`FetchTileTransport(TILES_HOST_URL)`), read from its
 * source: it is a Kotlin constant, so no TS module can stand in for "what the runtime reaches".
 */
function kotlinTilesHost(): string {
  const file = join(dirname(STORE_DIR), 'map-region-service-proxy/src/commonMain/kotlin/org/tenshu/mapregion/proxy/TilesManifestKey.kt')
  const match = /const val TILES_HOST_URL: String = "([^"]+)"/.exec(readFileSync(file, 'utf8'))
  if (!match) throw new Error('TILES_HOST_URL not found in TilesManifestKey.kt')
  return match[1]!
}

async function runtimeOrigins() {
  vi.resetModules()
  const { RELAY_URL } = await load<{ RELAY_URL: string }>('lib/relayUrl.ts')
  const { COMMERCE_URL } = await load<{ COMMERCE_URL: string }>('control/services/commerceApi.ts')
  const { HOSTED_APPS } = await load<{ HOSTED_APPS: readonly App[] }>('apps/registry/index.ts')
  return { connect: [originOf(RELAY_URL), originOf(COMMERCE_URL), originOf(kotlinTilesHost())], apps: HOSTED_APPS.map((a) => originOf(a.origin())), ids: HOSTED_APPS.map((a) => a.id) }
}

describe('the CSP names the origins the SPA actually reaches', () => {
  afterEach(() => vi.unstubAllEnvs())

  it('reads the same hosted apps as the registry', async () => {
    const { ids } = await runtimeOrigins()
    expect(hostedAppIds()).toEqual([...ids].sort())
  })

  it('in a production build with nothing configured', async () => {
    vi.stubEnv('PROD', true)
    vi.stubEnv('DEV', false)
    const runtime = await runtimeOrigins()
    const env = { PROD: true, DEV: false }
    expect(connectOrigins(env)).toEqual(runtime.connect)
    expect(hostedAppOrigins(env, runtime.ids)).toEqual(runtime.apps)
  })

  it('in a build configured for another host (the dev lab)', async () => {
    const env: Record<string, string | boolean> = {
      PROD: true,
      DEV: false,
      VITE_TENSHU_RELAY_URL: 'https://relay.tenshu.xyz',
      VITE_TENSHU_COMMERCE_URL: 'https://commerce.tenshu.xyz',
    }
    for (const id of hostedAppIds()) env[`VITE_${id.toUpperCase().replace(/-/g, '_')}_ORIGIN`] = `https://${id}.tenshu.xyz`
    vi.stubEnv('PROD', true)
    vi.stubEnv('DEV', false)
    for (const [name, value] of Object.entries(env)) if (typeof value === 'string') vi.stubEnv(name, value)
    const runtime = await runtimeOrigins()
    expect(connectOrigins(env)).toEqual(runtime.connect)
    expect(hostedAppOrigins(env, runtime.ids)).toEqual(runtime.apps)
    expect(runtime.apps.every((o) => o.endsWith('.tenshu.xyz'))).toBe(true)
  })
})
