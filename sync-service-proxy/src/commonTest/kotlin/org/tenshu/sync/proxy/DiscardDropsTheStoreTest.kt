package org.tenshu.sync.proxy

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createSyntheticTestRegistry
import org.tenshu.core.common.delegate.InMemorySecureStorageDelegate
import org.tenshu.core.common.proxy.db.InMemoryObjectStore
import org.tenshu.core.common.proxy.db.ObjectStore
import org.tenshu.core.common.proxy.db.WipeableObjectStore
import org.tenshu.core.security.syncCryptoManager
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * RG-3 / D3 (decided 2026-08-23): a discard drops the device's local copy of realm data.
 *
 * ## ⚠⚠ The failure this pins, which is not "some rows survived"
 *
 * Keeping the store does not make the discard smaller, it makes it **broken**. Observed on desktop
 * the day "Forget this device" shipped there: the retirement worked and custody was cleared, but the
 * process kept running, so `currentDeviceId` — a `val` that mints a fresh UUID when the key is gone —
 * generated and persisted a **new device id registered with no realm**. Every push then returned
 * `401`, the outbound queue grew, and the surviving store kept the app looking signed in to a realm
 * it had already left. Unrecoverable in-app: in no realm, and unable to re-onboard because the store
 * says it already is. The only exit was deleting the data directory by hand.
 *
 * ⚠ So the assertion is about the *pair*: identity gone AND store gone. Either alone is the half
 * reset this repo already names as worse than none.
 */
class DiscardDropsTheStoreTest {

    private lateinit var testProxy: TestProxy
    private lateinit var objectStore: InMemoryObjectStore
    private lateinit var syncManager: SyncManagerImpl

    @BeforeTest
    fun setup() {
        testProxy = TestProxy(schemaRegistry = createSyntheticTestRegistry())
        objectStore = InMemoryObjectStore()
        syncManager = SyncManagerImpl(
            cryptoManager = testProxy.tContext.syncCryptoManager(),
            storageDelegate = InMemorySecureStorageDelegate(),
            context = testProxy.tContext,
            objectStore = objectStore,
        )
        syncManager.initialize("mock phrase")
    }

    @Test
    fun wipeLocalStore_emptiesTheStore_andReportsThatItDid() = runTest {
        objectStore.storeObject(ObjectStore.DbEntry(type = "t", dataIdHash = 1L, fields = emptyList()))
        objectStore.storeObject(ObjectStore.DbEntry(type = "t", dataIdHash = 2L, fields = emptyList()))
        assertFalse(objectStore.isEmpty(), "precondition: the store has rows to lose")

        val reported = syncManager.wipeLocalStore()

        assertTrue(reported, "a store that CAN wipe must report that it did — the caller shows this")
        assertTrue(
            objectStore.isEmpty(),
            "the discard left realm data behind: a device with no identity and a full store is the " +
                "half reset D3 closed — 401 forever, and no way to re-onboard",
        )
    }

    /**
     * ⚠⚠ **State DERIVED from the realm goes with it.** The inference vector store is device-local and
     * keyed by file PATH, so a vector kept across a wipe answers a search in the next realm with the
     * previous realm's photograph, and its `visited` mark keeps the new one out of the index.
     */
    @Test
    fun wipeLocalStore_clearsStateDerivedFromTheRealm() = runTest {
        var derivedCleared = 0
        val manager = SyncManagerImpl(
            cryptoManager = testProxy.tContext.syncCryptoManager(),
            storageDelegate = InMemorySecureStorageDelegate(),
            context = testProxy.tContext,
            objectStore = objectStore,
            onLocalStoreWiped = { derivedCleared++ },
        )

        assertTrue(manager.wipeLocalStore())

        assertEquals(1, derivedCleared, "the realm's local copy went and the state derived from it stayed")
    }

    /**
     * ⚠ The derived clear cannot fail the wipe. The realm copy going is the irreversible half; a
     * derived store that will not clear must not strand the device half-wiped.
     */
    @Test
    fun aDerivedClearThatThrowsDoesNotFailTheWipe() = runTest {
        val manager = SyncManagerImpl(
            cryptoManager = testProxy.tContext.syncCryptoManager(),
            storageDelegate = InMemorySecureStorageDelegate(),
            context = testProxy.tContext,
            objectStore = objectStore,
            onLocalStoreWiped = { error("the derived store is unreachable") },
        )
        objectStore.storeObject(ObjectStore.DbEntry(type = "t", dataIdHash = 1L, fields = emptyList()))

        assertTrue(manager.wipeLocalStore())
        assertTrue(objectStore.isEmpty(), "the rows still went")
    }

    /**
     * ⚠⚠ The capability is optional, so the honest answer for a store that cannot wipe is `false` —
     * never a silent success. A caller that assumed success would print "forgotten" over exactly the
     * state this exists to prevent.
     */
    @Test
    fun aStoreWithoutTheCapabilityReportsFalseRatherThanPretending() = runTest {
        val plain = object : ObjectStore {
            override fun storeObject(dbEntry: ObjectStore.DbEntry) = Unit
            override fun getObject(dataIdHash: Long): ObjectStore.DbEntry? = null
            override fun setListener(dataIdHash: Long, listener: (ObjectStore.DbEntry) -> Unit) = Unit
            override fun removeListener(dataIdHash: Long) = Unit
            override fun removeObject(dataIdHash: Long) = Unit
        }
        // ⭐ No assertion that the fixture lacks the capability — the compiler rejects one as
        // statically false, which is a stronger guarantee than a runtime check could give.

        val manager = SyncManagerImpl(
            cryptoManager = testProxy.tContext.syncCryptoManager(),
            storageDelegate = InMemorySecureStorageDelegate(),
            context = testProxy.tContext,
            objectStore = plain,
        )

        assertFalse(
            manager.wipeLocalStore(),
            "a store that cannot wipe must say so — silence here is how a half reset ships",
        )
    }

    /** The production stores carry the capability; a regression here is what makes the wipe inert. */
    @Test
    fun theInMemoryStoreCarriesTheCapability() {
        assertIs<WipeableObjectStore>(objectStore)
    }

    /**
     * ⚠⚠ The hook runs **after** the rows are gone, not before — and the ordering is invisible in
     * the end state, so only a hook that looks at the store while it runs can pin it. A derived store
     * cleared first would be free to repopulate from rows the wipe has not yet removed.
     */
    @Test
    fun theDerivedStoreIsClearedAfterTheRowsAreGone() = runTest {
        var storeWasEmptyWhenTheHookRan: Boolean? = null
        val manager = SyncManagerImpl(
            cryptoManager = testProxy.tContext.syncCryptoManager(),
            storageDelegate = InMemorySecureStorageDelegate(),
            context = testProxy.tContext,
            objectStore = objectStore,
            onLocalStoreWiped = { storeWasEmptyWhenTheHookRan = objectStore.isEmpty() },
        )
        objectStore.storeObject(ObjectStore.DbEntry(type = "t", dataIdHash = 1L, fields = emptyList()))

        assertTrue(manager.wipeLocalStore())

        assertEquals(true, storeWasEmptyWhenTheHookRan, "the hook must see a store already wiped")
    }

    /**
     * ⚠⚠ A cancellation is the caller's to see. `runCatching` swallows `CancellationException` along
     * with everything else, so a hook cancelled while suspended would report a wipe that cleared the
     * derived store when it did not — from a coroutine that is no longer running.
     */
    @Test
    fun aCancelledHookIsNotReportedAsASuccessfulWipe() = runTest {
        val manager = SyncManagerImpl(
            cryptoManager = testProxy.tContext.syncCryptoManager(),
            storageDelegate = InMemorySecureStorageDelegate(),
            context = testProxy.tContext,
            objectStore = objectStore,
            onLocalStoreWiped = { throw CancellationException("the session went away") },
        )

        assertFailsWith<CancellationException> { manager.wipeLocalStore() }
    }
}
