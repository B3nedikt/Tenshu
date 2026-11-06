package app.tenshu.hub

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FileWrappingKeyStoreTest {

    private val dir = createTempDirectory("hub-wrapkey-test").toFile()

    @AfterTest
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun createsA32ByteKeyAndReturnsTheSameKeyOnSubsequentLoads() {
        val file = File(dir, "wrap.key")
        val store = FileWrappingKeyStore(file)

        val first = assertNotNull(store.loadOrCreate())
        assertEquals(32, first.size)
        assertTrue(file.exists(), "wrapping key must be persisted")

        // Same store instance and a FRESH instance (a hub restart) both read it back.
        assertContentEquals(first, store.loadOrCreate())
        assertContentEquals(first, FileWrappingKeyStore(file).loadOrCreate())
    }

    @Test
    fun clearRemovesTheKeySoTheNextLoadMintsAFreshOne() {
        val file = File(dir, "wrap.key")
        val store = FileWrappingKeyStore(file)
        val first = assertNotNull(store.loadOrCreate())

        store.clear()
        val second = assertNotNull(store.loadOrCreate())
        assertEquals(32, second.size)
        assertTrue(!first.contentEquals(second), "cleared store must mint a fresh key")
    }
}
