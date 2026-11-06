package app.tenshu.ui.components

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QrCameraScannerTest {

    @Test
    fun parsesFrameThenDecodedResult() = runBlocking {
        val bytes = framed(
            message(TYPE_FRAME, byteArrayOf(1, 2, 3)),
            message(TYPE_RESULT, "decoded\tPAIR-XYZ".encodeToByteArray()),
        )
        val events = parseFramedStream(ByteArrayInputStream(bytes)).toList()

        assertEquals(2, events.size)
        val frame = events[0]
        assertTrue(frame is ScanEvent.Frame)
        assertContentEquals(byteArrayOf(1, 2, 3), frame.jpeg)
        assertEquals(
            ScanEvent.Result(ScanOutcome.Decoded("PAIR-XYZ")),
            events[1],
        )
    }

    @Test
    fun eofMidStreamYieldsCancelled() = runBlocking {
        // Empty stream = helper exited before any result (e.g. killed).
        val events = parseFramedStream(ByteArrayInputStream(ByteArray(0))).toList()
        assertEquals(listOf(ScanEvent.Result(ScanOutcome.Cancelled)), events)
    }

    @Test
    fun parseResultMapsEveryStatus() {
        assertEquals(ScanOutcome.Decoded("abc"), parseResult("decoded\tabc"))
        assertEquals(ScanOutcome.PermissionDenied, parseResult("permission_denied"))
        assertEquals(ScanOutcome.NoCamera, parseResult("no_camera"))
        assertEquals(ScanOutcome.Failed("boom"), parseResult("failed\tboom"))
        assertTrue(parseResult("weird") is ScanOutcome.Failed)
    }

    @Test
    fun scannerWithoutHelperReportsNoCamera() = runBlocking {
        // spawn returns null (helper unresolved) → terminal NoCamera, so the
        // pairing UI keeps its manual fallback.
        val scanner = MacQrCameraScanner(spawn = { null })
        assertEquals(
            listOf(ScanEvent.Result(ScanOutcome.NoCamera)),
            scanner.scan().toList(),
        )
    }

    @Test
    fun probeWithoutHelperReportsNoCamera() = runBlocking {
        val scanner = MacQrCameraScanner(spawn = { null })
        assertEquals(CameraPermission.NoCamera, scanner.probePermission())
    }

    @Test
    fun readFirstResultSkipsFramesAndReturnsResultText() {
        val bytes = framed(
            message(TYPE_FRAME, byteArrayOf(9, 9)),
            message(TYPE_RESULT, "not_determined".encodeToByteArray()),
        )
        assertEquals("not_determined", readFirstResult(ByteArrayInputStream(bytes)))
        assertNull(readFirstResult(ByteArrayInputStream(ByteArray(0))))
    }

    @Test
    fun parsePermissionResultMapsEveryStatus() {
        assertEquals(CameraPermission.Authorized, parsePermissionResult("authorized"))
        assertEquals(CameraPermission.NotDetermined, parsePermissionResult("not_determined"))
        assertEquals(CameraPermission.Denied, parsePermissionResult("denied"))
        assertEquals(CameraPermission.NoCamera, parsePermissionResult("no_camera"))
        assertEquals(CameraPermission.NoCamera, parsePermissionResult(null))
    }

    @Test
    fun forCurrentOsResolvesByHostOs() {
        // Desktop is one Kotlin target; the Mac-vs-rest split is a runtime
        // decision, so assert against the host the test actually runs on.
        val scanner = QrCameraScanner.forCurrentOs()
        if (isMacOs()) {
            assertTrue(scanner is MacQrCameraScanner)
        } else {
            assertNull(scanner)
        }
    }

    private fun message(type: Int, payload: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream()
        DataOutputStream(bos).apply {
            writeByte(type)
            writeInt(payload.size)
            write(payload)
            flush()
        }
        return bos.toByteArray()
    }

    private fun framed(vararg parts: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream()
        parts.forEach { bos.write(it) }
        return bos.toByteArray()
    }
}
