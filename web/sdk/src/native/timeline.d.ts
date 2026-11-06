// `Timeline` — the `:timeline-service` read bridge.
// A fragment of `../native.d.ts` (see there for the bundle's shape and the type mapping).

// Timeline: the `:timeline-service` read bridge (moments / trips / movement
// traces). Mirrors the `@JsExport` classes in `Timeline.kt`; every numeric field
// is a JS number (`Double` on the Kotlin side).
//
// ⚠ `subjectKind` / `subjectId` are the *un-hydrated* polymorphic ref — a `place`
// names a `Place.placeId` and a `workout` a `Workout.workoutId`, neither of which
// has a JS bridge yet. Only `trace` resolves today, via `traces()`.
interface NativeTimelineMoment {
  readonly momentId: string
  readonly timestampMs: number
  // 'Activity' | 'Visit' | 'Event' | 'Quiet' | 'Movement' (Moment.MomentKind).
  readonly kind: string
  readonly title: string
  readonly durationSec: number
  // 'none' | 'place' | 'workout' | 'trace' (SubjectKind).
  readonly subjectKind: string
  readonly subjectId: string
  // The canonical moment's key when reconciliation folded this one away; '' otherwise.
  readonly supersededBy: string
  readonly sources: string[]
}

interface NativeTimelineTrip {
  readonly tripId: string
  readonly title: string
  readonly dateRange: string
  readonly year: string
  readonly momentIds: string[]
}

// Summary only — the decimated polyline blob never crosses this boundary.
interface NativeTimelineTrace {
  readonly traceId: string
  // 'walk' | 'run' | 'cycle' | 'drive' | 'unknown' (MovementMode).
  readonly mode: string
  readonly distanceM: number
}


// ── Projected read model (web-app-suite Wave 2, Timeline run A — `TimelineBook.kt`) ──
// Mirrors the `@JsExport` classes there. ⚠ Other subject kinds' fields are `null`, never ''.

interface NativeTimelineDayMoment {
  readonly momentId: string
  readonly timestampMs: number
  readonly kind: string
  readonly title: string
  readonly durationSec: number
  readonly subjectKind: string
  // For a place: the merge ROOT (a folded place's visits point at the survivor).
  readonly subjectId: string
  readonly subjectResolved: boolean
  readonly sources: string[]
  readonly placeId: string | null
  readonly placeName: string | null
  readonly placeCity: string | null
  readonly placeCountryCode: string | null
  readonly placeLatE7: number | null
  readonly placeLngE7: number | null
  // Confirmed roles only ('home' | 'work').
  readonly placeRole: string | null
  readonly activityType: string | null
  readonly traceId: string | null
  readonly movementMode: string | null
  readonly distanceM: number | null
  // The fold, from the shared `:timeline-projection` (feed rows). The fields above stay the
  // representative stored moment's; on trip / place-detail rows these restate that moment.
  readonly displayTitle: string
  readonly spanDurationMin: number | null
  readonly spanDistanceM: number | null
  readonly mergedStayCount: number
  readonly traceIds: string[]
  readonly containedMoves: NativeTimelineContainedMove[]
  readonly originPlace: string | null
  readonly destinationPlace: string | null
  // Confirmed roles only.
  readonly originRole: string | null
  readonly destinationRole: string | null
}

interface NativeTimelineContainedMove {
  readonly mode: string
  readonly distanceM: number
  readonly durationMin: number | null
}

interface NativeTimelineDay {
  // 'YYYY-MM-DD' in the zone the read was asked for.
  readonly date: string
  readonly moments: NativeTimelineDayMoment[]
}

interface NativeTimelineTripSummary {
  readonly tripId: string
  readonly title: string
  readonly dateRange: string
  readonly year: string
  readonly momentIds: string[]
}

interface NativeTimelineTripDetail {
  readonly tripId: string
  readonly title: string
  readonly dateRange: string
  readonly year: string
  readonly days: NativeTimelineDay[]
  readonly missingMomentCount: number
}

interface NativeTimelineTripSuggestion {
  readonly suggestionId: string
  readonly title: string
  readonly dateRange: string
  readonly daysSpan: number
  readonly placesCount: number
  readonly startMs: number
  readonly endMs: number
  readonly momentIds: string[]
}

interface NativeTimelinePlace {
  readonly placeId: string
  readonly name: string
  readonly city: string
  readonly countryCode: string
  readonly latE7: number
  readonly lngE7: number
  readonly nameIsUsers: boolean
  readonly role: string | null
  readonly roleIsSuggestion: boolean
  readonly visitCount: number
  readonly lastVisitMs: number | null
}

interface NativeTimelineFoldedPlace {
  readonly placeId: string
  readonly name: string
}

interface NativeTimelinePlaceDetail {
  readonly place: NativeTimelinePlace
  readonly visits: NativeTimelineDayMoment[]
  readonly mergedPlaceCount: number
  readonly redirectedFrom: string | null
  readonly foldedPlaces: NativeTimelineFoldedPlace[]
}

// `TimelinePlaceVisitSummaryJs`: the detail's two visit numbers, without the visits.
interface NativeTimelinePlaceVisitSummary {
  readonly placeId: string
  readonly visitCount: number
  readonly lastVisitMs: number | null
  readonly redirectedFrom: string | null
}

interface NativeTimelineRoutePoint {
  readonly latitude: number
  readonly longitude: number
  readonly timestampMs: number
}

type NativeTimelineFeed =
  | {
      readonly ok: true
      readonly days: NativeTimelineDay[]
      readonly trips: NativeTimelineTripSummary[]
      readonly suggestion: NativeTimelineTripSuggestion | null
    }
  | NativeFailure
type NativeTimelinePlaces = { readonly ok: true; readonly places: NativeTimelinePlace[] } | NativeFailure

interface TimelineInstance {
  // Every moment, newest first. Raw: superseded rows included, no reconciliation.
  moments(): Promise<NativeTimelineMoment[]>
  // One moment by id, or null when unknown.
  // ⚠ `moment` null = no such row (superseded or never existed); `ok: false` = the read failed.
  moment(momentId: string): Promise<{ readonly ok: true; readonly moment: NativeTimelineMoment | null } | NativeFailure>
  trips(): Promise<NativeTimelineTrip[]>
  traces(): Promise<NativeTimelineTrace[]>

  // Projected (run A). `zone` is an IANA zone, '' = the browser's own.
  feed(zone: string): Promise<NativeTimelineFeed>
  observeFeed(zone: string, callback: (result: NativeTimelineFeed) => void, onError: (message: string) => void): () => void
  momentDetail(momentId: string): Promise<{ readonly ok: true; readonly moment: NativeTimelineDayMoment | null } | NativeFailure>
  trip(tripId: string, zone: string): Promise<{ readonly ok: true; readonly trip: NativeTimelineTripDetail | null } | NativeFailure>
  places(): Promise<NativeTimelinePlaces>
  observePlaces(callback: (result: NativeTimelinePlaces) => void, onError: (message: string) => void): () => void
  place(placeId: string): Promise<{ readonly ok: true; readonly place: NativeTimelinePlaceDetail | null } | NativeFailure>
  placeVisitSummary(
    placeId: string,
  ): Promise<{ readonly ok: true; readonly summary: NativeTimelinePlaceVisitSummary | null } | NativeFailure>
  movementRoute(traceIds: string[]): Promise<{ readonly ok: true; readonly points: NativeTimelineRoutePoint[] } | NativeFailure>
  movementRoutes(legs: string[][]): Promise<{ readonly ok: true; readonly legs: NativeTimelineRoutePoint[][] } | NativeFailure>
  renamePlace(placeId: string, name: string): Promise<{ readonly ok: true } | NativeFailure>
  setPlaceRole(placeId: string, role: string): Promise<{ readonly ok: true } | NativeFailure>
  mergePlaces(a: string, b: string): Promise<{ readonly ok: true; readonly survivorId: string } | NativeFailure>
  unmergePlace(placeId: string): Promise<{ readonly ok: true } | NativeFailure>
  acceptTripSuggestion(suggestionId: string, zone: string): Promise<{ readonly ok: true; readonly tripId: string } | NativeFailure>
  dismissTripSuggestion(suggestionId: string): Promise<{ readonly ok: true } | NativeFailure>
}

declare module '*/productionExecutable/tenshu-sdk-js.js' {
  export const Timeline: { getInstance(): TimelineInstance }
}
