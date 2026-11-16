package app.tenshu.windows

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Real DPAPI calls on a Windows host; off Windows, only that the binding admits it is unavailable.
 *
 * ⚠ On Windows the availability assertion is **hard**, not a skip: a binding that silently failed
 * to resolve would make every other case here return early, and the master-key store would quietly
 * fall back to in-memory secrets — the re-pair-on-every-restart bug this binding exists to end.
 */
class WindowsDpapiTest {

    /** Mirrors [WindowsDpapi]'s own `isWindows`; spelled the same way on purpose. */
    private val windows = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)

    private val entropy = "app.tenshu.test.dpapi".encodeToByteArray()

    @Test
    fun availableExactlyOnWindows() {
        assertEquals(windows, WindowsDpapi.isAvailable())
        if (!windows) {
            assertNull(WindowsDpapi.protect(byteArrayOf(1, 2, 3), entropy), "unavailable must answer null")
        }
    }

    @Test
    fun aProtectedKeyRoundTrips_andTheBlobIsNotThePlaintext() {
        if (!windows) return
        val key = ByteArray(32) { (it * 7 + 3).toByte() }

        val blob = assertNotNull(WindowsDpapi.protect(key, entropy))
        assertFalse(blob.asList().windowed(key.size).any { it == key.asList() }, "the key must not appear in the blob")
        assertContentEquals(key, WindowsDpapi.unprotect(blob, entropy))
    }

    @Test
    fun theWrongEntropyOrATamperedBlobOpensNothing() {
        if (!windows) return
        val blob = assertNotNull(WindowsDpapi.protect(ByteArray(32) { it.toByte() }, entropy))

        assertNull(WindowsDpapi.unprotect(blob, "someone-else".encodeToByteArray()))
        val tampered = blob.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        assertNull(WindowsDpapi.unprotect(tampered, entropy))
        assertNull(WindowsDpapi.unprotect(byteArrayOf(0, 1, 2), entropy), "garbage is a failure, not a crash")
    }

    @Test
    fun emptyInputIsAValidBlob() {
        if (!windows) return
        val blob = assertNotNull(WindowsDpapi.protect(ByteArray(0), entropy))
        assertTrue(blob.isNotEmpty())
        assertContentEquals(ByteArray(0), WindowsDpapi.unprotect(blob, entropy))
    }
}
