package org.tenshu.core.security

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNotEquals

class SyncCryptoTest {

    private val cryptoManager = BootstrapSyncCryptoManager()

    @Test
    fun testKeyDerivation() {
        val phrase =
            "abandon ability able about above absent absorb abstract absurd abuse access accident"
        val keys1 = cryptoManager.deriveKeys(phrase)
        val keys2 = cryptoManager.deriveKeys(phrase)
        val keys3 = cryptoManager.deriveKeys("different phrase")

        assertContentEquals(keys1.sharedIdentityKey, keys2.sharedIdentityKey)
        assertNotEquals(keys1.sharedIdentityKey.toList(), keys3.sharedIdentityKey.toList())
    }

    @Test
    fun testEncryptionRoundTrip() {
        val key = byteArrayOf(1, 2, 3, 4, 5)
        val payload = "Sensitive Data".encodeToByteArray()

        val encrypted = cryptoManager.encrypt(payload, key)
        val decrypted = cryptoManager.decrypt(encrypted, key)

        assertNotEquals(payload.toList(), encrypted.toList())
        assertContentEquals(payload, decrypted)
    }
}
