import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { SESSION_HINT_KEY, readSessionHint } from './sessionHint'

// jsdom's localStorage here is method-less (see ConstructionGate.test); install a real one.
function installMemoryLocalStorage(): Map<string, string> {
  const map = new Map<string, string>()
  const storage = {
    getItem: (k: string) => map.get(k) ?? null,
    setItem: (k: string, v: string) => void map.set(k, v),
    removeItem: (k: string) => void map.delete(k),
    clear: () => map.clear(),
    key: () => null,
    get length() { return map.size },
  }
  Object.defineProperty(window, 'localStorage', { configurable: true, value: storage })
  return map
}

let map: Map<string, string>
const original = Object.getOwnPropertyDescriptor(window, 'localStorage')
beforeEach(() => { map = installMemoryLocalStorage() })
afterEach(() => { if (original) Object.defineProperty(window, 'localStorage', original) })

describe('readSessionHint', () => {
  it('is first-time with no hint, or an unreadable one', () => {
    expect(readSessionHint()).toEqual({ kind: 'firstTime' })
    map.set(SESSION_HINT_KEY, 'not json')
    expect(readSessionHint()).toEqual({ kind: 'firstTime' })
  })

  it('is active while the token is unexpired, locked after', () => {
    map.set(SESSION_HINT_KEY, JSON.stringify({ deviceName: 'Test Browser', expiresAtMs: 2_000 }))
    expect(readSessionHint(1_000)).toEqual({ kind: 'active', deviceName: 'Test Browser' })
    expect(readSessionHint(3_000)).toEqual({ kind: 'locked', deviceName: 'Test Browser' })
  })
})
