package org.tenshu.mapregion.proxy

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * [HttpRegionRangeFetcher] over a MockEngine that honours the `Range` header — asserts the range is
 * requested against `$baseUrl/$path` and the 206 window is returned verbatim.
 */
class HttpRegionRangeFetcherTest {

    private val bundle = ByteArray(1000) { it.toByte() }

    @Test
    fun issuesARangeGetAndReturnsThePartialWindow() = runTest {
        var seenRange: String? = null
        var seenPath: String? = null
        val engine = MockEngine { request ->
            seenPath = request.url.encodedPath
            seenRange = request.headers[HttpHeaders.Range]
            // Parse "bytes=start-end" and serve the slice as a 206.
            val (start, end) = seenRange!!.removePrefix("bytes=").split("-").map { it.toInt() }
            val slice = bundle.copyOfRange(start, end + 1)
            respond(
                content = slice,
                status = HttpStatusCode.PartialContent,
                headers = headersOf(HttpHeaders.ContentRange, "bytes $start-$end/${bundle.size}"),
            )
        }
        val fetcher = HttpRegionRangeFetcher(HttpClient(engine), baseUrl = "https://tiles.test")

        val bytes = fetcher.fetchRange("regions/2026-07/de.pmtiles", offset = 200, length = 64)

        assertEquals("/regions/2026-07/de.pmtiles", seenPath)
        assertEquals("bytes=200-263", seenRange)
        assertContentEquals(bundle.copyOfRange(200, 264), bytes)
    }
}
