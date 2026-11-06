package org.tenshu.inference.proxy.catalog

import java.net.HttpURLConnection
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * What the model CDN really answers to a `Range` request (`ios-on-device-inference.md` §14.12).
 *
 * ⚠⚠ **Opt-in, never in the gate**: it talks to production. Run it with
 * `TENSHU_MODEL_CDN_PROBE=1 ./gradlew :inference-service-proxy:jvmTest --tests '*ModelCdnRangeProbeTest*'`.
 * Without the variable it does nothing and passes.
 *
 * ⚠⚠ **Headers only.** The connection is dropped as soon as the status line and the headers are
 * in: the text archive has been seen to answer a 1 000-byte range with `200` and all 555 MB, and
 * a client that reads that body has downloaded the archive.
 *
 * What it pins is what both downloaders rely on, not which of the two answers a request got:
 * - a ranged GET is answered `206` with exactly the range asked for, **or** `200` with the whole
 *   artifact's length. Never anything else. The in-process downloader restarts from zero on the
 *   second; the background one never sends a range of its own;
 * - every artifact has a validator (`ETag`), without which the system cannot resume a transfer
 *   at all, and announces the catalog's size.
 */
class ModelCdnRangeProbeTest {

    private val base = "https://models.service.tenshu.app/siglip-base-patch16-256-multilingual/1"

    /** The published `coreml` entry's artifacts and their sizes, as verified on 2026-10-11. */
    private val artifacts: Map<String, Long> = mapOf(
        "$base/coreml/vision_fp16.mlpackage.tar" to 184_842_240L,
        "$base/coreml/text_fp16.mlpackage.tar" to 555_540_480L,
        "$base/tokenizer.bin" to 3_066_150L,
    )

    private class Answer(val status: Int, val headers: Map<String, String>)

    private fun ask(url: String, range: String?): Answer {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 20_000
            connection.readTimeout = 20_000
            range?.let { connection.setRequestProperty("Range", it) }
            val status: Int = connection.responseCode
            val headers: Map<String, String> = connection.headerFields
                .filterKeys { it != null }
                .map { (name, values) -> name.lowercase() to values.joinToString(",") }
                .toMap()
            return Answer(status, headers)
        } finally {
            // ⚠ Before a byte of the body is read.
            connection.disconnect()
        }
    }

    @Test
    fun aRangedRequestIsAnsweredWithThatRangeOrWithTheWholeArtifact() {
        if (System.getenv("TENSHU_MODEL_CDN_PROBE") != "1") return
        val seen = StringBuilder()
        artifacts.forEach { (url, size) ->
            val answer: Answer = ask(url, "bytes=1000-1999")
            seen.append("\n  ${url.substringAfterLast('/')}: ${answer.status} ")
                .append(answer.headers.filterKeys { it in setOf("content-length", "content-range", "etag", "cf-cache-status") })
            when (answer.status) {
                206 -> {
                    assertEquals("bytes 1000-1999/$size", answer.headers["content-range"], url)
                    assertEquals("1000", answer.headers["content-length"], url)
                }
                // ⚠⚠ The whole body: a client that appends this to what it holds has a corrupt file.
                200 -> assertEquals(size.toString(), answer.headers["content-length"], url)
                else -> error("$url answered a range with ${answer.status}")
            }
            assertNotNull(answer.headers["etag"], "$url has no validator: a transfer of it cannot be resumed")
        }
        // Read from the report when the case is run by hand.
        assertTrue(seen.isNotEmpty())
        println("ModelCdnRangeProbeTest ->$seen")
    }
}
