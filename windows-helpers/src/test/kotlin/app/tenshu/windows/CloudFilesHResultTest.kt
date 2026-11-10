package app.tenshu.windows

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CloudFilesHResultTest {

    @Test
    fun theCodesThatDriveDecisionsAreHResultFromWin32OfTheirErrorNumbers() {
        // HRESULT_FROM_WIN32(n) = 0x80070000 | n; the numbers are winerror.h's ERROR_CLOUD_FILE_*.
        fun fromWin32(n: Int) = 0x80070000.toInt() or n
        assertEquals(fromWin32(362), CloudFilesHResult.PROVIDER_NOT_RUNNING)
        assertEquals(fromWin32(376), CloudFilesHResult.NOT_A_CLOUD_FILE)
        assertEquals(fromWin32(378), CloudFilesHResult.ALREADY_CONNECTED)
        assertEquals(fromWin32(390), CloudFilesHResult.NOT_UNDER_SYNC_ROOT)
        assertEquals(fromWin32(404), CloudFilesHResult.PROVIDER_TERMINATED)
        // 385 sits inside the cloud-file block but is the virtualization layer's code.
        assertTrue(CloudFilesHResult.describe(fromWin32(385)).contains("FILE_SYSTEM_VIRTUALIZATION"))
    }

    @Test
    fun describeNamesKnownCodesAndFallsBackToHex() {
        assertEquals(
            "0x8007016A ERROR_CLOUD_FILE_PROVIDER_NOT_RUNNING: the cloud file provider is not running",
            CloudFilesHResult.describe(CloudFilesHResult.PROVIDER_NOT_RUNNING),
        )
        assertEquals("0x00000000 S_OK", CloudFilesHResult.describe(0))
        assertEquals("0x80071234 Win32 error 4660", CloudFilesHResult.describe(0x80071234.toInt()))
        assertEquals("0x8BADF00D", CloudFilesHResult.describe(0x8BADF00D.toInt()))
    }

    @Test
    fun successIsTheSignBit() {
        assertTrue(CloudFilesHResult.succeeded(CloudFilesHResult.S_OK))
        assertTrue(CloudFilesHResult.succeeded(CloudFilesHResult.S_FALSE))
        assertFalse(CloudFilesHResult.succeeded(CloudFilesHResult.E_ACCESSDENIED))
    }
}
