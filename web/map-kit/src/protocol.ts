// The MapLibre side of a streamed region: a PMTiles `Source` whose bytes come from `RegionAccess`
// (the bridge, in an app), and the `addProtocol` handler that serves TileJSON + tiles from it.
//
// ⚠⚠ Our own protocol handler, not `pmtiles`' `Protocol`: that one answers a key it has never seen
// by constructing a `FetchSource` for it — a `fetch()` of whatever the URL says. A map must never
// fetch tiles itself (the app frame's only network is its own origin), so an unknown key here is an
// error, never a fallback.
// ⚠ This module imports `pmtiles`, so only the lazily loaded `MapView` may import it (`lazySplit.test`).
import { PMTiles, type RangeResponse, type Source } from 'pmtiles'
import { MAX_RANGE_BYTES, RegionTileError, type RegionAccess } from './regions'

export const PROTOCOL = 'tenshu-pmtiles'

/** The style URL of a region's vector source. */
export function regionTileUrl(regionId: string): string {
  return `${PROTOCOL}://${encodeURIComponent(regionId)}`
}

/**
 * A region's archive as a PMTiles `Source`. A read longer than one bridge window is split into
 * windows and joined; a short window means EOF and ends the read. Any failure REJECTS — a PMTiles
 * reader would take empty bytes for a missing tile, which is how a broken transport turns into a
 * silently blank map.
 */
export class RegionArchiveSource implements Source {
  readonly regionId: string
  private readonly access: RegionAccess

  constructor(regionId: string, access: RegionAccess) {
    this.regionId = regionId
    this.access = access
  }

  getKey(): string {
    return encodeURIComponent(this.regionId)
  }

  async getBytes(offset: number, length: number, signal?: AbortSignal): Promise<RangeResponse> {
    const out = new Uint8Array(length)
    let filled = 0
    while (filled < length) {
      signal?.throwIfAborted()
      const want = Math.min(MAX_RANGE_BYTES, length - filled)
      const result = await this.access.readRange(this.regionId, offset + filled, want)
      if (!result.ok) throw new RegionTileError(result.error.kind, result.error.message ?? result.error.kind)
      const chunk = new Uint8Array(result.bytes)
      if (chunk.byteLength > want) {
        throw new RegionTileError('error_map_unavailable', `asked for ${want} bytes at ${offset + filled}, got ${chunk.byteLength}`)
      }
      out.set(chunk, filled)
      filled += chunk.byteLength
      if (chunk.byteLength < want) break // EOF
    }
    return { data: filled === length ? out.buffer : out.slice(0, filled).buffer }
  }
}

interface RequestParameters {
  readonly url: string
  readonly type?: string
}

/** Registered archives by key. One handler serves every map on the page. */
const archives = new Map<string, PMTiles>()

/** Makes [regionId]'s archive servable at [regionTileUrl]; returns that URL. */
export function registerRegion(regionId: string, access: RegionAccess): string {
  const source = new RegionArchiveSource(regionId, access)
  archives.set(source.getKey(), new PMTiles(source))
  return regionTileUrl(regionId)
}

/** Test seam: forget every registered archive. */
export function clearRegions(): void {
  archives.clear()
}

const TILE_URL = new RegExp(`^${PROTOCOL}://([^/]+)/(\\d+)/(\\d+)/(\\d+)$`)
const ARCHIVE_URL = new RegExp(`^${PROTOCOL}://([^/]+)$`)

function archive(key: string): PMTiles {
  const found = archives.get(key)
  if (!found) throw new RegionTileError('error_map_unknown_region', `no archive registered for ${key}`)
  return found
}

/**
 * The `maplibregl.addProtocol(PROTOCOL, …)` handler (MapLibre v4+ promise form). A TileJSON request
 * answers from the archive header; a tile request answers the tile, an empty buffer for a vector tile
 * the archive does not have (open sea), and a REJECTION for every failure — which MapLibre reports as
 * a tile `error` event that `MapView` turns into a visible notice.
 */
export async function handleRegionRequest(
  params: RequestParameters,
  abort: AbortController,
): Promise<{ data: unknown }> {
  const whole = ARCHIVE_URL.exec(params.url)
  if (whole) {
    const header = await archive(whole[1]!).getHeader()
    return {
      data: {
        tiles: [`${params.url}/{z}/{x}/{y}`],
        minzoom: header.minZoom,
        maxzoom: header.maxZoom,
        bounds: [header.minLon, header.minLat, header.maxLon, header.maxLat],
      },
    }
  }
  const tile = TILE_URL.exec(params.url)
  if (!tile) throw new RegionTileError('error_map_bad_request', `not a ${PROTOCOL} URL: ${params.url}`)
  const pm = archive(tile[1]!)
  const found = await pm.getZxy(Number(tile[2]), Number(tile[3]), Number(tile[4]), abort.signal)
  if (found) return { data: new Uint8Array(found.data) }
  return { data: new Uint8Array(0) }
}
