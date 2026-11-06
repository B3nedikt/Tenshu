// `<TenshuMap>`'s props and copy, shared by both halves — kept apart from `TenshuMap.tsx` so the
// lazily loaded `MapView` does not import its own loader.
import type { MapCamera, MapPin, MapPolyline } from './overlays'
import type { RegionAccess } from './regions'
import type { MapTheme } from './style/basemapStyle'

/** Copy the consuming app localises. English defaults so a harness renders without i18n. */
export interface TenshuMapLabels {
  readonly map: string
  readonly loading: string
  readonly unavailable: string
  readonly noWebGL: string
  readonly notConfigured: string
  readonly tilesFailed: string
  readonly noRegion: string
}

export const DEFAULT_MAP_LABELS: TenshuMapLabels = {
  map: 'Map',
  loading: 'Loading map…',
  unavailable: 'Map unavailable',
  noWebGL: 'This browser cannot draw maps (WebGL is off or unsupported).',
  notConfigured: 'Map tiles are not set up for this server.',
  tilesFailed: 'Map tiles could not be loaded.',
  noRegion: 'No map data for this area.',
}

export interface TenshuMapProps {
  readonly regions: RegionAccess
  readonly camera: MapCamera
  readonly pins?: readonly MapPin[]
  readonly polylines?: readonly MapPolyline[]
  /** Follow the app's theme; defaults to the system scheme. */
  readonly theme?: MapTheme
  /** BCP-47; defaults to `navigator.language`. */
  readonly language?: string
  /** Where the map app serves its glyphs; defaults to `<own origin>/glyphs`. */
  readonly glyphsBase?: string
  readonly onPinClick?: (id: string) => void
  readonly labels?: Partial<TenshuMapLabels>
  readonly className?: string
}
