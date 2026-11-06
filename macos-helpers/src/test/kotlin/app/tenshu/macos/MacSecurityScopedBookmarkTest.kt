package app.tenshu.macos

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the native bookmark contract on macOS and the explicit no-op contract elsewhere.
 *
 * The test intentionally uses a temporary directory rather than opening a picker: picker UX belongs
 * to the sandboxed app's UI phase, while this class proves that the persisted bookmark bytes round
 * trip to a live security scope.
 */
class MacSecurityScopedBookmarkTest {
    private val mac =
        System.getProperty("os.name").orEmpty().startsWith("Mac", ignoreCase = true)

    @Test
    fun offMacOs_theCapabilityDegradesWithoutTouchingFoundation() {
        if (mac) return
        assertFalse(MacSecurityScopedBookmark.isAvailable)
        assertNull(MacSecurityScopedBookmark.create("/tmp"))
        assertNull(MacSecurityScopedBookmark.resolve(byteArrayOf(1, 2, 3)))
    }

    @Test
    fun macOs_aSelectedDirectoryRoundTripsThroughBookmarkData() {
        if (!mac) return
        val directory = Files.createTempDirectory("tenshu-security-scope").toFile()
        try {
            val bookmark = MacSecurityScopedBookmark.create(directory.absolutePath)
            assertNotNull(bookmark)
            assertTrue(bookmark.isNotEmpty())

            val access = MacSecurityScopedBookmark.resolve(bookmark)
            assertNotNull(access)
            access.use {
                assertTrue(Files.isSameFile(directory.toPath(), java.nio.file.Path.of(it.path)))
                assertTrue(Files.isDirectory(directory.toPath()))
            }
            access.close() // idempotent close is part of the ownership contract
        } finally {
            directory.deleteRecursively()
        }
    }
}
