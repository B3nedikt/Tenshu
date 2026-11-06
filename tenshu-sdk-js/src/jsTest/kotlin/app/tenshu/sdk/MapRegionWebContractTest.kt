@file:OptIn(DelicateCoroutinesApi::class)

package app.tenshu.sdk

import app.tenshu.sdk.internal.JsRuntime
import app.tenshu.sdk.internal.JsRuntimeBuilder
import app.tenshu.sdk.internal.MapRegionPlugin
import app.tenshu.sdk.internal.SyncedServicePlugins
import app.tenshu.sdk.internal.UnconfiguredTileTransport
import app.tenshu.sdk.internal.WebTileTransport
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.await
import kotlinx.coroutines.delay
import kotlinx.coroutines.promise
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Int8Array
import org.tenshu.core.common.security.NobleEd25519Backend
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Boundary-contract tests (`docs/testing.md` §9.3) for `MapRegion` — web-app-suite.md W-MAP — against
 * the **real** in-process runtime: the published row shape, the exact bytes, and the typed failures.
 *
 * ⚠ The jsTest suite shares one `JsRuntime`, and the transport is runtime-global, so every test
 * installs the transport it needs and [restore] puts the shipped (unconfigured) one back.
 */
class MapRegionWebContractTest : SdkRuntimeTest() {

    private val ed25519 = NobleEd25519Backend()
    private val seed = ByteArray(32) { 7 }
    private val testKey = ed25519.derivePublicKey(seed)

    // Two and a bit 256 KiB chunks, so a read can straddle a chunk boundary and hit the short last one.
    private val archive = ByteArray(600_000) { i -> ((i * 31 + i / 7) % 251).toByte() }

    private val catalogJson = """
        {"schemaVersion":1,"buildDate":"2026-09","regions":[
          {"id":"it","name":"Italy","coverage":"6.6,36.6,18.5,47.1","byteSize":600000,"sha256":"aa","tileSchemaVersion":4,"path":"regions/2026-09/it.pmtiles"},
          {"id":"it-toscana","name":"Toscana","coverage":"9.6,42.2,12.4,44.5","byteSize":600000,"sha256":"bb","tileSchemaVersion":4,"path":"regions/2026-09/it-toscana.pmtiles"},
          {"id":"de-bayern","name":"Bayern","coverage":"8.9,47.2,13.9,50.6","byteSize":600000,"sha256":"cc","tileSchemaVersion":4,"path":"regions/2026-09/de-bayern.pmtiles"},
          {"id":"no-box","name":"Nowhere","coverage":"","byteSize":10,"sha256":"dd","tileSchemaVersion":4,"path":"regions/2026-09/no-box.pmtiles"},
          {"id":"future","name":"Future","coverage":"0,0,1,1","byteSize":10,"sha256":"ee","tileSchemaVersion":5,"path":"regions/2026-09/future.pmtiles"}
        ]}
    """.trimIndent().encodeToByteArray()

    /** What the transport was asked for: `path` or `path@offset+length`. */
    private val requests = mutableListOf<String>()

    private fun fixture(signature: ByteArray = ed25519.sign(seed, catalogJson)) = WebTileTransport { path, offset, length ->
        requests += if (offset == null) path else "$path@$offset+$length"
        when {
            path == "catalog.json" -> catalogJson
            path == "catalog.json.sig" -> signature
            path.endsWith(".pmtiles") && offset != null && length != null -> {
                val end = minOf(archive.size.toLong(), offset + length).toInt()
                archive.copyOfRange(offset.toInt(), end)
            }
            else -> error("fixture has no $path")
        }
    }

    private fun install(transport: WebTileTransport = fixture()) {
        requests.clear()
        MapRegionPlugin.useTransport(transport, key = testKey)
    }

    @AfterTest
    fun restore() {
        MapRegionPlugin.useTransport(UnconfiguredTileTransport)
    }

    private fun kindOf(result: dynamic): String {
        assertEquals(false, result.ok, "expected a failure, got ${JSON.stringify(result)}")
        return result.error.kind as String
    }

    @Test
    fun theCatalog_isVerified_filteredToRenderableSchema_andHasThePublishedRowShape() = GlobalScope.promise {
        install()
        val res = MapRegion.catalog().await()
        assertEquals(true, res.ok)
        assertEquals("2026-09", res.buildDate)
        val rows = res.regions.unsafeCast<Array<dynamic>>()
        assertEquals(listOf("it", "it-toscana", "de-bayern", "no-box"), rows.map { it.regionId as String }, "v5 filtered out")
        val toscana = rows[1]
        assertEquals("Toscana", toscana.name)
        assertEquals(600_000.0, toscana.byteSize)
        assertContentEquals(arrayOf(9.6, 42.2, 12.4, 44.5), toscana.bounds.unsafeCast<Array<Double>>())
        assertNull(rows[3].bounds, "an unparseable coverage is null, not a made-up box")
        assertEquals(setOf("catalog.json", "catalog.json.sig"), requests.toSet())
    }

    @Test
    fun regionAt_answersTheTightestCoveringRegion_orNull() = GlobalScope.promise {
        install()
        val florence = MapRegion.regionAt(43.7696, 11.2558).await()
        assertEquals("it-toscana", florence.region.regionId, "a sub-country split beats the country (D7)")
        assertEquals("it", MapRegion.regionAt(41.9028, 12.4964).await().region.regionId) // Rome
        val atlantic = MapRegion.regionAt(30.0, -40.0).await()
        assertEquals(true, atlantic.ok)
        assertNull(atlantic.region, "outside every region is an answer, not a failure")
        assertEquals("error_map_bad_request", kindOf(MapRegion.regionAt(91.0, 0.0).await()))
    }

    @Test
    fun regionsIn_aBoxInsideOneRegion_isThatRegion_aCrossBorderBox_isEveryRegionItTouches() = GlobalScope.promise {
        install()
        val inFlorence = MapRegion.regionsIn(11.2, 43.75, 11.3, 43.8).await().regions.unsafeCast<Array<dynamic>>()
        assertEquals(listOf("it-toscana"), inFlorence.map { it.regionId as String })
        // Florence → Munich: contained by no single region.
        val trip = MapRegion.regionsIn(11.25, 43.77, 11.58, 48.14).await().regions.unsafeCast<Array<dynamic>>()
        assertEquals(listOf("it-toscana", "de-bayern", "it"), trip.map { it.regionId as String }, "tightest first")
    }

    @Test
    fun aRangeRead_returnsExactlyTheRequestedBytes_asItsOwnArrayBuffer() = GlobalScope.promise {
        install()
        // Straddles the 256 KiB chunk boundary at 262 144.
        val res = MapRegion.streamRange("it-toscana", 262_000.0, 5_000).await()
        assertEquals(true, res.ok)
        val bytes: Any? = res.bytes
        assertTrue(bytes is ArrayBuffer, "bytes cross the bridge as an ArrayBuffer")
        val buffer: ArrayBuffer = bytes
        assertEquals(5_000, buffer.byteLength, "an ArrayBuffer of exactly the bytes read, not a view's backing store")
        assertContentEquals(archive.copyOfRange(262_000, 267_000), Int8Array(buffer).unsafeCast<ByteArray>())
        // Ranged reads only, chunk-aligned, against the catalog's path for that region.
        assertTrue(requests.contains("regions/2026-09/it-toscana.pmtiles@0+262144"), "$requests")
        assertTrue(requests.contains("regions/2026-09/it-toscana.pmtiles@262144+262144"), "$requests")

        val tail = MapRegion.streamRange("it-toscana", 599_990.0, 100).await()
        assertEquals(10, tail.bytes.unsafeCast<ArrayBuffer>().byteLength, "short only at the archive's end")
        val eof = MapRegion.streamRange("it-toscana", 600_000.0, 100).await()
        assertEquals(true, eof.ok)
        assertEquals(0, eof.bytes.unsafeCast<ArrayBuffer>().byteLength, "empty at EOF")
    }

    @Test
    fun anUnknownRegion_isATypedError_neverEmptyBytes() = GlobalScope.promise {
        install()
        assertEquals("error_map_unknown_region", kindOf(MapRegion.streamRange("atlantis", 0.0, 16).await()))
        // A region the schema filter dropped is unknown too: the renderer must never be handed v5 tiles.
        assertEquals("error_map_unknown_region", kindOf(MapRegion.streamRange("future", 0.0, 16).await()))
        assertTrue(requests.none { it.contains(".pmtiles") }, "no tile byte was fetched: $requests")
    }

    @Test
    fun badRanges_areRefusedBeforeTheRuntime() = GlobalScope.promise {
        install()
        assertEquals("error_map_bad_request", kindOf(MapRegion.streamRange("it", -1.0, 16).await()))
        assertEquals("error_map_bad_request", kindOf(MapRegion.streamRange("it", 1.5, 16).await()))
        assertEquals("error_map_bad_request", kindOf(MapRegion.streamRange("it", 0.0, 0).await()))
        assertEquals("error_map_bad_request", kindOf(MapRegion.streamRange("it", 0.0, MapRegion.MAX_RANGE_BYTES + 1).await()))
        assertTrue(requests.isEmpty(), "$requests")
    }

    @Test
    fun aCatalogThatDoesNotVerify_failsClosed() = GlobalScope.promise {
        install(fixture(signature = ByteArray(64)))
        assertEquals("error_map_catalog_unverified", kindOf(MapRegion.catalog().await()))
        assertEquals("error_map_catalog_unverified", kindOf(MapRegion.regionAt(43.77, 11.25).await()))
        assertEquals("error_map_catalog_unverified", kindOf(MapRegion.streamRange("it", 0.0, 16).await()))
    }

    @Test
    fun theShippedKey_isThePinnedReleaseKey_soATestSignedCatalogDoesNotVerifyUnderIt() = GlobalScope.promise {
        requests.clear()
        MapRegionPlugin.useTransport(fixture()) // default key: the pin Control uses
        assertEquals("error_map_catalog_unverified", kindOf(MapRegion.catalog().await()))
    }

    @Test
    fun aShortBodyMidArchive_isATransportFailure_neverAnEarlyEof() = GlobalScope.promise {
        val healthy = fixture()
        install { path, offset, length ->
            val bytes = healthy.fetch(path, offset, length)
            if (path.endsWith(".pmtiles")) bytes.copyOfRange(0, bytes.size / 2) else bytes
        }
        assertEquals("error_map_transport_failed", kindOf(MapRegion.streamRange("it-toscana", 0.0, 16).await()))
    }

    @Test
    fun aFailingTransport_isATransportFailure_notEmptyBytes() = GlobalScope.promise {
        val healthy = fixture()
        install { path, offset, length ->
            if (path.endsWith(".pmtiles")) throw IllegalStateException("HTTP 503") else healthy.fetch(path, offset, length)
        }
        assertEquals("error_map_transport_failed", kindOf(MapRegion.streamRange("it", 0.0, 16).await()))
    }

    @Test
    fun anUnconfiguredTransport_refusesWithItsOwnKind() = GlobalScope.promise {
        MapRegionPlugin.useTransport(UnconfiguredTileTransport)
        assertEquals("error_map_transport_unconfigured", kindOf(MapRegion.catalog().await()))
        assertEquals("error_map_transport_unconfigured", kindOf(MapRegion.streamRange("it", 0.0, 16).await()))
    }

    @Test
    fun nothingStartsAtInit_theTransportIsUntouchedUntilAMapAsks() = GlobalScope.promise {
        install()
        JsRuntime.awaitReady()
        delay(200)
        assertTrue(requests.isEmpty(), "the runtime fetched without being asked: $requests")
        assertTrue(MapRegionPlugin in JsRuntimeBuilder.orderedPlugins)
        assertTrue(SyncedServicePlugins.none { it.id == "mapRegion" }, "mapRegion graduated from sync-only")
        MapRegion.catalog().await()
        assertEquals(setOf("catalog.json", "catalog.json.sig"), requests.toSet(), "and asking fetches the catalog only")
    }
}
