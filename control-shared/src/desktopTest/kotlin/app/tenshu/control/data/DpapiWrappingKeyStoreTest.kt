package app.tenshu.control.data

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Windows wrapping-key store's own logic, over a fake DPAPI so it runs on every host. The real
 * DPAPI calls are pinned by `:windows-helpers`' `WindowsDpapiTest` on a Windows host.
 */
class DpapiWrappingKeyStoreTest {

    private val dir: File = Files.createTempDirectory("dpapi-store").toFile()
    private val blobFile = File(dir, DpapiWrappingKeyStore.FILE_NAME)

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    /** A reversible stand-in: "sealed" = entropy-length prefix + XOR. Fails on the wrong entropy. */
    private class FakeDpapi(var available: Boolean = true) {
        var protects = 0
        fun protect(plain: ByteArray, entropy: ByteArray): ByteArray? {
            if (!available) return null
            protects++
            return byteArrayOf(entropy.size.toByte()) + plain.mapIndexed { i, b -> (b.toInt() xor entropy[i % entropy.size].toInt()).toByte() }
        }
        fun unprotect(blob: ByteArray, entropy: ByteArray): ByteArray? {
            if (!available || blob.isEmpty() || blob[0].toInt() != entropy.size) return null
            return blob.drop(1).mapIndexed { i, b -> (b.toInt() xor entropy[i % entropy.size].toInt()).toByte() }.toByteArray()
        }
    }

    private fun store(dpapi: FakeDpapi) = DpapiWrappingKeyStore(blobFile, dpapi::protect, dpapi::unprotect)

    @Test
    fun theKeyIsCreatedOnce_andSurvivesARestart() {
        val dpapi = FakeDpapi()
        val first = assertNotNull(store(dpapi).loadOrCreate())
        assertEquals(32, first.size)
        assertTrue(blobFile.exists())
        assertFalse(blobFile.readBytes().contentEquals(first), "the key must not be written in the clear")

        assertContentEquals(first, store(dpapi).loadOrCreate(), "a restart must read the same key back")
        assertEquals(1, dpapi.protects, "an existing blob is opened, never re-minted")
    }

    @Test
    fun noDpapiMeansNoKey_andNoFile() {
        assertNull(store(FakeDpapi(available = false)).loadOrCreate())
        assertFalse(blobFile.exists())
    }

    /**
     * ⚠⚠ The data-loss case: a blob that will not open must not be replaced by a fresh key, or
     * `secrets.enc` is orphaned while everything looks fine.
     */
    @Test
    fun aBlobThatWillNotOpenIsNeitherReplacedNorGuessed() {
        val dpapi = FakeDpapi()
        store(dpapi).loadOrCreate()
        val original = blobFile.readBytes()
        dpapi.available = false // e.g. another user's profile, or an admin password reset

        assertNull(store(dpapi).loadOrCreate())
        assertContentEquals(original, blobFile.readBytes(), "the unopenable blob stays for diagnosis")
        assertEquals(1, dpapi.protects)
    }

    @Test
    fun aBlobOfTheWrongSizeIsRefused() {
        val dpapi = FakeDpapi()
        blobFile.writeBytes(dpapi.protect(ByteArray(16), "app.tenshu.control.master-wrap-key.v1".encodeToByteArray())!!)
        assertNull(store(dpapi).loadOrCreate())
    }

    @Test
    fun clearRemovesTheBlob_soTheNextStartMintsAgain() {
        val dpapi = FakeDpapi()
        val s = store(dpapi)
        s.loadOrCreate()
        s.clear()
        assertFalse(blobFile.exists())
        assertNotNull(s.loadOrCreate())
        assertEquals(2, dpapi.protects)
    }

    /** The end-to-end property that matters: secrets written by one Control are read by the next. */
    @Test
    fun secretsPersistAcrossARestartThroughTheMasterKeyStore() {
        val dpapi = FakeDpapi()
        DesktopMasterKeyStore(dir, store(dpapi)).storeSecret("OWNER_ID", "owner-42")

        val restarted = DesktopMasterKeyStore(dir, store(dpapi))
        assertTrue(restarted.isPersistent())
        assertEquals("owner-42", restarted.getSecret("OWNER_ID"))
    }
}
