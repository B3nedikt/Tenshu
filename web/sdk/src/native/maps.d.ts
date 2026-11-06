// `Maps` — the saved-places surface (web-app-suite Wave 3, Maps run A).
// A fragment of `../native.d.ts` (see there for the bundle's shape and the type mapping).
//
// Mirrors `Maps.kt` / `PlacesBook.kt`. ⚠ No saved / note / list field: they have no store
// (`maps-app.md` §Web scope 5a).

interface NativeMapsPlace {
  readonly placeId: string
  // '' = unnamed.
  readonly name: string
  readonly nameIsUsers: boolean
  readonly city: string
  readonly countryCode: string
  // Degrees.
  readonly latitude: number
  readonly longitude: number
  // A CONFIRMED role, else null.
  readonly role: 'home' | 'work' | null
}

interface NativeMapsPlaceDetail {
  readonly place: NativeMapsPlace
  readonly redirectedFrom: string | null
  readonly mergedPlaceCount: number
}

type NativeMapsPlaces = { readonly ok: true; readonly places: NativeMapsPlace[] } | NativeFailure

interface MapsInstance {
  places(): Promise<NativeMapsPlaces>
  observePlaces(callback: (result: NativeMapsPlaces) => void, onError: (message: string) => void): () => void
  place(placeId: string): Promise<{ readonly ok: true; readonly place: NativeMapsPlaceDetail | null } | NativeFailure>
  renamePlace(placeId: string, name: string): Promise<{ readonly ok: true } | NativeFailure>
}

declare module '*/productionExecutable/tenshu-sdk-js.js' {
  export const Maps: { getInstance(): MapsInstance }
}
