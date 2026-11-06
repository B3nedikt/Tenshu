package org.tenshu.sync.proxy

import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createCoreTestRegistry
import org.tenshu.core.common.delegate.InMemorySecureStorageDelegate
import org.tenshu.core.security.BootstrapSyncCryptoManager
import org.tenshu.sync.SyncStatus
import org.tenshu.sync.common.syncServiceCommonModule
import org.tenshu.sync.proxy.impl.GetSyncStatusImplementation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Pins the resume-across-restart contract (`docs/proposals/sync-pairing-flow-improvements.md`
 * follow-up): `GetSyncStatus` reflects an in-flight *host* pairing session read-only so a
 * recreated `SyncDashboardViewModel` (Control window closed → reopened) can resume polling
 * instead of sitting idle until the user re-clicks "Add device".
 *
 * The session lives in the proxy's `SyncManager`; the op must report its live pairing string
 * **without** generating/mutating one (that's StartPairing's job). These tests drive the op
 * against a real `SyncManagerImpl` and assert: null before any session, the live code after
 * `generatePairingContext()`, and null again after `stopPairing()`.
 */
class GetSyncStatusPairingStringTest {

    private val testProxy =
        TestProxy(schemaRegistry = createCoreTestRegistry()).also {
            it.schemaRegistry.registerConfig(syncServiceCommonModule)
        }

    private fun syncManager(): SyncManagerImpl =
        SyncManagerImpl(
            cryptoManager = BootstrapSyncCryptoManager(),
            storageDelegate = InMemorySecureStorageDelegate(),
            context = testProxy.tContext,
        )

    /** Run the GetSyncStatus op against [sm] and return the produced status. */
    private fun runGetSyncStatus(sm: SyncManagerImpl): SyncStatus {
        val status = GetSyncStatusImplementation(sm).execute<SyncStatus>(
            executionContext = testProxy.executionContext,
            operationContext = null,
            implementationFactory = testProxy.executionContext.implementationFactory,
            caller = null,
            params = null,
        )
        return status
    }

    @Test
    fun reportsNoPairingString_beforeAnySession() {
        val sm = syncManager()
        sm.initialize("test recovery phrase placeholder words here")

        assertNull(
            runGetSyncStatus(sm).pairingString,
            "no host pairing session is active, so the status must not report a code",
        )
    }

    @Test
    fun reportsLivePairingString_whileSessionActive() {
        val sm = syncManager()
        sm.initialize("test recovery phrase placeholder words here")

        sm.generatePairingContext()
        val expected = sm.getPairingString()
        assertNotNull(expected, "a generated context yields a pairing string")

        assertEquals(
            expected,
            runGetSyncStatus(sm).pairingString?.get(),
            "getSyncStatus must reflect the in-flight host session's live pairing code",
        )
    }

    @Test
    fun reportsNoPairingString_afterStopPairing() {
        val sm = syncManager()
        sm.initialize("test recovery phrase placeholder words here")

        sm.generatePairingContext()
        assertNotNull(runGetSyncStatus(sm).pairingString, "session active → code present")

        sm.stopPairing()
        assertNull(
            runGetSyncStatus(sm).pairingString,
            "ending the session clears the reported pairing code",
        )
    }
}
