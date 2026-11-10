package app.tenshu.windows

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Cloud Files bindings resolve on Windows and answer read-only questions correctly. Nothing here
 * registers a sync root — that changes Explorer state for the user, so it lives in the X0 spike
 * (`./gradlew :windows-helpers:cfapiSpike`), which cleans up after itself, not in a unit test.
 *
 * ⚠ Availability is a **hard** assertion on Windows (the `WindowsDpapiTest` rule): a binding that
 * silently failed to resolve would turn every other case into an early return.
 */
class WindowsCloudFilesTest {

    private val windows = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)

    @Test
    fun availableExactlyOnWindows() {
        assertEquals(windows, WindowsCloudFiles.isAvailable())
        assertEquals(windows, WindowsSyncRootManager.isAvailable())
        if (!windows) {
            assertNull(WindowsCloudFiles.syncRootFileId(Files.createTempDirectory("cf")))
            assertNull(WindowsSyncRootManager.currentSyncRootIds())
        }
    }

    @Test
    fun anOrdinaryFolderIsNotUnderASyncRoot() {
        if (!windows) return
        val dir = Files.createTempDirectory("tenshu-cf-test")
        try {
            val result = assertNotNull(WindowsCloudFiles.syncRootFileId(dir))
            val failure = result.exceptionOrNull() as? CloudFilesException
            assertEquals(
                CloudFilesHResult.NOT_UNDER_SYNC_ROOT,
                failure?.hresult,
                "expected NOT_UNDER_SYNC_ROOT, got ${result.getOrNull() ?: failure?.message}",
            )
            val attributes = assertNotNull(WindowsCloudFiles.fileAttributes(dir))
            assertTrue(attributes and WindowsCloudFiles.FILE_ATTRIBUTE_DIRECTORY != 0)
            assertEquals(0, attributes and WindowsCloudFiles.FILE_ATTRIBUTE_RECALL_ON_DATA_ACCESS)
        } finally {
            Files.deleteIfExists(dir)
        }
    }

    @Test
    fun theSyncRootManagerAnswersOverRawComCalls() {
        if (!windows) return
        assertEquals(true, WindowsSyncRootManager.isSupported())
        // Read-only: lists whatever is registered (OneDrive, …), possibly nothing on a CI runner.
        val ids = assertNotNull(WindowsSyncRootManager.currentSyncRootIds()).getOrThrow()
        ids.forEach { assertTrue(it.isNotBlank()) }
    }
}
