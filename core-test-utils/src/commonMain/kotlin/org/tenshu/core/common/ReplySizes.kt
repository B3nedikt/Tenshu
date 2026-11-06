package org.tenshu.core.common

import org.tenshu.core.TResult
import org.tenshu.core.common.parser.BinarySerializer
import org.tenshu.core.schema.Type

/**
 * The size of every reply a [TestProxy] handed back, serialised the way the IPC transport would —
 * so a test can assert that a read is **bounded in bytes**, the property that decides whether it
 * answers at all over Binder (~1 MB a transaction; `photos-app.md` §39/§40).
 *
 * ```
 * val replies = ReplySizes.measure(proxy)
 * … the read …
 * assertTrue(replies.largest <= budget)
 * ```
 *
 * ⚠ Covers one-shot replies only. A `.flow()` subscription's elements are pushed past the executor;
 * assert `streamRegistry.activeStreamCount` for those.
 */
class ReplySizes private constructor() {
    private val recorded = mutableListOf<Int>()

    /** Every reply since the last [clear], in bytes, in order. */
    val all: List<Int> get() = recorded.toList()
    val largest: Int get() = all.maxOrNull() ?: 0
    val count: Int get() = all.size

    fun clear() = recorded.clear()

    companion object {
        /** Start measuring [proxy]'s replies (replaces any earlier [TestProxy.replyObserver]). */
        fun measure(proxy: TestProxy): ReplySizes {
            val sizes = ReplySizes()
            val serializer = BinarySerializer(proxy.schemaRegistry)
            val type = Type.create(baseSchema = TResult.Schema)
            proxy.replyObserver = { result -> sizes.recorded += serializer.serialize(result, expectedType = type).size }
            return sizes
        }
    }
}
