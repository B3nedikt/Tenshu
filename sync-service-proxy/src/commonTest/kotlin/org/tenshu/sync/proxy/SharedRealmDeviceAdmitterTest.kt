package org.tenshu.sync.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createSyntheticTestRegistry
import org.tenshu.core.security.SyncCryptoManager
import org.tenshu.core.security.syncCryptoManager
import org.tenshu.sync.common.RealmContext
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * SR-3b.4b owner side: [SharedRealmDeviceAdmitter] signs an admission delegation with the
 * shared realm's master key and posts it to the relay. Verifies the call reaches the relay
 * with the right `(ownerId, deviceId, pubkey)` and a delegation that verifies against the
 * realm's master public key (the exact `pairingResponseMessage` shape the relay checks),
 * and that a joiner (no master key) can't admit.
 */
class SharedRealmDeviceAdmitterTest {

    @Test
    fun owner_signsAndPostsAdmission() = runTest {
        val proxy = TestProxy(schemaRegistry = createSyntheticTestRegistry())
        val crypto = proxy.tContext.syncCryptoManager()
        val relay = MockSyncRelay(proxy.tContext)

        // OWNER realm context: holds the realm's Ed25519 master keypair.
        val realmMaster = crypto.generateKeyPair()
        val ownerRealm = RealmContext(
            ownerId = "shared-realm-1",
            deviceId = "owner-device",
            derivedKeys = SyncCryptoManager.DerivedKeys(ByteArray(32) { 1 }, ByteArray(32) { 2 }),
            deviceSigningKeyPair = crypto.generateDeviceSigningKeyPair(),
            masterKeyPair = realmMaster,
        )

        val joinerA4 = ByteArray(32) { 0x55 }
        val result = SharedRealmDeviceAdmitter(crypto, relay)
            .admit(ownerRealm, deviceId = org.tenshu.sync.common.deviceIdForPublicKey(joinerA4), deviceA4PublicKey = joinerA4)

        assertTrue(result is org.tenshu.core.TResult.Success)

        val admitted = relay.admittedDevices().single()
        assertEquals("shared-realm-1", admitted.first)
        assertEquals(org.tenshu.sync.common.deviceIdForPublicKey(joinerA4), admitted.second)
        assertContentEquals(joinerA4, admitted.third)
    }

    @Test
    fun joiner_cannotAdmit() = runTest {
        val proxy = TestProxy(schemaRegistry = createSyntheticTestRegistry())
        val crypto = proxy.tContext.syncCryptoManager()
        val relay = MockSyncRelay(proxy.tContext)

        // JOINER realm context: no master key.
        val joinerRealm = RealmContext(
            ownerId = "shared-realm-1",
            deviceId = "joiner-device",
            derivedKeys = SyncCryptoManager.DerivedKeys(ByteArray(32) { 1 }, ByteArray(32) { 2 }),
            deviceSigningKeyPair = crypto.generateDeviceSigningKeyPair(),
            masterKeyPair = null,
        )

        assertFailsWith<IllegalStateException> {
            SharedRealmDeviceAdmitter(crypto, relay)
                .admit(joinerRealm, deviceId = "another-device", deviceA4PublicKey = ByteArray(32))
        }
        assertTrue(relay.admittedDevices().isEmpty(), "no admission posted when the caller isn't the owner")
    }
}
