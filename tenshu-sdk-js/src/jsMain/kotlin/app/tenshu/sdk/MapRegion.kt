@file:OptIn(ExperimentalJsExport::class, DelicateCoroutinesApi::class)

package app.tenshu.sdk

import app.tenshu.mapregion.app.GeoBounds
import app.tenshu.mapregion.app.LatLng
import app.tenshu.mapregion.app.RegionResolver
import app.tenshu.mapregion.app.ResolvableRegion
import app.tenshu.sdk.internal.JsRuntime
import app.tenshu.sdk.internal.MapRegionErrors
import app.tenshu.sdk.internal.failureResult
import app.tenshu.sdk.internal.okResult
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.promise
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Int8Array
import org.tenshu.core.GenericError
import org.tenshu.core.InternalError
import org.tenshu.core.TError
import org.tenshu.core.TResult
import org.tenshu.core.collection.list.toList
import org.tenshu.core.getOrNull
import org.tenshu.core.mapregion.RegionEntry
import org.tenshu.core.mapregion.catalog
import org.tenshu.core.mapregion.streamRegionRange
import org.tenshu.mapregion.client.mapRegionService
import kotlin.js.Promise

/**
 * The streamed-region surface of the shared web map renderer (`web-app-suite.md` §4, W-MAP) — the web
 * twin of `RegionTerminus.renderableStream` (`:map-region-app`): which catalog region covers a place or
 * a camera, and the bytes of its PMTiles archive, a range at a time. `@tenshu/map-kit` binds a MapLibre
 * PMTiles source to [streamRange]; the archive itself is read by `StreamingRegionSource`, the class
 * Control runs (`MapRegionPlugin.kt`).
 *
 * ⚠⚠ **Until the transport is decided, every call that needs the tile host answers
 * `error_map_transport_unconfigured`** (`maps-region-delivery.md` §Web scope W.1). That is the shipped
 * state, and it must read as "maps are not available here yet", never as an empty catalog.
 *
 * Every call resolves `{ ok: true, … }` or `{ ok: false, error: { kind, message } }`, `kind` an
 * `error_map_…` catalogue key (`MapRegionErrors`). ⚠ A failure is never an empty answer: an unreachable
 * catalog is not "no regions", and a failed read is not zero bytes. The one empty answer is a range at
 * or past the archive's end, which is EOF.
 *
 * A region row is `{ regionId, name, byteSize, bounds }`; `bounds` is `[minLng, minLat, maxLng, maxLat]`
 * from the catalog's coarse coverage (D3: for framing, not rendering), or `null` when the row has no
 * parseable box — such a row is never chosen by a lookup.
 */
@JsExport
object MapRegion {

    /**
     * The largest [streamRange] window: 1 MiB. Bigger than native's ≤ 256 KiB (a Binder transaction cap
     * the bridge does not have), and still small enough that one postMessage never carries an archive.
     */
    const val MAX_RANGE_BYTES: Int = 1 shl 20

    /** The verified, schema-filtered catalog: `{ ok: true, buildDate, regions }`. */
    fun catalog(): Promise<dynamic> = GlobalScope.promise {
        JsRuntime.awaitReady()
        withCatalog { buildDate, rows ->
            okResult { r ->
                r.buildDate = buildDate
                r.regions = rows.map { it.toJs() }.toTypedArray()
            }
        }
    }

    /**
     * The tightest region covering the point (D7: a sub-country split beats the country around it):
     * `{ ok: true, region }`, `region` null when no region covers it — an answer, not a failure.
     */
    fun regionAt(latitude: Double, longitude: Double): Promise<dynamic> = GlobalScope.promise {
        JsRuntime.awaitReady()
        if (!validLatLng(latitude, longitude)) {
            return@promise failureResult(MapRegionErrors.BAD_REQUEST, "regionAt($latitude, $longitude): not a WGS84 point")
        }
        withCatalog { _, rows ->
            val hit = RegionResolver.coveringRegion(LatLng(latitude, longitude), rows.resolvable())
            okResult { r -> r.region = rows.firstOrNull { it.regionId == hit?.regionId }?.toJs() }
        }
    }

    /**
     * The regions a camera box `[minLng, minLat, maxLng, maxLat]` touches, tightest first:
     * `{ ok: true, regions }`.
     *
     * When one region contains the **whole** box, only the tightest such region is returned — a Florence
     * camera wants Tuscany, not Tuscany and Italy. Otherwise every region intersecting the box, so a
     * cross-border route can see what it spans.
     */
    fun regionsIn(minLng: Double, minLat: Double, maxLng: Double, maxLat: Double): Promise<dynamic> =
        GlobalScope.promise {
            JsRuntime.awaitReady()
            if (!validLatLng(minLat, minLng) || !validLatLng(maxLat, maxLng) || minLat > maxLat || minLng > maxLng) {
                return@promise failureResult(MapRegionErrors.BAD_REQUEST, "regionsIn: not a WGS84 box")
            }
            val box = GeoBounds(minLat = minLat, minLng = minLng, maxLat = maxLat, maxLng = maxLng)
            withCatalog { _, rows ->
                val boxed = rows.filter { it.bounds != null }.sortedBy { it.bounds!!.area }
                val containing = boxed.firstOrNull { it.bounds!!.containsBox(box) }
                val hits = if (containing != null) listOf(containing) else boxed.filter { it.bounds!!.intersects(box) }
                okResult { r -> r.regions = hits.map { it.toJs() }.toTypedArray() }
            }
        }

    /**
     * Up to [length] bytes at [offset] of [regionId]'s archive: `{ ok: true, bytes }`, `bytes` an
     * **`ArrayBuffer` of its own** — exactly the bytes read, never a view into a shared buffer — so the
     * gateway can hand it across the bridge as a transferable without detaching anything the runtime
     * still holds. Shorter than [length] only at the archive's end; empty at or past it.
     *
     * ⚠ The region is checked against the verified catalog first, so an unknown id is
     * `error_map_unknown_region` — never empty bytes, which a PMTiles reader would take for EOF.
     */
    fun streamRange(regionId: String, offset: Double, length: Int): Promise<dynamic> = GlobalScope.promise {
        JsRuntime.awaitReady()
        if (regionId.isEmpty() || !(offset >= 0.0) || offset != kotlin.math.floor(offset) || offset > MAX_SAFE_INTEGER ||
            length < 1 || length > MAX_RANGE_BYTES
        ) {
            return@promise failureResult(
                MapRegionErrors.BAD_REQUEST,
                "streamRange('$regionId', $offset, $length): offset must be a non-negative integer, length 1..$MAX_RANGE_BYTES",
            )
        }
        withCatalog { _, rows ->
            if (rows.none { it.regionId == regionId }) {
                return@withCatalog failureResult(MapRegionErrors.UNKNOWN_REGION, "no region '$regionId' in the verified catalog")
            }
            val read = JsRuntime.tenshuClient.mapRegionService.streamRegionRange(regionId, offset.toLong(), length)
            val bytes = read.getOrNull()?.get()
            when {
                bytes != null -> okResult { r -> r.bytes = ownBuffer(bytes) }
                read is TResult.Failure<*> -> failure(read.error)
                else -> failureResult(MapRegionErrors.UNAVAILABLE, "streamRange answered no bytes")
            }
        }
    }

    // ── internals ──────────────────────────────────────────────────────────────────────────────

    private const val MAX_SAFE_INTEGER = 9007199254740991.0

    private class Row(val regionId: String, val name: String, val byteSize: Long, val bounds: GeoBounds?)

    private suspend fun withCatalog(block: suspend (buildDate: String, rows: List<Row>) -> dynamic): dynamic =
        JsRuntime.tenshuClient.mapRegionService.catalog().let { result ->
            val catalog = result.getOrNull()
            when {
                catalog != null -> block(catalog.buildDate.get(), catalog.regions.toList().map { it.toRow() })
                result is TResult.Failure<*> -> failure(result.error)
                else -> failureResult(MapRegionErrors.UNAVAILABLE, "catalog answered nothing")
            }
        }

    private fun RegionEntry.toRow() = Row(
        regionId = regionId.get(),
        name = name.get(),
        byteSize = byteSize.get(),
        bounds = GeoBounds.parseBbox(coverage.get()),
    )

    private fun List<Row>.resolvable() =
        mapNotNull { row -> row.bounds?.let { ResolvableRegion(regionId = row.regionId, bounds = it) } }

    private fun Row.toJs(): dynamic {
        val o: dynamic = js("({})")
        o.regionId = regionId
        o.name = name
        o.byteSize = byteSize.toDouble()
        o.bounds = bounds?.let { arrayOf(it.minLng, it.minLat, it.maxLng, it.maxLat) }
        return o
    }

    private fun GeoBounds.containsBox(b: GeoBounds) =
        b.minLat >= minLat && b.maxLat <= maxLat && b.minLng >= minLng && b.maxLng <= maxLng

    private fun GeoBounds.intersects(b: GeoBounds) =
        b.minLat <= maxLat && b.maxLat >= minLat && b.minLng <= maxLng && b.maxLng >= minLng

    private fun validLatLng(lat: Double, lng: Double) = lat in -90.0..90.0 && lng in -180.0..180.0

    /** A fresh `ArrayBuffer` holding exactly [bytes] — always a copy, so transferring it detaches nothing else. */
    private fun ownBuffer(bytes: ByteArray): ArrayBuffer {
        val view = bytes.unsafeCast<Int8Array>()
        return view.buffer.slice(view.byteOffset, view.byteOffset + view.byteLength)
    }

    private fun failure(error: TError): dynamic {
        val text = (error as? GenericError)?.message?.get()
            ?: (error as? InternalError)?.internalDetail?.get()
            ?: error.schema.id.get()
        return failureResult(MapRegionErrors.kindOf(text), text)
    }
}
