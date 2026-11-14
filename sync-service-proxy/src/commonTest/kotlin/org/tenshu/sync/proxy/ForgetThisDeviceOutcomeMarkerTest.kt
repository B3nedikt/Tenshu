package org.tenshu.sync.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.Data
import org.tenshu.core.TString
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createSyntheticTestRegistry
import org.tenshu.core.common.delegate.InMemorySecureStorageDelegate
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.db.InMemoryObjectStore
import org.tenshu.core.common.proxy.executor.AsyncOperationImplementation
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.security.syncCryptoManager
import org.tenshu.sync.proxy.impl.ForgetThisDeviceImplementation
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import org.tenshu.core.common.delegate.SecureStorageDelegate
import kotlin.test.assertTrue

/**
 * The **`⚠` marker contract** between this op and Control's Welcome-screen notice.
 *
 * ⚠⚠ `ForgetThisDevice` is deliberately partially-failable, so its outcome string is the only thing
 * that can tell a user a row was left behind in their realm — and the remedy is always *"from
 * another device"*, which they no longer have in front of them. Control decides whether to surface
 * it by looking for `⚠` (`WelcomeScreen.kt`'s `forgetNoticesFor`), so **which outcomes carry the marker is a
 * contract, not a formatting choice.**
 *
 * ⚠ Pinned from the *producing* side on purpose. A test on the consumer would only prove that
 * `contains('⚠')` works; the way this breaks in practice is someone rewording an outcome here and
 * dropping (or adding) the marker, at which point the notice silently shows nothing — or shows on
 * every clean removal, on a fresh-start screen, which trains people to ignore it.
 */
class ForgetThisDeviceOutcomeMarkerTest {

    private lateinit var testProxy: TestProxy
    private lateinit var syncManager: SyncManagerImpl

    @BeforeTest
    fun setup() {
        testProxy = TestProxy(schemaRegistry = createSyntheticTestRegistry())
        syncManager = SyncManagerImpl(
            cryptoManager = testProxy.tContext.syncCryptoManager(),
            storageDelegate = InMemorySecureStorageDelegate(),
            context = testProxy.tContext,
            objectStore = InMemoryObjectStore(),
        )
        syncManager.initialize("mock phrase")
    }

    private class FakeRevoke(
        private val onRelayStripFailed: () -> Unit,
        private val stripFails: Boolean,
    ) : AsyncOperationImplementation {
        @Suppress("UNCHECKED_CAST")
        override suspend fun <T : Data?> execute(
            executionContext: ExecutionContext,
            operationContext: OperationContext?,
            implementationFactory: OperationImplementationFactory,
            caller: Any?,
            params: Map<String, Data>?,
        ): T {
            if (stripFails) onRelayStripFailed()
            return null as T
        }
    }

    private suspend fun outcomeOf(stripFails: Boolean): String {
        var ticks = 0L
        val impl = ForgetThisDeviceImplementation(
            syncManager = syncManager,
            revokeDevice = { onStripFailed -> FakeRevoke(onStripFailed, stripFails) },
            nowMs = { ticks += 150L; ticks },
        )
        return impl.execute<TString>(
            executionContext = testProxy.executionContext,
            operationContext = null,
            implementationFactory = testProxy.executionContext.implementationFactory,
            caller = null,
            params = null,
        ).get().orEmpty()
    }

    /** ⚠ A retirement we could not confirm MUST carry the marker, or the user is never told. */
    @Test
    fun anUnconfirmedRetirement_carriesTheMarker() = runTest {
        val outcome = outcomeOf(stripFails = true)
        assertTrue(
            outcome.contains('⚠'),
            "Control surfaces the Welcome notice on this marker alone — without it a device left in " +
                "the realm is announced to nobody. Got: $outcome",
        )
    }

    /**
     * ⚠⚠ The other half, and the one that rots quietly: a **clean** removal must NOT carry it.
     * A warning banner on every successful fresh start is how people learn to ignore warnings.
     */
    @Test
    fun aCleanRemoval_carriesNoMarker() = runTest {
        val outcome = outcomeOf(stripFails = false)
        assertTrue(outcome.contains("removed from your realm"), "precondition — got: $outcome")
        assertFalse(
            outcome.contains('⚠'),
            "a clean removal must stay silent on the Welcome screen. Got: $outcome",
        )
    }

    /**
     * ⚠⚠ A new key that could not be saved: marked, under the phrase Control's Welcome notice
     * matches (`FORGET_OUTCOME_KEY_NOT_SAVED`), and **not** "not finished" — that one says custody
     * was kept, and here it is already gone. The store note travels beside it.
     */
    @Test
    fun aKeyThatCouldNotBeSaved_carriesItsOwnPhrase_andTheStoreNote() = runTest {
        val inner = InMemorySecureStorageDelegate()
        val failing = object : SecureStorageDelegate by inner {
            var fail = false
            override fun storeSecret(key: String, value: String) {
                check(!(fail && key == SyncManagerImpl.Keys.DEVICE_SIGNING_KEY)) { "made up: refused" }
                inner.storeSecret(key, value)
            }
        }
        val manager = SyncManagerImpl(
            cryptoManager = testProxy.tContext.syncCryptoManager(),
            storageDelegate = failing,
            context = testProxy.tContext,
            objectStore = InMemoryObjectStore(),
        )
        manager.currentDeviceId
        failing.fail = true
        val outcome = ForgetThisDeviceImplementation(
            syncManager = manager,
            revokeDevice = { onStripFailed -> FakeRevoke(onStripFailed, stripFails = false) },
            nowMs = { 0L },
        ).execute<TString>(testProxy.executionContext, null, testProxy.executionContext.implementationFactory, null, null)
            .get().orEmpty()

        assertTrue(outcome.contains("⚠ new device key NOT saved"), "Welcome matches this phrase. Got: $outcome")
        assertFalse(outcome.contains("not finished"), "custody is gone — this is not the kept-custody outcome. Got: $outcome")
        assertTrue(outcome.contains("local store"), "the store note is still reported. Got: $outcome")
    }
}
