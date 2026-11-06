package org.tenshu.core.common.proxy

import kotlinx.coroutines.runBlocking
import org.tenshu.core.Data
import org.tenshu.core.Operation
import org.tenshu.core.TResult
import org.tenshu.core.UnannouncedSessionError
import org.tenshu.core.collection.list.TList
import org.tenshu.core.id
import org.tenshu.core.collection.list.toTList
import org.tenshu.core.common.TENSHU_STORAGE_FORMAT_VERSION
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.app.AnnounceSession
import org.tenshu.core.common.impl.OperationDto
import org.tenshu.core.common.module.tModule
import org.tenshu.core.common.module.operation
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.SyncOperationImplementation
import org.tenshu.core.common.schema.SessionAdmission
import org.tenshu.core.common.schema.VersionVector
import org.tenshu.core.common.schema.currentVersionVector
import org.tenshu.core.schema.Type
import org.tenshu.core.schema.operation
import org.tenshu.core.toTString
import org.tenshu.core.trait.TerminalOperation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The reject-if-silent gate (`core-schema-evolution.md` §6.5) at the
 * serialized-transport boundary: a session with no admitted announce may run
 * exactly one thing over the wire — `AnnounceSession` — and everything else
 * fails with the typed `UnannouncedSessionError` instead of risking a skewed
 * client's positional bytes (the HR #9 cryptic-`error/internal` class, §6.7).
 */
class SchemaSessionGateTest {

    private val stubSchema = operation(
        id = "gate-stub",
        traits = listOf(TerminalOperation.Schema)
    )

    private class StubOp : SyncOperationImplementation {
        var runs = 0
        override fun <T : Data?> execute(
            executionContext: ExecutionContext,
            operationContext: OperationContext?,
            implementationFactory: OperationImplementationFactory,
            caller: Any?,
            params: Map<String, Data>?
        ): T {
            runs++
            @Suppress("UNCHECKED_CAST")
            return null as T
        }
    }

    private fun proxyWithStub(stub: StubOp): TestProxy =
        TestProxy(
            implementationFactoryOverride = { id, _ ->
                if (id == stubSchema.id.get()) stub else null
            }
        ).apply {
            schemaRegistry.registerConfig(
                tModule { operation(interfaceClass = Operation::class, protocolSchema = stubSchema) }
            )
        }

    private fun TestProxy.bytesOf(vararg elements: Data): ByteArray {
        val task: TList<Data> = elements.toList().toTList(tContext)
        return serializer.serialize(
            task,
            isTopLevel = true,
            expectedType = Type.create(baseSchema = TList.Schema)
        )
    }

    private fun TestProxy.stubCallBytes(): ByteArray =
        bytesOf(OperationDto(params = emptyMap(), schema = stubSchema))

    private fun TestProxy.announceBytes(
        vector: VersionVector = schemaRegistry.currentVersionVector(tContext),
    ): ByteArray = bytesOf(
        OperationDto(
            params = mapOf(
                AnnounceSession.Params.APP_ID to "app.test".toTString(tContext),
                AnnounceSession.Params.CURRENT to vector,
                AnnounceSession.Params.REQUIRES to vector,
            ),
            schema = AnnounceSession.Schema,
        )
    )

    private fun TestProxy.parseResult(bytes: ByteArray): TResult<Data> =
        parser.parse(bytes, expectedType = Type.create(baseSchema = TResult.Schema))

    @Test
    fun unannouncedSessionIsRefusedTyped() = runBlocking {
        val stub = StubOp()
        val proxy = proxyWithStub(stub)

        val result = proxy.parseResult(proxy.gateway.call("c1", proxy.stubCallBytes()))

        assertTrue(result is TResult.Failure, "the gate must refuse an unannounced session")
        assertEquals(UnannouncedSessionError.ID, result.error.id)
        assertEquals(0, stub.runs, "the stub must never execute behind the gate")
    }

    @Test
    fun announceItselfPassesTheGateAndAdmitsTheSession() = runBlocking {
        val stub = StubOp()
        val proxy = proxyWithStub(stub)

        val announceResult = proxy.parseResult(proxy.gateway.call("c1", proxy.announceBytes()))
        assertTrue(announceResult is TResult.Success, "announce must pass the gate: $announceResult")
        val admission = announceResult.value as SessionAdmission
        assertTrue(admission.admitted.get())

        val stubResult = proxy.parseResult(proxy.gateway.call("c1", proxy.stubCallBytes()))
        assertTrue(stubResult is TResult.Success, "an admitted session runs ops: $stubResult")
        assertEquals(1, stub.runs)
    }

    @Test
    fun refusedAnnounceLeavesTheSessionGated() = runBlocking {
        val stub = StubOp()
        val proxy = proxyWithStub(stub)

        // Announce a core version the proxy doesn't run → refused, typed, successful reply.
        val refused = proxy.parseResult(
            proxy.gateway.call(
                "c1",
                proxy.announceBytes(
                    vector = VersionVector.create(
                        proxy.tContext,
                        coreVersion = TENSHU_STORAGE_FORMAT_VERSION + 1,
                        services = emptyList(),
                    )
                )
            )
        )
        assertTrue(refused is TResult.Success, "a refusal is a successful typed reply (§6.3)")
        val admission = refused.value as SessionAdmission
        assertEquals(false, admission.admitted.get())

        val stubResult = proxy.parseResult(proxy.gateway.call("c1", proxy.stubCallBytes()))
        assertTrue(stubResult is TResult.Failure, "a refused session stays gated")
        assertEquals(UnannouncedSessionError.ID, stubResult.error.id)
        assertEquals(0, stub.runs)
    }
}
