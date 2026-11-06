package org.tenshu.sync.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.Data
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createCoreTestRegistry
import org.tenshu.core.common.delegate.InMemorySecureStorageDelegate
import org.tenshu.core.common.parser.BinarySerializer
import org.tenshu.core.common.security.BouncyCastleEd25519Backend
import org.tenshu.core.common.security.Ed25519Backend
import org.tenshu.core.entity.DataId
import org.tenshu.core.security.BootstrapSyncCryptoManager
import org.tenshu.core.security.SyncCryptoManager
import org.tenshu.core.time.HybridLogicalClock
import org.tenshu.identity.Entitlement
import org.tenshu.identity.EntitlementSignature
import org.tenshu.identity.common.identityServiceCommonModule
import org.tenshu.sync.common.BlockPayloadEnvelope
import org.tenshu.sync.common.EnvelopeAuth
import org.tenshu.sync.DataBlockPayload
import org.tenshu.sync.SyncBlock
import org.tenshu.sync.common.syncServiceCommonModule
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Verify-on-merge for the Publisher-signed `Entitlement` (storage-entitlement-sync.md
 * slice 4a): an incoming entitlement block is accepted only if its signature verifies
 * against the known publisher key. Mirrors [StaleEpochRejectionTest] (the `Device`
 * T-K-02 reject) but for the entitlement-signature reject.
 *
 * Uses **real** Ed25519 (BouncyCastle) for sign + verify — the stub
 * `BootstrapSyncCryptoManager` can't validate a real `(seed, publicKey)` pair — so this
 * lives in `jvmTest`, where the JVM backend is available.
 */
class EntitlementVerifyOnMergeTest {

    private val ed: Ed25519Backend = BouncyCastleEd25519Backend()

    private lateinit var testProxy: TestProxy
    private lateinit var processor: SyncProcessor
    private lateinit var syncManager: SyncManagerImpl
    private lateinit var crypto: SyncCryptoManager

    /** Real Ed25519 for sign/verify; everything else (encrypt/decrypt/derive) is the stub. */
    private class RealEd25519Crypto(
        base: SyncCryptoManager,
        private val ed: Ed25519Backend,
    ) : SyncCryptoManager by base {
        override fun signEd25519(privateKey: ByteArray, message: ByteArray): ByteArray =
            ed.sign(privateKey, message)

        override fun verifyEd25519(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean =
            ed.verify(publicKey, message, signature)
    }

    @BeforeTest
    fun setup() {
        testProxy = TestProxy(
            schemaRegistry = createCoreTestRegistry().also {
                it.registerConfig(syncServiceCommonModule)
                it.registerConfig(identityServiceCommonModule)
            },
        )
        crypto = RealEd25519Crypto(BootstrapSyncCryptoManager(), ed)
        syncManager = SyncManagerImpl(
            context = testProxy.tContext,
            cryptoManager = crypto,
            storageDelegate = InMemorySecureStorageDelegate(),
        )
        syncManager.initialize("entitlement-verify-test-phrase")
        processor = SyncProcessor(
            wireIdResolver = WireIdResolver(syncManager),
            executionContext = testProxy.executionContext,
            syncManager = syncManager,
            cryptoManager = crypto,
            tombstoneRegistry = TombstoneRegistry(testProxy.executionContext, wireIdFor = WireIdResolver(syncManager)::ofOrNull),
        )
    }

    private companion object {
        const val VALID_FROM = 100L
        const val VALID_UNTIL = 200L
    }

    private fun entitlement(ownerId: String, publisherId: String, signature: ByteArray): Entitlement =
        Entitlement.create(
            context = testProxy.tContext,
            ownerId = ownerId,
            publisherId = publisherId,
            productId = "tenshu.drive.free",
            validFrom = VALID_FROM,
            validUntil = VALID_UNTIL,
            publisherSignature = signature,
        )

    private fun devSigned(ownerId: String): Entitlement {
        val message = EntitlementSignature.message(
            ownerId = ownerId,
            publisherId = EntitlementSignature.DEV_PUBLISHER_ID,
            productId = "tenshu.drive.free",
            validFrom = VALID_FROM,
            validUntil = VALID_UNTIL,
        )
        return entitlement(ownerId, EntitlementSignature.DEV_PUBLISHER_ID, ed.sign(EntitlementSignature.DEV_SEED, message))
    }

    /**
     * A record signed by the **commerce-service** issuer (`tenshu.commerce`) — proves a Phase-1
     * service mint would be accepted on sync end-to-end. The commerce dev seed literal is
     * reproduced here (it lives only in `:commerce-service`); its public half is the pinned
     * `EntitlementSignature.COMMERCE_PUBLIC_KEY`.
     */
    private fun commerceSigned(ownerId: String): Entitlement {
        val commerceDevSeed = ByteArray(32) { (it + 65).toByte() }
        val message = EntitlementSignature.message(
            ownerId = ownerId,
            publisherId = EntitlementSignature.COMMERCE_PUBLISHER_ID,
            productId = "tenshu.drive.free",
            validFrom = VALID_FROM,
            validUntil = VALID_UNTIL,
        )
        return entitlement(ownerId, EntitlementSignature.COMMERCE_PUBLISHER_ID, ed.sign(commerceDevSeed, message))
    }

    private fun blockFor(data: Data, blockId: String): SyncBlock {
        val payload = DataBlockPayload.create(context = testProxy.tContext, propertyId = null, value = data)
        // Envelope bound as AAD (sync-envelope-authentication §3.2).
        return EnvelopeAuth.sealSyncBlock(
            crypto = crypto,
            context = testProxy.tContext,
            blockId = blockId,
            ownerId = "user-1",
            hlc = HybridLogicalClock.create(testProxy.tContext, physicalTime = 100L, logicalCounter = 0L, deviceId = "remote"),
            targetEntityIdHash = null,
            plaintext = BlockPayloadEnvelope.seal(BinarySerializer().serialize(payload), testProxy.versionStampBytes()),
            identityKey = syncManager.getDerivedKeys()!!.sharedIdentityKey,
        )
    }

    private fun storedEntitlement(ownerId: String): Data? =
        testProxy.database.getData(DataId.StringDataId.create(testProxy.tContext, Entitlement.ID, ownerId))

    @Test
    fun acceptsEntitlement_withValidPublisherSignature() = runTest {
        processor.processSyncBlock(blockFor(devSigned("owner-valid"), "valid-block"))
        assertNotNull(
            storedEntitlement("owner-valid"),
            "An entitlement with a valid dev-publisher signature must be accepted on merge",
        )
    }

    @Test
    fun acceptsEntitlement_withValidCommerceServiceSignature() = runTest {
        processor.processSyncBlock(blockFor(commerceSigned("owner-commerce"), "commerce-block"))
        assertNotNull(
            storedEntitlement("owner-commerce"),
            "An entitlement minted by the commerce-service issuer must be accepted on merge",
        )
    }

    /**
     * ⚠⚠ Behaviour CHANGED by `entitlement-trust-anchor-distribution.md` D1: an unverifiable record
     * is **retained**, not dropped. These two cases previously asserted `assertNull`.
     *
     * Dropping protected nothing — the relay re-verifies every record against its own configured
     * key before honouring it — and cost a great deal: it was silent, indistinguishable from "no
     * entitlement yet", and it stranded session-less paired devices write-dead on `402` by
     * withholding the very row `EntitlementRecordCredentialProvider` forwards as proof.
     *
     * Retention is only safe because the *reader* declines to derive storage figures from an
     * unverifiable record (`initEntitlementStorageReader`). ⚠ If that ever regresses, these tests
     * still pass while the client starts trusting unverified records — so the reader-side rule has
     * its own test in `:storage-service-proxy`; neither is sufficient alone.
     */
    @Test
    fun retainsEntitlement_withInvalidSignature_ratherThanDroppingIt() = runTest {
        // Right publisher, empty (invalid) signature.
        processor.processSyncBlock(
            blockFor(entitlement("owner-bad", EntitlementSignature.DEV_PUBLISHER_ID, ByteArray(0)), "bad-block"),
        )
        assertNotNull(
            storedEntitlement("owner-bad"),
            "an unverifiable entitlement must be retained so it can still be forwarded as a " +
                "credential — the relay is the authority on whether it is acceptable",
        )
    }

    @Test
    fun retainsEntitlement_fromUnknownPublisher_ratherThanDroppingIt() = runTest {
        // Unknown publisher id — no key to verify against, even if 'signed'.
        val message = EntitlementSignature.message("owner-unknown", "evil.corp", "tenshu.drive.free", VALID_FROM, VALID_UNTIL)
        val forged = entitlement("owner-unknown", "evil.corp", ed.sign(EntitlementSignature.DEV_SEED, message))
        processor.processSyncBlock(blockFor(forged, "unknown-block"))
        assertNotNull(
            storedEntitlement("owner-unknown"),
            "an unknown publisher usually means a record from a newer issuer, not an attack — " +
                "retain it and let the relay judge",
        )
    }
}
