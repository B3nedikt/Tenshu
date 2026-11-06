// `mapRegion.*`: the streamed-region surface of the shared web map renderer (web-app-suite.md W-MAP).
// Thin pass-throughs to `MapRegion` (`@tenshu/sdk/mapRegion`); the rules (verified catalog, typed
// failures, exact bytes) live in the Kotlin SDK and are pinned by `MapRegionWebContractTest`.
//
// ⚠⚠ `mapRegion.streamRange` TRANSFERS its ArrayBuffer (`transferring`), so a tile's bytes cross the
// origin boundary without a structured-clone copy. Safe because `MapRegion.streamRange` hands back a
// fresh copy per call; never wrap a buffer the runtime keeps.
// ⚠ Reachable only by an origin whose allowlist names the method (F5): each map app grants itself
// the reads its maps make (Timeline and Maps: `regionAt` + `streamRange`), never the whole namespace.
import { transferring } from '@tenshu/sdk/bridge'
import { MapRegion, MAX_REGION_RANGE_BYTES } from '@tenshu/sdk/mapRegion'
import type { GatewayModule } from './module'
import { asRecord, readId } from './params'

function readNumber(params: unknown, key: string, op: string): number {
  const v = asRecord(params)[key]
  if (typeof v !== 'number' || !Number.isFinite(v)) throw new Error(`${op} requires { ${key}: number }`)
  return v
}

export const mapRegionGateway: GatewayModule = {
  handlers: {
    'mapRegion.catalog': async () => MapRegion.catalog(),
    'mapRegion.regionAt': async (params) =>
      MapRegion.regionAt(
        readNumber(params, 'latitude', 'mapRegion.regionAt'),
        readNumber(params, 'longitude', 'mapRegion.regionAt'),
      ),
    'mapRegion.regionsIn': async (params) => {
      const op = 'mapRegion.regionsIn'
      return MapRegion.regionsIn(
        readNumber(params, 'minLng', op),
        readNumber(params, 'minLat', op),
        readNumber(params, 'maxLng', op),
        readNumber(params, 'maxLat', op),
      )
    },
    'mapRegion.streamRange': async (params) => {
      const op = 'mapRegion.streamRange'
      const offset = readNumber(params, 'offset', op)
      const length = readNumber(params, 'length', op)
      if (!Number.isInteger(offset) || offset < 0) throw new Error(`${op} requires a non-negative integer offset`)
      if (!Number.isInteger(length) || length < 1 || length > MAX_REGION_RANGE_BYTES) {
        throw new Error(`${op} requires an integer length in 1..${MAX_REGION_RANGE_BYTES}`)
      }
      const result = await MapRegion.streamRange(readId(params, 'regionId', op), offset, length)
      return result.ok ? transferring(result, [result.bytes]) : result
    },
  },
}
