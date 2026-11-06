// Timeline entry point: the `:timeline-service` **read** bridge — the moment
// spine, bound trips, and movement-trace summaries
// (`docs/proposals/timeline-services.md` §5). Backed by `Timeline` on the Kotlin
// side (`:tenshu-sdk-js`), dispatched through the apex in-process runtime.
//
// This is the bridge, not an app. There is no `web/timeline/` package: per
// ADR 0020 the first *new* app surface is what extracts `@tenshu/app-kit`, so a
// Timeline UI is a later, larger slice. What lands here is the boundary such an
// app (or an apex panel) would consume.
//
// Two layers. The RAW reads (`moments`, `moment`, `trips`, `traces`) return stored rows with
// un-hydrated `{subjectKind, subjectId}` pointers. The PROJECTED surface (web-app-suite Wave 2,
// Timeline run A) mirrors the native `TimelineRepository`: a day feed with visits resolved through
// `:place-service` and activities through `:health-service`, moment / trip / place detail, route
// points (data only — the map is W-MAP), and the pure-data place and trip-suggestion writes.
//
// ⚠ The projected day list is DATA-LEVEL: reconciled and merge-folded like native, but without the
// native row folds (consecutive-visit merge, in-ride station-stop collapse, "Home → Work"
// endpoints) — `timeline-app.md` §Web scope 4a. ⚠ Every projected call is `{ ok: true, … }` or a
// `TimelineFailure`; a failed read is a failure, never an empty feed.

import * as bundle from './_bundle'

// One timeline moment. `kind` drives the card anatomy; `durationSec` is 0 when
// not applicable. `supersededBy` is non-empty when cross-source reconciliation
// folded this moment into a canonical one — the native projector hides those, and
// a consumer here should too.
export interface TimelineMoment {
  readonly momentId: string
  readonly timestampMs: number
  // 'Activity' | 'Visit' | 'Event' | 'Quiet' | 'Movement'
  readonly kind: string
  readonly title: string
  readonly durationSec: number
  // 'none' | 'place' | 'workout' | 'trace'
  readonly subjectKind: string
  readonly subjectId: string
  readonly supersededBy: string
  // Provenance tags ('capture' / 'google' / …); empty for a pre-reconciliation moment.
  readonly sources: string[]
}

// A bound trip — a user curation over moments. `momentIds` is its membership,
// resolvable against `moments()`.
export interface TimelineTrip {
  readonly tripId: string
  readonly title: string
  readonly dateRange: string
  readonly year: string
  readonly momentIds: string[]
}

// A movement-trace summary — what a 'Movement' moment's `subjectId` points at.
// Deliberately blob-free: the decimated path stays on the Kotlin side.
export interface TimelineTrace {
  readonly traceId: string
  // 'walk' | 'run' | 'cycle' | 'drive' | 'unknown'
  readonly mode: string
  readonly distanceM: number
}


// ── Projected surface (run A) ────────────────────────────────────────────────

export interface TimelineFailure {
  readonly ok: false
  readonly error: { readonly kind: string; readonly message?: string }
}

// The catalogue keys this surface mints itself. Any other failure carries the key derived from
// the service's typed error. Branch on `kind`, never on `message`.
export const TimelineErrorKind = {
  invalidZone: 'error_timeline_invalid_zone',
  placeNotFound: 'error_timeline_place_not_found',
  blankName: 'error_timeline_blank_name',
  invalidRole: 'error_timeline_invalid_role',
  invalidMerge: 'error_timeline_invalid_merge',
  notMerged: 'error_timeline_not_merged',
  // The suggestion the UI showed is no longer offered — refresh, never accept another one.
  suggestionGone: 'error_timeline_suggestion_gone',
  corruptRoute: 'error_timeline_corrupt_route',
  // Both ids already resolve to one place (either may be a stale, folded id).
  alreadyMerged: 'error_timeline_already_merged',
  // A relation read never answered — a typed failure instead of a feed that hangs.
  sourceSilent: 'error_timeline_source_silent',
} as const

// What `setPlaceRole` accepts. 'none' is the user's dismissal of the question.
export type TimelinePlaceRole = 'home' | 'work' | 'none'

// One feed row: a stored moment, its subject resolved. Fields of other subject kinds are `null`:
// `placeName === ''` is an UNNAMED place, `placeName === null` is not a place at all.
export interface TimelineDayMoment {
  readonly momentId: string
  readonly timestampMs: number
  readonly kind: string
  // The stored, ingest-composed (English) title; natively the headline is derived at render.
  readonly title: string
  readonly durationSec: number
  readonly subjectKind: string
  // For a place: the merge root.
  readonly subjectId: string
  // False when the subject row is not on this device (yet).
  readonly subjectResolved: boolean
  readonly sources: string[]
  readonly placeId: string | null
  readonly placeName: string | null
  readonly placeCity: string | null
  readonly placeCountryCode: string | null
  readonly placeLatE7: number | null
  readonly placeLngE7: number | null
  // CONFIRMED roles only ('home' | 'work') — a suggestion never renames a row.
  readonly placeRole: string | null
  readonly activityType: string | null
  readonly traceId: string | null
  // 'walk' | 'run' | 'cycle' | 'drive' | 'transit' | 'unknown'
  readonly movementMode: string | null
  readonly distanceM: number | null
  // ── The fold. Feed rows are folded by the SAME projection native Home runs
  // (`:timeline-projection`): a split stay is one row, a train's station stop is folded into the
  // ride, a movement names its ends. Every field above still describes the row's REPRESENTATIVE
  // stored moment; these describe the row. On trip / place-detail rows (unfolded, as natively) they
  // restate that single moment.
  // The headline — for a collapsed ride, regenerated for the whole ride.
  readonly displayTitle: string
  // Whole span in minutes (merged stay; collapsed ride incl. station stops); null = no duration.
  readonly spanDurationMin: number | null
  // A movement row's distance — every leg's, for a collapsed ride.
  readonly spanDistanceM: number | null
  // Stored Visit fragments this row stands for; 1 = not merged.
  readonly mergedStayCount: number
  // Every leg's trace of a collapsed ride, in order.
  readonly traceIds: string[]
  readonly containedMoves: TimelineContainedMove[]
  // "Home → Work": the bracketing visits' names; null when unknown.
  readonly originPlace: string | null
  readonly destinationPlace: string | null
  // CONFIRMED roles of either end only.
  readonly originRole: string | null
  readonly destinationRole: string | null
}

// A short move folded inside a merged stay.
export interface TimelineContainedMove {
  readonly mode: string
  readonly distanceM: number
  readonly durationMin: number | null
}

// One local day; `date` is 'YYYY-MM-DD' in the zone asked for. Days newest first, moments oldest
// first; a visit across midnight belongs to the day it began.
export interface TimelineDay {
  readonly date: string
  readonly moments: TimelineDayMoment[]
}

export interface TimelineTripSummary {
  readonly tripId: string
  readonly title: string
  readonly dateRange: string
  readonly year: string
  readonly momentIds: string[]
}

export interface TimelineTripDetail {
  readonly tripId: string
  readonly title: string
  readonly dateRange: string
  readonly year: string
  readonly days: TimelineDay[]
  // Members not (yet) on this device — a synced trip can arrive before its moments.
  readonly missingMomentCount: number
}

// The "read as a trip?" card. Pass `suggestionId` back to accept / dismiss exactly this one.
export interface TimelineTripSuggestion {
  readonly suggestionId: string
  readonly title: string
  readonly dateRange: string
  readonly daysSpan: number
  readonly placesCount: number
  readonly startMs: number
  readonly endMs: number
  readonly momentIds: string[]
}

export interface TimelinePlace {
  readonly placeId: string
  // '' = unnamed. On web only the user names a place (no geocoder).
  readonly name: string
  readonly city: string
  readonly countryCode: string
  readonly latE7: number
  readonly lngE7: number
  readonly nameIsUsers: boolean
  // With `roleIsSuggestion` it is a question to ask ("Is this your Home?"), not a fact.
  readonly role: string | null
  readonly roleIsSuggestion: boolean
  readonly visitCount: number
  readonly lastVisitMs: number | null
}

// A place folded into another. `placeId` is what `unmergePlace` takes; `name` is its own ('' = unnamed).
export interface TimelineFoldedPlace {
  readonly placeId: string
  readonly name: string
}

export interface TimelinePlaceDetail {
  readonly place: TimelinePlace
  // Newest first.
  readonly visits: TimelineDayMoment[]
  // How many places are folded into this one (= foldedPlaces.length).
  readonly mergedPlaceCount: number
  // The id asked for, when it was folded into `place` (a stale link); else null.
  readonly redirectedFrom: string | null
  // Places folded directly into this one. ⚠ *Unmerge* takes one of THESE ids — the survivor
  // itself has no merge row, so `unmergePlace(place.placeId)` is `notMerged`.
  readonly foldedPlaces: TimelineFoldedPlace[]
}

// `placeVisitSummary`: the detail's two visit numbers, without the visits. `placeId` is the
// survivor's; `redirectedFrom` the id asked for when it was folded (else null).
export interface TimelinePlaceVisitSummary {
  readonly placeId: string
  readonly visitCount: number
  readonly lastVisitMs: number | null
  readonly redirectedFrom: string | null
}

export interface TimelineRoutePoint {
  readonly latitude: number
  readonly longitude: number
  readonly timestampMs: number
}

export type TimelineFeedResult =
  | {
      readonly ok: true
      readonly days: TimelineDay[]
      readonly trips: TimelineTripSummary[]
      readonly suggestion: TimelineTripSuggestion | null
    }
  | TimelineFailure
export type TimelinePlacesResult = { readonly ok: true; readonly places: TimelinePlace[] } | TimelineFailure
// ⚠ `null` = no such row (an answer); `ok: false` = the read failed.
export type TimelineMomentResult = { readonly ok: true; readonly moment: TimelineDayMoment | null } | TimelineFailure
export type TimelineTripResult = { readonly ok: true; readonly trip: TimelineTripDetail | null } | TimelineFailure
export type TimelinePlaceResult = { readonly ok: true; readonly place: TimelinePlaceDetail | null } | TimelineFailure
export type TimelinePlaceVisitSummaryResult =
  | { readonly ok: true; readonly summary: TimelinePlaceVisitSummary | null }
  | TimelineFailure
export type TimelineRouteResult = { readonly ok: true; readonly points: TimelineRoutePoint[] } | TimelineFailure
// `legs[i]` is the points of the i-th leg asked for; an unknown trace adds nothing, so a leg of
// unknown traces is `[]`, never missing.
export type TimelineRoutesResult = { readonly ok: true; readonly legs: TimelineRoutePoint[][] } | TimelineFailure
export type TimelineWriteResult = { readonly ok: true } | TimelineFailure

export interface TimelineApi {
  // Every moment, newest first. Raw: superseded rows are included and no
  // cross-source reconciliation is applied (both are projector concerns —
  // doing half of it here would diverge from the native feed).
  moments(): Promise<TimelineMoment[]>
  // One moment by id. ⚠ Result-shaped: `moment` null means no such row (superseded, or never
  // existed) — an answer — while `ok: false` means the read failed. The old `T | null` meant
  // both, so a caller could only render "not found".
  moment(momentId: string): Promise<
    | { readonly ok: true; readonly moment: TimelineMoment | null }
    | { readonly ok: false; readonly error: { readonly kind: string; readonly message?: string } }
  >
  trips(): Promise<TimelineTrip[]>
  traces(): Promise<TimelineTrace[]>

  // ── projected (run A); `zone` is an IANA zone, '' = the browser's own ──
  // Home: days + bound trips + the best suggestion, from ONE snapshot.
  feed(zone: string): Promise<TimelineFeedResult>
  // Push form. `onError` is terminal: nothing is pushed after it.
  observeFeed(zone: string, callback: (result: TimelineFeedResult) => void, onError: (message: string) => void): () => void
  // Looks past reconciliation, so a moment opened from a trip resolves.
  momentDetail(momentId: string): Promise<TimelineMomentResult>
  trip(tripId: string, zone: string): Promise<TimelineTripResult>
  places(): Promise<TimelinePlacesResult>
  observePlaces(callback: (result: TimelinePlacesResult) => void, onError: (message: string) => void): () => void
  // A folded id is followed to its survivor (`redirectedFrom`).
  place(placeId: string): Promise<TimelinePlaceResult>
  // Just `place`'s `visitCount` + `lastVisitMs` (same counting), followed through a fold the same
  // way. ⚠ For a view that shows only the stats: `place` also resolves every visit moment.
  placeVisitSummary(placeId: string): Promise<TimelinePlaceVisitSummaryResult>
  // Legs concatenated in the given order; snapped route over the captured path per leg.
  movementRoute(traceIds: string[]): Promise<TimelineRouteResult>
  // Several legs in ONE read, boundaries kept: each relation is narrowed to the requested ids, so
  // only those polylines are loaded. A map coloured per leg uses this, never one call per leg.
  movementRoutes(legs: string[][]): Promise<TimelineRoutesResult>
  renamePlace(placeId: string, name: string): Promise<TimelineWriteResult>
  // Always written user-authored; never overwritten by inference.
  setPlaceRole(placeId: string, role: TimelinePlaceRole): Promise<TimelineWriteResult>
  // Both ids are resolved to their roots first; the survivor is min(rootA, rootB), not the
  // caller's pick. Ids already sharing a root are `alreadyMerged`.
  mergePlaces(a: string, b: string): Promise<{ readonly ok: true; readonly survivorId: string } | TimelineFailure>
  unmergePlace(placeId: string): Promise<TimelineWriteResult>
  acceptTripSuggestion(suggestionId: string, zone: string): Promise<{ readonly ok: true; readonly tripId: string } | TimelineFailure>
  // Per tab (never synced): a reload offers it again, as an app restart does natively.
  dismissTripSuggestion(suggestionId: string): Promise<TimelineWriteResult>
}

// Operations, not view models: Promise-shaped snapshots React refetches (thin-SDK
// decision, House Rule #5). ⚠ The LIST reads still fold a failure to `[]`, matching
// `Shares` / `IngestSources` / `Storage`; `moment` no longer does, because for a single-row
// lookup "not found" and "the read broke" are different answers and only one is actionable.
export const Timeline: TimelineApi = bundle.Timeline.getInstance()
