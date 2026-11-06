package org.tenshu.core.common.proxy

import org.tenshu.core.Data
import org.tenshu.core.InternalError
import org.tenshu.core.TResult
import org.tenshu.core.TenshuException
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.impl.InternalErrorDto
import org.tenshu.core.id
import org.tenshu.core.toTString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * ⚠ A reply whose byte payload could not be handed to the client must not go out carrying a token
 * for it (`core-client-resubscription.md` §4.8): on Android that token resolved to nothing and the
 * read failed later as *ParcelFileDescriptor … not found in cache*. The caller gets the typed error.
 */
class SerializeReplyTest {

    private val proxy = TestProxy()

    private fun parse(bytes: ByteArray): TResult<Data> = proxy.parser.parse(bytes)

    @Test
    fun anUndeliverablePayloadBecomesTheTypedFailure() {
        val result = TResult.Success.create<Data>(proxy.tContext, "payload".toTString(proxy.tContext))

        val bytes = proxy.serializer.serializeReply(result, proxy.tContext) {
            throw TenshuException(InternalErrorDto(internalDetail = "IPC: undeliverable"))
        }

        val reply = assertIs<TResult.Failure<*>>(parse(bytes))
        assertEquals(InternalError.ID, reply.error.id)
    }

    @Test
    fun aDeliverableReplyIsUnchanged() {
        val result = TResult.Success.create<Data>(proxy.tContext, "payload".toTString(proxy.tContext))

        val bytes = proxy.serializer.serializeReply(result, proxy.tContext) { null }

        assertIs<TResult.Success<*>>(parse(bytes))
    }
}
