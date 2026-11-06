package org.tenshu.sync.proxy

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.tenshu.core.Data
import org.tenshu.core.TByteArray
import org.tenshu.core.TResult
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createCoreTestRegistry
import org.tenshu.core.common.createTestContext
import org.tenshu.core.common.delegate.InMemorySecureStorageDelegate
import org.tenshu.core.entity.DataId
import org.tenshu.core.file.sync.DeclarableContent
import org.tenshu.core.security.BootstrapSyncCryptoManager
import org.tenshu.core.security.SyncCryptoManager
import org.tenshu.identity.proxy.initIdentityService
import org.tenshu.identity.common.identityServiceCommonModule
import org.tenshu.sync.common.ContentBlobStore
import org.tenshu.sync.common.ContentDescription
import org.tenshu.sync.common.SyncManager
import org.tenshu.sync.common.SyncRelay
import org.tenshu.sync.common.syncServiceCommonModule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The declaration backfill's wiring (`content-plane-refcount.md` §9.7): `initSyncService` builds the
 * backfill from its sources, and the scheduler's entry point runs it.
 *
 * ⚠ The pass only describes, and a client store has no way to free anything: freeing is the relay
 * sweep's.
 *
 * ⚠⚠ What these pin is the seam, not the arithmetic — [ContentDeclarationBackfill]'s own test owns
 * the batching and the marker. The seam matters separately because the pass is **only** reachable
 * through a scheduled op: an unwired backfill is completely silent, and the corpus it exists to
 * describe stays undescribed with nothing saying so.
 */
class ContentBackfillWiringTest {

    private class RecordingBlobStore : ContentBlobStore {
        override suspend fun putChunk(ref: String, ciphertext: ByteArray): TResult<Data?> =
            error("the backfill writes no bytes")

        // ⚠ The relay's 404, said explicitly. The interface default is `null` ("cannot tell"), and a
        // store that cannot tell must keep the backfill incomplete: this harness holds no content.
        override suspend fun hasChunk(ref: String): Boolean? = false

        override suspend fun getChunk(ref: String): TResult<TByteArray> =
            error("the backfill reads no chunks — only manifests, through chunkRefsOf")

        /** Every description the pass sent (⭐ the only thing it sends). */
        val describedContents = mutableListOf<ContentDescription>()

        override suspend fun describeContent(contents: List<ContentDescription>): Boolean {
            describedContents += contents
            return true
        }
    }

    /**
     * A realm this build created, recovered or joined was described as it uploaded, so the pass is
     * complete from the start: no walk, no manifest fetch, ever.
     */
    @Test
    fun aRealmBornDescribedNeverRunsThePass() = runTest {
        var walks = 0
        val proxy = setup(
            scope = backgroundScope,
            store = RecordingBlobStore(),
            sources = ReclamationSources(
                declarableContent = {
                    walks++
                    DeclarableContent.Result(entries = emptyList(), walkComplete = true)
                },
                readBackfillComplete = { false },
                writeBackfillComplete = { },
            ),
            describedFromGenesis = true,
        )

        proxy.scheduler.backfillContentDeclarations()

        assertEquals(0, walks, "a realm described from genesis has nothing to backfill")
    }

    /** And `initialize` is what stamps it: genesis by this build. */
    @Test
    fun genesisStampsTheRealmDescribed() {
        val m = SyncManagerImpl(
            cryptoManager = BootstrapSyncCryptoManager(),
            storageDelegate = InMemorySecureStorageDelegate(),
            context = createTestContext(createCoreTestRegistry().apply { registerConfig(syncServiceCommonModule) }),
        )
        assertEquals(false, m.contentDescribedFromGenesis)
        m.initialize("genesis-stamp-realm")
        assertEquals(true, m.contentDescribedFromGenesis)
    }

    @Test
    fun theSchedulersEntryPointRunsThePass() = runTest {
        val store = RecordingBlobStore()
        var markedComplete: Boolean? = null
        val proxy = setup(
            scope = backgroundScope,
            store = store,
            sources = ReclamationSources(
                declarableContent = {
                    DeclarableContent.Result(
                        entries = listOf(DeclarableContent.Entry(holderH1, "p1", "c1")),
                        walkComplete = true,
                    )
                },
                readBackfillComplete = { false },
                writeBackfillComplete = { markedComplete = it },
            ),
        )

        proxy.scheduler.backfillContentDeclarations()

        // ⚠ No manifest resolves in this harness (there is no relay content), so there is nothing to
        // describe — and that does NOT block the marker: completeness asks "did I
        // see everything?", not "was everything describable?" (§9.11). The walk finished, so the pass
        // is entitled to record itself done.
        assertTrue(store.describedContents.isEmpty())
        assertEquals(
            true,
            markedComplete,
            "the marker is written through the REAL wiring, which is what this test adds over the " +
                "unit one",
        )
    }

    /**
     * `control-background-work-off-fgs.md` §5.6: the pass runs under its own WorkManager job, which
     * the OS stops at its 10-minute limit or when a constraint is lost. A stopped pass must let go of
     * the single-flight lock as it unwinds, or the job's continuation is turned away as "a pass is
     * already running" — and it must raise no liveness key: nothing holds the process for it but its job.
     */
    @Test
    fun aStoppedPassReleasesItsLock_soTheJobsContinuationRunsTheNextOne() = runTest {
        val liveness = org.tenshu.core.common.proxy.BackgroundLiveness()
        val sourcesDuringWalk = mutableListOf<Set<String>>()
        var walks = 0
        var gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val proxy = setup(
            scope = backgroundScope,
            store = RecordingBlobStore(),
            sources = ReclamationSources(
                declarableContent = {
                    walks++
                    sourcesDuringWalk += liveness.activeSources.value
                    gate.await()
                    DeclarableContent.Result(entries = emptyList(), walkComplete = true)
                },
                readBackfillComplete = { false },
                writeBackfillComplete = { },
            ),
            backgroundLiveness = liveness,
        )

        val stopped = launch { proxy.scheduler.backfillContentDeclarations() }
        kotlinx.coroutines.yield()
        assertEquals(1, walks)
        stopped.cancel(); stopped.join()   // the OS stops the job

        gate = kotlinx.coroutines.CompletableDeferred<Unit>().also { it.complete(Unit) }
        proxy.scheduler.backfillContentDeclarations()   // WorkManager's rerun
        assertEquals(2, walks, "the continuation must run a pass, not be skipped behind the stopped one")
        assertEquals(listOf(emptySet<String>(), emptySet()), sourcesDuringWalk, "no liveness key: the job is what holds the pass")
    }

    /** The scheduled op and a manual trigger share one entry point; the second caller is turned away. */
    @Test
    fun aSecondPassWhileOneRunsIsSkipped_notRunAlongside() = runTest {
        var walks = 0
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val proxy = setup(
            scope = backgroundScope,
            store = RecordingBlobStore(),
            sources = ReclamationSources(
                declarableContent = {
                    walks++
                    gate.await()
                    DeclarableContent.Result(entries = emptyList(), walkComplete = true)
                },
                readBackfillComplete = { false },
                writeBackfillComplete = { },
            ),
        )

        val first = launch { proxy.scheduler.backfillContentDeclarations() }
        kotlinx.coroutines.yield()
        proxy.scheduler.backfillContentDeclarations()   // returns at once
        assertEquals(1, walks, "the second call must not start a second walk")
        gate.complete(Unit); first.join()

        proxy.scheduler.backfillContentDeclarations()
        assertEquals(2, walks, "and the next pass runs once the first has ended")
    }

    @Test
    fun withoutADeclarableContentSource_thePassIsSilentlyAbsent() = runTest {
        // ⚠ Inert by absence: a platform that wires no
        // enumeration builds no backfill, and the scheduler's entry point is a no-op rather than a
        // crash.
        val store = RecordingBlobStore()
        val proxy = setup(
            scope = backgroundScope,
            store = store,
            sources = ReclamationSources(),
        )

        proxy.scheduler.backfillContentDeclarations()

        assertTrue(store.describedContents.isEmpty())
    }

    private class Proxy(val scheduler: SyncScheduler, val components: SyncProxyComponents, val testProxy: TestProxy)

    private val holderH1 = DataId.StringDataId.create(TestProxy().tContext, "tenshu/file/system/file", "/h1")

    private fun setup(
        scope: CoroutineScope,
        store: ContentBlobStore,
        sources: ReclamationSources,
        backgroundLiveness: org.tenshu.core.common.proxy.BackgroundLiveness? = null,
        describedFromGenesis: Boolean = false,
    ): Proxy {
        val registry = createCoreTestRegistry().apply {
            registerConfig(syncServiceCommonModule)
            registerConfig(identityServiceCommonModule)
        }
        val relay = MockSyncRelay(createTestContext(registry))
        lateinit var syncManager: SyncManager
        lateinit var crypto: SyncCryptoManager
        val proxy = TestProxy(
            schemaRegistry = createCoreTestRegistry().also {
                it.registerConfig(syncServiceCommonModule)
                it.registerConfig(identityServiceCommonModule)
            },
            additionalComponents = mapOf(SyncRelay.ID to relay),
            coroutineScope = scope,
        ) { id, _ -> syncProxyImplementationFactory(id, relay, syncManager, crypto) }
        crypto = BootstrapSyncCryptoManager()
        syncManager = SyncManagerImpl(
            cryptoManager = crypto,
            storageDelegate = InMemorySecureStorageDelegate(),
            context = proxy.tContext,
        )
        syncManager.initialize("content-backfill-wiring-realm")
        proxy.executionContext.initIdentityService()
        val components = proxy.executionContext.initSyncService(
            syncManager = syncManager,
            cryptoManager = crypto,
            coroutineScope = scope,
            pollingIntervalMs = 100,
            startBackgroundLoops = false,
            contentBlobStore = store,
            reclamationSources = sources,
            backgroundLiveness = backgroundLiveness,
            platformScheduler = NoOpPlatformScheduler,
        )
        // ⚠⚠ The marker only latches on a device that has finished catching up, and `0` is
        // "walked to the bottom". ⚠ Set AFTER `initSyncService`, not before: init rebinds the
        // object store that backs this property, so a cursor written earlier is gone by the time
        // the pass reads it.
        syncManager.backfillCursor = 0L
        // A realm from before the declaration seam: genesis by this build would mark it described.
        (syncManager as SyncManagerImpl).contentDescribedFromGenesis = describedFromGenesis
        return Proxy(components.scheduler, components, proxy)
    }
}
