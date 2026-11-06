@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.tenshu.sdk.ios

import app.tenshu.drive.data.StreamingContentHandle
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.runBlocking
import platform.Foundation.NSData
import platform.posix.memcpy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * S7 coverage (ios-drive-parity.md): the iOS presentation-edge wrapper the Swift
 * `AVAssetResourceLoaderDelegate` drives. The proxy seek engine + range assembly + chunk decrypt
 * are already covered cross-platform (`ContentStreamOpsTest` / `StreamingContentSessionTest` /
 * `FileChunkDecryptorTest`); here we pin the NEW iOS binding — that [IosContentStreamSession]
 * delegates to the [StreamingContentHandle] and converts each range `ByteArray`→`NSData`
 * byte-for-byte (the bytes the resource-loader hands to `dataRequest.respond(with:)`).
 */
class IosContentStreamSessionTest {

    private fun handle(
        bytes: ByteArray,
        onClose: () -> Unit = {},
    ) = StreamingContentHandle(
        handle = "test-stream",
        totalSize = bytes.size.toLong(),
        // A faithful stub of the proxy range read: clamp to EOF, empty at/after end.
        readRangeFn = { _, offset, length ->
            if (offset >= bytes.size) ByteArray(0)
            else bytes.copyOfRange(offset.toInt(), minOf(bytes.size, offset.toInt() + length))
        },
        closeFn = { onClose() },
    )

    @Test
    fun readRangeReturnsTheRequestedBytesAsNsData() = runBlocking {
        val content = ByteArray(1000) { (it % 251).toByte() }
        val session = IosContentStreamSession(handle(content))

        assertEquals(1000L, session.totalSize)

        // A mid-file range (a seek) — the delegate's dataRequest fulfilment unit.
        val data = session.readRange(offset = 300, length = 256)
        assertTrue(data != null)
        assertEquals(256, data!!.length.toInt())
        assertTrue(
            data.toByteArray().contentEquals(content.copyOfRange(300, 556)),
            "the NSData handed to the player is byte-identical to the requested range",
        )
    }

    @Test
    fun readRangeClampsAtEofAndReturnsEmptyPastIt() = runBlocking {
        val content = ByteArray(500) { it.toByte() }
        val session = IosContentStreamSession(handle(content))

        val tail = session.readRange(offset = 400, length = 256)
        assertEquals(100, tail!!.length.toInt(), "a range spanning EOF clamps to the remaining bytes")

        val pastEof = session.readRange(offset = 500, length = 256)
        assertEquals(0, pastEof!!.length.toInt(), "reading at/after EOF yields empty NSData (the loader finishes)")
    }

    @Test
    fun closeDelegatesToTheHandle() = runBlocking {
        var closed = false
        val session = IosContentStreamSession(handle(ByteArray(10)) { closed = true })
        session.close()
        assertTrue(closed, "close releases the proxy-side session")
    }

    @Test
    fun nullRangeFromAGateFailurePropagatesAsNull() = runBlocking {
        // A closed stream / failed chunk gate returns null from the handle → the delegate errors.
        val failing = StreamingContentHandle(
            handle = "x", totalSize = 100,
            readRangeFn = { _, _, _ -> null },
            closeFn = {},
        )
        assertNull(IosContentStreamSession(failing).readRange(0, 256))
    }

    private fun NSData.toByteArray(): ByteArray {
        val size = length.toInt()
        if (size == 0) return ByteArray(0)
        val out = ByteArray(size)
        out.usePinned { pinned -> memcpy(pinned.addressOf(0), bytes, length) }
        return out
    }
}
