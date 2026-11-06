package app.tenshu.sdk.internal

import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.promise
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Int8Array
import org.tenshu.mapregion.proxy.TILES_HOST_URL
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** The shipped W.1 transport, against a fake `fetch`: what it asks for, and how it reads each answer. */
@OptIn(DelicateCoroutinesApi::class)
class FetchTileTransportTest {

    private class Seen(val url: String, val init: dynamic)

    private val body = ByteArray(100) { it.toByte() }

    private fun fake(status: Int, payload: ByteArray, seen: MutableList<Seen>) = { url: String, init: dynamic ->
        seen += Seen(url, init)
        val buffer: ArrayBuffer = Int8Array(payload.unsafeCast<Int8Array>()).buffer
        val response: dynamic = js("({})")
        response.status = status
        response.arrayBuffer = { Promise.resolve(buffer) }
        Promise.resolve(response.unsafeCast<JsFetchResponse>())
    }

    @Test
    fun aRangedRead_sendsOneByteRange_withNoCookiesAndNoReferrer() = GlobalScope.promise {
        val seen = mutableListOf<Seen>()
        val t = FetchTileTransport(TILES_HOST_URL, fake(206, body.copyOfRange(10, 30), seen))
        val bytes = t.fetch("regions/2026-09/it.pmtiles", 10, 20)
        assertContentEquals(body.copyOfRange(10, 30), bytes)
        val s = seen.single()
        assertEquals("https://tiles.service.tenshu.app/regions/2026-09/it.pmtiles", s.url)
        assertEquals("bytes=10-29", s.init.headers["Range"] as String)
        assertEquals("omit", s.init.credentials as String)
        assertEquals("no-referrer", s.init.referrerPolicy as String)
        assertEquals("cors", s.init.mode as String)
    }

    @Test
    fun aWholeRead_sendsNoRange() = GlobalScope.promise {
        val seen = mutableListOf<Seen>()
        val bytes = FetchTileTransport(TILES_HOST_URL, fake(200, body, seen)).fetch("catalog.json", null, null)
        assertContentEquals(body, bytes)
        assertNull(seen.single().init.headers)
    }

    @Test
    fun aServerThatIgnoresRange_isSlicedHere() = GlobalScope.promise {
        val bytes = FetchTileTransport(TILES_HOST_URL, fake(200, body, mutableListOf())).fetch("r.pmtiles", 90, 20)
        assertContentEquals(body.copyOfRange(90, 100), bytes, "clipped at the object's end")
    }

    @Test
    fun anyOtherStatus_throws_neverBytes() = GlobalScope.promise {
        for (status in listOf(403, 404, 416, 500)) {
            assertFailsWith<IllegalStateException>("HTTP $status") {
                FetchTileTransport(TILES_HOST_URL, fake(status, body, mutableListOf())).fetch("r.pmtiles", 0, 10)
            }
        }
    }

    @Test
    fun aPathThatLeavesTheTileHost_isRefusedBeforeAnyFetch() = GlobalScope.promise {
        val seen = mutableListOf<Seen>()
        for (bad in listOf("/catalog.json", "https://evil.example/x", "../x", "regions/../../x")) {
            assertFailsWith<IllegalArgumentException>(bad) {
                FetchTileTransport(TILES_HOST_URL, fake(200, body, seen)).fetch(bad, null, null)
            }
        }
        assertEquals(0, seen.size)
    }
}
