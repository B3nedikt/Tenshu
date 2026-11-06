// Maps entry point: saved places and place detail (web-app-suite Wave 3, Maps run A). Backed by
// `Maps` on the Kotlin side (`:tenshu-sdk-js`), which mirrors the native `PlaceRepository` over
// Timeline's place reads — one store, one read, one write path (`PlacesBook.kt` header).
//
// ⚠ No map here: rendering and `mapRegion` are W-MAP's. ⚠⚠ No saved state, notes or saved lists:
// they have no store on any platform (`maps-app.md` §Web scope 5a), so there is no method for them.
// ⚠ Every call is `{ ok: true, … }` or a `MapsFailure`; a failed read is a failure, never `[]`.

import * as bundle from './_bundle'

export interface MapsFailure {
  readonly ok: false
  readonly error: { readonly kind: string; readonly message?: string }
}

// The keys this surface mints. They are Timeline's: the reads and the write are shared with it.
// Any other failure carries the key derived from the service's typed error. Branch on `kind`.
export const MapsErrorKind = {
  placeNotFound: 'error_timeline_place_not_found',
  blankName: 'error_timeline_blank_name',
  // A relation read never answered — a typed failure instead of a list that hangs.
  sourceSilent: 'error_timeline_source_silent',
} as const

export type MapsPlaceRole = 'home' | 'work'

export interface MapsPlace {
  readonly placeId: string
  // '' = unnamed. On web only the user names a place (no geocoder).
  readonly name: string
  readonly nameIsUsers: boolean
  readonly city: string
  readonly countryCode: string
  // Degrees (WGS84).
  readonly latitude: number
  readonly longitude: number
  // CONFIRMED roles only — a suggestion never changes how a place is drawn.
  readonly role: MapsPlaceRole | null
}

export interface MapsPlaceDetail {
  readonly place: MapsPlace
  // The id asked for, when it was folded into `place` (a stale link); else null.
  readonly redirectedFrom: string | null
  // Places folded directly into this one.
  readonly mergedPlaceCount: number
}

export type MapsPlacesResult = { readonly ok: true; readonly places: MapsPlace[] } | MapsFailure
// ⚠ `place: null` = no such place (an answer); `ok: false` = the read failed.
export type MapsPlaceResult = { readonly ok: true; readonly place: MapsPlaceDetail | null } | MapsFailure
export type MapsWriteResult = { readonly ok: true } | MapsFailure

export interface MapsApi {
  // Every surviving (un-folded) place, in store order.
  places(): Promise<MapsPlacesResult>
  // Push form, on every place / merge / role change. `onError` is terminal.
  observePlaces(callback: (result: MapsPlacesResult) => void, onError: (message: string) => void): () => void
  // A folded id is followed to its survivor (`redirectedFrom`).
  place(placeId: string): Promise<MapsPlaceResult>
  // Timeline's rename: trimmed, user-authored. Pass the id a read returned.
  renamePlace(placeId: string, name: string): Promise<MapsWriteResult>
}

export const Maps: MapsApi = bundle.Maps.getInstance()
