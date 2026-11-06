// F5 — per-app gateway permissions, driven through the REAL `serveProxyGateway` (the bridge module
// is plain TypeScript; only the handlers would need the Kotlin runtime, and these are stubs).
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { isMethodPermitted, serveProxyGateway, type ProxyGateway } from '@tenshu/sdk/bridge'
import {
  CONTACTS_METHODS,
  DRIVE_METHODS,
  SESSION_METHODS,
  configuredAppOrigins,
  gatewayPermissions,
} from './gatewayPermissions'
import { HOSTED_APPS } from '../apps/registry'

const DRIVE = 'https://drive.tenshu.app'
const CONTACTS = 'https://contacts.tenshu.app'
const FOREIGN = 'https://evil.example'

const REQUEST = 'tenshu/bridge/req'
const RESPONSE = 'tenshu/bridge/res'
const HELLO = 'tenshu/bridge/hello'
const SUBSCRIBE = 'tenshu/bridge/sub'
const EVENT = 'tenshu/bridge/evt'

function fire(data: unknown, origin: string, source: { postMessage: ReturnType<typeof vi.fn> }) {
  const ev = new MessageEvent('message', { data, origin })
  Object.defineProperty(ev, 'source', { value: source, configurable: true })
  window.dispatchEvent(ev)
}

// Every method either app might reach for, each answering its own name.
const METHODS = [
  'bridge.ping',
  'runtime.lease',
  'fs.list',
  'fs.delete',
  'membership.grant',
  'sharedFile.browse',
  'contact.connected',
  'contact.createInvite',
  'contact.setVerified',
  'contact.merge',
  'contact.delete',
  'contact.list',
]
const handlers = Object.fromEntries(METHODS.map((m) => [m, () => m]))
const subscriptions = {
  'sync.health.observe': () => () => undefined,
  'contact.list.observe': () => () => undefined,
}

describe('gateway permissions (F5)', () => {
  let gateway: ProxyGateway | null = null
  afterEach(() => {
    gateway?.dispose()
    gateway = null
  })

  function serve() {
    gateway = serveProxyGateway({
      permissions: gatewayPermissions({ drive: [DRIVE], contacts: [CONTACTS] }),
      handlers,
      subscriptions,
    })
  }

  async function call(origin: string, method: string) {
    const source = { postMessage: vi.fn() }
    fire({ kind: REQUEST, id: `${origin}-${method}`, method, params: null }, origin, source)
    await vi.waitFor(() => expect(source.postMessage).toHaveBeenCalled())
    return source.postMessage.mock.calls[0]?.[0] as { ok: boolean; result?: unknown; error?: string }
  }

  it('serves an allowed call from each app', async () => {
    serve()
    expect(await call(DRIVE, 'fs.list')).toMatchObject({ kind: RESPONSE, ok: true, result: 'fs.list' })
    expect(await call(DRIVE, 'contact.connected')).toMatchObject({ ok: true })
    expect(await call(CONTACTS, 'contact.list')).toMatchObject({ ok: true, result: 'contact.list' })
    expect(await call(CONTACTS, 'contact.setVerified')).toMatchObject({ ok: true })
    for (const origin of [DRIVE, CONTACTS]) {
      expect(await call(origin, 'bridge.ping')).toMatchObject({ ok: true })
      expect(await call(origin, 'runtime.lease')).toMatchObject({ ok: true })
    }
  })

  // ⚠⚠ The threat this exists for: script on Drive's origin marking a key verified without anyone
  // comparing digits (A24), or folding/deleting people.
  it.each(['contact.setVerified', 'contact.merge', 'contact.delete', 'contact.list'])(
    'refuses Drive the address-book method %s',
    async (method) => {
      serve()
      const response = await call(DRIVE, method)
      expect(response.ok).toBe(false)
      expect(response.error).toMatch(/^not permitted: /)
    },
  )

  it.each(['fs.list', 'fs.delete', 'membership.grant', 'sharedFile.browse'])(
    'refuses Contacts the Drive method %s',
    async (method) => {
      serve()
      const response = await call(CONTACTS, method)
      expect(response.ok).toBe(false)
      expect(response.error).toMatch(/^not permitted: /)
    },
  )

  it('says "not permitted", not "unknown method", for a method that exists but is not yours', async () => {
    serve()
    expect((await call(DRIVE, 'contact.merge')).error).not.toMatch(/unknown method/)
    // ...and an admitted origin calling a method nobody has learns only that it is not its own: the
    // allowlist is checked first and holds exact names, so an app cannot probe which methods exist.
    expect((await call(CONTACTS, 'contact.nope')).error).toMatch(/not permitted/)
  })

  it('ignores an unknown origin entirely — no ready, no answer', async () => {
    serve()
    const source = { postMessage: vi.fn() }
    fire({ kind: HELLO }, FOREIGN, source)
    fire({ kind: REQUEST, id: 'x', method: 'bridge.ping', params: null }, FOREIGN, source)
    await new Promise((r) => setTimeout(r, 10))
    expect(source.postMessage).not.toHaveBeenCalled()
  })

  it('covers subscriptions the same way', async () => {
    serve()
    const source = { postMessage: vi.fn() }
    fire({ kind: SUBSCRIBE, id: 'd1', method: 'contact.list.observe', params: null }, DRIVE, source)
    await vi.waitFor(() => expect(source.postMessage).toHaveBeenCalled())
    expect(source.postMessage.mock.calls[0]?.[0]).toMatchObject({
      kind: EVENT,
      id: 'd1',
      error: expect.stringMatching(/^not permitted: /),
    })

    const ok = { postMessage: vi.fn() }
    fire({ kind: SUBSCRIBE, id: 'c1', method: 'contact.list.observe', params: null }, CONTACTS, ok)
    fire({ kind: SUBSCRIBE, id: 'c2', method: 'sync.health.observe', params: null }, CONTACTS, ok)
    await new Promise((r) => setTimeout(r, 10))
    expect(ok.postMessage).not.toHaveBeenCalled() // started, nothing emitted, nothing refused
  })

  it('refuses one origin configured for two apps rather than merging their lists', () => {
    expect(() => gatewayPermissions({ drive: [DRIVE], contacts: [DRIVE] })).toThrow(/both drive and contacts/)
  })
})

describe('isMethodPermitted', () => {
  it('matches exact names and `.*` namespaces, and nothing looser', () => {
    expect(isMethodPermitted(['contact.*'], 'contact.list')).toBe(true)
    expect(isMethodPermitted(['contact.*'], 'contact.list.observe')).toBe(true)
    expect(isMethodPermitted(['contact.*'], 'contactx.list')).toBe(false)
    expect(isMethodPermitted(['contact.*'], 'contact')).toBe(false)
    expect(isMethodPermitted(['fs.list'], 'fs.listAll')).toBe(false)
    expect(isMethodPermitted(['*'], 'fs.list')).toBe(false)
  })
})

// The lists are only right if they match what each app's UI actually calls. Read the method table
// out of each app's bridge client as text — no cross-package import.
function methodsCalledBy(app: string): string[] {
  const source = readFileSync(resolve(__dirname, `../../../${app}/src/lib/proxy.ts`), 'utf8')
  const table = source.slice(source.indexOf('export const ProxyMethod = {'))
  const body = table.slice(0, table.indexOf('} as const'))
  return [...body.matchAll(/:\s*'([^']+)'/g)].map((m) => m[1]!)
}

describe('allowlists vs the apps’ own method tables', () => {
  it('Drive is allowed exactly what web/drive calls — nothing more', () => {
    expect([...DRIVE_METHODS].sort()).toEqual([...new Set(methodsCalledBy('drive'))].sort())
  })

  it('Contacts is allowed exactly what web/contacts calls — nothing more', () => {
    const called = methodsCalledBy('contacts')
    expect(called.length).toBeGreaterThan(10)
    expect([...CONTACTS_METHODS].sort()).toEqual([...new Set(called)].sort())
  })

  it('Contacts is refused the handshake ops it never runs', () => {
    for (const method of ['contact.redeem', 'contact.awaitAck', 'contact.listInvites', 'contact.connected', 'contact.pending']) {
      expect(isMethodPermitted(CONTACTS_METHODS, method), method).toBe(false)
    }
  })

  it('both apps get the session methods', () => {
    for (const method of SESSION_METHODS) {
      expect(isMethodPermitted(DRIVE_METHODS, method)).toBe(true)
      expect(isMethodPermitted(CONTACTS_METHODS, method)).toBe(true)
    }
  })
})

// F4: the same pin for every registered app, so an app added after Drive and Contacts is held to
// its own table from its first commit (each app's list now lives in `apps/registry/apps/<id>.ts`).
describe('every registered app', () => {
  it.each(HOSTED_APPS.map((app) => [app.id, app] as const))(
    '%s is allowed exactly what its web/<id> calls',
    (id, app) => {
      expect([...app.methods].sort()).toEqual([...new Set(methodsCalledBy(id))].sort())
    },
  )

  // ⚠⚠ Home (web-app-suite Wave 4, run B): READ-ONLY. `web/home` is allowed exactly the eight
  // `HOME_METHODS` reads (plus the session methods), each by its exact name — never a `home.*` prefix,
  // which would admit any `home.` write a later module added. And no other app reaches `home.*` at all.
  // ⚠ Spelled out, not imported: `gateway/home.ts` value-imports the SDK. `proxyGateway.home.test.ts`
  // pins `HOME_METHODS` to the same eight names.
  const HOME_METHODS = [
    'home.devices',
    'home.devices.observe',
    'home.hubStorage',
    'home.hubStorage.observe',
    'home.automations',
    'home.automations.observe',
    'home.activityPage',
    'home.activity.changes',
  ]

  it('Home is allowed exactly HOME_METHODS, by exact name, and nothing else in home.*', () => {
    const home = HOSTED_APPS.find((app) => app.id === 'home')!
    expect(home).toBeDefined()
    expect(home.methods.filter((m) => m === 'home' || m.startsWith('home.')).sort()).toEqual([...HOME_METHODS].sort())
    expect(home.methods.filter((m) => m.endsWith('*'))).toEqual([])
    // Probes of what a Home write would be called: none may pass (no prefix grants them).
    for (const method of [
      'home.anything',
      'home.setPower',
      'home.deviceCommand',
      'home.renameDevice',
      'home.routineSave',
      'home.routineDelete',
      'home.routineSetEnabled',
      'home.routineRunNow',
      'home.pairing',
    ]) {
      expect(isMethodPermitted(home.methods, method), method).toBe(false)
    }
    for (const method of SESSION_METHODS) expect(isMethodPermitted(home.methods, method), method).toBe(true)
  })

  it('no app but Home may call home.*', () => {
    const probes = [...HOME_METHODS, 'home.anything']
    for (const app of HOSTED_APPS) {
      if (app.id === 'home') continue
      expect(app.methods.filter((m) => m === 'home' || m.startsWith('home.')), app.id).toEqual([])
      for (const method of probes) expect(isMethodPermitted(app.methods, method), `${app.id} ${method}`).toBe(false)
    }
  })

  it('admits each app at its production host and its configured origin, with its own list', () => {
    const permissions = gatewayPermissions(configuredAppOrigins())
    for (const app of HOSTED_APPS) {
      expect(permissions[app.prodOrigin], app.id).toBe(app.methods)
      expect(permissions[app.origin()], app.id).toBe(app.methods)
    }
    expect(Object.keys(permissions)).toHaveLength(HOSTED_APPS.length * 2)
  })
})

