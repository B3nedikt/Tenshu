package app.tenshu.drive.data

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * D2b S3 — [StreamingContentHandle] forwards reads/close to its injected op closures with the
 * bound handle. (The op round-trip itself is covered proxy-side by `ContentStreamOpsTest`.)
 */
class StreamingContentHandleTest {

    @Test
    fun readRange_delegates_withBoundHandle_andReturnsBytes() = runTest {
        var captured: Triple<String, Long, Int>? = null
        val handle = StreamingContentHandle(
            handle = "cs-1",
            totalSize = 1_000L,
            readRangeFn = { h, offset, length ->
                captured = Triple(h, offset, length)
                byteArrayOf(1, 2, 3)
            },
            closeFn = {},
        )

        val bytes = handle.readRange(offset = 64L, length = 16)

        assertContentEquals(byteArrayOf(1, 2, 3), bytes)
        assertEquals(Triple("cs-1", 64L, 16), captured, "forwards bound handle + offset + length")
        assertEquals(1_000L, handle.totalSize)
    }

    @Test
    fun close_delegates_withBoundHandle() = runTest {
        val closed = mutableListOf<String>()
        val handle = StreamingContentHandle(
            handle = "cs-2",
            totalSize = 0L,
            readRangeFn = { _, _, _ -> null },
            closeFn = { closed += it },
        )

        handle.close()

        assertEquals(listOf("cs-2"), closed)
    }
}
