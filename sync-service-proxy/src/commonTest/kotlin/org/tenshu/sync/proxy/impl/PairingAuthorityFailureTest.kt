package org.tenshu.sync.proxy.impl

import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createSyntheticTestRegistry
import org.tenshu.core.common.delegate.InMemorySecureStorageDelegate
import org.tenshu.core.security.syncCryptoManager
import org.tenshu.sync.proxy.SyncManagerImpl
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A sealed device must not tell the user their account is missing.
 *
 * ⚠⚠ The real report (2026-08-19): a desktop Control refused an iPhone pairing with *"no account is
 * set up on this device"* while its store held three `Device` rows, an entitlement and an admission
 * grant. It was simply locked, and its own error log said so — 37 × *"realm locked — unlock this
 * device…"*. The message sent the reader hunting a commerce-shaped problem.
 *
 * ⚠ Driven through the real [SyncManagerImpl] rather than a fake: the whole bug lives in the gap
 * between `currentOwnerId` (in-memory, cleared by `lock()`) and the persisted unlock wrapper, and a
 * fake that returns a hand-written `UnlockStatus` would model my belief about that gap instead of
 * the thing itself.
 */
class PairingAuthorityFailureTest {

    private lateinit var testProxy: TestProxy

    @BeforeTest
    fun setup() {
        testProxy = TestProxy(schemaRegistry = createSyntheticTestRegistry())
    }

    private fun syncManager() = SyncManagerImpl(
        cryptoManager = testProxy.tContext.syncCryptoManager(),
        storageDelegate = InMemorySecureStorageDelegate(),
        context = testProxy.tContext,
    )

    @Test
    fun aLockedDeviceIsToldToUnlock_notThatItHasNoAccount() {
        val sm = syncManager()
        // ⚠ A real mnemonic, not a placeholder: `setUpLocalUnlock` now refuses to seal a
        // payload that would not decode on the way back out, so "mock phrase" would leave
        // this device with no wrapper — and `isSetUp`, the discriminator this test is
        // about, reads exactly that wrapper's existence.
        sm.initialize(LEGAL_TEST_PHRASE)
        assertTrue(sm.setUpLocalUnlock("a-passphrase-12"), "precondition: the realm is wrapped")
        sm.lock()

        // The precondition the approval path actually trips on.
        assertNull(sm.currentOwnerId, "precondition: a locked realm has no in-memory ownerId")

        val message = sm.pairingAuthorityFailure()
        assertTrue(message.contains("locked"), "must name the real state: $message")
        assertTrue(
            !message.contains("no account"),
            "must NOT send the user looking for a missing account: $message",
        )
    }

    @Test
    fun aDeviceWithNoRealmStillSaysNoAccount() {
        val sm = syncManager()
        assertNull(sm.currentOwnerId)

        assertEquals(
            "Cannot approve pairing: no account is set up on this device",
            sm.pairingAuthorityFailure(),
            "a genuinely blank device must keep the original message",
        )
    }

    private companion object {
        const val LEGAL_TEST_PHRASE =
            "abandon abandon abandon abandon abandon abandon " +
                "abandon abandon abandon abandon abandon abandon " +
                "abandon abandon abandon abandon abandon abandon " +
                "abandon abandon abandon abandon abandon art"
    }
}
