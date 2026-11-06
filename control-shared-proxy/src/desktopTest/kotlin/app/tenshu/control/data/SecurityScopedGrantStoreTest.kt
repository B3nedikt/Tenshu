package app.tenshu.control.data

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SecurityScopedGrantStoreTest {
    private val dir: File = Files.createTempDirectory("grant-store").toFile()
    private val store = SecurityScopedGrantStore(dir)

    @AfterTest
    fun cleanup() {
        dir.deleteRecursively()
    }

    @Test
    fun aStoredBookmarkReadsBackUnderItsId() {
        val id = store.put(byteArrayOf(1, 2, 3))
        assertContentEquals(byteArrayOf(1, 2, 3), store.read(id))
        assertNotEquals(id, store.put(byteArrayOf(1, 2, 3)), "every pick gets its own id")
    }

    /** A refreshed stale bookmark keeps its id, so the handle Drive holds stays valid. */
    @Test
    fun replacingKeepsTheIdAndSwapsTheBytes() {
        val id = store.put(byteArrayOf(1))
        assertTrue(store.replace(id, byteArrayOf(9, 9)))
        assertContentEquals(byteArrayOf(9, 9), store.read(id))
        assertEquals(listOf("$id.bookmark"), dir.list()!!.toList(), "no temp file is left behind")
    }

    @Test
    fun anUnknownIdIsNeitherReadNorCreatedByReplace() {
        val unknown = "0123456789abcdef0123456789abcdef"
        assertNull(store.read(unknown))
        assertFalse(store.replace(unknown, byteArrayOf(1)))
        assertFalse(File(dir, "$unknown.bookmark").exists())
    }

    /** Ids arrive from IPC clients; one that is not 32 hex digits must never name a file. */
    @Test
    fun aMalformedIdNeverReachesTheFileSystem() {
        val outside = File(dir.parentFile, "escaped.bookmark").apply { writeBytes(byteArrayOf(7)) }
        try {
            assertNull(store.read("../escaped"))
            assertFalse(store.replace("../escaped", byteArrayOf(1)))
            assertContentEquals(byteArrayOf(7), outside.readBytes())
        } finally {
            outside.delete()
        }
    }
}
