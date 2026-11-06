package org.tenshu.sdk.ios

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.tenshu.core.collection.list.flow
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.delegate.InMemorySecureStorageDelegate
import org.tenshu.core.getOrNull
import org.tenshu.sync.RuntimeIdentity
import org.tenshu.sync.client.runtimeIdentity
import org.tenshu.sync.client.syncService
import org.tenshu.sync.isControlHosted
import platform.Foundation.NSTemporaryDirectory
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * The per-writer `RuntimeIdentity` row over one replica that several route-1 apps open at once,
 * each through its own full assembly — its own store handle over the shared rows of one
 * test replica (`TestReplicas`), as two apps of an App Group build are to each other.
 *
 * ⚠⚠ A single unkeyed row is wrong in exactly one shape: open one app, then another, and the
 * first app's banner names the second. A test with one runtime cannot see that, because the
 * last writer is then always the reader.
 */
class RuntimeIdentitySharedReplicaTest {

    private class Process(clientId: String, directory: String) {
        private val runtime = FirstPartyLocalRuntime(
            FirstPartyLocalRuntime.Config(clientId = clientId, secureStorageService = "test.$clientId"),
        )
        val base = runtime.assembleBase(
            replicaDirectory = directory,
            secureStorage = InMemorySecureStorageDelegate(),
        )

        /** This app's own row, as its banner reads it — exactly the call the app runtimes make. */
        suspend fun ownIdentity(): RuntimeIdentity? =
            withTimeout(30_000) { base.tenshuClient.syncService.runtimeIdentity(base.hostAppId).first() }

        /**
         * Brings the sync runtime up and waits until it has published. ⚠ Waits on *a* row rather
         * than this app's own, so a writer that keys the row wrongly fails at the assertions that
         * name the defect instead of timing out here.
         */
        suspend fun launched(): Process = apply {
            withTimeout(30_000) {
                base.tenshuClient.syncService.runtimeIdentities.flow()
                    .first { it.getOrNull()?.toList()?.isNotEmpty() == true }
            }
        }

        /** The foreground return. Nothing to refresh: every read goes to the shared rows. */
        fun foreground() = Unit
    }

    private fun directory() =
        NSTemporaryDirectory() + "runtime-identity-shared-replica-" + Random.nextLong().toString(16)

    @Test
    fun controlLaunchedLast_overTheSameReplica_doesNotTellFitThatControlHostsIt() = runBlocking {
        val dir = directory()
        val fit = Process(FIT, dir).launched()
        Process(RuntimeIdentity.CONTROL_APP_ID, dir).launched()

        fit.foreground() // Fit now sees Control's write on disk
        val identity = fit.ownIdentity()

        assertNotNull(identity, "Fit's own row survived Control's launch")
        assertEquals(false, identity.isControlHosted(), "Fit's own proxy hosts Fit")
        assertEquals(FIT, identity.appId.get())
    }

    @Test
    fun eachOfThreeApps_readsItsOwnRow_whicheverLaunchedLast() = runBlocking {
        val dir = directory()
        val fit = Process(FIT, dir).launched()
        val maps = Process(MAPS, dir).launched()
        val control = Process(RuntimeIdentity.CONTROL_APP_ID, dir).launched()

        listOf(fit, maps, control).forEach { it.foreground() }

        assertEquals(FIT, fit.ownIdentity()?.appId?.get())
        assertEquals(MAPS, maps.ownIdentity()?.appId?.get())
        assertEquals(false, maps.ownIdentity().isControlHosted())
        assertEquals(true, control.ownIdentity().isControlHosted())
    }

    private companion object {
        const val FIT = "app.tenshu.fit.ios"
        const val MAPS = "app.tenshu.maps.ios"
    }
}
