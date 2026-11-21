package org.tenshu.core.common.parser

import org.tenshu.core.Data
import org.tenshu.core.TString
import org.tenshu.core.common.createSyntheticTestRegistry
import org.tenshu.core.common.createTestContext
import org.tenshu.core.core.dataFactory
import org.tenshu.core.toTString
import java.util.concurrent.CyclicBarrier
import kotlin.test.Test
import kotlin.test.assertContentEquals

/**
 * ⚠⚠ **One serializer, concurrent calls, each with its own value interceptor.** That is exactly
 * how `ProxyGateway` serializes replies: one `BinarySerializer`, and per reply the connection's own
 * `tokenize`. The per-call tokenizer must never leak into another call's bytes. It did: the
 * tokenizer lived in a field of the shared `LinearSerializer`, so a reply serialized while another
 * started was finished with the other's interceptor (`core-semantic-permissions.md` SP-D6's audit).
 */
class SharedSerializerConcurrencyTest {

    private val context = createTestContext(createSyntheticTestRegistry())
    private val list = context.dataFactory().createList<Data>((1..2_000).map { "v$it".toTString(context) })

    private fun marking(mark: String): (org.tenshu.core.TValue) -> TokenString? = { value ->
        if (value is TString) buildTokenString { append(Token.StringToken(mark)) } else null
    }

    @Test
    fun aCallsInterceptorNeverReachesAnotherCallsBytes() {
        val expectedA = BinarySerializer().serialize(list, interceptValueTokenization = marking("A"))
        val expectedB = BinarySerializer().serialize(list, interceptValueTokenization = marking("B"))
        val shared = BinarySerializer()

        repeat(ROUNDS) {
            val barrier = CyclicBarrier(2)
            var gotA = ByteArray(0)
            var gotB = ByteArray(0)
            val a = Thread { barrier.await(); gotA = shared.serialize(list, interceptValueTokenization = marking("A")) }
            val b = Thread { barrier.await(); gotB = shared.serialize(list, interceptValueTokenization = marking("B")) }
            a.start(); b.start(); a.join(); b.join()
            assertContentEquals(expectedA, gotA, "round $it: A's reply carried B's tokens")
            assertContentEquals(expectedB, gotB, "round $it: B's reply carried A's tokens")
        }
    }

    private companion object {
        const val ROUNDS = 200
    }
}
