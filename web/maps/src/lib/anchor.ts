// Which place the list map is ABOUT. A map draws one region (`maps-region-delivery.md`
// §Web scope W.5), and the region is looked up at one point. The middle of every shown place is the
// wrong point: Berlin + New York's middle is the Atlantic (no region, no basemap), and Munich +
// Hamburg's lands in one of the two at random. So the map is anchored on a real place:
//   1. the confirmed home, else
//   2. the densest cluster (most places within a cell of each other), else — when clusters tie —
//   3. the most recently visited of the tied, else the first of them in list order.
// map-kit fits the camera to the places inside the anchor's region; every shown place keeps its pin.
import type { LatLng } from '@tenshu/map-kit'
import type { MapsPlace } from '@tenshu/sdk/maps'

// ~55 km of latitude: a city and its suburbs are one cluster, two cities are two.
export const CLUSTER_DEGREES = 0.5
const COLUMNS = Math.round(360 / CLUSTER_DEGREES)

export interface AnchorChoice {
  readonly anchor: MapsPlace | null
  // Clusters tied and no recency was given: the caller may read it and choose again.
  readonly needsRecency: boolean
}

function cellOf(p: MapsPlace): readonly [number, number] {
  const lng = ((((p.longitude + 180) % 360) + 360) % 360) - 180
  return [Math.floor(p.latitude / CLUSTER_DEGREES), Math.floor((lng + 180) / CLUSTER_DEGREES) % COLUMNS]
}

// Neighbouring cells, the column wrapping at the antimeridian.
function adjacent(a: readonly [number, number], b: readonly [number, number]): boolean {
  const dCol = Math.abs(a[1] - b[1])
  return Math.abs(a[0] - b[0]) <= 1 && Math.min(dCol, COLUMNS - dCol) <= 1
}

// O(n): each place counts the places in its own and the eight cells around it.
export function chooseAnchor(
  places: readonly MapsPlace[],
  lastVisitMs?: ReadonlyMap<string, number | null>,
): AnchorChoice {
  if (places.length === 0) return { anchor: null, needsRecency: false }
  const home = places.find((p) => p.role === 'home')
  if (home) return { anchor: home, needsRecency: false }

  const cells = places.map(cellOf)
  const sizes = new Map<string, number>()
  for (const [r, c] of cells) sizes.set(`${r}:${c}`, (sizes.get(`${r}:${c}`) ?? 0) + 1)
  const counts = cells.map(([r, c]) => {
    let n = 0
    for (let dr = -1; dr <= 1; dr++)
      for (let dc = -1; dc <= 1; dc++) n += sizes.get(`${r + dr}:${(c + dc + COLUMNS) % COLUMNS}`) ?? 0
    return n
  })
  const max = Math.max(...counts)
  const top = places.map((_, i) => i).filter((i) => counts[i] === max)

  // The densest places, grouped: neighbours are one cluster, not a tie.
  const seeds: number[] = []
  for (const i of top) if (!seeds.some((s) => adjacent(cells[s]!, cells[i]!))) seeds.push(i)
  if (seeds.length === 1) return { anchor: places[seeds[0]!]!, needsRecency: false }

  if (lastVisitMs) {
    let best: number | null = null
    let bestMs = -Infinity
    for (const i of top) {
      const ms = lastVisitMs.get(places[i]!.placeId) ?? null
      if (ms !== null && ms > bestMs) {
        best = i
        bestMs = ms
      }
    }
    if (best !== null) return { anchor: places[best]!, needsRecency: false }
    return { anchor: places[seeds[0]!]!, needsRecency: false }
  }
  return { anchor: places[seeds[0]!]!, needsRecency: true }
}

export function position(p: MapsPlace): LatLng {
  return { lat: p.latitude, lng: p.longitude }
}
