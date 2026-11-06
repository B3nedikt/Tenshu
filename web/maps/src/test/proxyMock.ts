// One mock of `lib/proxy` for every component test. Each test file does
//   vi.mock('../lib/proxy', async () => (await import('../test/proxyMock')).proxyModule)
// and drives the pushed places stream through `streams.places`.
import { vi } from 'vitest'
import type { RegionAccess } from '@tenshu/map-kit'
import type { MapsFailure, MapsPlace, MapsPlaceDetail, MapsPlaceResult, MapsPlacesResult, MapsWriteResult } from '@tenshu/sdk/maps'
import type { TimelinePlaceVisitSummaryResult } from '@tenshu/sdk/timeline'

type Sub<T> = { onValue: (v: T) => void; onError: (e: Error) => void; closed: boolean }

function stream<T>() {
  const subs: Sub<T>[] = []
  return {
    subs,
    subscribe: (onValue: (v: T) => void, onError: (e: Error) => void) => {
      const sub: Sub<T> = { onValue, onError, closed: false }
      subs.push(sub)
      return () => {
        sub.closed = true
      }
    },
    push(value: T) {
      for (const s of subs) if (!s.closed) s.onValue(value)
    },
    fail(error: Error) {
      for (const s of subs) if (!s.closed) s.onError(error)
    },
    reset: () => subs.splice(0),
  }
}

export const streams = {
  places: stream<MapsPlacesResult>(),
}

// A tile transport that is not configured (`error_map_transport_unconfigured`): the map's explicit
// unavailable state, so no component test needs WebGL.
export const regions: RegionAccess = {
  regionAt: vi.fn(async () => ({ ok: false as const, error: { kind: 'error_map_transport_unconfigured' } })),
  readRange: vi.fn(async () => ({ ok: false as const, error: { kind: 'error_map_transport_unconfigured' } })),
}

export const proxy = {
  runtimeLease: vi.fn(async () => 'held' as unknown),
  runtimeUseHere: vi.fn(async () => {}),
  places: vi.fn(async (): Promise<MapsPlacesResult> => ({ ok: true, places: [] })),
  subscribePlaces: vi.fn(streams.places.subscribe),
  place: vi.fn(async (_id: string): Promise<MapsPlaceResult> => ({ ok: true, place: null })),
  renamePlace: vi.fn(async (_id: string, _name: string): Promise<MapsWriteResult> => ({ ok: true })),
  placeRecency: vi.fn(async (): Promise<ReadonlyMap<string, number | null>> => new Map()),
  placeVisits: vi.fn(async (_id: string): Promise<TimelinePlaceVisitSummaryResult> => ({ ok: true, summary: null })),
}

export const proxyModule = { ...proxy, regions, ProxyMethod: {}, APEX: 'http://localhost:5173' }

export function resetProxy() {
  // ⚠ `mockReset`, not `restoreMocks`: since Vitest 3 `restoreMocks` leaves `vi.fn(impl)` alone, so
  // a `mockResolvedValue` would leak into the next test. `mockReset` puts each back to its `impl`.
  for (const fn of Object.values(proxy)) fn.mockReset()
  ;(regions.regionAt as ReturnType<typeof vi.fn>).mockClear()
  streams.places.reset()
}

export function failure(kind: string): MapsFailure {
  return { ok: false, error: { kind, message: `diagnostic for ${kind}` } }
}

// ── fixtures ─────────────────────────────────────────────────────────────────

export function placeRow(overrides: Partial<MapsPlace> & { placeId: string }): MapsPlace {
  return {
    name: '',
    nameIsUsers: false,
    city: '',
    countryCode: '',
    latitude: 48.13743,
    longitude: 11.57549,
    role: null,
    ...overrides,
  }
}

export function placeDetail(place: MapsPlace, overrides: Partial<MapsPlaceDetail> = {}): MapsPlaceDetail {
  return { place, redirectedFrom: null, mergedPlaceCount: 0, ...overrides }
}

// Timeline's visit summary of the same place.
export function visitsOf(placeId: string, visitCount: number, lastVisitMs: number | null): TimelinePlaceVisitSummaryResult {
  return { ok: true, summary: { placeId, visitCount, lastVisitMs, redirectedFrom: null } }
}

// A big list: 5 000 places over eight cities, every seventh unnamed.
const CITIES = ['München', 'Berlin', 'København', 'Paris', 'Łódź', 'Hamburg', 'Wien', 'Zürich']
const COUNTRIES = ['DE', 'DE', 'DK', 'FR', 'PL', 'DE', 'AT', 'CH']
export function manyPlaces(n: number): MapsPlace[] {
  return Array.from({ length: n }, (_, i) =>
    placeRow({
      placeId: `p${i}`,
      name: i % 7 === 0 ? '' : `Place ${i} Café`,
      city: CITIES[i % CITIES.length]!,
      countryCode: COUNTRIES[i % COUNTRIES.length]!,
      latitude: 40 + (i % 100) / 10,
      longitude: (i % 360) - 180,
    }),
  )
}
