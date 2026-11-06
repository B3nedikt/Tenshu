package app.tenshu.drive.data

import kotlinx.coroutines.runBlocking
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * P1 of drive-folder-upload.md §11 — a cloud (online-only) file whose on-demand
 * fault-in stalls must fail THAT file, not freeze the whole folder upload.
 */
class DesktopCloudFileReadTest {

    @Test
    fun readWithBudget_returnsBytes_forAPromptReader() = runBlocking {
        val bytes = byteArrayOf(1, 2, 3, 4)
        val out = readWithBudget("fast.bin", timeoutMs = 30_000) { bytes }
        assertContentEquals(bytes, out)
    }

    @Test
    fun readWithBudget_throwsCloudFileReadTimeout_whenTheReadOutlastsTheBudget() = runBlocking {
        // Simulates a dataless OneDrive placeholder whose fault-in never returns:
        // the read parks far longer than the budget, so we abandon it and fail
        // THIS file rather than freezing the whole upload.
        val ex = assertFailsWith<CloudFileReadTimeout> {
            readWithBudget("stuck-onedrive.pdf", timeoutMs = 100) {
                Thread.sleep(10_000)
                ByteArray(0)
            }
        }
        assertTrue(ex.message!!.contains("stuck-onedrive.pdf"))
    }

    @Test
    fun readWithBudget_propagatesTheReadersOwnFailure_unwrapped() = runBlocking {
        val caught: Throwable? = try {
            readWithBudget("gone.txt", timeoutMs = 30_000) { throw IOException("boom") }
            null
        } catch (e: Throwable) {
            e
        }
        assertTrue(
            caught is IOException,
            "expected the reader's IOException unwrapped, got ${caught?.let { it::class.qualifiedName }}",
        )
        assertEquals("boom", caught.message)
    }

    @Test
    fun cloudReadTimeoutMs_floorsAtThirtySeconds_forSmallOrUnknownSizes() {
        assertEquals(30_000L, cloudReadTimeoutMs(0))
        assertEquals(30_000L, cloudReadTimeoutMs(-1))
        assertEquals(30_000L, cloudReadTimeoutMs(1024)) // 1 KiB → floor
    }

    @Test
    fun cloudReadTimeoutMs_scalesAboveTheFloor_forLargeFiles() {
        // 10 MiB at the conservative 128 KiB/s floor rate = 80 s, above the 30 s floor.
        val tenMiB = 10L * 1024 * 1024
        assertTrue(cloudReadTimeoutMs(tenMiB) > 30_000L)
        // A larger file scales proportionally larger still.
        assertTrue(cloudReadTimeoutMs(500L * 1024 * 1024) > cloudReadTimeoutMs(tenMiB))
    }
}
