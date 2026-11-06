// `timeline.*` — the Timeline bridge: `:timeline-service` + the place / health reads it joins.
import { Timeline } from '@tenshu/sdk/timeline'
import type { GatewayModule } from './module'
import { asRecord, readId, readString } from './params'

export const timelineGateway: GatewayModule = {
  handlers: {
    // `:timeline-service` raw read bridge — the moment spine, bound trips and
    // movement-trace summaries. No `web/timeline/` app consumes these yet, so no
    // origin's allowlist names them and they are unreachable until one does (run
    // B registers the app). The handlers are pass-throughs; the projection lives
    // in the Kotlin SDK, mirroring `TimelineProjectionRepository`.
    'timeline.moments': async () => {
      return Timeline.moments()
    },
    'timeline.moment': async (params) => {
      return Timeline.moment(readMomentId(params))
    },
    'timeline.trips': async () => {
      return Timeline.trips()
    },
    'timeline.traces': async () => {
      return Timeline.traces()
    },

    // web-app-suite.md Wave 2, Timeline run A — the native `TimelineRepository` over the projected
    // read model (visits resolved through `:place-service`, activities through `:health-service`).
    // Every call resolves `{ ok, … }` with a catalogue-key `error.kind`; the feed and the place list
    // also have a push form under `subscriptions`. Detail reads are snapshot + refetch.
    'timeline.feed': async (params) => Timeline.feed(readZone(params, 'timeline.feed')),
    'timeline.momentDetail': async (params) =>
      Timeline.momentDetail(readId(params, 'momentId', 'timeline.momentDetail')),
    'timeline.trip': async (params) =>
      Timeline.trip(readId(params, 'tripId', 'timeline.trip'), readZone(params, 'timeline.trip')),
    'timeline.places': async () => Timeline.places(),
    'timeline.place': async (params) => Timeline.place(readId(params, 'placeId', 'timeline.place')),
    // The stats alone (web Maps' detail) — no visit moments over postMessage.
    'timeline.placeVisitSummary': async (params) =>
      Timeline.placeVisitSummary(readId(params, 'placeId', 'timeline.placeVisitSummary')),
    'timeline.movementRoute': async (params) => Timeline.movementRoute(readTraceIds(params)),
    // Every leg of a trip in one read (per-leg boundaries kept) — never one `movementRoute` per leg.
    'timeline.movementRoutes': async (params) => Timeline.movementRoutes(readLegs(params)),
    // Pure-data writes (timeline-app.md §Web scope). A blank name is the SDK's typed failure, so
    // `name` is only required to be a string here.
    'timeline.renamePlace': async (params) =>
      Timeline.renamePlace(
        readId(params, 'placeId', 'timeline.renamePlace'),
        readString(params, 'name', 'timeline.renamePlace'),
      ),
    // ⚠⚠ Always user-authored — the SDK has no way to write an inferred role.
    'timeline.setPlaceRole': async (params) =>
      Timeline.setPlaceRole(readId(params, 'placeId', 'timeline.setPlaceRole'), readRole(params)),
    'timeline.mergePlaces': async (params) =>
      Timeline.mergePlaces(readId(params, 'a', 'timeline.mergePlaces'), readId(params, 'b', 'timeline.mergePlaces')),
    'timeline.unmergePlace': async (params) =>
      Timeline.unmergePlace(readId(params, 'placeId', 'timeline.unmergePlace')),
    'timeline.acceptTripSuggestion': async (params) =>
      Timeline.acceptTripSuggestion(
        readId(params, 'suggestionId', 'timeline.acceptTripSuggestion'),
        readZone(params, 'timeline.acceptTripSuggestion'),
      ),
    'timeline.dismissTripSuggestion': async (params) =>
      Timeline.dismissTripSuggestion(readId(params, 'suggestionId', 'timeline.dismissTripSuggestion')),
  },
  subscriptions: {
    // Genuine push over the relations' Flows. ⚠ `fail` is wired through: a collector that dies ends
    // the subscriber's stream with a terminal error instead of freezing its screen.
    'timeline.feed.observe': (params, emit, fail) =>
      Timeline.observeFeed(readZone(params, 'timeline.feed.observe'), (result) => emit(result), fail),
    'timeline.places.observe': (_params, emit, fail) =>
      Timeline.observePlaces((result) => emit(result), fail),
  },
}

// `zone` is an IANA zone or '' (the browser's own) — required, so a caller that forgot it gets
// the gateway's refusal rather than silently grouping days in some other zone. An unknown zone
// is the SDK's typed `error_timeline_invalid_zone`.
function readZone(params: unknown, op: string): string {
  return readString(params, 'zone', op)
}

// A non-empty array of non-empty ids: an empty list would answer "no route" for a malformed call.
function readTraceIds(params: unknown): string[] {
  const v = asRecord(params).traceIds
  if (!Array.isArray(v) || v.length === 0 || !v.every((id) => typeof id === 'string' && id.length > 0)) {
    throw new Error('timeline.movementRoute requires { traceIds: string[] }')
  }
  return v as string[]
}

// ⚠ A leg may be empty of known traces (it answers `[]`), but never of ids: an empty leg is a
// caller bug, and refusing it keeps the answer's legs aligned with what the caller meant to ask.
function readLegs(params: unknown): string[][] {
  const v = asRecord(params).legs
  const isIds = (leg: unknown) =>
    Array.isArray(leg) && leg.length > 0 && leg.every((id) => typeof id === 'string' && id.length > 0)
  if (!Array.isArray(v) || v.length === 0 || !v.every(isIds)) {
    throw new Error('timeline.movementRoutes requires { legs: string[][] }')
  }
  return v as string[][]
}

const PLACE_ROLES = ['home', 'work', 'none'] as const

function readRole(params: unknown): (typeof PLACE_ROLES)[number] {
  const v = asRecord(params).role
  if (typeof v !== 'string' || !(PLACE_ROLES as readonly string[]).includes(v)) {
    throw new Error("timeline.setPlaceRole requires { role: 'home' | 'work' | 'none' }")
  }
  return v as (typeof PLACE_ROLES)[number]
}

// Timeline read validator. Required and non-empty: a blank id would silently
// resolve to "no such moment" and read as an empty detail screen rather than as
// the malformed call it is.
function readMomentId(params: unknown): string {
  const v = asRecord(params).momentId
  if (typeof v !== 'string' || v.length === 0) {
    throw new Error('timeline.moment requires { momentId }')
  }
  return v
}
