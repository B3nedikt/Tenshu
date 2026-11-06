package org.tenshu.core.common.transport

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.assertFalse
import org.tenshu.core.CancellableTaskInteractor
import org.tenshu.core.Data
import org.tenshu.core.Operation
import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.toTList
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.impl.OperationDto
import org.tenshu.core.toTString
import org.tenshu.core.common.schema.currentVersionVector
import org.tenshu.core.common.app.AnnounceSession
import org.tenshu.core.common.impl.OperationRo
import org.tenshu.core.common.module.operation
import org.tenshu.core.common.module.tModule
import org.tenshu.core.common.parser.BinaryParser
import org.tenshu.core.common.parser.BinarySerializer
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.AsyncOperationImplementation
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.request.LocalRequestChain
import org.tenshu.core.schema.Type
import org.tenshu.core.schema.operation
import org.tenshu.core.trait.BackgroundOperation
import org.tenshu.core.trait.TerminalOperation
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Wire-level `TaskHandle` / cross-process cancellation
 * (docs/proposals/core-background-processing.md §8 "Phase L2").
 *
 * Scope: the cross-process path (ProxyGateway in-flight registry + transport
 * cancel frame).
 */
class PhaseL2CancellationTest {

    private val stubSchema = operation(
        id = "phase-l2-stub",
        traits = listOf(TerminalOperation.Schema, BackgroundOperation.Schema)
    )

    /** A long-running BackgroundOperation that stays inside the request
     *  coroutine; records start and cooperative cancellation. */
    private class StubBgOp(
        val started: CompletableDeferred<Unit>,
        val cancelled: CompletableDeferred<Unit>,
        val release: CompletableDeferred<Unit> = CompletableDeferred()
    ) : AsyncOperationImplementation {
        override suspend fun <T : Data?> execute(
            executionContext: ExecutionContext,
            operationContext: OperationContext?,
            implementationFactory: OperationImplementationFactory,
            caller: Any?,
            params: Map<String, Data>?
        ): T {
            started.complete(Unit)
            try {
                release.await()
                @Suppress("UNCHECKED_CAST")
                return null as T
            } catch (e: CancellationException) {
                cancelled.complete(Unit)
                throw e
            }
        }
    }

    private fun proxyWithStub(
        impl: AsyncOperationImplementation,
        serviceScope: CoroutineScope = CoroutineScope(Dispatchers.Default)
    ): TestProxy =
        TestProxy(
            coroutineScope = serviceScope,
            implementationFactoryOverride = { id, _ ->
                if (id == stubSchema.id.get()) impl else null
            }
        ).apply {
            schemaRegistry.registerConfig(
                tModule {
                    operation(interfaceClass = Operation::class, protocolSchema = stubSchema)
                }
            )
            // §6.5 reject-if-silent gate: this test drives gateway.call directly
            // with no announce wiring — pre-admit the session it uses.
            admitSchemaSession("c1")
        }

    private fun TestProxy.stubCallstackBytes(): ByteArray {
        val task: TList<Data> =
            listOf<Data>(OperationDto(params = emptyMap(), schema = stubSchema))
                .toTList(tContext)
        return serializer.serialize(
            task,
            isTopLevel = true,
            expectedType = Type.create(baseSchema = TList.Schema)
        )
    }

    // --- Registry add-on-start / remove-on-complete + proxy coroutine stopped ---

    @Test
    fun gateway_cancel_stops_the_proxy_coroutine_and_is_idempotent() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val proxy = proxyWithStub(StubBgOp(started, cancelled))
        val bytes = proxy.stubCallstackBytes()

        val callJob = launch(Dispatchers.Default) {
            try {
                proxy.gateway.call("c1", bytes, taskId = "t1")
            } catch (_: CancellationException) {
                // expected: requestJob cancellation unwinds the call
            }
        }

        withTimeout(5_000) { started.await() } // registered + proxy coroutine running
        // The in-flight count drives ForegroundTaskLiveness (§8 "Phase L2-LIVE").
        assertEquals(1, proxy.gateway.inFlightCount.value, "registered task ticks inFlightCount")
        proxy.gateway.cancel("c1", "t1") // out-of-band wire cancel
        withTimeout(5_000) { cancelled.await() } // PROXY coroutine actually stopped
        callJob.join()
        assertEquals(0, proxy.gateway.inFlightCount.value, "completion deregisters → idle")

        // remove-on-complete: the entry is gone, so a repeat cancel and an
        // unknown id are both harmless no-ops (must not throw / hang).
        proxy.gateway.cancel("c1", "t1")
        proxy.gateway.cancel("c1", "never-existed")
    }

    @Test
    fun cancel_arriving_before_registration_still_cancels_the_call() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val proxy = proxyWithStub(StubBgOp(started, cancelled))
        val bytes = proxy.stubCallstackBytes()

        // Cancel parked before the call registers (the FIFO-on-wire-but-async-
        // dispatch race the pendingCancels set closes).
        proxy.gateway.cancel("c1", "early")

        val result = runCatching {
            withTimeout(5_000) { proxy.gateway.call("c1", bytes, taskId = "early") }
        }
        assertTrue(
            result.exceptionOrNull() is CancellationException,
            "call registered with a pre-parked cancel must be cancelled immediately"
        )
        assertTrue(!started.isCompleted, "the op body must not have run")
    }

    // --- Per-transport cancel-frame round-trip: desktop Unix socket ---

    @Test
    fun socket_cancel_frame_stops_the_server_side_coroutine() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val proxy = proxyWithStub(StubBgOp(started, cancelled))

        val socketPath = Files.createTempFile("tenshu-l2-test", ".sock")
            .also { Files.delete(it) }
            .toAbsolutePath()
            .toString()

        val server = UnixSocketServer(proxy.gateway, proxy.serializer, socketPath)
        server.start()

        val parser = BinaryParser(proxy.schemaRegistry, proxy.schemaRegistry)
        val transport = UnixSocketTaskTransport(parser, socketPath)
        val executor = RemoteBinaryTaskExecutor(
            BinarySerializer(), parser, transport, proxy.tContext, this
        )
        // §6.5 gate: the socket path is a real serialized transport, so this
        // client announces like production does — through the session
        // preflight, exercising the whole mechanism over the real socket.
        executor.sessionPreflight = {
            val announce: TList<Data> = listOf<Data>(
                OperationDto(
                    params = mapOf(
                        AnnounceSession.Params.APP_ID to "app.test".toTString(proxy.tContext),
                        AnnounceSession.Params.CURRENT to
                            proxy.schemaRegistry.currentVersionVector(proxy.tContext),
                        AnnounceSession.Params.REQUIRES to
                            proxy.schemaRegistry.currentVersionVector(proxy.tContext),
                    ),
                    schema = AnnounceSession.Schema,
                )
            ).toTList(proxy.tContext)
            executor.executeAsync(announce)
        }
        executor.bind()

        try {
            val task: TList<Data> =
                listOf<Data>(OperationDto(params = emptyMap(), schema = stubSchema))
                    .toTList(proxy.tContext)

            val callJob = launch(Dispatchers.Default) {
                try {
                    executor.executeAsyncCancellable(task, "wire-1")
                } catch (_: CancellationException) {
                }
            }

            // ⚠ 8 s is not a latency budget and must not be "fixed" by raising it: these waits
            // measure **24 ms** and **1 ms** on a quiet box (333× / 8000× margins), so a timeout
            // here means a signal never arrived — e.g. a connect landing before `UnixSocketServer
            // .start()`'s socket is bound, which is swallowed silently.
            withTimeout(8_000) { started.await() } // request crossed the socket + running
            executor.cancel("wire-1") // TYPE_CANCEL frame, out-of-band
            withTimeout(8_000) { cancelled.await() } // server-side coroutine stopped
            callJob.cancel()
        } finally {
            executor.unbind()
            Files.deleteIfExists(Path.of(socketPath))
        }
    }

    // --- Discipline boundary: work detached onto sessionScope survives cancel ---

    @Test
    fun work_detached_onto_sessionScope_is_NOT_reached_by_the_wire_cancel() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val detachedCompleted = CompletableDeferred<Unit>()

        val detachingImpl = object : AsyncOperationImplementation {
            override suspend fun <T : Data?> execute(
                executionContext: ExecutionContext,
                operationContext: OperationContext?,
                implementationFactory: OperationImplementationFactory,
                caller: Any?,
                params: Map<String, Data>?
            ): T {
                // The leak the static guard below forbids: escaping requestJob onto sessionScope.
                executionContext.sessionScope.launch {
                    delay(500)
                    detachedCompleted.complete(Unit)
                }
                started.complete(Unit)
                try {
                    awaitCancellation()
                } catch (e: CancellationException) {
                    cancelled.complete(Unit)
                    throw e
                }
            }
        }
        val proxy = proxyWithStub(detachingImpl)
        val bytes = proxy.stubCallstackBytes()

        val callJob = launch(Dispatchers.Default) {
            try {
                proxy.gateway.call("c1", bytes, taskId = "t1")
            } catch (_: CancellationException) {
            }
        }
        withTimeout(5_000) { started.await() }
        proxy.gateway.cancel("c1", "t1")
        withTimeout(5_000) { cancelled.await() } // in-request part IS cancelled
        callJob.join()

        // ...but the detached coroutine outlives requestJob — documenting that
        // crossing the scope boundary defeats the wire cancel.
        withTimeout(5_000) { detachedCompleted.await() }
    }

    // --- Static guard: no feature-service proxy op impl may detach ---

    @Test
    fun no_proxy_operation_implementation_detaches_onto_session_or_service_scope() {
        val repoRoot = generateSequence(File(".").absoluteFile) { it.parentFile }
            .take(10)
            .firstOrNull { File(it, "settings.gradle.kts").exists() }
        if (repoRoot == null) {
            println("PhaseL2: repo root not locatable; skipping source static check")
            return
        }

        val proxyModules = listOf(
            "core-proxy",
            "file-service-proxy",
            "sync-service-proxy",
            "notification-service-proxy",
            "identity-service-proxy",
        )
        val detachRegex = Regex("""\b(sessionScope|serviceScope)\.launch\s*[({]""")

        val offenders = proxyModules
            .map { File(repoRoot, "$it/src/commonMain") }
            .filter { it.isDirectory }
            .flatMap { it.walkTopDown().filter { f -> f.isFile && f.extension == "kt" } }
            .filter { detachRegex.containsMatchIn(it.readText().withoutComments()) }
            .map { it.relativeTo(repoRoot).path }

        assertEquals(
            emptyList(),
            offenders,
            "Wire-cancel discipline boundary: a BackgroundOperation handled in the " +
                "request coroutine is only cancellable while its work stays there. " +
                "These proxy operation implementations detach onto " +
                "sessionScope/serviceScope and would survive a wire cancel. Move " +
                "the work back into the request coroutine, or this is a deliberate " +
                "fire-and-forget that must be documented and allowlisted here."
        )
    }

    // --- Provider routing: a BackgroundOperation resolves to a handle ---

    @Test
    fun background_operation_resolves_to_a_CancellableTaskInteractor() = runBlocking {
        val proxy = proxyWithStub(StubBgOp(CompletableDeferred(), CompletableDeferred()))

        val ro = object : OperationRo(
            requestChain = LocalRequestChain(proxy.localTaskExecutor, proxy.tContext)
                .addArray(OperationDto(schema = stubSchema, params = emptyMap())),
            context = proxy.tContext
        ) {
            override val schema = stubSchema
        }

        val interactor = proxy.interactorProvider.getInteractor<Data?>(ro)
        assertTrue(
            interactor is CancellableTaskInteractor<*>,
            "a BackgroundOperation (no IoOperation) must resolve to a " +
                "CancellableTaskInteractor, got ${interactor::class.simpleName}"
        )
        val handle = (interactor as CancellableTaskInteractor<Data?>).execute()
        assertTrue(handle.taskId.isNotBlank(), "handle must carry a client-minted task id")
    }

    // --- A withTask block covers Background ops — the TASK's id is the
    // L2 correlation id, so the op's saves join the task's sync lane
    // (ProxyGateway → requestSyncLaneId) and cancel addresses the task's
    // current in-flight call. core-task-composite.md §5.

    @Test
    fun background_operation_inside_withTask_reuses_the_tasks_id() = runBlocking {
        val proxy = proxyWithStub(StubBgOp(CompletableDeferred(), CompletableDeferred()))

        val ro = object : OperationRo(
            requestChain = LocalRequestChain(proxy.localTaskExecutor, proxy.tContext)
                .addArray(OperationDto(schema = stubSchema, params = emptyMap())),
            context = proxy.tContext
        ) {
            override val schema = stubSchema
        }

        val interactor = kotlinx.coroutines.withContext(
            org.tenshu.core.WithTaskElement("task-under-test")
        ) {
            proxy.interactorProvider.getInteractor<Data?>(ro)
        }
        val handle = (interactor as CancellableTaskInteractor<Data?>).execute()
        assertEquals(
            "task-under-test", handle.taskId,
            "inside withTask, a Background op must ride the TASK's id, not a minted one"
        )
    }

    // --- Option A: Layer-2 task survives client unbind (clear/unregister) ---
    // docs/proposals/core-background-processing.md, § "Known gap — L2-LIVE is
    // necessary but not sufficient" (defects 1 & 2).

    @Test
    fun unbind_does_NOT_cancel_an_in_flight_taskId_call_but_explicit_cancel_does() =
        runBlocking {
            val started = CompletableDeferred<Unit>()
            val cancelled = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val proxy = proxyWithStub(StubBgOp(started, cancelled, release))
            val bytes = proxy.stubCallstackBytes()

            val callJob = launch(Dispatchers.Default) {
                try {
                    proxy.gateway.call("c1", bytes, taskId = "t1")
                } catch (_: CancellationException) {
                }
            }
            withTimeout(5_000) { started.await() }
            assertEquals(1, proxy.gateway.inFlightCount.value)

            // ⚠ This is the BLANKET teardown, not the unbind path:
            // ProxyService.onUnbind calls unregisterClient() per bound client.
            // unregisterAll() → ClientRegistry.clear() → sessionScope.cancel() +
            // context.destroy() survives as this test's subject and has no other
            // production caller. A detached Layer-2 task must NOT be collaterally
            // cancelled even here.
            proxy.gateway.unregisterAll()

            // Give any erroneous cancellation a chance to propagate.
            delay(300)
            assertFalse(
                cancelled.isCompleted,
                "Option A: a taskId-carrying call must outlive the client " +
                    "binding — clear()/unbind must not cancel its requestJob"
            )
            assertFalse(callJob.isCompleted, "the detached call must still be running")
            assertEquals(1, proxy.gateway.inFlightCount.value, "still in flight")

            // Explicit client-driven cancel still works (via the inFlight map,
            // independent of job lineage / the detached parent).
            proxy.gateway.cancel("c1", "t1")
            withTimeout(5_000) { cancelled.await() }
            callJob.join()
            assertEquals(0, proxy.gateway.inFlightCount.value, "drained → idle")
        }

    @Test
    fun unbind_DOES_cancel_an_in_flight_non_taskId_call_existing_behaviour() =
        runBlocking {
            val started = CompletableDeferred<Unit>()
            val cancelled = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>() // never completed
            val proxy = proxyWithStub(StubBgOp(started, cancelled, release))
            val bytes = proxy.stubCallstackBytes()

            // No taskId → ordinary one-shot RPC, session-parented. Cancel on
            // client death is correct here (the client is synchronously
            // awaiting); Option A must preserve this.
            val callJob = launch(Dispatchers.Default) {
                try {
                    proxy.gateway.call("c1", bytes)
                } catch (_: CancellationException) {
                }
            }
            withTimeout(5_000) { started.await() }

            proxy.gateway.unregisterAll() // sessionScope.cancel() cascades

            withTimeout(5_000) { cancelled.await() } // IS cancelled
            callJob.join()
        }

    @Test
    fun full_service_scope_shutdown_cancels_a_detached_task_no_leak() =
        runBlocking {
            val started = CompletableDeferred<Unit>()
            val cancelled = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>() // never completed
            val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val proxy = proxyWithStub(StubBgOp(started, cancelled, release), serviceScope)
            val bytes = proxy.stubCallstackBytes()

            val callJob = launch(Dispatchers.Default) {
                try {
                    proxy.gateway.call("c1", bytes, taskId = "t1")
                } catch (_: CancellationException) {
                }
            }
            withTimeout(5_000) { started.await() }

            // A detached task survives a per-client unbind (clear)…
            proxy.gateway.unregisterAll()
            delay(200)
            assertFalse(cancelled.isCompleted, "survives the routine unbind")

            // …but a true proxy/service shutdown (the service scope the
            // detached requestJob is parented to being cancelled) MUST reach
            // it — no leak past process shutdown.
            serviceScope.cancel("service shutdown")
            withTimeout(5_000) { cancelled.await() }
            callJob.join()
        }
}

/**
 * Strips `//` and block comments so the discipline scan matches **code**, not prose.
 *
 * ⚠ Not cosmetic: without it, a KDoc explaining *why* a call site must not detach is reported as
 * an offender. A check that punishes documenting the pattern it enforces teaches people to stop
 * documenting it.
 *
 * Deliberately naive (a `//` inside a string literal is stripped too). The alternative is a Kotlin
 * parser for a five-line guard; a false *negative* here costs a missed detach, which the reviewer
 * and the runtime both still catch, while a false positive breaks the build.
 */
private fun String.withoutComments(): String =
    replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), " ")
        .replace(Regex("""//[^\n]*"""), " ")
