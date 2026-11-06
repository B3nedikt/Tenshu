package app.tenshu.control.di

import app.tenshu.control.notifications.PendingRequestsRepository
import app.tenshu.control.sync.HostPairingMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.koin.core.Koin
import org.koin.core.qualifier.named
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.koin.core.module.dsl.onClose
import org.koin.core.module.dsl.withOptions
import org.tenshu.core.common.TenshuClient
import org.tenshu.core.common.delegate.InMemorySecureStorageDelegate
import org.tenshu.core.common.delegate.SecureStorageDelegate
import org.tenshu.core.common.proxy.ClientRegistry
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.FileDatabase
import org.tenshu.core.common.proxy.InMemoryFileDatabase
import org.tenshu.core.common.proxy.db.InMemoryObjectStore
import org.tenshu.core.common.proxy.db.ObjectStore
import org.tenshu.core.common.request.AsyncTaskExecutor
import org.tenshu.inference.proxy.MediaIndexRunner
import org.tenshu.inference.proxy.catalog.ModelDeliveryComponents
import org.tenshu.mapregion.proxy.MapRegionProxyComponents
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * ⚠⚠ Q45 (`ios-shared-store-sqlite.md` B.16.8): **a proxy graph's Koin close cancels every scope
 * its definitions made for themselves**, and nothing else's.
 *
 * Before, `Koin.close()` dropped the singletons and cancelled nothing: the model downloader's,
 * `MediaIndexRunner`'s, the region components', `serviceScope` (and every session scope under it),
 * the announcer's scope all outlived an iOS rebuild's old graph, so a download already running
 * finished in the orphaned graph.
 *
 * ⭐ The real `buildProxyModule()` over the desktop platform module, with only the stores that
 * would reach the developer's real data directory replaced, and the platform's per-session
 * initialisation left out (it is not what is under test and it starts the sync runtime).
 *
 * ⚠⚠ A full graph is torn down here by cancelling its root and **joining** it before the close,
 * never by a bare close: `HostPairingMonitor` and the pending-requests feed start ops in their
 * `init`, on scopes with no exception handler, and an op caught mid-flight by a close throws a
 * non-cancellation exception into kotlinx-coroutines-test's collector, which fails the next
 * `runTest` in the JVM (`ScheduleNetworkConstraintTest`, seen in the gate). The close's own cancel
 * is tested over the bare module.
 */
class ProxyGraphJobTeardownTest {

    private fun graph(): Koin = koinApplication {
        modules(
            buildProxyModule(),
            buildPlatformProxyModule(),
            module {
                single(named("platformPackageName")) { CLIENT }
                single<ObjectStore> { InMemoryObjectStore() }
                single<FileDatabase> { InMemoryFileDatabase() }
                single<SecureStorageDelegate> { InMemorySecureStorageDelegate() }
                single<ExecutionContext.() -> Unit>(named("platformProxyInitialization")) { {} }
            },
        )
    }.koin

    /** Resolves every owner that makes a scope in `buildProxyModule()`. */
    private fun resolveOwners(koin: Koin) {
        koin.get<ModelDeliveryComponents>()
        koin.get<MediaIndexRunner>()
        koin.get<MapRegionProxyComponents>()
        koin.get<ClientRegistry>()
        koin.get<AsyncTaskExecutor>()
        koin.get<TenshuClient>()
        koin.get<HostPairingMonitor>()
        koin.get<PendingRequestsRepository>()
    }

    private val CoroutineScope.job: Job get() = assertNotNull(coroutineContext[Job], "a scope with no job cannot be cancelled")

    @Test
    fun `cancelling the graph's root cancels the scope of every owner, and the session scopes under serviceScope`() = runBlocking {
        val koin = graph()
        resolveOwners(koin)
        val graph = proxyGraphJob(koin)

        // Every owner made its scope through the graph's root job.
        assertEquals(ALL_OWNERS, graph.scopes.keys, "an owner made a scope that is not the graph's")
        graph.scopes.forEach { (owner, scope) -> assertTrue(scope.job.isActive, "$owner's scope is not running before the teardown") }

        // ⚠ The owners' own fields, where they can be read, are the scopes the graph made: the
        // record above is not a second copy that the owner never uses.
        val registry = koin.get<ClientRegistry>()
        val serviceScope = assertNotNull(registry.serviceScope)
        assertSame(graph.scopes.getValue(ProxyGraphJob.Owners.SERVICE_SCOPE), serviceScope)
        assertSame(graph.scopes.getValue(ProxyGraphJob.Owners.MEDIA_INDEX_RUNNER), privateScope(koin.get<MediaIndexRunner>()))
        assertSame(graph.scopes.getValue(ProxyGraphJob.Owners.HOST_PAIRING_MONITOR), privateScope(koin.get<HostPairingMonitor>()))
        assertSame(graph.scopes.getValue(ProxyGraphJob.Owners.PENDING_REQUESTS), privateScope(koin.get<PendingRequestsRepository>()))

        // A session minted under serviceScope, and work running on each owner's scope.
        val session = registry.getExecutionContext(CLIENT).sessionScope
        val running = graph.scopes.mapValues { (_, scope) -> scope.launch { awaitCancellation() } } +
            ("session" to session.launch { awaitCancellation() })

        graph.cancel()

        assertTrue(graph.job.isCancelled)
        graph.scopes.forEach { (owner, scope) -> assertTrue(scope.job.isCancelled, "$owner's scope outlived its graph's root") }
        assertTrue(session.job.isCancelled, "a session scope outlived its graph's root")
        withTimeout(5_000) { graph.job.join() }
        running.forEach { (owner, job) -> assertTrue(job.isCancelled, "work on $owner's scope was not cancelled") }
        koin.close()
    }

    @Test
    fun `a plain Koin close cancels the graph's root too`() = runBlocking {
        // The backstop for every closer that is not the iOS rebuild (the hub, the harnesses).
        val koin = koinApplication { modules(buildProxyModule()) }.koin
        val graph = proxyGraphJob(koin)
        val work = graph.scope("probe").launch { awaitCancellation() }

        koin.close()

        assertTrue(graph.job.isCancelled, "the graph's root job was not cancelled by its close")
        withTimeout(5_000) { work.join() }
        assertTrue(work.isCancelled)
    }

    @Test
    fun `the teardown cancels the root before the close drops any singleton`() {
        // B.16.15 (295): cancelled first, so a coroutine still at work meets its cancellation, not a
        // lookup in a graph already emptied. ⚠ Many probes, because Koin drops its singletons in its
        // map's order: with the close's own `onClose` alone, some probe is dropped first.
        val probes = 32
        val rootSeenAtDrop = mutableListOf<Boolean>()
        lateinit var root: Job
        val koin = koinApplication {
            modules(
                buildProxyModule(),
                module {
                    repeat(probes) { i ->
                        single(named("probe-$i")) { i } withOptions { onClose { rootSeenAtDrop += root.isCancelled } }
                    }
                },
            )
        }.koin
        root = proxyGraphJob(koin).job
        repeat(probes) { koin.get<Int>(named("probe-$it")) }

        closeProxyGraph(koin)

        assertEquals(probes, rootSeenAtDrop.size, "the probes were not dropped")
        assertTrue(rootSeenAtDrop.all { it }, "a singleton was dropped before the graph's root was cancelled: $rootSeenAtDrop")
    }

    @Test
    fun `closing one graph leaves another graph's scopes running`() = runBlocking {
        // The iOS rebuild closes the old graph after the new one's modules are fresh instances:
        // the new graph's root must be its own, never the closed one's.
        val old = koinApplication { modules(buildProxyModule()) }.koin
        val new = koinApplication { modules(buildProxyModule()) }.koin
        val oldGraph = proxyGraphJob(old)
        val newGraph = proxyGraphJob(new)
        oldGraph.scope("probe")
        val work = newGraph.scope("probe").launch { awaitCancellation() }

        closeProxyGraph(old)

        assertTrue(oldGraph.job.isCancelled)
        assertTrue(newGraph.job.isActive, "closing another graph cancelled this one's root")
        assertTrue(work.isActive)
        closeProxyGraph(new)
        withTimeout(5_000) { work.join() }
    }

    @Test
    fun `a graph that is never closed never cancels its scopes`() = runBlocking {
        // Android, desktop and the web: the graph is the process's, and nothing closes it.
        val koin = graph()
        resolveOwners(koin)
        val graph = proxyGraphJob(koin)
        assertFalse(graph.job.isCancelled)
        graph.scopes.forEach { (owner, scope) -> assertTrue(scope.job.isActive, "$owner's scope was cancelled with the graph open") }
        graph.cancel()
        withTimeout(5_000) { graph.job.join() }
        koin.close()
    }

    @Test
    fun `no definition of the proxy module makes a scope that is not the graph's`() {
        // ⚠ The trap a later owner falls into: `CoroutineScope(SupervisorJob() + …)` compiles,
        // works, and outlives a rebuilt graph. The runtime test above sees only today's owners.
        val module = File("src/commonMain/kotlin/app/tenshu/control/di/ProxyModule.kt")
        assertTrue(module.isFile, "ProxyModule.kt moved; this test is no longer guarding")
        val code = module.readLines().filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }
        val offenders = code.filter { "CoroutineScope(" in it || "SupervisorJob(" in it || "MainScope(" in it || "GlobalScope" in it }
            .filterNot { "): CoroutineScope =" in it }
        assertEquals(emptyList(), offenders, "a scope made outside `graphScope` outlives the graph (Q45)")
    }

    private fun privateScope(owner: Any): CoroutineScope {
        val field = generateSequence<Class<*>>(owner.javaClass) { it.superclass }
            .firstNotNullOfOrNull { c -> c.declaredFields.firstOrNull { it.name == "scope" } }
        assertNotNull(field, "${owner.javaClass.simpleName} has no `scope` field: this check moved")
        field.isAccessible = true
        return field.get(owner) as CoroutineScope
    }

    private companion object {
        const val CLIENT = "app.tenshu.control.test"
        val ALL_OWNERS = setOf(
            ProxyGraphJob.Owners.MODEL_DELIVERY,
            ProxyGraphJob.Owners.MEDIA_INDEX_RUNNER,
            ProxyGraphJob.Owners.MAP_REGION,
            ProxyGraphJob.Owners.SERVICE_SCOPE,
            ProxyGraphJob.Owners.CLIENT_STREAM,
            ProxyGraphJob.Owners.SCHEMA_ANNOUNCER,
            ProxyGraphJob.Owners.HOST_PAIRING_MONITOR,
            ProxyGraphJob.Owners.PENDING_REQUESTS,
        )
    }
}
