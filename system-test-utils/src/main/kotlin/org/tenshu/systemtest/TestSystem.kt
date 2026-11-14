package org.tenshu.systemtest

import io.ktor.client.HttpClient
import io.ktor.server.testing.TestApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import org.tenshu.core.common.proxy.executor.OperationImplementation
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.KoinApplication
import org.koin.core.qualifier.named
import org.koin.dsl.koinApplication
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import org.tenshu.sync.relay.SyncMailbox
import org.tenshu.sync.relay.auth.KnownDevicesStore
import org.tenshu.sync.relay.mailbox.AdmissionRevocationStore
import org.tenshu.sync.relay.mailbox.ContentRefStore
import org.tenshu.sync.relay.mailbox.ContentEdgeStore
import org.tenshu.sync.relay.mailbox.ContentBlobStore
import org.tenshu.sync.relay.mailbox.HlcTipStore
import org.tenshu.sync.relay.mailbox.RealmStore
import org.tenshu.core.common.proxy.db.ObjectStore
import org.tenshu.systemtest.di.LockCheckedObjectStore
import org.tenshu.systemtest.di.systemTestPlatformModule
import org.tenshu.systemtest.di.systemTestProxyModule

/**
 * One in-process world: a real `:sync-relay-server` `Application.module()`
 * hosted via Ktor's testApplication, plus N [TestClient] instances sharing
 * its in-process `client: HttpClient`. See
 * `docs/proposals/testing-system-tests.md` §4.
 *
 * Lifecycle: build via [TestSystem.builder], use inside `runTest { ... }`,
 * call [shutdown] in a `finally` block. [shutdown] cancels each client's
 * scheduler, closes its Koin scope, and stops the shared testApplication
 * (which fires `ApplicationStopped` and cancels the relay's GC sweep).
 *
 * ## Two ways to move sync forward
 *
 * **Polled** (the default): every device's `SyncScheduler` runs its real loop at
 * [client]'s `pollingIntervalMs`, and a test waits for an outcome with [advanceUntil], which only
 * polls a predicate. Convergence costs real ticks of real time, and *which* device reaches the
 * relay first is whatever the two loops happened to do.
 *
 * **Stepped** ([TestSystemBuilder.stepped]): no loop starts, and nothing syncs until the test says
 * so. [sync] ticks devices in the order the test names them, [syncUntil] repeats rounds until a
 * predicate holds, [syncUntilQuiet] repeats them until nothing more moves. Every tick goes through
 * the scheduler's own `tickMutex` (`drainNow`), never `tickOnce` directly
 * (`docs/tech-debt.md` § "Residual Suite-Ordering Flake: ContactHandshakeSystemTest", route 1:
 * cycle hooks such as the invite pickup rely on that mutex for their single-flight). So a race
 * between two devices is an interleaving the test writes — "the move reaches the relay first" is
 * `sync(mover)` before `sync(deleter)` — and a bound on convergence is a count of rounds, not a
 * wall-clock budget.
 *
 * ⚠ Stepped mode stops the **sync** loops only. A device's view-model timers, derived views and
 * observer fan-out still run on their own coroutines; a state computed from synced rows *after*
 * ingest (a derived feed row, a VM's state) is still awaited, with [advanceUntil]. And where the
 * real scheduler's cadence or backoff **is** the subject, stay polled (`docs/testing.md` §10).
 */
class TestSystem internal constructor(
    internal val relayApp: TestApplication,
    val relayHttpClient: HttpClient,
    val knownDevices: KnownDevicesStore,
    val realmStore: RealmStore,
    val hlcTipStore: HlcTipStore,
    /** The relay's admission revocation ledger (delegated admission P2/P5). */
    val admissionRevocations: AdmissionRevocationStore = AdmissionRevocationStore(),
    /**
     * The relay's block mailbox — exposed like [realmStore] / [hlcTipStore] so a test can assert
     * on what the relay *accepted*, not merely on what eventually propagated. Load-bearing for
     * entitlement enforcement: a refused `POST /sync/push` is invisible from the client side (the
     * block simply stays queued locally), so "did this write land?" has to be read here.
     */
    val syncMailbox: SyncMailbox = SyncMailbox(),
    /**
     * The relay's content plane, exposed for the same reason as [syncMailbox]: a lifecycle assertion
     * has to read the ref index and the bytes, which are invisible from the client side.
     *
     * ⚠⚠ Freeing bytes while leaving the index behind is a real failure mode — measured on production
     * 2026-09-21, where one delete-driven reclamation left 1,771 ref rows pointing at dropped blobs.
     */
    // ⚠ Typed as the registries, not the in-memory stores: [TestSystemBuilder.durableContentPlane] puts the
    // SQLite set production runs behind them (CR-2 B5r's system test needs the real queue and drain).
    val contentRefs: org.tenshu.sync.relay.mailbox.ContentRefRegistry = ContentRefStore(),
    val contentEdges: org.tenshu.sync.relay.mailbox.ContentEdgeRegistry = ContentEdgeStore(),
    val contentBlobs: org.tenshu.sync.relay.mailbox.ContentBlobRegistry = ContentBlobStore(),
    /** CR-2 B4's FREED state. A test frees content through it, standing in for B5's sweep. */
    val contentFreeing: org.tenshu.sync.relay.mailbox.ContentFreeing? = null,
    /**
     * CR-2 B5r's sweep, when the builder was given [TestSystemBuilder.durableContentPlane]: a test runs it
     * with [org.tenshu.sync.relay.storage.ContentSweepJob.runOnce] on the plane's own clock. `null` otherwise.
     */
    val contentSweep: org.tenshu.sync.relay.storage.ContentSweepJob? = null,
    /** Built with [TestSystemBuilder.stepped]: see the class KDoc. */
    val stepped: Boolean = false,
    /**
     * Stepped mode's sync clock, shared by every device: the `nowMs` their sync runtimes read for
     * `RejectionBackoff` windows, relay reachability and health stamps. It starts at real time when
     * the system is built and then moves **only** when the test calls [TestSyncClock.advance], so
     * "the backoff window has elapsed" is a step the test takes, like a tick. `null` in a polled
     * system, whose devices read real time. See [TestSyncClock] for what it does not drive.
     */
    val syncClock: TestSyncClock? = null,
    private val onShutdown: () -> Unit = {},
) {
    private val _clients = mutableListOf<TestClient>()
    val clients: List<TestClient> get() = _clients

    /**
     * Register a fresh [TestClient]. Builds a fresh isolated [KoinApplication]
     * including [systemTestProxyModule] and a per-client
     * [systemTestPlatformModule] keyed by [deviceId].
     *
     * [pollingIntervalMs] controls this client's `SyncScheduler` cadence;
     * default 100ms matches the existing fast-converging tests. Tests that
     * want to model the production 30s cadence (or simulate one device pushing
     * late) can pass a much larger value per client.
     */
    fun client(
        deviceId: String,
        role: TestClientRole,
        pollingIntervalMs: Long = DEFAULT_POLLING_INTERVAL_MS,
        /** Commerce bearer token for `X-Entitlement`; `{ null }` = none, the default everywhere. */
        entitlementTokenProvider: suspend () -> String? = { null },
        /** See `systemTestPlatformModule`'s parameter of the same name. */
        operationDecorator: (operationType: String, implementation: OperationImplementation) -> OperationImplementation =
            { _, implementation -> implementation },
        /** See `systemTestPlatformModule`'s parameter of the same name. */
        contentGate: org.tenshu.sync.common.SyncGate = org.tenshu.sync.common.SyncGate.AlwaysAllow,
        /** See `systemTestPlatformModule`'s parameter of the same name — the personal push gate. */
        storageGate: org.tenshu.sync.common.SyncGate = org.tenshu.sync.common.SyncGate.AlwaysAllow,
        /** See `systemTestPlatformModule`'s parameter of the same name: `AwaitPairing`'s pause. */
        pairingWaitPacer: (suspend () -> Unit)? = null,
    ): TestClient {
        require(_clients.none { it.deviceId == deviceId }) {
            "TestClient with deviceId=$deviceId already registered"
        }
        require(!stepped || pollingIntervalMs == DEFAULT_POLLING_INTERVAL_MS) {
            "pollingIntervalMs=$pollingIntervalMs on '$deviceId' would be ignored: a stepped system starts no " +
                "polling loop, so a device ticks only when the test syncs it"
        }
        val koinApp = koinApplication {
            modules(
                systemTestProxyModule(),
                systemTestPlatformModule(
                    deviceId = deviceId,
                    relayHttpClient = relayHttpClient,
                    pollingIntervalMs = pollingIntervalMs,
                    startBackgroundLoops = !stepped,
                    syncNowMs = syncClock?.let { clock -> { clock.nowMs() } } ?: { System.currentTimeMillis() },
                    entitlementTokenProvider = entitlementTokenProvider,
                    operationDecorator = operationDecorator,
                    contentGate = contentGate,
                    storageGate = storageGate,
                    pairingWaitPacer = pairingWaitPacer,
                )
            )
        }
        val client = TestClient(deviceId = deviceId, role = role, koinApp = koinApp, stepped = stepped)
        _clients += client
        return client
    }

    /**
     * Drive the test dispatcher forward in [pollInterval]-sized ticks until
     * [predicate] returns true or [timeout] elapses. Inside `runTest { ... }`
     * this consumes virtual time, not wall time.
     *
     * Throws [kotlinx.coroutines.TimeoutCancellationException] on timeout —
     * the failure surfaces with a stack trace pointing at the `advanceUntil`
     * call so the test reports which condition never held.
     */
    suspend fun advanceUntil(
        timeout: Duration = 30.seconds,
        pollInterval: Duration = 100.milliseconds,
        predicate: suspend () -> Boolean
    ) {
        withTimeout(timeout) {
            while (!predicate()) {
                delay(pollInterval)
            }
        }
    }

    /**
     * Stepped mode: one tick on each of [devices], **in the order given**, each finishing before the
     * next starts. `sync(phone, tablet)` means the phone pushes (and pulls) before the tablet pulls
     * (and pushes) — the interleaving a polled test can only hope for.
     *
     * Each tick first waits for the device's queued saves to become blocks ([TestClient.stepOnce]),
     * so a write the test made before this call is pushed by this call.
     */
    suspend fun sync(vararg devices: TestClient) {
        requireStepped(devices.asList())
        devices.forEach { it.stepOnce() }
    }

    /**
     * Stepped mode: rounds of [sync] over [devices] (every client, in registration order, by
     * default) until [predicate] holds. Checked before the first round, so a state that already
     * holds costs no tick. Returns the number of rounds it took.
     *
     * Fails with [what] after [maxRounds] rounds. ⚠ That bound is a count of rounds, not a time, so
     * a slow machine cannot reach it; a red here says the state was never reached by syncing.
     */
    suspend fun syncUntil(
        what: String,
        devices: List<TestClient> = clients,
        maxRounds: Int = DEFAULT_MAX_ROUNDS,
        predicate: suspend () -> Boolean,
    ): Int {
        requireStepped(devices)
        var rounds = 0
        while (!predicate()) {
            if (rounds == maxRounds) {
                throw AssertionError(
                    "$what — not after $maxRounds stepped rounds of " +
                        devices.joinToString(prefix = "[", postfix = "]") { it.deviceId },
                )
            }
            devices.forEach { it.stepOnce() }
            rounds++
        }
        return rounds
    }

    /**
     * Stepped mode: rounds of [sync] over [devices] until **two consecutive rounds in which nothing
     * moved** — the relay accepted no block or tombstone, and no device's tick left more to pull.
     * Returns the number of rounds, both still ones included.
     *
     * Why two. After the first still round every device has ticked since the last push, so each
     * has pulled everything there is. But a device that pulled in that round may have queued a
     * reaction to what it ingested (a merge write, a cascade), and that reaction only pushes on the
     * device's *next* tick. The second still round is that next tick for every device: nothing
     * pushed, so nothing was waiting. That is also the stepped form of "and it **stays** that way":
     * a resurrection is a later tick's push, and two still rounds are two ticks per device with none.
     *
     * ⚠ Blocks and tombstones are what it counts. Content-plane chunk uploads are not on the
     * relay's arrival counter; a test about bytes awaits them by their own state.
     */
    suspend fun syncUntilQuiet(
        devices: List<TestClient> = clients,
        maxRounds: Int = DEFAULT_MAX_ROUNDS,
    ): Int {
        requireStepped(devices)
        var stillRounds = 0
        repeat(maxRounds) { round ->
            val before = syncMailbox.lastArrivalSeq
            var backlog = false
            devices.forEach { if (it.stepOnce()) backlog = true }
            stillRounds = if (!backlog && syncMailbox.lastArrivalSeq == before) stillRounds + 1 else 0
            if (stillRounds == 2) return round + 1
        }
        throw AssertionError(
            "sync never went quiet in $maxRounds stepped rounds of " +
                devices.joinToString(prefix = "[", postfix = "]") { it.deviceId } +
                " — something pushes, or has more to pull, on every round",
        )
    }

    private fun requireStepped(devices: List<TestClient>) {
        check(stepped) {
            "sync/syncUntil/syncUntilQuiet are stepped-mode helpers; build with TestSystem.builder().stepped(). " +
                "A polled system's loops would tick between the steps, so the order would not be the test's."
        }
        require(devices.isNotEmpty()) { "no devices to step" }
        devices.forEach { device ->
            require(_clients.any { it === device }) { "'${device.deviceId}' is not a client of this TestSystem" }
        }
    }

    suspend fun shutdown() {
        // Read before any graph closes. Thrown last, so a violation never skips the teardown.
        val unlockedStoreAccesses = _clients.flatMap { client ->
            (client.koinApp.koin.get<ObjectStore>() as? LockCheckedObjectStore)
                ?.unlockedAccesses
                ?.map { (access, times) -> "[${client.deviceId}] ${times}x $access" }
                .orEmpty()
        }
        _clients.forEach {
            // Cancel the per-client `clientScope` and *wait* for in-flight
            // coroutines to actually finish before closing the Koin scope.
            // `cancel()` is non-blocking — coroutines mid-suspension or
            // mid-non-suspending-section keep running until they reach a
            // cancellation point; if we close Koin first they then hit a
            // closed scope on their next `get(...)`. The
            // 2s timeout caps shutdown latency for the rare case a coroutine
            // is stuck (e.g. waiting on the relay's testApplication client)
            // — better to log + continue than hang the whole suite.
            //
            // With ClientRegistry's SupervisorJob now properly parented to
            // serviceScope, this also cascades into every `sessionScope.launch`
            // (observer fanout, stream dispatch) — the orphaned-supervisor
            // bug that surfaced as cross-test suite interference is fixed at
            // the parenting site, and this awaits the cancellation.
            val scopeJob = it.koinApp.koin.get<CoroutineScope>(named("clientScope"))
                .coroutineContext[Job]!!
            val joined = withTimeoutOrNull(2_000) { scopeJob.cancelAndJoin() }
            // The "log + continue" this comment promised never existed, so a straggler that
            // missed the cap was abandoned alive and silently: the next `get(...)` it made hit the
            // closed graph below and was blamed on whichever `runTest` started next
            // (docs/tech-debt.md § "The full gate is flaky under parallel load", P0b). Say so —
            // on stderr, which Gradle keeps in the class's XML — so a sighting can be matched to
            // the run that produced it instead of inferred from a stack.
            if (joined == null) {
                System.err.println(
                    "TestSystem.shutdown: client '${it.deviceId}' clientScope did not finish " +
                        "cancelling within 2 s; a straggler may outlive this test and fail a later one",
                )
            }
            it.koinApp.close()
        }
        _clients.clear()
        relayHttpClient.close()
        relayApp.stop()
        onShutdown()
        if (unlockedStoreAccesses.isNotEmpty()) {
            throw AssertionError(
                "The object store was accessed without the DatabaseLock. No ObjectStore is " +
                    "thread-safe on its own, so each of these races every op on the device:\n" +
                    unlockedStoreAccesses.sorted().joinToString("\n"),
            )
        }
    }

    companion object {
        fun builder(): TestSystemBuilder = TestSystemBuilder()

        /**
         * [syncUntil] / [syncUntilQuiet]'s default round cap. A two-device exchange settles in two
         * or three rounds (push, pull, the pull's own reaction); the margin is for multi-hop chains
         * such as a pairing plus a first pull. It is a count, so it never trades a red for a slow box.
         */
        const val DEFAULT_MAX_ROUNDS = 25

        /** [client]'s default cadence, in a polled system. Meaningless, and refused, in a stepped one. */
        const val DEFAULT_POLLING_INTERVAL_MS = 100L
    }
}
