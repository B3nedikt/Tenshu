import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ensurePersistence, readPersistence } from './persistentStorage'

// jsdom exposes a non-functional localStorage here (a plain object, no getItem) — the same
// limitation ConstructionGate.test documents. Install a real one so the "asked once" memory is
// actually exercised rather than silently falling through its catch.
function installMemoryLocalStorage() {
  const map = new Map<string, string>()
  Object.defineProperty(window, 'localStorage', {
    configurable: true,
    value: {
      get length() {
        return map.size
      },
      clear: () => map.clear(),
      getItem: (k: string) => map.get(k) ?? null,
      setItem: (k: string, v: string) => void map.set(k, String(v)),
      removeItem: (k: string) => void map.delete(k),
      key: (i: number) => Array.from(map.keys())[i] ?? null,
    } as Storage,
  })
}

function installStorageManager(sm: unknown) {
  Object.defineProperty(navigator, 'storage', { configurable: true, value: sm })
}

beforeEach(() => installMemoryLocalStorage())

describe('persistentStorage', () => {
  it('reports unknown when the browser has no StorageManager', async () => {
    installStorageManager(undefined)
    expect(await readPersistence()).toBe('unknown')
    // ⚠ And unknown must not be reported as a problem — the Services row renders nothing.
    expect(await ensurePersistence()).toBe('unknown')
  })

  it('reports persisted without asking when already granted', async () => {
    const persist = vi.fn()
    installStorageManager({ persisted: async () => true, persist })
    expect(await ensurePersistence()).toBe('persisted')
    expect(persist).not.toHaveBeenCalled()
  })

  it('asks when evictable, and reports the grant', async () => {
    installStorageManager({ persisted: async () => false, persist: async () => true })
    expect(await ensurePersistence()).toBe('persisted')
  })

  // ⚠ The load-bearing one. Firefox PROMPTS on persist(); re-asking every load would turn a
  // one-time decision into a recurring interruption.
  it('does not ask twice after a refusal', async () => {
    const persist = vi.fn(async () => false)
    installStorageManager({ persisted: async () => false, persist })
    expect(await ensurePersistence()).toBe('evictable')
    expect(await ensurePersistence()).toBe('evictable')
    expect(persist).toHaveBeenCalledTimes(1)
  })

  // ⚠ But the STATE is always read fresh — a user who later installs the app must see the truth,
  // not our cached memory of a refusal.
  it('still reports a later grant despite having been refused before', async () => {
    const persist = vi.fn(async () => false)
    installStorageManager({ persisted: async () => false, persist })
    await ensurePersistence()

    installStorageManager({ persisted: async () => true, persist })
    expect(await ensurePersistence()).toBe('persisted')
  })

  it('treats a throwing StorageManager as unknown, not as a failure', async () => {
    installStorageManager({
      persisted: async () => {
        throw new Error('denied by policy')
      },
    })
    expect(await readPersistence()).toBe('unknown')
  })
})
