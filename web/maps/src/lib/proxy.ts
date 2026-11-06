// Maps' single connection to the apex proxy (docs/web/architecture.md §5.2): the app has no
// backend of its own, and every read and write goes through the apex, which holds the keys and
// runs the runtime. The connection is the app-kit's (`createApexBridge`); this file is the wire
// names and the typed calls.
//
// ⚠⚠ No value import from `@tenshu/sdk` here — `import type` only. A value import drags the Kotlin
// runtime into this bundle (web-app-suite.md §7; `sdkBoundary.test.ts` fails on it).
//
// ⚠⚠ What this origin may call is decided apex-side (F5): `methods` in
// `web/apex/src/apps/registry/apps/maps.ts`. A name added to `ProxyMethod` below must be added
// there too — the apex's `gatewayPermissions.test.ts` reads this table and fails until it is.
import { createApexBridge } from '@tenshu/app-kit/bridge'
import { bridgeRegionAccess, type RegionAccess } from '@tenshu/map-kit'
import type { RuntimeLeaseState } from '@tenshu/sdk/core'
import type { MapsPlaceResult, MapsPlacesResult, MapsWriteResult } from '@tenshu/sdk/maps'
import type { TimelinePlacesResult, TimelinePlaceVisitSummaryResult } from '@tenshu/sdk/timeline'

// The wire names, shared with the apex gateway (`web/apex/src/lib/gateway/{place,timeline,mapRegion}.ts`).
// ⚠ Not here, on purpose (`maps-app.md` §Web scope → Decided): no saved / note / list method (5a —
// none exists), no geocoder (1), and of `mapRegion.*` only the two `<TenshuMap>` calls — the map
// never lists regions or reads the catalogue (no offline regions on web, 3). Of Timeline only
// `timeline.placeVisitSummary`, for the visit stats (5b) — ⚠ not `timeline.place`, which ships every
// visit moment (thousands for a home place) to show two numbers — and `timeline.places`, read only
// to break a tie between equally dense clusters for the list map's anchor (`lib/anchor.ts`);
// renaming is `place.rename`.
export const ProxyMethod = {
  Ping: 'bridge.ping',
  RuntimeLease: 'runtime.lease',
  RuntimeUseHere: 'runtime.useHere',
  Places: 'place.list',
  PlacesObserve: 'place.list.observe',
  Place: 'place.get',
  RenamePlace: 'place.rename',
  PlaceVisits: 'timeline.placeVisitSummary',
  PlaceRecency: 'timeline.places',
  RegionAt: 'mapRegion.regionAt',
  StreamRange: 'mapRegion.streamRange',
} as const

// The one shared connection to `VITE_APEX_ORIGIN` (else the apex dev server), mounted on first use.
const bridge = createApexBridge(ProxyMethod)

// ── session ─────────────────────────────────────────────────────────────────

export function runtimeLease(): Promise<RuntimeLeaseState> {
  return bridge.call<RuntimeLeaseState>(ProxyMethod.RuntimeLease)
}

export async function runtimeUseHere(): Promise<void> {
  await bridge.call<void>(ProxyMethod.RuntimeUseHere)
}

// ── places ──────────────────────────────────────────────────────────────────
// ⚠ Every read resolves `{ ok: true, … }` or a `MapsFailure` — a failure is never an empty list. A
// rejected promise is the bridge itself failing (no answer, a refusal), not the service.

export function places(): Promise<MapsPlacesResult> {
  return bridge.call<MapsPlacesResult>(ProxyMethod.Places)
}

// A stable module-level function: `useLive` resubscribes when its `subscribe` changes.
export function subscribePlaces(onValue: (v: MapsPlacesResult) => void, onError: (e: Error) => void): () => void {
  return bridge.subscribe<MapsPlacesResult>(ProxyMethod.PlacesObserve, {}, onValue, onError)
}

// A folded id is followed to its survivor (`redirectedFrom`); `place: null` is "no such place".
export function place(placeId: string): Promise<MapsPlaceResult> {
  return bridge.call<MapsPlaceResult>(ProxyMethod.Place, { placeId })
}

// Timeline's rename (`error_timeline_*` keys). Pass the id a read returned.
export function renamePlace(placeId: string, name: string): Promise<MapsWriteResult> {
  return bridge.call<MapsWriteResult>(ProxyMethod.RenamePlace, { placeId, name })
}

// Visit stats (`visitCount`, `lastVisitMs`) — Timeline counts the Visit moments; Maps' own read has
// none (`maps-app.md` §Web scope 5b). Just the two numbers, never the moments.
export function placeVisits(placeId: string): Promise<TimelinePlaceVisitSummaryResult> {
  return bridge.call<TimelinePlaceVisitSummaryResult>(ProxyMethod.PlaceVisits, { placeId })
}

// Each place's last visit, by id — Timeline's place rows (no moments). Only the list map's anchor
// reads it, and only when two clusters tie (`lib/anchor.ts`). A typed failure rejects with its kind.
export async function placeRecency(): Promise<ReadonlyMap<string, number | null>> {
  const result = await bridge.call<TimelinePlacesResult>(ProxyMethod.PlaceRecency)
  if (!result.ok) throw new Error(result.error.kind)
  return new Map(result.places.map((p) => [p.placeId, p.lastVisitMs]))
}

// ── the map ─────────────────────────────────────────────────────────────────

// `<TenshuMap>`'s region access: `mapRegion.regionAt` + `mapRegion.streamRange`, both in the table
// above. ⚠ The map-kit spells the names itself (`MapRegionMethod`); `proxy.test.ts` pins that they
// are the same two.
export const regions: RegionAccess = bridgeRegionAccess(<T>(method: string, params?: unknown) =>
  bridge.call<T>(method as (typeof ProxyMethod)[keyof typeof ProxyMethod], params),
)

export const APEX = bridge.apexOrigin
