package org.tenshu.sync.proxy.impl

import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createCoreTestRegistry
import org.tenshu.core.common.delegate.InMemorySecureStorageDelegate
import org.tenshu.identity.common.identityServiceCommonModule
import org.tenshu.sync.common.AdmissionCapabilityCert
import org.tenshu.sync.common.syncServiceCommonModule
import org.tenshu.core.security.BootstrapSyncCryptoManager
import org.tenshu.core.security.SyncCryptoManager
import org.tenshu.sync.proxy.PairingKeyBlob
import org.tenshu.sync.proxy.SyncManagerImpl
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * F0 / A′ (`admission-revocation-names-the-capability.md` §7b.3): an admission captures the A33
 * capability key it mints, so a later revocation can name **the capability** rather than the
 * device-at-an-epoch.
 *
 * ⚠⚠ Why capture at admission rather than look it up later: R1 measured the revoker failing to
 * resolve the target's own `AdmissionKeyAnnouncement` on a real device
 * (`a33KeyResolvable=false`, §3.1). The admitter, by contrast, *generates* the keypair, so the
 * public half exists for certain at exactly this moment and nowhere else reliably.
 */
class AdmissionKeyCaptureTest {

    private lateinit var testProxy: TestProxy
    private lateinit var crypto: DistinctAdmissionKeyCrypto
    private lateinit var syncManager: SyncManagerImpl

    private val masterPhrase = "abcdefghijklmnopqrstuvwxyz012345"

    @BeforeTest
    fun setup() {
        testProxy = TestProxy(schemaRegistry = createCoreTestRegistry()).also {
            it.schemaRegistry.registerConfig(syncServiceCommonModule)
            it.schemaRegistry.registerConfig(identityServiceCommonModule)
        }
        crypto = DistinctAdmissionKeyCrypto()
        syncManager = SyncManagerImpl(crypto, InMemorySecureStorageDelegate(), testProxy.tContext)
        syncManager.initialize(masterPhrase)
    }

    /** Admits the device labelled [label] — its key is the label's, its id that key's (workstream B). */
    private fun admit(label: String) = PairingAdmission(
        syncManager = syncManager,
        cryptoManager = crypto,
    ).admit(
        context = testProxy.tContext,
        sharedSecret = ByteArray(32) { 7 },
        newDeviceSigningPublicKey = org.tenshu.sync.proxy.fixtureDeviceKey(label),
        newDeviceId = org.tenshu.sync.proxy.fixtureDeviceId(label),
    )

    /**
     * Workstream B: an admitter never signs over an id that is not the key's — neither the
     * delegation nor the A33 cert naming it. Nothing is minted for a mismatched pair.
     */
    @Test
    fun anIdThatIsNotTheKeys_isRefused_beforeAnythingIsSigned() {
        val failure = kotlin.test.assertFailsWith<org.tenshu.core.TenshuException> {
            PairingAdmission(syncManager = syncManager, cryptoManager = crypto).admit(
                context = testProxy.tContext,
                sharedSecret = ByteArray(32) { 7 },
                newDeviceSigningPublicKey = org.tenshu.sync.proxy.fixtureDeviceKey("dev-new"),
                newDeviceId = org.tenshu.sync.proxy.fixtureDeviceId("someone-else"),
            )
        }
        kotlin.test.assertTrue(failure.toString().isNotEmpty())
    }

    @Test
    fun anAdmissionReportsTheA33KeyItMinted_notAnEmptyPlaceholder() {
        val admitted = admit("dev-new")

        assertTrue(
            admitted.admissionPublicKey.isNotEmpty(),
            "the capture is the whole point of F0 — an empty key leaves a revocation with nothing to name",
        )
    }

    /**
     * ⚠⚠ The load-bearing assertion, and the one a "field is populated" test would miss: the
     * captured key must be **the key that was actually minted for this device**, i.e. the one
     * inside the cert the admitter signed. Stamping *some* valid-looking key would satisfy the
     * test above and still make every key-scoped revocation name the wrong capability.
     */
    @Test
    fun theCapturedKeyIsTheOneInsideTheMintedCert() {
        val admitted = admit("dev-new")

        // Read the cert the NEW device actually receives — it travels inside the sealed blob, so
        // this decrypts with the same shared secret the joiner would use. Asserting against the
        // real payload rather than a re-derivation is the point: it is what the joiner will hold.
        val blob = assertNotNull(
            PairingKeyBlob.unpack(crypto.decrypt(admitted.encryptedMasterKey, ByteArray(32) { 7 })),
        )
        val leaf: AdmissionCapabilityCert = blob.admissionCertChain.first()

        assertEquals(org.tenshu.sync.proxy.fixtureDeviceId("dev-new"), leaf.deviceId, "precondition: the leaf cert is the new device's")
        assertContentEquals(
            leaf.deviceA33PublicKey,
            admitted.admissionPublicKey,
            "the captured key must be the cert's subject key, or a revocation names the wrong capability",
        )
    }

    @Test
    fun twoAdmissionsCaptureDifferentKeys_soARevocationCannotOverreach() {
        // ⚠ Distinct keys per admission are what make key-scoping *narrower* than device-scoping.
        // If admissions shared a key, naming it would revoke both — reintroducing the
        // over-revocation this whole track exists to remove.
        val first = admit("dev-a").admissionPublicKey
        val second = admit("dev-b").admissionPublicKey

        assertTrue(
            !first.contentEquals(second),
            "each admission must mint its own capability",
        )
    }

    /**
     * ⚠⚠ `BootstrapSyncCryptoManager.generateKeyPair()` returns a **fixed** `"dummy"` keypair, so
     * under it every admission mints the *same* A33 key and the 32-byte blob layout never matches.
     * A test on that fake cannot see a key-scoping error at all — it is the "happy-path fake hides
     * the missing branch" shape.
     *
     * This double keeps Bootstrap's cheap XOR crypto but honours the one property the real
     * generator has and F0 depends on: **a fresh 32-byte keypair per call.**
     */
    private class DistinctAdmissionKeyCrypto : SyncCryptoManager by BootstrapSyncCryptoManager() {
        private var minted = 0

        override fun generateAdmissionKeyPair(): SyncCryptoManager.KeyPair {
            minted++
            return SyncCryptoManager.KeyPair(
                publicKey = ByteArray(32) { (minted * 31 + it).toByte() },
                privateKey = ByteArray(32) { (minted * 17 + it).toByte() },
            )
        }
    }
}
