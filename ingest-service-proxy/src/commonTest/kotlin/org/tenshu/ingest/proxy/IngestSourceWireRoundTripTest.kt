package org.tenshu.ingest.proxy

import org.tenshu.core.Data
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.createTestContext
import org.tenshu.core.common.parser.BinaryParser
import org.tenshu.core.common.parser.BinarySerializer
import org.tenshu.core.core.dataFactory
import org.tenshu.core.ingest.IngestSource
import org.tenshu.core.number.TInteger
import org.tenshu.ingest.test.createIngestTestRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * End-to-end wire coverage for the exact shape that desynced the binary parser:
 * an [IngestSource] with **null `lastRunAt` / `pendingCount` sitting between
 * populated optional fields** — the row a freshly-registered Android gallery /
 * document-tree source produces.
 *
 * Crosses the real IPC/stream path: `BinarySerializer().serialize(data)` with the
 * default `expectedType = null` (the non-optimized stream-push path Control uses
 * for a reactive `.flow()`), then `BinaryParser.parse(bytes)` with no expected
 * type. Before the fix this threw `No type id registered for bytes: 00000008…`
 * (fixed-length optional value desync) or mis-mapped trailing fields (the
 * positional null-collapse). See `core-common` OptionalValueFieldRoundTripTest.
 */
class IngestSourceWireRoundTripTest {

    private val registry = createIngestTestRegistry()
    private val context = createTestContext(registry)
    private val serializer = BinarySerializer(registry)
    private val parser = BinaryParser(registry, registry)

    private fun gallerySource(deviceId: String): IngestSource = IngestSource.create(
        context = context,
        sourceId = "drive.gallery",
        appId = "app.tenshu.drive",
        deviceId = deviceId,
        displayName = "Photos & Videos",
        sourceKind = IngestSource.KIND_GALLERY,
        enabled = true,
        watchedPaths = null,                       // mobile: empty
        lastRunAt = null,                          // freshly registered → null (MIDDLE optional)
        lastStatus = IngestSource.STATUS_IDLE,     // present, after a null
        pendingCount = null,                       // null (MIDDLE optional)
        lastError = null,                          // trailing null
    )

    private fun assertIsFreshGallery(row: IngestSource, deviceId: String) {
        assertEquals(IngestSource.keyOf("drive.gallery", deviceId), row.key.get())
        assertEquals("drive.gallery", row.sourceId.get())
        assertEquals("app.tenshu.drive", row.appId.get())
        assertEquals(deviceId, row.deviceId.get())
        assertEquals("Photos & Videos", row.displayName.get())
        assertEquals(IngestSource.KIND_GALLERY, row.sourceKind.get())
        assertEquals(true, row.enabled.get())
        assertNull(row.watchedPaths, "null/empty watchedPaths must survive as null")
        assertNull(row.lastRunAt, "null lastRunAt must survive as null (not a misframed value)")
        assertEquals(IngestSource.STATUS_IDLE, row.lastStatus?.get(), "lastStatus must not shift into lastRunAt")
        assertNull(row.pendingCount, "null pendingCount must survive as null")
        assertNull(row.lastError, "trailing null lastError must survive as null")
    }

    @Test
    fun singleFreshGallerySource_roundTrips_nonOptimized() {
        val source = gallerySource("device-A")
        val bytes = serializer.serialize(source) // default expectedType = null (stream-push)
        val parsed = parser.parse<Data>(bytes) as IngestSource
        assertIsFreshGallery(parsed, "device-A")
    }

    @Test
    fun listOfFreshSources_roundTrips_nonOptimized() {
        val list = context.dataFactory().createList<Data>(
            listOf(
                gallerySource("device-A"),
                gallerySource("device-B"),
                gallerySource("device-C"),
            )
        )
        val bytes = serializer.serialize(list) // default expectedType = null
        val parsed = parser.parse<Data>(bytes)
        val rows = (parsed as org.tenshu.core.collection.list.TList<*>).toList()
            .filterIsInstance<IngestSource>()
        assertEquals(3, rows.size)
        assertIsFreshGallery(rows[0], "device-A")
        assertIsFreshGallery(rows[1], "device-B")
        assertIsFreshGallery(rows[2], "device-C")
    }

    @Test
    fun presentStatusFields_roundTrip_nonOptimized() {
        // A source that has run: present lastRunAt (incl. a 0 / non-zero int) +
        // present pendingCount — guards the present-optional-value path (the
        // length prefix must not be read as the value).
        val source = IngestSource.create(
            context = context,
            sourceId = "drive.gallery",
            appId = "app.tenshu.drive",
            deviceId = "device-A",
            displayName = "Photos & Videos",
            sourceKind = IngestSource.KIND_GALLERY,
            enabled = false,
            watchedPaths = "/sdcard/DCIM\n/sdcard/Pictures",
            lastRunAt = 0L,                        // present value 0 must NOT be read as null
            lastStatus = IngestSource.STATUS_UP_TO_DATE,
            pendingCount = 3L,
            lastError = null,
        )
        val bytes = serializer.serialize(source)
        val parsed = parser.parse<Data>(bytes) as IngestSource

        assertEquals(false, parsed.enabled.get())
        assertEquals("/sdcard/DCIM\n/sdcard/Pictures", parsed.watchedPaths?.get())
        assertEquals(0L, (parsed.lastRunAt as TInteger).get(), "present lastRunAt == 0 must survive, not become null")
        assertEquals(IngestSource.STATUS_UP_TO_DATE, parsed.lastStatus?.get())
        assertEquals(3L, parsed.pendingCount?.get())
        assertNull(parsed.lastError)
    }
}
