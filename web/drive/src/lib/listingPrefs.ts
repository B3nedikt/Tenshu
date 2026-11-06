// Listing preferences — sort key, direction and layout (W5).
//
// ⚠⚠ Stored in `localStorage`, NOT the PreferenceService. Native persists these as synced
// per-device settings, and `:preference-service` has no JS plugin — the JS runtime registers no
// preference service at all. Wiring one is a real slice; until then this is deliberately a
// DIVERGENCE, not an oversight: on web these choices stay on this browser and do not follow you
// to another device. Recorded in `drive-web-parity.md` W5.
//
// ⚠ Reads defensively. A value written by a future build (a key this one does not know) must not
// break the listing — fall back to the default rather than throwing on a bad parse.

export type SortKey = 'name' | 'modified' | 'created' | 'size'
export type Layout = 'list' | 'grid'

export interface ListingPrefs {
  readonly key: SortKey
  readonly descending: boolean
  readonly layout: Layout
}

// Matches native's `FileSortKey.defaultDescending`: A→Z for a name, newest/largest first for the
// date and size keys — "sort by date" means "most recent on top".
export function defaultDescending(key: SortKey): boolean {
  return key !== 'name'
}

export const DEFAULT_PREFS: ListingPrefs = { key: 'name', descending: false, layout: 'list' }

const STORAGE_KEY = 'tenshu-drive-listing'
const KEYS: readonly SortKey[] = ['name', 'modified', 'created', 'size']
const LAYOUTS: readonly Layout[] = ['list', 'grid']

export function loadPrefs(): ListingPrefs {
  try {
    const raw = window.localStorage.getItem(STORAGE_KEY)
    if (!raw) return DEFAULT_PREFS
    const parsed = JSON.parse(raw) as Partial<ListingPrefs>
    return {
      key: KEYS.includes(parsed.key as SortKey) ? (parsed.key as SortKey) : DEFAULT_PREFS.key,
      descending:
        typeof parsed.descending === 'boolean' ? parsed.descending : DEFAULT_PREFS.descending,
      layout: LAYOUTS.includes(parsed.layout as Layout)
        ? (parsed.layout as Layout)
        : DEFAULT_PREFS.layout,
    }
  } catch {
    // Disabled storage (private mode), or a value this build cannot parse.
    return DEFAULT_PREFS
  }
}

export function savePrefs(prefs: ListingPrefs): void {
  try {
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify(prefs))
  } catch {
    // A preference that cannot be saved is not worth failing a listing for.
  }
}
