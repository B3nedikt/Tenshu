package app.tenshu.control.data

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopMasterKeyStoreTest {

    private val dir: File = Files.createTempDirectory("mks").toFile()

    @AfterTest
    fun cleanup() {
        dir.deleteRecursively()
    }

    /** A fixed wrapping key stands in for the Keychain so tests never touch the real login Keychain. */
    private class FixedWrappingKeyStore(private val key: ByteArray) : WrappingKeyStore {
        override fun loadOrCreate(): ByteArray = key
        override fun clear() = Unit
    }

    private fun keyOf(seed: Byte) = ByteArray(32) { seed }

    @Test
    fun secretsSurviveRestart_whenWrappingKeyPersists() {
        val key = FixedWrappingKeyStore(keyOf(1))

        DesktopMasterKeyStore(dir, key).apply {
            assertTrue(isPersistent())
            storeSecret("sync_recovery_phrase", "alpha bravo charlie")
            storeSecret("sync_owner_id", "owner-42")
        }

        // New instance over the same dir + key simulates an app restart.
        val reopened = DesktopMasterKeyStore(dir, key)
        assertEquals("alpha bravo charlie", reopened.getSecret("sync_recovery_phrase"))
        assertEquals("owner-42", reopened.getSecret("sync_owner_id"))
    }

    @Test
    fun blobIsEncryptedAtRest() {
        val store = DesktopMasterKeyStore(dir, FixedWrappingKeyStore(keyOf(2)))
        store.storeSecret("sync_recovery_phrase", "TOP-SECRET-MNEMONIC")

        val blob = File(dir, "secrets.enc")
        assertTrue(blob.exists(), "secrets.enc should be written when persistent")
        val raw = blob.readBytes().decodeToString()
        assertFalse(raw.contains("TOP-SECRET-MNEMONIC"), "plaintext must not appear in the at-rest blob")
        assertFalse(raw.contains("sync_recovery_phrase"), "keys must not appear in plaintext either")
    }

    @Test
    fun inMemoryFallback_whenNoWrappingKey() {
        DesktopMasterKeyStore(dir, NoWrappingKeyStore).apply {
            assertFalse(isPersistent())
            storeSecret("sync_recovery_phrase", "ephemeral")
            assertEquals("ephemeral", getSecret("sync_recovery_phrase"))
        }

        // Nothing written to disk, and a fresh instance has lost the secret.
        assertFalse(File(dir, "secrets.enc").exists())
        assertNull(DesktopMasterKeyStore(dir, NoWrappingKeyStore).getSecret("sync_recovery_phrase"))
    }

    @Test
    fun removeAndAbsentLookups() {
        val store = DesktopMasterKeyStore(dir, FixedWrappingKeyStore(keyOf(3)))
        store.storeSecret("k", "v")
        assertEquals("v", store.getSecret("k"))
        store.remove("k")
        assertNull(store.getSecret("k"))
        assertNull(store.getSecret("never-set"))
        store.remove("never-set") // idempotent, no throw
    }

    @Test
    fun wrongWrappingKeyYieldsEmptyBucket_noCrash() {
        DesktopMasterKeyStore(dir, FixedWrappingKeyStore(keyOf(4)))
            .storeSecret("sync_owner_id", "owner-7")

        // A different wrapping key (e.g. Keychain item rotated/orphaned) must not
        // crash — the blob is undecryptable, so the bucket reads as empty (re-pair).
        val rekeyed = DesktopMasterKeyStore(dir, FixedWrappingKeyStore(keyOf(9)))
        assertNull(rekeyed.getSecret("sync_owner_id"))

        // The unreadable file is kept aside, so the next write cannot destroy the only copy.
        rekeyed.storeSecret("sync_owner_id", "owner-8")
        val aside = dir.listFiles { f -> f.name.contains(".unreadable-") }.orEmpty()
        assertEquals(1, aside.size, "the unreadable secrets file is kept, not overwritten")
    }
}
