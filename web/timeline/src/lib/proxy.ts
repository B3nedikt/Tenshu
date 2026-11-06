// Timeline's single connection to the apex proxy (docs/web/architecture.md §5.2): the app has no
// backend of its own, and every read and write goes through the apex, which holds the keys and
// runs the runtime. The connection is the app-kit's (`createApexBridge`); this file is the wire
// names and the typed calls.
//
// ⚠⚠ No value import from `@tenshu/sdk` here — `import type` only. A value import drags the Kotlin
// runtime into this bundle (web-app-suite.md §7; `sdkBoundary.test.ts` fails on it).
//
// ⚠⚠ What this origin may call is decided apex-side (F5): `methods` in
// `web/apex/src/apps/registry/apps/timeline.ts`. A name added to `ProxyMethod` below must be added
// there too — the apex's `gatewayPermissions.test.ts` reads this table and fails until it is.
import { createApexBridge } from '@tenshu/app-kit/bridge'
import type { RuntimeLeaseState } from '@tenshu/sdk/core'
import type { MapRegionAt, MapRegionRange } from '@tenshu/sdk/mapRegion'
import type {
  TimelineFailure,
  TimelineFeedResult,
  TimelineMomentResult,
  TimelinePlaceResult,
  TimelinePlaceRole,
  TimelinePlacesResult,
  TimelineRoutesResult,
  TimelineTripResult,
  TimelineWriteResult,
} from '@tenshu/sdk/timeline'

// The wire names, shared with the apex gateway (`web/apex/src/lib/gateway/timeline.ts`).
// ⚠ Not here, on purpose: the raw `timeline.moments` / `.moment` / `.trips` / `.traces` reads (the
// projected surface supersedes them). Add them only with a screen that calls them.
// ⚠ The `mapRegion.*` names are spelled out, not `MapRegionMethod.*`: the apex's
// `gatewayPermissions.test.ts` reads this table as TEXT. They are map-kit's two calls
// (`bridgeRegionAccess`); `regionsIn` / `catalog` are not used.
export const ProxyMethod = {
  Ping: 'bridge.ping',
  RuntimeLease: 'runtime.lease',
  RuntimeUseHere: 'runtime.useHere',
  Feed: 'timeline.feed',
  FeedObserve: 'timeline.feed.observe',
  MomentDetail: 'timeline.momentDetail',
  Trip: 'timeline.trip',
  Places: 'timeline.places',
  PlacesObserve: 'timeline.places.observe',
  Place: 'timeline.place',
  RenamePlace: 'timeline.renamePlace',
  SetPlaceRole: 'timeline.setPlaceRole',
  MergePlaces: 'timeline.mergePlaces',
  UnmergePlace: 'timeline.unmergePlace',
  AcceptTripSuggestion: 'timeline.acceptTripSuggestion',
  DismissTripSuggestion: 'timeline.dismissTripSuggestion',
  // The trip and moment maps (web-app-suite.md Wave 3, Timeline routes).
  MovementRoutes: 'timeline.movementRoutes',
  MapRegionAt: 'mapRegion.regionAt',
  MapRegionStreamRange: 'mapRegion.streamRange',
} as const

// The one shared connection to `VITE_APEX_ORIGIN` (else the apex dev server), mounted on first use.
const bridge = createApexBridge(ProxyMethod)

// Days are grouped in this zone. '' = the browser's own (the SDK resolves it with `Intl`), which
// is the zone the page renders its times in too, so a row's day and its clock always agree.
const ZONE = ''

// ── session ─────────────────────────────────────────────────────────────────

export function runtimeLease(): Promise<RuntimeLeaseState> {
  return bridge.call<RuntimeLeaseState>(ProxyMethod.RuntimeLease)
}

export async function runtimeUseHere(): Promise<void> {
  await bridge.call<void>(ProxyMethod.RuntimeUseHere)
}

// ── reads ───────────────────────────────────────────────────────────────────
// ⚠ Every read resolves `{ ok: true, … }` or a `TimelineFailure` — a failure is never an empty
// feed. A rejected promise is the bridge itself failing (no answer, a refusal), not the service.

export function feed(): Promise<TimelineFeedResult> {
  return bridge.call<TimelineFeedResult>(ProxyMethod.Feed, { zone: ZONE })
}

// Stable module-level functions: `useLive` resubscribes when its `subscribe` changes.
export function subscribeFeed(onValue: (v: TimelineFeedResult) => void, onError: (e: Error) => void): () => void {
  return bridge.subscribe<TimelineFeedResult>(ProxyMethod.FeedObserve, { zone: ZONE }, onValue, onError)
}

export function places(): Promise<TimelinePlacesResult> {
  return bridge.call<TimelinePlacesResult>(ProxyMethod.Places)
}

export function subscribePlaces(onValue: (v: TimelinePlacesResult) => void, onError: (e: Error) => void): () => void {
  return bridge.subscribe<TimelinePlacesResult>(ProxyMethod.PlacesObserve, {}, onValue, onError)
}

export function momentDetail(momentId: string): Promise<TimelineMomentResult> {
  return bridge.call<TimelineMomentResult>(ProxyMethod.MomentDetail, { momentId })
}

export function trip(tripId: string): Promise<TimelineTripResult> {
  return bridge.call<TimelineTripResult>(ProxyMethod.Trip, { tripId, zone: ZONE })
}

export function place(placeId: string): Promise<TimelinePlaceResult> {
  return bridge.call<TimelinePlaceResult>(ProxyMethod.Place, { placeId })
}

// Route points per leg — `legs[i]` answers the i-th trace-id list — in ONE read (the SDK narrows
// each relation to these ids and snaps each trace's route over its captured path). ⚠ Never one call
// per leg: that was one full-store polyline load per leg. ⚠ Not `timeline.movementRoute` (no leg
// boundaries), which this app is not granted.
export function movementRoutes(legs: readonly (readonly string[])[]): Promise<TimelineRoutesResult> {
  return bridge.call<TimelineRoutesResult>(ProxyMethod.MovementRoutes, { legs })
}

// ── the map's region reads (`@tenshu/map-kit`'s `RegionAccess`, same wire shape as
// `bridgeRegionAccess`) ──
// Tiles stream through the apex runtime's `FetchTileTransport` (W.1). ⚠ A runtime built without a
// transport still answers `error_map_transport_unconfigured`, which the map renders as "not
// available on the web" with no retry (`AppMap`).

export function mapRegionAt(latitude: number, longitude: number): Promise<MapRegionAt> {
  return bridge.call<MapRegionAt>(ProxyMethod.MapRegionAt, { latitude, longitude })
}

export function mapRegionRange(regionId: string, offset: number, length: number): Promise<MapRegionRange> {
  return bridge.call<MapRegionRange>(ProxyMethod.MapRegionStreamRange, { regionId, offset, length })
}

// ── writes (pure data — timeline-app.md §Web scope) ──────────────────────────

export function renamePlace(placeId: string, name: string): Promise<TimelineWriteResult> {
  return bridge.call<TimelineWriteResult>(ProxyMethod.RenamePlace, { placeId, name })
}

// Always written user-authored: this is the user's answer, never an inference.
export function setPlaceRole(placeId: string, role: TimelinePlaceRole): Promise<TimelineWriteResult> {
  return bridge.call<TimelineWriteResult>(ProxyMethod.SetPlaceRole, { placeId, role })
}

// ⚠ The survivor is min(rootA, rootB), not the caller's pick — follow `survivorId`.
export function mergePlaces(a: string, b: string): Promise<{ readonly ok: true; readonly survivorId: string } | TimelineFailure> {
  return bridge.call(ProxyMethod.MergePlaces, { a, b })
}

// ⚠ Takes a FOLDED place's id (`TimelinePlaceDetail.foldedPlaces`), never the survivor's.
export function unmergePlace(placeId: string): Promise<TimelineWriteResult> {
  return bridge.call<TimelineWriteResult>(ProxyMethod.UnmergePlace, { placeId })
}

export function acceptTripSuggestion(suggestionId: string): Promise<{ readonly ok: true; readonly tripId: string } | TimelineFailure> {
  return bridge.call(ProxyMethod.AcceptTripSuggestion, { suggestionId, zone: ZONE })
}

// Per tab, never synced: a reload offers it again, as an app restart does natively (§Web scope 4c).
export function dismissTripSuggestion(suggestionId: string): Promise<TimelineWriteResult> {
  return bridge.call<TimelineWriteResult>(ProxyMethod.DismissTripSuggestion, { suggestionId })
}

export const APEX = bridge.apexOrigin
