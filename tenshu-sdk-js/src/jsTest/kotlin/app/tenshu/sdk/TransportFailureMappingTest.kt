@file:OptIn(DelicateCoroutinesApi::class)

package app.tenshu.sdk

import app.tenshu.sdk.internal.JsRuntime
import app.tenshu.sdk.internal.TransportFailureMapping
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.promise
import org.tenshu.core.TResult
import org.tenshu.sync.proxy.RestContentBlobStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * The HTTP seam behind `ContentEmitFetchFailureTest`. The engine fails the way ktor-client-js does
 * for a refused `fetch`: `kotlin.Error("Fail to fetch")`, a `Throwable` that is not an `Exception`.
 * With [TransportFailureMapping] installed, `RestContentBlobStore`'s common `catch (e: Exception)`
 * sees it, on every path the content plane takes.
 */
class TransportFailureMappingTest {

    private fun storeOver(engine: MockEngine) = RestContentBlobStore(
        baseUrl = "http://relay.invalid",
        httpClient = HttpClient(engine) { install(TransportFailureMapping) },
        context = JsRuntime.tContext,
        authTokenProvider = { null },
    )

    private val refusedFetch = MockEngine { throw Error("Fail to fetch") }

    @Test
    fun aRefusedFetch_isAFailedChunkPut_notAThrow() = GlobalScope.promise {
        val result = storeOver(refusedFetch).putChunk("blob-x", ByteArray(16))
        assertIs<TResult.Failure<*>>(result, "the content emit reads a failed chunk and leaves the upload to the drain")
    }

    @Test
    fun aRefusedFetch_isAFailedChunkGet_andAnUnknownProbe() = GlobalScope.promise {
        val store = storeOver(refusedFetch)
        assertIs<TResult.Failure<*>>(store.getChunk("blob-x"))
        assertNull(store.hasChunk("blob-x"), "unreachable is unknown, never absent")
    }

    @Test
    fun cancellation_isNeverMapped() = GlobalScope.promise {
        val thrown = assertFailsWith<CancellationException> {
            storeOver(MockEngine { throw CancellationException("stop") }).hasChunk("blob-x")
        }
        assertEquals("stop", thrown.message)
    }
}
