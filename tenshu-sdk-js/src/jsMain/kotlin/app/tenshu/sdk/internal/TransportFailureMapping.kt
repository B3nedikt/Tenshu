package app.tenshu.sdk.internal

import io.ktor.client.plugins.api.Send
import io.ktor.client.plugins.api.createClientPlugin
import kotlinx.io.IOException

/**
 * A refused browser `fetch` (offline, DNS, CORS, connection refused), reported as the
 * [IOException] every relay client already catches.
 *
 * ⚠⚠ Why. ktor-client-js rejects a failed `fetch` with `kotlin.Error("Fail to fetch")`, and
 * `kotlin.Error` is a [Throwable] that is NOT an [Exception]. Every `Rest*` client in
 * `:sync-service-proxy` (and `RestShareRelayClient`, `RelayUsageClient`) is common code that
 * catches `Exception`, which is right on the JVM and wrong here: the failure went past them. The
 * worst case was `RestContentBlobStore.putChunk`. The content emit journals an upload before it
 * sends a byte, so a failed chunk `PUT` is meant to come back as a failed chunk and leave the
 * upload to the drain. Instead the throw failed an op that had already saved its row, its local
 * blob and its journal entry: an offline browser could not upload a file (`docs/tech-debt.md`,
 * resolved entry "A JS fetch failure escaped the content emit as an op failure").
 *
 * Mapped here, at the one place the browser's failure enters, so each caller's existing
 * `catch (e: Exception)` is right on every platform and no common code widens to `Throwable`
 * (which on the JVM would also swallow `OutOfMemoryError` / `StackOverflowError`).
 *
 * ⚠ Wraps the send only (request out, response head in). A failure while a streamed body is read
 * afterwards does not pass through here.
 * ⚠ Any [Exception] passes through untouched, `CancellationException` included, so cancellation
 * still cancels and Ktor's own typed failures (timeouts, `ResponseException`) keep their type.
 */
internal val TransportFailureMapping = createClientPlugin("TransportFailureMapping") {
    on(Send) { request ->
        try {
            proceed(request)
        } catch (e: Exception) {
            throw e
        } catch (t: Throwable) {
            throw FetchFailedException(t)
        }
    }
}

/** A browser `fetch` that never produced a response. [message] keeps the engine's wording. */
internal class FetchFailedException(cause: Throwable) : IOException(cause.message ?: "Fail to fetch", cause)
