import { beforeEach, describe, expect, it } from 'vitest'
import { DEFAULT_PREFS, defaultDescending, loadPrefs, savePrefs } from './listingPrefs'

// ⚠ jsdom in this vitest setup exposes a NON-FUNCTIONAL `localStorage` — a plain object with no
// getItem/setItem. Without a real one these tests would silently exercise the no-storage
// fallback and pass while proving nothing. Same fake the apex suite installs.
function installMemoryLocalStorage() {
  const map = new Map<string, string>()
  const storage: Storage = {
    get length() {
      return map.size
    },
    clear: () => map.clear(),
    getItem: (k) => map.get(k) ?? null,
    setItem: (k, v) => void map.set(k, String(v)),
    removeItem: (k) => void map.delete(k),
    key: (i) => Array.from(map.keys())[i] ?? null,
  }
  Object.defineProperty(window, 'localStorage', { value: storage, configurable: true })
}

describe('listingPrefs', () => {
  beforeEach(() => installMemoryLocalStorage())

  // Mirrors native's `FileSortKey.defaultDescending`: "sort by date" means newest first, but
  // "sort by name" means A→Z. Carrying a direction across keys is how you get oldest-first.
  it('gives each key its own default direction', () => {
    expect(defaultDescending('name')).toBe(false)
    expect(defaultDescending('modified')).toBe(true)
    expect(defaultDescending('created')).toBe(true)
    expect(defaultDescending('size')).toBe(true)
  })

  it('round-trips through storage', () => {
    savePrefs({ key: 'size', descending: false, layout: 'grid' })
    expect(loadPrefs()).toEqual({ key: 'size', descending: false, layout: 'grid' })
  })

  it('falls back to defaults when nothing is stored', () => {
    expect(loadPrefs()).toEqual(DEFAULT_PREFS)
  })

  // ⚠ A value written by a future build must not break the listing. Unknown keys fall back per
  // field rather than throwing or discarding the whole object.
  it('survives a value this build does not understand', () => {
    window.localStorage.setItem(
      'tenshu-drive-listing',
      JSON.stringify({ key: 'colour', descending: 'yes', layout: 'carousel' }),
    )
    expect(loadPrefs()).toEqual(DEFAULT_PREFS)
  })

  it('survives unparseable storage', () => {
    window.localStorage.setItem('tenshu-drive-listing', 'not json')
    expect(loadPrefs()).toEqual(DEFAULT_PREFS)
  })
})
