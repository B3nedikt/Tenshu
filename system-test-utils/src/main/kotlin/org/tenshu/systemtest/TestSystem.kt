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
        pollingIntervalMs: Long = 100,
        /** Commerce bearer token for `X-Entitlement`; `{ null }` = none, the default everywhere. */
        entitlementTokenProvider: suspend () -> String? = { null },
        /** See `systemTestPlatformModule`'s parameter of the same name. */
        operationDecorator: (operationType: String, implementation: OperationImplementation) -> OperationImplementation =
            { _, implementation -> implementation },
        /** See `systemTestPlatformModule`'s parameter of the same name. */
        contentGate: org.tenshu.sync.common.SyncGate = org.tenshu.sync.common.SyncGate.AlwaysAllow,
        /** See `systemTestPlatformModule`'s parameter of the same name — the personal push gate. */
        storageGate: org.tenshu.sync.common.SyncGate = org.tenshu.sync.common.SyncGate.AlwaysAllow,
    ): TestClient {
        require(_clients.none { it.deviceId == deviceId }) {
            "TestClient with deviceId=$deviceId already registered"
        }
        val koinApp = koinApplication {
            modules(
                systemTestProxyModule(),
                systemTestPlatformModule(
                    deviceId = deviceId,
                    relayHttpClient = relayHttpClient,
                    pollingIntervalMs = pollingIntervalMs,
                    entitlementTokenProvider = entitlementTokenProvider,
                    operationDecorator = operationDecorator,
                    contentGate = contentGate,
                    storageGate = storageGate,
                )
            )
        }
        val client = TestClient(deviceId = deviceId, role = role, koinApp = koinApp)
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
    }
}
