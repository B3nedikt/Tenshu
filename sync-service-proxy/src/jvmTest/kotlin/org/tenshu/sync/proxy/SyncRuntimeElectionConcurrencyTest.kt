package org.tenshu.sync.proxy

import kotlinx.coroutines.runBlocking
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createSyntheticTestRegistry
import org.tenshu.core.common.delegate.InMemorySecureStorageDelegate
import org.tenshu.core.security.syncCryptoManager
import org.tenshu.sync.common.syncServiceCommonModule
import java.util.Collections
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * One sync runtime per process must hold when the sessions arrive **at once**
 * (`drive-upload-progress.md` §7; [SyncRuntimeHolder]).
 *
 * [SyncRuntimeElectionTest] proves the reuse one session after another. Production does not arrive
 * one after another: when Control restarts, every running first-party app rebinds in the same
 * instant, and each session's platform init runs on its own binder thread. The election was a
 * check at the top of `initSyncService` and a publish at the bottom, so every session that checked
 * before the first one published built its own runtime.
 *
 * Measured on a phone (2026-10-30): five schedulers ticking in one Control process after a restart
 * with five apps open. Their tick-ends overwrote the one status row each second, so a forced
 * "Upload over cellular" read as *Waiting for Wi-Fi* for the whole upload, and each held its own
 * emit-key table, so on Wi-Fi they could upload one parked file several times.
 */
class SyncRuntimeElectionConcurrencyTest {

    @Test
    fun sessionsArrivingAtOnceShareOneScheduler() {
        // A single collision proves little either way; rounds make an open window near-certain to show.
        repeat(ROUNDS) { round ->
            val proxy = TestProxy(schemaRegistry = createSyntheticTestRegistry())
                .also { it.schemaRegistry.registerConfig(syncServiceCommonModule) }
            val crypto = proxy.tContext.syncCryptoManager()
            val syncManager = SyncManagerImpl(
                cryptoManager = crypto,
                storageDelegate = InMemorySecureStorageDelegate(),
                context = proxy.tContext,
            ).also { it.initialize("mock phrase") }
            val holder = SyncRuntimeHolder()
            // Minted up front, so the only thing the threads race on is the election itself.
            val sessions = (0 until SESSIONS).map { proxy.clientRegistry.getExecutionContext("app-$it") }
            val built = Collections.synchronizedList(mutableListOf<SyncProxyComponents>())
            val barrier = CyclicBarrier(SESSIONS)
            val pool = Executors.newFixedThreadPool(SESSIONS)
            try {
                sessions.map { session ->
                    pool.submit {
                        barrier.await()
                        built += session.initSyncService(
                            syncManager,
                            crypto,
                            runtimeHolder = holder,
                            platformScheduler = NoOpPlatformScheduler,
                        )
                    }
                }.forEach { it.get(30, TimeUnit.SECONDS) }

                assertEquals(
                    1,
                    built.map { it.scheduler }.distinct().size,
                    "round $round: $SESSIONS sessions arriving together must elect ONE scheduler",
                )
                assertEquals(
                    SESSIONS,
                    built.map { it.syncObserver }.distinct().size,
                    "round $round: each session still gets its own observer",
                )
            } finally {
                pool.shutdownNow()
                built.forEach {
                    it.scheduler.stop()
                    it.scheduleDrainLoop.stop()
                }
                runBlocking { proxy.shutdown() }
            }
        }
    }

    private companion object {
        const val SESSIONS = 6
        const val ROUNDS = 10
    }
}
