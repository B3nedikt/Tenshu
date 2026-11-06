package org.tenshu.core.common.transport

import org.tenshu.core.common.util.toByteArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The framing layer's input validation (2026-08-21 IPC audit item #3). Both numbers a frame
 * carries about itself — the 4-byte payload length and, on a tagged frame, the 4-byte task-id
 * length — used to be trusted and handed straight to an allocator.
 */
class StreamFramerTest {

    @Test
    fun aFrameLengthIsValidUpToTheCapAndNotBeyond() {
        assertTrue(StreamFramer.isValidFrameLength(0), "an empty payload is legal")
        assertTrue(StreamFramer.isValidFrameLength(1))
        assertTrue(StreamFramer.isValidFrameLength(StreamFramer.MAX_FRAME_BYTES))
        assertFalse(StreamFramer.isValidFrameLength(StreamFramer.MAX_FRAME_BYTES + 1))
    }

    /**
     * ⚠ The case that motivated the cap: `0x7FFFFFFF` reaching `ByteBuffer.allocate` is a 2 GiB
     * request from four bytes anyone on the socket can send.
     */
    @Test
    fun theTwoGigabyteHeaderIsRejected() {
        assertFalse(StreamFramer.isValidFrameLength(Int.MAX_VALUE))
    }

    /**
     * `getInt()` is signed, so a `0xFFFFFFFF` header arrives as -1. It was survivable before
     * (`allocate` throws) but only by accident, and the failure carried no framing context.
     */
    @Test
    fun aNegativeHeaderIsRejected() {
        assertFalse(StreamFramer.isValidFrameLength(-1))
        assertFalse(StreamFramer.isValidFrameLength(Int.MIN_VALUE))
    }

    @Test
    fun aTaggedFrameRoundTrips() {
        val body = byteArrayOf(1, 2, 3, 4, 5)
        val (taskId, unpacked) = StreamFramer.unpackTagged(StreamFramer.packTagged("task-42", body))

        assertEquals("task-42", taskId)
        assertContentEquals(body, unpacked)
    }

    @Test
    fun anEmptyBodyRoundTrips() {
        val (taskId, unpacked) = StreamFramer.unpackTagged(
            StreamFramer.packTagged("task-42", ByteArray(0))
        )

        assertEquals("task-42", taskId)
        assertContentEquals(ByteArray(0), unpacked)
    }

    @Test
    fun aTaggedFrameDeclaringMoreIdThanItCarriesIsAFramingError() {
        // 64 declared, 3 present.
        val malformed = 64.toByteArray() + byteArrayOf(1, 2, 3)

        assertFailsWith<IllegalArgumentException> { StreamFramer.unpackTagged(malformed) }
    }

    @Test
    fun aTaggedFrameDeclaringANegativeIdLengthIsAFramingError() {
        val malformed = (-1).toByteArray() + byteArrayOf(1, 2, 3)

        assertFailsWith<IllegalArgumentException> { StreamFramer.unpackTagged(malformed) }
    }

    @Test
    fun aTaggedFrameTooShortToCarryItsOwnHeaderIsAFramingError() {
        assertFailsWith<IllegalArgumentException> {
            StreamFramer.unpackTagged(byteArrayOf(1, 2))
        }
    }
}
