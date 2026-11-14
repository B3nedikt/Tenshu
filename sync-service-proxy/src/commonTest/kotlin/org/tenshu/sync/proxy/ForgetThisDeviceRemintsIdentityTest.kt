package org.tenshu.sync.proxy

import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createSyntheticTestRegistry
import org.tenshu.core.common.delegate.InMemorySecureStorageDelegate
import org.tenshu.core.security.syncCryptoManager
import org.tenshu.core.time.clock
import org.tenshu.sync.common.deviceIdForPublicKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.runTest
import org.tenshu.core.Data
import org.tenshu.core.TString
import org.tenshu.core.common.delegate.SecureStorageDelegate
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.AsyncOperationImplementation
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.sync.proxy.impl.ForgetThisDeviceImplementation
import kotlin.test.BeforeTest
import kotlin.test.assertFailsWith
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * *Forget this device* gives the **running process** a new identity
 * (`admission-revocation-names-the-capability.md` §7i.5).
 *
 * ⚠⚠ Found on a desktop, 2026-11-11: after Forget, pairing from the same process (offer flow) was
 * refused by the relay — `403 device-removed` on every approve — because `currentDeviceId` was a
 * `by lazy` and the A4 pair an atomic nobody cleared. The custody slots were gone at rest, but the
 * process kept offering the forgotten id and its key until Control was restarted, and F2′ (a
 * removed identity never returns) refused it, correctly.
 *
 * Every holder of the identity inside [SyncManagerImpl] is checked here: the key, the id derived
 * from it, the plaintext id cache, the clock that stamps with the id, the persisted key, and the
 * pairing sessions the old identity had open.
 */
class ForgetThisDeviceRemintsIdentityTest {

    private lateinit var testProxy: TestProxy
    private lateinit var storage: InMemorySecureStorageDelegate

    @BeforeTest
    fun setup() {
        testProxy = TestProxy(schemaRegistry = createSyntheticTestRegistry())
        // Shared across managers: the disk, so a second manager is the next launch.
        storage = InMemorySecureStorageDelegate()
    }

    private fun newManager() = SyncManagerImpl(
        cryptoManager = testProxy.tContext.syncCryptoManager(),
        storageDelegate = storage,
        context = testProxy.tContext,
    )

    @Test
    fun forget_givesThisProcessANewKey_andTheIdDerivedFromIt() {
        val manager = newManager()
        val oldKey = manager.getDeviceSigningKeyPair()
        val oldId = manager.currentDeviceId

        manager.forgetThisDevice()

        val newKey = manager.getDeviceSigningKeyPair()
        assertFalse(
            newKey.publicKey.contentEquals(oldKey.publicKey),
            "the forgotten A4 key must not survive in memory — the next pairing would sign with it",
        )
        assertNotEquals(
            oldId, manager.currentDeviceId,
            "a pairing started after Forget presented the removed id until a restart (F2′ refused it)",
        )
        assertEquals(deviceIdForPublicKey(newKey.publicKey), manager.currentDeviceId, "§7i: the id is the key's")
        assertTrue(oldKey.privateKey.all { it == 0.toByte() }, "the dropped A4 private key is zeroed in memory")
    }

    @Test
    fun forget_leavesNothingInTheManagerHoldingTheOldId() {
        val manager = newManager()
        val oldId = manager.currentDeviceId
        // A tip ahead of the wall clock (a peer's, folded in) — a reset successor would fall below it.
        val ahead = testProxy.tContext.clock().nowEpochMillis() + 3_600_000L
        val before = manager.hlcManager.seed(ahead, 7L)
        assertEquals(oldId, storage.getString(SyncManagerImpl.Keys.DEVICE_ID), "precondition: the cache is written")

        manager.forgetThisDevice()
        val newId = manager.currentDeviceId

        assertEquals(newId, manager.hlcManager.deviceId, "the clock must stamp the new id, not the forgotten one")
        val after = manager.hlcManager.tick()
        assertEquals(newId, after.deviceId.get())
        assertTrue(
            after.physicalTime.get() > before.physicalTime.get() ||
                (after.physicalTime.get() == before.physicalTime.get() &&
                    after.logicalCounter.get() > before.logicalCounter.get()),
            "the successor clock is seeded from its predecessor — a reset would publish a lower tip",
        )
        assertEquals(
            newId, storage.getString(SyncManagerImpl.Keys.DEVICE_ID),
            "the plaintext id cache is read before a SyncManager exists — it must name the new device",
        )
    }

    @Test
    fun forget_persistsTheNewKey_soTheNextLaunchIsTheSameNewDevice() {
        val manager = newManager()
        val oldId = manager.currentDeviceId
        manager.forgetThisDevice()
        val newId = manager.currentDeviceId

        val nextLaunch = newManager()
        assertEquals(newId, nextLaunch.currentDeviceId, "the re-minted key is the device's key at rest too")
        assertNotEquals(oldId, nextLaunch.currentDeviceId)
    }

    @Test
    fun forget_closesThePairingSessionsTheOldIdentityOpened() {
        val manager = newManager()
        val crypto = testProxy.tContext.syncCryptoManager()
        manager.startOfferSession("offer-hash", crypto.generateKeyPair(), "Laptop")
        manager.startResponderSession(
            pairingSecretHash = "responder-hash",
            ephemeralKeyPair = crypto.generateKeyPair(),
            offererPublicKey = ByteArray(32) { 1 },
            offererSigningKey = ByteArray(32) { 2 },
            offererDeviceId = "someone-else",
            deviceName = "Phone",
        )

        manager.forgetThisDevice()

        assertNull(manager.getOfferSession("offer-hash"), "an offer minted under the forgotten identity is dead")
        assertNull(manager.getResponderSession("responder-hash"))
        assertNull(manager.getPairingString(), "no host context survives either")
    }

    /**
     * ⚠⚠ MED-2: a new key that cannot be written must never be used. Kept in memory alone it would
     * be paired, then replaced by a restart — the admitted id orphaned at the relay, stalling the
     * tombstone-GC quorum and content reclamation (threat model A4 *lifecycle*, T-K (iii)).
     */
    @Test
    fun aNewKeyThatCannotBePersisted_isNeverHeld_andForgetSaysKeyNotSaved() = runTest {
        val failing = FailingKeyStorage(storage)
        val manager = SyncManagerImpl(testProxy.tContext.syncCryptoManager(), failing, testProxy.tContext)
        val oldId = manager.currentDeviceId
        manager.startOfferSession("offer-hash", testProxy.tContext.syncCryptoManager().generateKeyPair(), "Laptop")
        failing.failKeyWrites = true

        val outcome = ForgetThisDeviceImplementation(
            syncManager = manager,
            revokeDevice = { _ -> NoRevoke() },
            nowMs = { 0L },
        ).execute<TString>(testProxy.executionContext, null, testProxy.executionContext.implementationFactory, null, null)
            .get().orEmpty()

        assertTrue(outcome.contains("⚠ new device key NOT saved"), "Forget must not report a clean forget: $outcome")
        assertFailsWith<IllegalStateException>("no in-memory-only key may be handed out — pairing would use it") {
            manager.getDeviceSigningKeyPair()
        }
        assertFailsWith<IllegalStateException> { manager.currentDeviceId }
        assertNull(manager.getOfferSession("offer-hash"), "a failed re-mint still closes the old identity's sessions")

        // Storage recovers: the identity minted now is at rest, and it is never the forgotten one.
        failing.failKeyWrites = false
        val newId = manager.currentDeviceId
        assertNotEquals(oldId, newId)
        assertEquals(newId, newManager().currentDeviceId, "the key in use is the key at rest")
    }

    /** LOW-3: overlapping forgets end with memory and disk on the SAME key. */
    @Test
    fun overlappingForgets_leaveMemoryAndDiskOnOneKey() = runTest {
        // ⚠ The test map is not thread-safe; the platform stores are. Serialise IT, not the manager.
        val shared = LockedStorage(storage)
        fun manager() = SyncManagerImpl(testProxy.tContext.syncCryptoManager(), shared, testProxy.tContext)
        repeat(50) {
            val manager = manager()
            manager.currentDeviceId
            // Real threads (JVM / Native): the interleaving is the point.
            withContext(Dispatchers.Default) {
                val start = kotlinx.coroutines.CompletableDeferred<Unit>()
                val forgets = List(4) { launch { start.await(); manager.forgetThisDevice() } }
                start.complete(Unit)
                forgets.joinAll()
            }
            assertEquals(
                manager().currentDeviceId, manager.currentDeviceId,
                "an interleaving left this process on a key other than the one at rest",
            )
        }
    }
}

/** [inner] behind one lock — the thread-safety the platform secure stores give for free. */
private class LockedStorage(private val inner: SecureStorageDelegate) : SecureStorageDelegate {
    private val lock = kotlinx.atomicfu.locks.reentrantLock()
    override fun storeSecret(key: String, value: String) = lock.withLock { inner.storeSecret(key, value) }
    override fun getSecret(key: String): String? = lock.withLock { inner.getSecret(key) }
    override fun storeString(key: String, value: String) = lock.withLock { inner.storeString(key, value) }
    override fun getString(key: String): String? = lock.withLock { inner.getString(key) }
    override fun remove(key: String) = lock.withLock { inner.remove(key) }
}

/** Delegates to [inner]; refuses to write the A4 key while [failKeyWrites] is set. */
private class FailingKeyStorage(private val inner: SecureStorageDelegate) : SecureStorageDelegate by inner {
    var failKeyWrites = false

    override fun storeSecret(key: String, value: String) {
        check(!(failKeyWrites && key == SyncManagerImpl.Keys.DEVICE_SIGNING_KEY)) { "made up: the Keystore refused the write" }
        inner.storeSecret(key, value)
    }
}

private class NoRevoke : AsyncOperationImplementation {
    @Suppress("UNCHECKED_CAST")
    override suspend fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?,
    ): T = null as T
}
