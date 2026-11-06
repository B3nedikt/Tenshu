package org.tenshu.core.common.proxy

import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import org.tenshu.core.Data
import org.tenshu.core.PureOperationRunner
import org.tenshu.core.common.createCoreTestRegistry
import org.tenshu.core.common.createTestContext
import org.tenshu.core.entity.DataId
import org.tenshu.core.trait.traitResolver
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins `ClientRegistry`'s session maps as safe under concurrent mutation:
 * `getExecutionContext` mutating the session map on a binder/socket thread while
 * `shutdownAndJoin` / `clear` snapshot `clients.values + deferredTeardown.values` on the
 * teardown thread. Plain `mutableMapOf`s throw `ArrayIndexOutOfBoundsException` from
 * inside `LinkedHashMap.valuesToArray` here (~1 run in N); the copy-on-write
 * `AtomicReference<immutable>` maps cannot.
 *
 * JVM-only (`desktopTest`): the race needs real OS threads; commonTest's JS target
 * is single-threaded and would never exercise it.
 */
class ClientRegistryConcurrencyTest {

    @Test
    fun concurrentGetExecutionContext_racesShutdown_withoutThrowing() = repeat(50) { iteration ->
        val registry = createRegistry()
        val failures = ConcurrentLinkedQueue<Throwable>()
        val start = CountDownLatch(1)

        // Writers: keep minting sessions on many threads.
        val writers = (0 until 8).map { w ->
            thread {
                start.await()
                try {
                    repeat(200) { i -> registry.getExecutionContext("client-$w-$i", "session") }
                } catch (t: Throwable) {
                    failures += t
                }
            }
        }

        // Teardown thread: snapshots the maps while the writers mutate them.
        val teardown = thread {
            start.await()
            try {
                repeat(20) {
                    // quiesceSessionsAndJoin (not shutdownAndJoin): the final
                    // shutdown CLOSES the registry against new sessions, so
                    // racing writers would (correctly) throw. The drain+snapshot
                    // shape under test is identical in both.
                    if (iteration % 2 == 0) runBlocking { registry.quiesceSessionsAndJoin() } else registry.clear()
                }
            } catch (t: Throwable) {
                failures += t
            }
        }

        start.countDown()
        writers.forEach { it.join() }
        teardown.join()

        assertTrue(
            failures.isEmpty(),
            "concurrent getExecutionContext vs teardown threw: ${failures.joinToString { it::class.simpleName + ": " + it.message }}"
        )
    }

    /**
     * Two concurrent FIRST calls for one clientId must be handed ONE session, and the call that
     * lost the mint race must have its connection destroyed and its scope cancelled.
     *
     * `getExecutionContext` is check-then-act with no lock, and the in-process client
     * (`SessionAwareLocalTaskExecutor`) calls it from whichever thread runs the op, so two first
     * ops on different threads both see no session and both mint. With a plain `put` the second
     * publish overwrites the first, and the orphan — the one that already ran the platform init
     * hook and therefore owns the sync scheduler and schedule drain loop — is reachable from
     * nothing: `shutdownAndJoin` quiesces only the published session, and the orphan's loops
     * outlive the Koin app, throwing on every tick until the JVM exits (`docs/tech-debt.md`,
     * "A session that lost the mint race kept the whole sync runtime alive").
     *
     * ⚠ Reverting `putIfAbsent` to `update` in `getExecutionContext` fails this on the FIRST
     * iteration with `expected:<1> but was:<8>`: a barrier releases eight threads into the same
     * first call at once, which is the production shape compressed into a point, and every one of
     * them mints.
     */
    @Test
    fun concurrentFirstCalls_forOneClientId_shareOneSession_andTheLoserIsTornDown() = repeat(200) { iteration ->
        val minted = AtomicInteger(0)
        val destroyed = AtomicInteger(0)
        val registry = createRegistry(
            onConnectionMinted = { minted.incrementAndGet() },
            onConnectionDestroyed = { destroyed.incrementAndGet() },
        )
        val threads = 8
        val ready = CyclicBarrier(threads)
        val contexts = ConcurrentLinkedQueue<ExecutionContext>()
        val failures = ConcurrentLinkedQueue<Throwable>()

        (0 until threads).map {
            thread {
                try {
                    ready.await()
                    contexts += registry.getExecutionContext("hub", null)
                } catch (t: Throwable) {
                    failures += t
                }
            }
        }.forEach { it.join() }

        assertTrue(failures.isEmpty(), "iteration $iteration threw: ${failures.joinToString { it::class.simpleName + ": " + it.message }}")
        val distinct = contexts.map { System.identityHashCode(it) }.toSet()
        assertEquals(
            1,
            distinct.size,
            "iteration $iteration: $threads concurrent first calls for one clientId were handed " +
                "${distinct.size} different sessions — every extra one owns background loops that " +
                "nothing will ever cancel",
        )
        // A loser may have minted a connection before it noticed it lost; every such connection is
        // destroyed again, so exactly one survives.
        assertEquals(
            minted.get() - 1,
            destroyed.get(),
            "iteration $iteration: ${minted.get()} connections minted, ${destroyed.get()} destroyed — " +
                "a losing mint must tear its connection down",
        )
        assertTrue(contexts.first().sessionScope.isActive, "the surviving session's scope must stay active")
        runBlocking { registry.shutdownAndJoin() }
    }

    /**
     * Two concurrent first calls for one clientId with DIFFERENT session identities must never be
     * handed one session. A session carries its transport connection: sharing the winner across
     * identities would answer an Android re-bind's or a replaced socket's requests on the other
     * channel. The sequential path replaces on an identity change; the racing path has to as well.
     *
     * ⚠ Making the loser reuse the winner regardless of identity fails this on the first iteration
     * with "shared one session across two identities".
     */
    @Test
    fun concurrentFirstCalls_withDifferentIdentities_neverShareASession() = repeat(200) { iteration ->
        val registry = createRegistry()
        val ready = CyclicBarrier(2)
        val results = ConcurrentLinkedQueue<Pair<String, ExecutionContext>>()
        val failures = ConcurrentLinkedQueue<Throwable>()

        listOf("identity-A", "identity-B").map { identity ->
            thread {
                try {
                    ready.await()
                    results += identity to registry.getExecutionContext("app", identity)
                } catch (t: Throwable) {
                    failures += t
                }
            }
        }.forEach { it.join() }

        assertTrue(failures.isEmpty(), "iteration $iteration threw: ${failures.joinToString { it::class.simpleName + ": " + it.message }}")
        val byIdentity = results.toMap()
        assertTrue(
            byIdentity.getValue("identity-A") !== byIdentity.getValue("identity-B"),
            "iteration $iteration: two transports with different identities shared one session " +
                "across two identities — one of them answers on the other's connection",
        )
        runBlocking { registry.shutdownAndJoin() }
    }

    /**
     * A caller that won the publish but was displaced by a DIFFERENT identity while its session
     * was still initialising must not be handed the torn-down session. Deterministic rather than
     * raced: the registry's initialisation hook parks the first mint until the other identity has
     * replaced it, exactly that window.
     *
     * The displaced caller goes round again and ends up with a live session; the displacer's is
     * the one torn down in turn, which is the sequential re-bind rule and is not asserted on.
     *
     * ⚠ Dropping the winner's post-init publication re-check in `getExecutionContext` fails this
     * with "was already cancelled when it was returned".
     */
    @Test
    fun aWinnerDisplacedByAnotherIdentityDuringInit_isNotHandedTheTornDownSession() {
        val firstMintPublished = CountDownLatch(1)
        val displaced = CountDownLatch(1)
        val initialisations = AtomicInteger(0)
        val registry = createRegistry(
            initialization = {
                // Only the very first mint parks; the re-mint after displacement must not.
                if (initialisations.getAndIncrement() == 0) {
                    firstMintPublished.countDown()
                    displaced.await()
                }
            },
        )

        var winnerContext: ExecutionContext? = null
        var winnerActiveAtReturn = false
        val winner = thread {
            val context = registry.getExecutionContext("app", "identity-A")
            winnerActiveAtReturn = context.sessionScope.isActive
            winnerContext = context
        }
        firstMintPublished.await()
        // The other identity arrives while A is still initialising: it finds A's session
        // published under a different identity and replaces it, as a sequential re-bind does.
        val displacer = registry.getExecutionContext("app", "identity-B")
        displaced.countDown()
        winner.join()

        assertTrue(
            winnerActiveAtReturn,
            "the session handed to identity-A was already cancelled when it was returned — displaced " +
                "by identity-B while it was still initialising, and handed over anyway",
        )
        assertTrue(assertNotNullContext(winnerContext) !== displacer, "the displaced winner must not be handed the displacer's session either")
        // Three initialisations, deterministically: A's parked first mint, B's mint, A's re-mint.
        assertEquals(3, initialisations.get(), "the displaced winner re-mints exactly once")
        runBlocking { registry.shutdownAndJoin() }
    }

    /**
     * The same displacement with work IN FLIGHT: the displacer then parks the winner's session in
     * `deferredTeardown` with its scope still active, so a liveness check would pass and hand the
     * caller a session that is no longer the published one — to be cancelled under it by
     * `drainDeferredTeardown`. The re-check has to be publication.
     *
     * Also pins that BOTH deferred sessions — the winner's displaced one and the displacer's,
     * which the winner's re-mint parks under the same in-flight probe — are retained until the
     * drain and torn down together then. A keyed overwrite leaves the first alive and reachable
     * from nothing; tearing it down on supersede pulls it out from under the detached task that
     * deferral exists to protect.
     *
     * ⚠ Replacing the publication re-check with `sessionScope.isActive` fails this with
     * "is not the published session"; destroying a superseded deferral early fails it with
     * "torn down before the client's in-flight work drained".
     */
    @Test
    fun aWinnerDisplacedWithWorkInFlight_isHandedThePublishedSession_andNothingIsOrphaned() {
        val firstMintPublished = CountDownLatch(1)
        val displaced = CountDownLatch(1)
        val initialisations = AtomicInteger(0)
        val minted = AtomicInteger(0)
        val destroyed = AtomicInteger(0)
        val registry = createRegistry(
            onConnectionMinted = { minted.incrementAndGet() },
            onConnectionDestroyed = { destroyed.incrementAndGet() },
            initialization = {
                if (initialisations.getAndIncrement() == 0) {
                    firstMintPublished.countDown()
                    displaced.await()
                }
            },
        )
        registry.inFlightProbe = { true }

        var winnerContext: ExecutionContext? = null
        val winner = thread { winnerContext = registry.getExecutionContext("app", "identity-A") }
        firstMintPublished.await()
        val displacer = registry.getExecutionContext("app", "identity-B")
        displaced.countDown()
        winner.join()

        val context = assertNotNullContext(winnerContext)
        assertTrue(
            registry.connectionFor("app") === context.clientConnection,
            "the session handed to identity-A is not the published session — it was parked for " +
                "deferred teardown by identity-B while still initialising, and handed over anyway",
        )
        assertTrue(context !== displacer, "the displaced winner must not be handed the displacer's session")
        assertTrue(context.sessionScope.isActive, "the published session must be live")

        // Both displaced sessions are parked, and neither is touched before the drain: the
        // earlier one may still be serving the detached task the probe reports.
        assertEquals(3, minted.get(), "winner, displacer, winner's re-mint")
        assertEquals(0, destroyed.get(), "a superseded deferred session was torn down before the client's in-flight work drained")
        assertTrue(displacer.sessionScope.isActive, "the displacer's deferred session must stay live until the drain")

        // Draining tears down every deferred session for the client and leaves the published one.
        registry.drainDeferredTeardown("app")
        assertTrue(context.sessionScope.isActive, "draining the deferred teardown tore down the published session")
        assertTrue(!displacer.sessionScope.isActive, "the displacer's session should have been drained")
        assertEquals(2, destroyed.get(), "every connection but the published one is destroyed by the drain — nothing stays parked alive and unreachable")
        runBlocking { registry.shutdownAndJoin() }
    }

    private fun assertNotNullContext(context: ExecutionContext?): ExecutionContext {
        assertTrue(context != null, "the winner thread never returned a context")
        return context!!
    }

    private fun createRegistry(
        onConnectionMinted: () -> Unit = {},
        onConnectionDestroyed: () -> Unit = {},
        initialization: ExecutionContext.() -> Unit = {},
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
            clientConnectionFactory = {
                onConnectionMinted()
                object : ClientConnection {
                    override fun init() {}
                    override fun send(data: Data) {}
                    override fun destroy() = onConnectionDestroyed()
                }
            },
            materializer = CallstackMaterializer(schemaRegistry, tContext),
            pureOperationRunner = tContext.components[PureOperationRunner.ID] as PureOperationRunner,
            baseContext = tContext,
            initialization = initialization,
        )
    }
}
