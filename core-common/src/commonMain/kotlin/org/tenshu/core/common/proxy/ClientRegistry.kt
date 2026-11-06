package org.tenshu.core.common.proxy

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import org.tenshu.core.PureOperationRunner
import org.tenshu.core.common.relation.installRelationMaintainer
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.core.TContext
import org.tenshu.core.trait.TraitResolver
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * Predicate `ProxyGateway` installs on `ClientRegistry` so the registry can
 * ask "does this client still have a *detached* Layer-2 task in flight?"
 * before tearing a session down. See the Option-A note in
 * `docs/proposals/core-background-processing.md` (§ "Known gap — L2-LIVE is
 * necessary but not sufficient").
 */
typealias InFlightProbe = (clientId: String) -> Boolean

@OptIn(ExperimentalAtomicApi::class)
class ClientRegistry(
    private val database: Database,
    val traitResolver: TraitResolver,
    val implementationFactory: OperationImplementationFactory,
    var clientConnectionFactory: (clientId: String) -> ClientConnection,
    val materializer: CallstackMaterializer,
    val pureOperationRunner: PureOperationRunner,
    val baseContext: TContext,
    val serviceScope: CoroutineScope? = null,
    /**
     * Forwarded to every minted [ExecutionContext] so `LocalTaskExecutor`'s
     * catch-all can persist diagnostic [org.tenshu.logging.ErrorEvent]s
     * (`docs/proposals/core-error-channel.md`). `null` ⇒ no recorder; the
     * catch-all just wraps and returns.
     */
    private val errorEventRecorder: ErrorEventRecorder? = null,
    private val initialization: ExecutionContext.() -> Unit = {},
    /**
     * Fired once per **newly minted** client session (not on a cache hit /
     * same-identity rebind), after the session's [ExecutionContext] is created
     * and `init()`-ed. The handshake-triggered app-registration writer hooks
     * here (`org.tenshu.core.common.app.proxy.appRegistrationTrigger`,
     * `docs/tracks/track-a-control-apps.md` A4); kept as a generic callback so
     * `ClientRegistry` stays free of the app-registration feature. Default is a
     * no-op (test / JS registries that don't register apps). Must not throw —
     * it runs on the binder/socket thread inside [getExecutionContext].
     */
    private val onSessionEstablished: (clientId: String, context: ExecutionContext) -> Unit = { _, _ -> },
    /**
     * Opt in to the proxy's own **system session** — one [ExecutionContext] belonging to no client,
     * for subsystems whose lifetime is the *process* rather than any app's binding
     * (`drive-upload-progress.md` P7). See [systemContext].
     *
     * ⚠ **Off by default, deliberately.** The system session runs [initialization] like any other,
     * so switching it on adds one more full pass of whatever a host's initialisation does. That is
     * free for a host whose initialisation is idempotent and cheap, and decidedly not free for one
     * that stands up a scheduler per pass — so each host opts in once it has checked, rather than
     * inheriting it from a core default. It also keeps every existing caller (JS runtime, system
     * tests, plain test registries) byte-for-byte unchanged.
     */
    private val mintSystemSession: Boolean = false,
) {
    companion object {
        /**
         * Client id of the proxy's own [systemContext]. Not an app id and can never collide with
         * one: real ids are package names, which cannot contain `/`.
         */
        const val SYSTEM_CLIENT_ID = "tenshu/proxy/system"
    }

    private data class ClientSession(
        val context: ExecutionContext,
        val identity: Any?,
        val sessionScope: CoroutineScope
    )

    /**
     * Session bookkeeping held as an immutable map behind an [AtomicReference]
     * and mutated copy-on-write (see [update] / [remove] / [drain]). The maps
     * are read on the binder/socket thread ([getExecutionContext]) and snapshot
     * on the teardown thread ([shutdown] / [shutdownAndJoin] / [clear]); a plain
     * `mutableMapOf` would let those interleave, so a mutation could race the
     * `clients.values + deferredTeardown.values` snapshot and throw
     * `ArrayIndexOutOfBoundsException` from inside `LinkedHashMap.valuesToArray`. Same
     * lock-free `AtomicReference<immutable>` copy-on-write idiom as
     * `ProxyGateway.inFlightKeys` / `ObserverRegistry`.
     */
    private val clients = AtomicReference<Map<String, ClientSession>>(emptyMap())

    /**
     * Sessions whose teardown ([CoroutineScope.cancel] +
     * [ExecutionContext.destroy]) was deferred because the client still had a detached Layer-2
     * task running when [unregisterClient]/[clear] was called. They are no
     * longer in [clients] (a rebind mints a fresh session), but their
     * resources are kept alive until the in-flight task drains — see
     * [drainDeferredTeardown], called by `ProxyGateway` when a client's last
     * detached task deregisters. Keyed by clientId, holding EVERY session
     * deferred for that client until then. ⚠ Not only the most recent: the
     * probe is per client, not per session, so an older deferred session may
     * still be the one serving the detached task the probe reports — replacing
     * it would leave it alive and reachable from nothing, and destroying it early
     * would tear its registries and connection out from under that task,
     * which is exactly what deferral exists to prevent.
     */
    private val deferredTeardown = AtomicReference<Map<String, List<ClientSession>>>(emptyMap())

    /**
     * Installed by `ProxyGateway` so this registry can ask, before tearing a
     * session down, whether that client still has a detached (taskId-carrying)
     * Layer-2 request in flight. `null` ⇒ tear down immediately (no detached
     * tasks exist, e.g. plain test registries). Option A of
     * `docs/proposals/core-background-processing.md`.
     */
    var inFlightProbe: InFlightProbe? = null

    /**
     * The scope a detached Layer-2 `requestJob` is parented to so a
     * per-client `sessionScope.cancel()` (one client unbinding) does not
     * cancel it, while a full proxy shutdown ([clear]) still does. Prefer the
     * injected [serviceScope]; when absent (test registries / no service
     * scope) fall back to a registry-owned [SupervisorJob] that [clear]
     * cancels — so detached tasks still cannot leak past a full shutdown.
     */
    private val fallbackDetachScope: CoroutineScope by lazy {
        CoroutineScope(SupervisorJob())
    }
    val detachScope: CoroutineScope get() = serviceScope ?: fallbackDetachScope

    /**
     * Copy-on-write helpers over the session maps. Each retries its CAS until it
     * wins against any concurrent mutation, so reads ([AtomicReference.load]) and
     * the teardown snapshot never observe a half-mutated map. [drain] atomically
     * swaps in an empty map and returns what was there, giving teardown a stable
     * snapshot that can't grow under it.
     */
    private fun <V> AtomicReference<Map<String, V>>.update(key: String, value: V) {
        while (true) {
            val current = load()
            if (compareAndSet(current, current + (key to value))) return
        }
    }

    /**
     * Publish [session] under [key] only if nothing is there yet; returns the session that is
     * already published, or `null` when [session] won. This is what makes a **mint** atomic and
     * not merely its publish — see [mintSession]'s race note.
     */
    private fun <V> AtomicReference<Map<String, V>>.putIfAbsent(key: String, value: V): V? {
        while (true) {
            val current = load()
            current[key]?.let { return it }
            if (compareAndSet(current, current + (key to value))) return null
        }
    }

    /**
     * Park [session] for [drainDeferredTeardown], alongside any session already parked for
     * [clientId]. ⚠ Appended, never replaced: the earlier one may still be serving the detached
     * task the in-flight probe reports, so it is neither dropped (alive and reachable from nothing
     * — the orphan shape [mintSession]'s race note describes) nor torn down early (its registries
     * and connection pulled out from under that task). All of them go together when the client's
     * last in-flight task drains.
     */
    private fun deferTeardown(clientId: String, session: ClientSession) {
        while (true) {
            val current = deferredTeardown.load()
            val parked = current[clientId].orEmpty()
            if (session in parked) return
            if (deferredTeardown.compareAndSet(current, current + (clientId to parked + session))) return
        }
    }

    private fun <V> AtomicReference<Map<String, V>>.remove(key: String): V? {
        while (true) {
            val current = load()
            val removed = current[key] ?: return null
            if (compareAndSet(current, current - key)) return removed
        }
    }

    private fun <V> AtomicReference<Map<String, V>>.drain(): Map<String, V> {
        while (true) {
            val current = load()
            if (compareAndSet(current, emptyMap())) return current
        }
    }

    /**
     * Set by the *final* teardown paths ([shutdown] / [shutdownAndJoin]) —
     * after them the process is exiting (or the test world is being disposed),
     * so minting a fresh session would resurrect background loops against
     * dropped collaborators. [quiesceSessionsAndJoin] deliberately does NOT
     * set it: an Android `ProxyService.onDestroy` quiesces the sessions while
     * the process (and this registry) lives on to serve re-binds and the
     * in-process client.
     */
    private val closed = AtomicReference(false)

    /**
     * The proxy's own session — one [ExecutionContext] belonging to **no client**
     * (`drive-upload-progress.md` P7). Proxy-owned subsystems whose lifetime is the *process*, not
     * any app's binding, run here: the sync runtime (`SyncRuntimeHolder`).
     *
     * ## Why a session that belongs to no client
     *
     * Run per client session, `initSyncService` puts the sync scheduler on whichever app happened
     * to connect first, and it **dies when that app disconnects**. Re-electing a new runtime at the
     * next session init bounds the damage, but re-election is churn: the runtime cannot outlive its
     * context, and every client context belongs to a client. Handing the scheduler a
     * longer-lived *scope* does not help — `destroy()` clears the observer/stream registries and
     * `quiesceSessions` destroys every session's context, and the teardown KDoc on
     * [ExecutionContext] names the sync scheduler as the exact thing that must not outlive them.
     * The lifetime that has to differ is the **context's**.
     *
     * ## Lifecycle
     *
     * Created lazily on first use (never at construction — [initialization] resolves DI
     * collaborators, and running it while this registry's own singleton is still being built is a
     * re-entrancy trap), kept **out of [clients]** so [unregisterClient] and [quiesceSessions]
     * cannot reach it, and destroyed only by [shutdown] / [shutdownAndJoin]. So it survives every
     * app disconnect and every service teardown that keeps the process alive — which is precisely
     * the window a client-owned sync runtime dies in.
     *
     * ⚠ [onSessionEstablished] is deliberately **not** fired for it: that hook writes an app
     * registration, and the proxy is not an app.
     */
    private val systemSession = AtomicReference<ClientSession?>(null)

    /**
     * The system session's context, minting it on first call. Returns `null` once the registry is
     * [closed], so a late caller degrades rather than resurrecting proxy loops against dropped
     * collaborators.
     *
     * ⚠ Check-then-act without a lock, matching [getExecutionContext]'s discipline. ⚠⚠ "The
     * binder/socket thread is the only caller" holds for the transports and not for the
     * in-process client, which calls [getExecutionContext] from whichever thread runs the op — so
     * two first calls CAN mint twice (`ClientRegistryConcurrencyTest`). What makes a mint safe is
     * not a lock but the publish being a compare-and-set, with the loser torn down before it runs
     * [initialization]: see [mintSession]. This path has the same shape.
     */
    fun systemContext(): ExecutionContext? {
        // ⚠ Known gap, inert while `mintSystemSession` is off: a caller that loses the mint race
        // here is handed the winner's session BEFORE the winner's initialisation has finished, so
        // its own client session's init can race the system session's for `SyncRuntimeHolder` —
        // the election `getExecutionContext` calls this first to decide. The real fix is a loser
        // that awaits the winner's init, which needs a blocking wait this common code has no
        // primitive for; `docs/tech-debt.md` "A session that lost the mint
        // race kept the whole sync runtime alive" tracks it for when P7 is enabled.
        if (!mintSystemSession || closed.load()) return null
        systemSession.load()?.let { return it.context }
        // Published inside mintSession, before initialisation runs — see its KDoc. Deliberately
        // NOT into `clients`: keeping it out is what stops unregisterClient / quiesceSessions from
        // tearing the proxy's own session down when an app disconnects.
        return mintSession(
            clientId = SYSTEM_CLIENT_ID,
            sessionIdentity = SYSTEM_CLIENT_ID,
            connectionFactory = { SystemClientConnection() },
            publish = { if (systemSession.compareAndSet(null, it)) null else systemSession.load() },
        ).session.context
    }

    fun getExecutionContext(clientId: String, sessionIdentity: Any? = null): ExecutionContext {
        check(!closed.load()) {
            "ClientRegistry is shut down — no new client sessions can be minted"
        }
        // Ensure the proxy's own session exists BEFORE any client's. Ordering is load-bearing:
        // process-scoped subsystems elect on whichever context initialises first
        // (`SyncRuntimeHolder`), so the system session must win that race — otherwise the runtime
        // lands on a client session again and the system session buys nothing.
        systemContext()

        while (true) {
            val existingSession = clients.load()[clientId]
            if (existingSession != null) {
                if (existingSession.identity == sessionIdentity) {
                    return existingSession.context
                }

                // Identity changed (e.g. new PID on Android), unregister old client
                unregisterClient(clientId)
            }

            val minted = mintSession(
                clientId = clientId,
                sessionIdentity = sessionIdentity,
                connectionFactory = { clientConnectionFactory(clientId) },
                publish = { clients.putIfAbsent(clientId, it) },
            )
            if (minted.fresh) {
                // A concurrent caller with a DIFFERENT identity may have displaced this session
                // while it was initialising — it takes the replace route below. Never hand a
                // displaced session to our caller: go round again, which is what its next call
                // would have done anyway. ⚠ The test is PUBLICATION, not liveness: a displacement
                // that found in-flight work parks the session in `deferredTeardown` with its scope
                // still active, to be cancelled under us later. ⚠ Two transports that keep
                // claiming one clientId under different identities displace each other on every
                // call; that is the sequential re-bind rule, not something this loop adds.
                if (clients.load()[clientId] !== minted.session) continue
                // Once per session, not once per caller: a caller that lost the mint race is handed
                // the winner's session, whose establishment the winner already announced.
                onSessionEstablished(clientId, minted.session.context)
                return minted.session.context
            }
            // Lost the race. The winner is only ours to share if it is OUR transport: a session
            // carries its connection, so handing an Android re-bind or a replaced socket the other
            // identity's session would answer its requests on the wrong channel. A different
            // identity takes the same route a sequential re-bind takes — replace it — by going
            // round again, where the top of the loop unregisters the winner and mints afresh.
            if (minted.session.identity == sessionIdentity) return minted.session.context
        }
    }

    /** What [mintSession] handed back: the session to use, and whether this call created it. */
    private class Minted(val session: ClientSession, val fresh: Boolean)

    /**
     * Build one session — connection, scope, context, initialisation — shared by client sessions
     * and the [systemContext].
     *
     * ⚠ [publish] runs **before** [ExecutionContext.runInitialization] and that ordering is
     * load-bearing for both callers: the initialisation hook starts background work that re-enters
     * this registry, so a session that is not yet visible gets minted a second time. For a client
     * that is a parallel session nothing cancels; for the system session it is unbounded
     * recursion — `systemContext()` → initialise → `getExecutionContext` → `systemContext()` →
     * `StackOverflowError`.
     */
    private fun mintSession(
        clientId: String,
        sessionIdentity: Any?,
        connectionFactory: () -> ClientConnection,
        /**
         * Publish the freshly built session, or answer with the one that is **already** published
         * under this id. ⚠⚠ Must be atomic (`putIfAbsent`, not `put`): two concurrent first calls
         * for one id both reach here with nothing published, and a plain put let the second
         * silently overwrite the first — see the race note below.
         */
        publish: (ClientSession) -> ClientSession?,
    ): Minted {
        val clientConnection = connectionFactory()
        // SupervisorJob is *parented* to the serviceScope's Job so a
        // `serviceScope.cancel()` cascades into every launch made on this
        // sessionScope (e.g. `ExecutionContext.sessionScope.launch { }` for
        // stream observer fanout). Without the parent linkage the new
        // SupervisorJob is an orphan: cancelling the serviceScope drops its
        // own Job but leaves the session coroutines running, which is what
        // produced the SystemTest cross-test interference where a paired-
        // device convergence test would time out only when run after the
        // pairing test (the orphaned scheduler coroutines from the prior
        // test starved `Dispatchers.Default` workers).
        val sessionScope = serviceScope?.let { parent ->
            CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]))
        } ?: CoroutineScope(SupervisorJob())

        val context = ExecutionContext(
            context = baseContext,
            database = database,
            traitResolver = traitResolver,
            clientConnection = clientConnection,
            implementationFactory = implementationFactory,
            materializer = materializer,
            pureOperationRunner = pureOperationRunner,
            sessionScope = sessionScope,
            // The attested identity this session belongs to — see ExecutionContext.clientId.
            clientId = clientId,
            errorEventRecorder = errorEventRecorder,
            initialization = initialization
        )
        context.init()
        // ⚠⚠ BEFORE `publish`, and that is the whole point. `publish` makes this context reachable
        // to callers while `runInitialization()` — which is what runs every `init<X>Service()` and
        // therefore `installRelationMaintainer()` — has not started yet. A save served in that
        // window would fan out to an observer registry with NO `RelationMaintainer`, so the entity
        // is written and never added to its service relation, and nothing ever retries: the save is
        // not re-observed, so the row stays on disk and invisible FOREVER.
        //
        // ⚠ Not theoretical: `ContactsLocalRuntimeTest` loses rows exactly this way without it.
        //
        // ⚠ Safe here, and it does NOT undo the publish-before-init ordering that the KDoc below
        // calls load-bearing: this only ADDS an observer. It dispatches nothing, starts no
        // background work and re-enters no session lookup, so the recursion that forced that
        // ordering cannot arise from it. The per-service calls stay and become no-ops — the
        // installer is idempotent per context by construction.
        context.installRelationMaintainer()
        val session = ClientSession(context, sessionIdentity, sessionScope)
        // Register BEFORE running the initialization hook. The hook starts
        // background work that re-enters this method for the same clientId (the
        // in-process `SessionAwareLocalTaskExecutor` session provider); with the
        // session not yet in `clients` that re-entry would mint a second, parallel
        // session whose background loops nothing ever cancels. See
        // [ExecutionContext.runInitialization].
        // ⚠⚠ And the publish must be a compare-and-set, because the re-entrant case above is not the
        // only way to mint twice. `getExecutionContext` is check-then-act with no lock — the KDoc on
        // `systemContext` records the assumption that one thread is the only caller — and the
        // in-process client (`SessionAwareLocalTaskExecutor`) calls it from whichever thread runs
        // the op. Two concurrent FIRST calls for one clientId can both see no session and both
        // mint; with a plain `put` the second overwrites the first, so the session that already ran
        // the platform init hook — and therefore owns the sync scheduler and the schedule drain
        // loop, via `SyncRuntimeHolder` — is reachable from nothing. `shutdownAndJoin` quiesces
        // only the published one; the orphan's loops outlive the Koin app and throw
        // `NullPointerException: null cannot be cast to … DataFactory` / `ClosedScopeException`
        // every tick until the JVM exits. So the loser is cancelled and destroyed before it runs
        // any initialisation, and its caller gets the winner.
        // ⚠ The winner is returned as-is; whether the loser may USE it is the caller's decision —
        // `getExecutionContext` shares it only when the identities match, and replaces it otherwise.
        publish(session)?.let { winner ->
            sessionScope.cancel("Lost the session mint race for '$clientId' to a concurrent first call")
            context.destroy()
            return Minted(winner, fresh = false)
        }
        context.runInitialization()
        return Minted(session, fresh = true)
    }

    /**
     * [clientId]'s connection **if it already has a session**, minting nothing
     * (`control-cross-app-open.md` TT-1b).
     *
     * ⚠⚠ **The non-minting half is the whole point.** [getExecutionContext] creates a session —
     * and therefore an `AndroidClientConnection` that binds — for any id it is handed, so asking
     * it *"is the handler running?"* would make the answer yes by asking. This reads the map and
     * stops.
     *
     * ⚠ A non-null connection means a session exists, **not** that the app's process is alive:
     * the transport reports its own liveness (`AndroidClientConnection.isConnected`), and a send
     * into a dead binder queues rather than throwing. Callers that need "will this actually
     * arrive" must ask the connection, not this.
     */
    fun connectionFor(clientId: String): ClientConnection? =
        clients.load()[clientId]?.context?.clientConnection

    fun unregisterClient(clientId: String) {
        val session = clients.remove(clientId) ?: return
        if (inFlightProbe?.invoke(clientId) == true) {
            // Option A: a detached Layer-2 task for this client is still
            // running and references this session's ExecutionContext
            // (observerRegistry / clientConnection). Re-parenting the job is
            // not enough — cancelling the sessionScope or destroying the
            // context here would tear those resources out from under the
            // running task. Defer both until the task drains
            // (drainDeferredTeardown, driven by ProxyGateway).
            deferTeardown(clientId, session)
            return
        }
        session.sessionScope.cancel("Client unregistered")
        session.context.destroy()
    }

    fun clear() {
        // Drain atomically first: deferring some sessions must not skip tearing
        // down the rest, and the swap-to-empty stops a concurrent
        // getExecutionContext from adding a session we'd then miss.
        clients.drain().forEach { (clientId, session) ->
            if (inFlightProbe?.invoke(clientId) == true) {
                deferTeardown(clientId, session)
            } else {
                session.sessionScope.cancel("Registry cleared")
                session.context.destroy()
            }
        }
    }

    /**
     * Called by `ProxyGateway` when a client's last detached Layer-2 task
     * deregisters: if that session's teardown was deferred (it had in-flight
     * work when [unregisterClient]/[clear] ran), perform it now. Idempotent —
     * an unknown / already-drained clientId is a no-op.
     */
    fun drainDeferredTeardown(clientId: String) {
        val sessions = deferredTeardown.remove(clientId) ?: return
        sessions.forEach { session ->
            session.sessionScope.cancel("Deferred teardown after in-flight tasks drained")
            session.context.destroy()
        }
    }

    /**
     * Full proxy/service shutdown. Unlike [unregisterClient] (the routine client unbind,
     * where detached Layer-2 tasks intentionally outlive the binding — [clear] is not
     * on that path and has no production caller), this
     * tears everything down unconditionally and cancels the fallback detach
     * scope, so no detached task can leak past process shutdown when no
     * external [serviceScope] was injected. When a [serviceScope] *was*
     * injected its owner cancels it directly; this still drops any sessions
     * (active or deferred) and their contexts.
     */
    fun shutdown() {
        closed.store(true)
        // Drain both maps atomically before iterating — a `+` snapshot over two
        // plain mutable maps throws AIOOBE when a concurrent
        // getExecutionContext mutates `clients` while `valuesToArray` copies it.
        (clients.drain().values + deferredTeardown.drain().values.flatten()).forEach {
            it.sessionScope.cancel("Registry shutdown")
            it.context.destroy()
        }
        // The proxy's own session goes LAST: process-scoped subsystems live on it (the sync
        // runtime), and tearing it down before the client sessions could pull the registries out
        // from under work those sessions are still unwinding.
        systemSession.exchange(null)?.let {
            it.sessionScope.cancel("Registry shutdown")
            it.context.destroy()
        }
        fallbackDetachScope.cancel("Registry shutdown")
    }

    /**
     * Quiesce every session — cancel **and join** its background work, then
     * dispose the context — while keeping the registry **open** for fresh
     * sessions. This is the Android `ProxyService.onDestroy` teardown: the
     * *service* is going away but the *process* may live on, and both the
     * re-binding Binder clients and the in-process local client
     * ([SessionAwareLocalTaskExecutor]) must be able to mint new sessions
     * afterwards. The final teardown paths ([shutdown] / [shutdownAndJoin])
     * additionally [closed] the registry so nothing can resurrect background
     * loops after the DI world is dropped.
     */
    suspend fun quiesceSessionsAndJoin() {
        (clients.drain().values + deferredTeardown.drain().values.flatten()).forEach { it.context.quiesce() }
    }

    /**
     * Suspend counterpart to [shutdown] that **quiesces** each session — cancels
     * its [CoroutineScope] *and joins it* (via [ExecutionContext.quiesce]) before
     * disposing the context — so no background loop / observer is still ticking
     * when the caller proceeds to drop DI singletons (`stopKoin`) or exit the
     * process. Closes the teardown race the synchronous [shutdown] only narrows
     * (cancellation there is fire-and-forget). Use this from contexts that can
     * suspend (tests, app/JS shutdown); [shutdown] stays for paths that can't.
     */
    suspend fun shutdownAndJoin() {
        closed.store(true)
        // Same atomic-drain-then-snapshot as [shutdown]: draining to immutable
        // snapshots removes the `clients.values + deferredTeardown.values` race.
        quiesceSessionsAndJoin()
        fallbackDetachScope.cancel("Registry shutdown")
    }
}
