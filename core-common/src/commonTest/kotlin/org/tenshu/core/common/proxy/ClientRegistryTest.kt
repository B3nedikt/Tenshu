package org.tenshu.core.common.proxy

import org.tenshu.core.Data
import org.tenshu.core.PureOperationRunner
import org.tenshu.core.common.createCoreTestRegistry
import org.tenshu.core.common.relation.RelationMaintainer
import org.tenshu.core.common.createTestContext
import org.tenshu.core.entity.DataId
import kotlinx.coroutines.test.runTest
import org.tenshu.core.trait.traitResolver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ClientRegistryTest {

    @Test
    fun getExecutionContext_returnsSameInstanceForSameIdentity() {
        val registry = createRegistry()
        val context1 = registry.getExecutionContext("client1", "session1")
        val context2 = registry.getExecutionContext("client1", "session1")
        assertSame(context1, context2)
    }

    @Test
    fun getExecutionContext_returnsDifferentInstanceAndDestroysOldOneForNewIdentity() {
        var destroyCalled = false
        val registry = createRegistry(
            connectionFactory = {
                object : ClientConnection {
                    override fun init() {}
                    override fun send(data: Data) {}
                    override fun destroy() {
                        destroyCalled = true
                    }
                }
            }
        )

        val context1 = registry.getExecutionContext("client1", "session1")
        val context2 = registry.getExecutionContext("client1", "session2")

        assertNotSame(context1, context2)
        assertEquals(true, destroyCalled, "Old context should be destroyed")
    }

    @Test
    fun unregisterClient_destroysContext() {
        var destroyCalled = false
        val registry = createRegistry(
            connectionFactory = {
                object : ClientConnection {
                    override fun init() {}
                    override fun send(data: Data) {}
                    override fun destroy() {
                        destroyCalled = true
                    }
                }
            }
        )

        registry.getExecutionContext("client1", "session1")
        registry.unregisterClient("client1")

        assertEquals(true, destroyCalled)
    }

    /**
     * The blast-radius guard for the 2026-08-21 IPC audit item #2. Android's
     * `ProxyService.onUnbind` used to call `unregisterAll()`, which drains every session the
     * registry holds — including the proxy's own in-process client and any EPC-paired peer,
     * neither of which is a Binder client. Releasing one client must release exactly one.
     */
    @Test
    fun unregisterClient_leavesEveryOtherSessionAlive() {
        val destroyed = mutableListOf<String>()
        val registry = createRegistry(
            connectionFactory = { clientId ->
                object : ClientConnection {
                    override fun init() {}
                    override fun send(data: Data) {}
                    override fun destroy() {
                        destroyed += clientId
                    }
                }
            }
        )

        val bound = registry.getExecutionContext("app.tenshu.drive.android", "pid-1")
        val inProcess = registry.getExecutionContext("app.tenshu.control.android", "local")
        val epc = registry.getExecutionContext("epc-peer-1", "epc-session")

        registry.unregisterClient("app.tenshu.drive.android")

        assertEquals(listOf("app.tenshu.drive.android"), destroyed)
        // Still the *same* live sessions, not freshly minted replacements.
        assertSame(inProcess, registry.getExecutionContext("app.tenshu.control.android", "local"))
        assertSame(epc, registry.getExecutionContext("epc-peer-1", "epc-session"))
        // And the released one really was released: a re-bind mints a new context.
        assertNotSame(bound, registry.getExecutionContext("app.tenshu.drive.android", "pid-1"))
    }

    /**
     * A proxy-side implementation must be able to scope per-client state to the caller it is
     * running for (audit item #2's other half: the Android upload path's descriptor cache).
     * The identity has to survive `withEvaluationScope`, because operations execute on a
     * per-request context rather than on the session one.
     */
    @Test
    fun clientId_reachesTheSessionContextAndSurvivesWithEvaluationScope() = runTest {
        val registry = createRegistry()

        val session = registry.getExecutionContext("app.tenshu.drive.android", "pid-1")
        assertEquals("app.tenshu.drive.android", session.clientId)

        val perRequest = session.withEvaluationScope(this)
        assertEquals("app.tenshu.drive.android", perRequest.clientId)
    }

    @Test
    fun init_isCalledOnNewContext() {
        var initCount = 0
        val registry = createRegistry(
            connectionFactory = {
                object : ClientConnection {
                    override fun init() {
                        initCount++
                    }

                    override fun send(data: Data) {}
                    override fun destroy() {}
                }
            }
        )

        registry.getExecutionContext("client1", "session1")
        assertEquals(1, initCount)

        registry.getExecutionContext("client1", "session1")
        assertEquals(1, initCount, "Should not re-init for same session")

        registry.getExecutionContext("client1", "session2")
        assertEquals(2, initCount, "Should init for new session")
    }

    @Test
    fun onSessionEstablished_firesOncePerNewSession_notOnCacheHit() {
        val established = mutableListOf<String>()
        val registry = createRegistry(
            onSessionEstablished = { clientId, _ -> established.add(clientId) }
        )

        // New session → fires.
        registry.getExecutionContext("client1", "session1")
        assertEquals(listOf("client1"), established)

        // Same identity (cache hit) → does not fire again.
        registry.getExecutionContext("client1", "session1")
        assertEquals(listOf("client1"), established, "cache hit must not re-fire the hook")

        // Identity change (e.g. new PID) mints a fresh session → fires again.
        registry.getExecutionContext("client1", "session2")
        assertEquals(listOf("client1", "client1"), established)

        // A distinct client → fires for it too.
        registry.getExecutionContext("client2", "session1")
        assertEquals(listOf("client1", "client1", "client2"), established)
    }

    @Test
    fun quiesceSessionsAndJoin_keepsTheRegistryOpen_forFreshSessions() = runTest {
        val registry = createRegistry()
        val before = registry.getExecutionContext("client1")

        // The Android ProxyService.onDestroy path: sessions quiesced, process
        // (and registry) live on.
        registry.quiesceSessionsAndJoin()

        val after = registry.getExecutionContext("client1")
        assertNotSame(before, after, "a fresh session is minted after a quiesce")
    }

    @Test
    fun shutdownAndJoin_closesTheRegistry_againstNewSessions() = runTest {
        val registry = createRegistry()
        registry.getExecutionContext("client1")

        registry.shutdownAndJoin()

        assertFailsWith<IllegalStateException>("a final shutdown must refuse to resurrect sessions") {
            registry.getExecutionContext("client1")
        }
    }

    @Test
    fun shutdown_closesTheRegistry_againstNewSessions() {
        val registry = createRegistry()
        registry.getExecutionContext("client1")

        registry.shutdown()

        assertFailsWith<IllegalStateException> {
            registry.getExecutionContext("client1")
        }
    }

    /**
     * The `initialization` hook starts background work that re-enters
     * [ClientRegistry.getExecutionContext] for the same clientId — the
     * in-process `SessionAwareLocalTaskExecutor` session provider does exactly
     * this. That re-entry must find the session being built, not mint a second
     * one: each extra session carries a full set of background loops (sync
     * scheduler, schedule-drain, sync-health collector) and only the last one
     * registered is reachable for teardown, so the rest tick on forever against
     * DI singletons that shutdown has already dropped.
     */
    @Test
    fun getExecutionContext_reentrantLookupDuringInitialization_reusesTheSameSession() {
        val seen = mutableListOf<ExecutionContext>()
        lateinit var registry: ClientRegistry
        registry = createRegistry(
            initialization = {
                // Re-enter exactly as the in-process executor's sessionProvider does.
                seen += registry.getExecutionContext("client1")
            }
        )

        val context = registry.getExecutionContext("client1")

        // ⚠ `seen` has TWO entries since P7 (`drive-upload-progress.md`): initialisation runs for
        // the proxy's own system session as well as for client1, and both re-enter. The claim this
        // test protects is unchanged and is about identity, not count — every re-entrant lookup
        // must land on the SAME session rather than minting a parallel one, because each extra
        // session carries a full set of background loops that nothing can reach for teardown.
        assertEquals(
            1,
            seen.distinct().size,
            "re-entrant lookups must all reuse one session, never mint parallel ones",
        )
        assertSame(context, seen.first(), "re-entrant lookup must reuse the session being built")
    }

    /**
     * P7 (`drive-upload-progress.md`): the proxy's own session, for subsystems whose lifetime is
     * the process rather than any app's binding — today the sync runtime.
     */
    @Test
    fun systemContext_isStableAcrossClientSessionsAndTheirTeardown() {
        val registry = createRegistry(mintSystemSession = true)

        val system = registry.systemContext()
        assertNotNull(system, "the proxy's own session must exist")

        registry.getExecutionContext("client1")
        assertSame(system, registry.systemContext(), "minting a client must not replace it")

        registry.unregisterClient("client1")
        assertSame(
            system,
            registry.systemContext(),
            "a client disconnecting must not take the proxy's session with it — that is the whole " +
                "point: the sync runtime used to die exactly here",
        )
    }

    @Test
    fun systemContext_survivesQuiesceButNotShutdown() {
        val registry = createRegistry(mintSystemSession = true)
        val system = registry.systemContext()
        registry.getExecutionContext("client1")

        registry.clear()
        assertSame(
            system,
            registry.systemContext(),
            "quiescing sessions keeps the process alive (Android onDestroy), so the proxy's own " +
                "session must survive it",
        )

        registry.shutdown()
        assertNull(
            registry.systemContext(),
            "after a final shutdown nothing may resurrect proxy loops against dropped collaborators",
        )
    }

    /**
     * ⚠⚠ **The session-init window.** `mintSession` publishes a session's context — making it
     * reachable and able to serve ops — **before** `runInitialization()`, and that ordering is
     * load-bearing (the hook re-enters `getExecutionContext`). But `runInitialization()` is what
     * used to run every `init<X>Service()` and therefore `installRelationMaintainer()`.
     *
     * So a save served in that window fanned out to an observer registry with **no**
     * `RelationMaintainer`: the entity was written and never added to its service relation, and
     * nothing retried, because a save is not re-observed. The row stayed on disk and invisible
     * forever — `ContactsLocalRuntimeTest` failed ~7 in 10 whole-class runs on exactly this, and
     * `hasMaintainer` measured inside `saveData` correlated perfectly with which rows were lost
     * (`docs/tech-debt.md`, "a save during the session-init window loses its relation").
     *
     * ⚠ This harness installs nothing of its own — the default `initialization` is empty — so
     * without the install in `mintSession` there is no maintainer here at all, and both assertions
     * below fail.
     */
    @Test
    fun aMintedSessionCanMaintainRelationsBeforeItsInitialisationHookRuns() {
        var presentWhenTheHookRan: Boolean? = null
        val registry = createRegistry(
            initialization = {
                presentWhenTheHookRan = observerRegistry.any { it is RelationMaintainer }
            },
        )

        val context = registry.getExecutionContext("client1", "session1")

        assertTrue(
            context.observerRegistry.any { it is RelationMaintainer },
            "a reachable session must be able to maintain relations",
        )
        // ⚠ The ordering, not just the presence: installed BEFORE the initialisation hook, so the
        // window between `publish` and `runInitialization()` is covered rather than narrowed.
        assertEquals(
            true,
            presentWhenTheHookRan,
            "the maintainer must already be installed when the initialisation hook runs",
        )
    }

    private fun createRegistry(
        connectionFactory: (String) -> ClientConnection = {
            object : ClientConnection {
                override fun init() {}
                override fun send(data: Data) {}
                override fun destroy() {}
            }
        },
        onSessionEstablished: (clientId: String, context: ExecutionContext) -> Unit = { _, _ -> },
        initialization: ExecutionContext.() -> Unit = {},
        mintSystemSession: Boolean = false,
    ): ClientRegistry {
        val schemaRegistry = createCoreTestRegistry()
        val tContext = createTestContext(schemaRegistry)
        return ClientRegistry(
            database = object : Database {
                override fun getData(dataId: DataId, asEntity: Boolean, projection: Projection?) = null
                override fun saveData(data: Data) {}
                override fun deleteData(dataId: DataId) {}
                override fun getArrayData(reference: ArrayDatabaseReference, projection: Projection?) = emptySequence<Data>()
                override fun getComposite(reference: CompositeDatabaseReference): Data? = null
                override fun addToArray(reference: ArrayDatabaseReference, value: Data, origin: WriteOrigin) {}
                override fun removeFromArray(reference: ArrayDatabaseReference, dataId: DataId, origin: WriteOrigin) {}
                override fun setArrayListener(
                    reference: ArrayDatabaseReference,
                    onAdded: (Data, WriteOrigin) -> Unit,
                    onRemoved: (DataId, WriteOrigin) -> Unit
                ): Long = 0L

                override fun removeArrayListener(handle: Long) {}
                override fun <R> transaction(block: () -> R): R = block()
            },
            traitResolver = tContext.traitResolver(),
            implementationFactory = { null },
            clientConnectionFactory = connectionFactory,
            materializer = CallstackMaterializer(schemaRegistry, tContext),
            pureOperationRunner = tContext.components[PureOperationRunner.ID] as PureOperationRunner,
            baseContext = tContext,
            initialization = initialization,
            onSessionEstablished = onSessionEstablished,
            mintSystemSession = mintSystemSession,
        )
    }
}
