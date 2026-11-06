// The composed gateway (web-app-suite.md F4): every module's handlers in one table, no method
// defined twice, and nothing an app is allowed to call left without a handler.
//
// ⚠ Every `@tenshu/sdk/*` module the gateway modules import is mocked: the real ones value-import
// the Kotlin bundle, and only the wiring is under test here (the ops are pinned in jsTest).
import { describe, expect, it, vi } from 'vitest'

vi.mock('@tenshu/sdk/core', () => ({ TenshuSdk: {} }))
vi.mock('@tenshu/sdk/files', () => ({ FileSystem: {} }))
vi.mock('@tenshu/sdk/content', () => ({ ContentStream: {} }))
vi.mock('@tenshu/sdk/share', () => ({ Shares: {} }))
vi.mock('@tenshu/sdk/contact', () => ({ Contacts: {} }))
vi.mock('@tenshu/sdk/membership', () => ({ Membership: {} }))
vi.mock('@tenshu/sdk/sharedFiles', () => ({ SharedFiles: {} }))
vi.mock('@tenshu/sdk/storage', () => ({ Storage: {} }))
vi.mock('@tenshu/sdk/syncHealth', () => ({ SyncHealth: {} }))
vi.mock('@tenshu/sdk/syncStatus', () => ({ SyncStatus: {} }))
vi.mock('@tenshu/sdk/timeline', () => ({ Timeline: {} }))
vi.mock('@tenshu/sdk/calendar', () => ({ Calendar: {} }))
vi.mock('@tenshu/sdk/maps', () => ({ Maps: {} }))
vi.mock('@tenshu/sdk/photos', () => ({ Photos: {}, MAX_FEED_PAGE_SIZE: 500 }))
vi.mock('@tenshu/sdk/fit', () => ({ Fit: {}, MAX_WORKOUT_PAGE_SIZE: 200, MAX_ACTIVITY_FILE_BYTES: 33554432 }))
vi.mock('@tenshu/sdk/home', () => ({ Home: {}, MAX_ACTIVITY_PAGE_SIZE: 200 }))
vi.mock('@tenshu/sdk/mia', () => ({
  Mia: {},
  MAX_UTTERANCE_CHARS: 500,
  MAX_TURN_PAGE_SIZE: 100,
  MiaMethod: {
    Ask: 'mia.ask',
    History: 'mia.history',
    HistoryObserve: 'mia.history.observe',
    TurnsPage: 'mia.turnsPage',
    Grants: 'mia.grants',
    GrantsObserve: 'mia.grants.observe',
    SetGranted: 'mia.grants.setGranted',
    SetWritable: 'mia.grants.setWritable',
  },
}))
vi.mock('@tenshu/sdk/mapRegion', () => ({ MapRegion: {}, MAX_REGION_RANGE_BYTES: 1 << 20 }))

const { composeGateway, GATEWAY_MODULES } = await import('./index')
const { HOSTED_APPS } = await import('../../apps/registry')
const { isMethodPermitted } = await import('@tenshu/sdk/bridge')

describe('gateway composition', () => {
  it('serves every method any app is allowed to call', () => {
    const { handlers, subscriptions } = composeGateway(GATEWAY_MODULES)
    const served = new Set([...Object.keys(handlers), ...Object.keys(subscriptions)])
    for (const app of HOSTED_APPS) {
      // An allowed method with no handler answers `unknown method` — a dead button in that app.
      expect(app.methods.filter((m) => !served.has(m)), app.id).toEqual([])
    }
  })

  // Home: the module serves its reads, and only `web/home` is allowed them (F5, run B).
  it('serves home.* to Home alone', async () => {
    const { HOME_METHODS } = await import('./home')
    const { handlers, subscriptions } = composeGateway(GATEWAY_MODULES)
    for (const method of HOME_METHODS) {
      expect(method in handlers || method in subscriptions, method).toBe(true)
      for (const app of HOSTED_APPS) {
        expect(isMethodPermitted(app.methods, method), `${app.id} ${method}`).toBe(app.id === 'home')
      }
    }
  })

  it('keeps each namespace in its own module', () => {
    for (const module of GATEWAY_MODULES) {
      const methods = [...Object.keys(module.handlers), ...Object.keys(module.subscriptions ?? {})]
      const namespaces = new Set(methods.map((m) => m.split('.')[0]))
      // The session module is the one mixed namespace: `bridge.*` + `runtime.*`.
      if (namespaces.has('bridge')) expect([...namespaces].sort()).toEqual(['bridge', 'runtime'])
      else expect(namespaces.size).toBe(1)
    }
  })

  it('refuses a method two modules both define, rather than letting the last one win', () => {
    const a = { handlers: { 'x.one': () => 1 } }
    const b = { handlers: { 'x.one': () => 2 } }
    expect(() => composeGateway([a, b])).toThrow(/x\.one is defined by two modules/)
    const s = { handlers: {}, subscriptions: { 'x.observe': () => () => undefined } }
    expect(() => composeGateway([s, s])).toThrow(/x\.observe/)
  })
})
