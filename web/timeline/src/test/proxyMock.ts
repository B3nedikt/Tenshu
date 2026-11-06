// One mock of `lib/proxy` for every component test. Each test file does
//   vi.mock('../lib/proxy', async () => (await import('../test/proxyMock')).proxyModule)
// and drives the two pushed streams through `streams`.
import { vi } from 'vitest'
import { drawnMaps } from './mapKitMock'
import type { MapRegionAt, MapRegionRange } from '@tenshu/sdk/mapRegion'
import type {
  TimelineDay,
  TimelineDayMoment,
  TimelineFailure,
  TimelineFeedResult,
  TimelineMomentResult,
  TimelinePlace,
  TimelinePlaceDetail,
  TimelinePlaceResult,
  TimelinePlaceRole,
  TimelinePlacesResult,
  TimelineRoutePoint,
  TimelineRoutesResult,
  TimelineTripResult,
  TimelineTripSuggestion,
  TimelineWriteResult,
} from '@tenshu/sdk/timeline'

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
  feed: stream<TimelineFeedResult>(),
  places: stream<TimelinePlacesResult>(),
}

const written = { ok: true as const }

export const proxy = {
  runtimeLease: vi.fn(async () => 'held' as unknown),
  runtimeUseHere: vi.fn(async () => {}),
  feed: vi.fn(async (): Promise<TimelineFeedResult> => ({ ok: true, days: [], trips: [], suggestion: null })),
  subscribeFeed: vi.fn(streams.feed.subscribe),
  places: vi.fn(async (): Promise<TimelinePlacesResult> => ({ ok: true, places: [] })),
  subscribePlaces: vi.fn(streams.places.subscribe),
  momentDetail: vi.fn(async (_id: string): Promise<TimelineMomentResult> => ({ ok: true, moment: null })),
  trip: vi.fn(async (_id: string): Promise<TimelineTripResult> => ({ ok: true, trip: null })),
  place: vi.fn(async (_id: string): Promise<TimelinePlaceResult> => ({ ok: true, place: null })),
  renamePlace: vi.fn(async (_id: string, _name: string): Promise<TimelineWriteResult> => written),
  setPlaceRole: vi.fn(async (_id: string, _role: TimelinePlaceRole): Promise<TimelineWriteResult> => written),
  mergePlaces: vi.fn(
    async (a: string, _b: string): Promise<{ ok: true; survivorId: string } | TimelineFailure> => ({ ok: true, survivorId: a }),
  ),
  unmergePlace: vi.fn(async (_id: string): Promise<TimelineWriteResult> => written),
  acceptTripSuggestion: vi.fn(
    async (id: string): Promise<{ ok: true; tripId: string } | TimelineFailure> => ({ ok: true, tripId: id }),
  ),
  dismissTripSuggestion: vi.fn(async (_id: string): Promise<TimelineWriteResult> => written),
  // No route points unless a test gives some: no map. One empty leg per leg asked for.
  movementRoutes: vi.fn(
    async (legs: readonly (readonly string[])[]): Promise<TimelineRoutesResult> => ({ ok: true, legs: legs.map(() => []) }),
  ),
  // The tile transport is live (W.1): a point outside every region answers no region. A test of a
  // runtime WITHOUT a transport says so (`error_map_transport_unconfigured`).
  mapRegionAt: vi.fn(async (_lat: number, _lng: number): Promise<MapRegionAt> => ({ ok: true, region: null })),
  mapRegionRange: vi.fn(
    async (_id: string, _offset: number, _length: number): Promise<MapRegionRange> => ({ ok: true, bytes: new ArrayBuffer(0) }),
  ),
}

export const proxyModule = { ...proxy, ProxyMethod: {}, APEX: 'http://localhost:5173' }

export function resetProxy() {
  // ⚠ `mockReset`, not `restoreMocks`: since Vitest 3 `restoreMocks` leaves `vi.fn(impl)` alone, so
  // a `mockResolvedValue` would leak into the next test. `mockReset` puts each back to its `impl`.
  for (const fn of Object.values(proxy)) fn.mockReset()
  streams.feed.reset()
  drawnMaps.splice(0)
  streams.places.reset()
}

export function failure(kind: string): TimelineFailure {
  return { ok: false, error: { kind, message: `diagnostic for ${kind}` } }
}

// Route points along a straight line, one a minute.
export function routePoints(...coordinates: [number, number][]): TimelineRoutePoint[] {
  return coordinates.map(([latitude, longitude], i) => ({ latitude, longitude, timestampMs: i * 60_000 }))
}

// ── fixtures ─────────────────────────────────────────────────────────────────

// The fold fields on an unfolded row restate the single moment (as trip / place rows do natively).
function restate(m: Omit<TimelineDayMoment, FoldKey>): TimelineDayMoment {
  return {
    ...m,
    displayTitle: m.title,
    spanDurationMin: m.durationSec > 0 ? Math.round(m.durationSec / 60) : null,
    spanDistanceM: m.distanceM,
    mergedStayCount: 1,
    traceIds: m.traceId ? [m.traceId] : [],
    containedMoves: [],
    originPlace: null,
    destinationPlace: null,
    originRole: null,
    destinationRole: null,
  }
}

type FoldKey =
  | 'displayTitle' | 'spanDurationMin' | 'spanDistanceM' | 'mergedStayCount' | 'traceIds'
  | 'containedMoves' | 'originPlace' | 'destinationPlace' | 'originRole' | 'destinationRole'

const NONE = {
  placeId: null,
  placeName: null,
  placeCity: null,
  placeCountryCode: null,
  placeLatE7: null,
  placeLngE7: null,
  placeRole: null,
  activityType: null,
  traceId: null,
  movementMode: null,
  distanceM: null,
}

export function visit(
  momentId: string,
  timestampMs: number,
  place: { id: string; name: string; city?: string; role?: string | null; latE7?: number; lngE7?: number },
  durationSec = 3600,
): TimelineDayMoment {
  return restate({
    momentId,
    timestampMs,
    kind: 'Visit',
    title: 'Visit',
    durationSec,
    subjectKind: 'place',
    subjectId: place.id,
    subjectResolved: true,
    sources: ['capture'],
    ...NONE,
    placeId: place.id,
    placeName: place.name,
    placeCity: place.city ?? '',
    placeCountryCode: '',
    placeLatE7: place.latE7 ?? 481_374_300,
    placeLngE7: place.lngE7 ?? 115_754_900,
    placeRole: place.role ?? null,
  })
}

export function movement(momentId: string, timestampMs: number, mode: string, distanceM: number, durationSec = 1200): TimelineDayMoment {
  return restate({
    momentId,
    timestampMs,
    kind: 'Movement',
    title: 'Walk · 2.4 km',
    durationSec,
    subjectKind: 'trace',
    subjectId: `tr-${momentId}`,
    subjectResolved: true,
    sources: ['capture'],
    ...NONE,
    traceId: `tr-${momentId}`,
    movementMode: mode,
    distanceM,
  })
}

export function activity(momentId: string, timestampMs: number, activityType: string | null, title = 'Morning run'): TimelineDayMoment {
  return restate({
    momentId,
    timestampMs,
    kind: 'Activity',
    title,
    durationSec: 1800,
    subjectKind: 'workout',
    subjectId: `w-${momentId}`,
    subjectResolved: activityType !== null,
    sources: ['fit'],
    ...NONE,
    activityType,
  })
}

export function day(date: string, ...moments: TimelineDayMoment[]): TimelineDay {
  return { date, moments }
}

export function placeRow(overrides: Partial<TimelinePlace> & { placeId: string }): TimelinePlace {
  return {
    name: '',
    city: '',
    countryCode: '',
    latE7: 481_374_300,
    lngE7: 115_754_900,
    nameIsUsers: false,
    role: null,
    roleIsSuggestion: false,
    visitCount: 1,
    lastVisitMs: null,
    ...overrides,
  }
}

export function placeDetail(place: TimelinePlace, overrides: Partial<TimelinePlaceDetail> = {}): TimelinePlaceDetail {
  return { place, visits: [], mergedPlaceCount: 0, redirectedFrom: null, foldedPlaces: [], ...overrides }
}

export function suggestionOf(overrides: Partial<TimelineTripSuggestion> = {}): TimelineTripSuggestion {
  return {
    suggestionId: 'sg-1',
    title: 'Lisbon',
    dateRange: 'Jun 3 – Jun 7',
    daysSpan: 5,
    placesCount: 4,
    startMs: 0,
    endMs: 0,
    momentIds: ['m1', 'm2'],
    ...overrides,
  }
}
