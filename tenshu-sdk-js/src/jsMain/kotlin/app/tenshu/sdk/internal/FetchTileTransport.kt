package app.tenshu.sdk.internal

import kotlinx.coroutines.await
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Int8Array
import kotlin.js.Promise

/**
 * The web's tile transport (`maps-region-delivery.md` §Web scope W.1, decided 2026-10-28): the apex
 * runtime reads the tile host directly with `fetch`, and the R2 bucket's CORS rule
 * (`deploy/cloudflare-tiles/main.tf`) admits only the apex origins. So the CDN, and only the CDN, sees
 * the browser's IP and viewport, as it sees Control's natively (T-MR-03).
 *
 * Every request goes out with no cookies and no referrer: the tile host learns which tiles, never
 * which page. A ranged read sends one `Range: bytes=a-b` (CORS-safelisted, so no preflight). A
 * `206` is the range; a `200` to a ranged read means the server ignored `Range` and is sliced here; any
 * other status throws, so a failure never reads as bytes.
 */
internal class FetchTileTransport(
    private val baseUrl: String,
    private val fetchFn: (String, dynamic) -> Promise<JsFetchResponse> = { url, init -> jsFetch(url, init) },
) : WebTileTransport {

    override suspend fun fetch(path: String, offset: Long?, length: Int?): ByteArray {
        require(!path.startsWith("/") && "://" !in path && ".." !in path) { "not a tile-host path: $path" }
        val init: dynamic = js("({})")
        init.method = "GET"
        init.mode = "cors"
        init.credentials = "omit"
        init.referrerPolicy = "no-referrer"
        val ranged = offset != null && length != null
        if (ranged) {
            val headers: dynamic = js("({})")
            headers["Range"] = "bytes=$offset-${offset!! + length!! - 1}"
            init.headers = headers
        }
        val response = fetchFn("${baseUrl.trimEnd('/')}/$path", init).await()
        val status = response.status
        if (status != 200 && status != 206) {
            throw IllegalStateException("tile host answered HTTP $status for $path")
        }
        val bytes = Int8Array(response.arrayBuffer().await()).unsafeCast<ByteArray>()
        if (!ranged || status == 206) return bytes
        // A 200 to a ranged read is the whole object: cut the window out of it.
        val start = offset!!.coerceAtMost(bytes.size.toLong()).toInt()
        val end = (offset + length!!).coerceAtMost(bytes.size.toLong()).toInt()
        return bytes.copyOfRange(start, end)
    }
}

internal external interface JsFetchResponse {
    val status: Int
    fun arrayBuffer(): Promise<ArrayBuffer>
}

@JsName("fetch")
internal external fun jsFetch(url: String, init: dynamic): Promise<JsFetchResponse>
