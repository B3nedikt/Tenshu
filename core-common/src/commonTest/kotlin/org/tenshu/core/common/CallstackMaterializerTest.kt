package org.tenshu.core.common

import org.tenshu.core.ChainTemplate
import org.tenshu.core.Composite
import org.tenshu.core.Data
import org.tenshu.core.Operation
import org.tenshu.core.common.impl.OperationDto
import org.tenshu.core.common.module.coreCommonModule
import org.tenshu.core.common.module.composite
import org.tenshu.core.common.module.operation
import org.tenshu.core.common.module.tModule
import org.tenshu.core.common.proxy.CallstackMaterializer
import org.tenshu.core.common.proxy.SchemaRegistry
import org.tenshu.core.core.TContext
import org.tenshu.core.core.findOperation
import org.tenshu.core.request.RequestChain
import org.tenshu.core.request.RequestObject
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.operation
import org.tenshu.core.schema.toType
import kotlin.test.Test
import kotlin.test.assertEquals

class CallstackMaterializerTest {

    interface TestComposite : Composite {
        companion object {
            const val ID = "test/composite"
            val Schema = composite(id = ID)
        }
    }

    interface TestOperation : Operation {
        companion object {
            const val ID = "test/operation"
            val Schema = operation(
                id = ID,
                receiver = TestComposite.Schema.toType(),
                returnType = Data.Schema.toType()
            )
        }
    }

    class TestCompositeRo(
        override val requestChain: RequestChain,
        override val context: TContext
    ) : TestComposite, RequestObject {
        override val schema: CompositeSchema get() = TestComposite.Schema
        override fun <T : Data> getField(reference: String): T = error("Not needed")
        override fun <T : Data> getOptionalField(reference: String): T? = null
    }

    class TestOperationRo(
        override val requestChain: RequestChain,
        override val context: TContext
    ) : TestOperation, RequestObject {
        override val schema: OperationSchema get() = TestOperation.Schema
        override fun <T : Data> getField(reference: String): T = error("Not needed")
        override fun <T : Data> getOptionalField(reference: String): T? = null
    }

    fun TestComposite.testOperation(): Data {
        return findOperation<TestOperation>(TestOperation.ID)
    }

    @Test
    fun invokesLambdaOnEveryMaterialiseCall() {
        // Chain caching for param-less relations now lives on the schema itself
        // (`dynamicPropertyRelation(receiver = ...)` materialises the chain at
        // schema-construction time — `core-relation-system.md` Phase 4). The
        // materializer is the resolve-time fallback for param-bearing and
        // wire-deserialised cases, where every call must re-invoke the lambda.
        val testModule = tModule {
            composite(
                interfaceClass = TestComposite::class,
                dataSchema = TestComposite.Schema,
                dtoFactory = { error("Not needed for materializer") },
                roFactory = { chain, ctx -> TestCompositeRo(chain, ctx) }
            )
            operation(
                interfaceClass = TestOperation::class,
                protocolSchema = TestOperation.Schema,
                roFactory = { chain, ctx ->
                    TestOperationRo(
                        chain.addArray(OperationDto(emptyMap(), TestOperation.Schema)),
                        ctx
                    )
                }
            )
        }

        lateinit var tContext: TContext
        val localRegistry = SchemaRegistry { tContext }.apply {
            registerConfig(coreCommonModule)
            registerConfig(testModule)
        }
        tContext = createTestContext(schemaRegistry = localRegistry)

        val materializer = CallstackMaterializer(localRegistry, tContext)

        var invocations = 0
        val template = ChainTemplate<TestComposite, Data> {
            invocations++
            testOperation()
        }

        val params = org.tenshu.core.collection.list.SimpleTList<Data>(emptyList())
        repeat(3) {
            materializer.materialize(TestComposite.ID, template, params)
        }

        assertEquals(
            expected = 3,
            actual = invocations,
            message = "Materializer is now uncached — every call re-invokes the lambda",
        )
    }

    @Test
    fun materializesSingleOperation() {
        val testModule = tModule {
            composite(
                interfaceClass = TestComposite::class,
                dataSchema = TestComposite.Schema,
                dtoFactory = { error("Not needed for materializer") },
                roFactory = { chain, ctx -> TestCompositeRo(chain, ctx) }
            )
            operation(
                interfaceClass = TestOperation::class,
                protocolSchema = TestOperation.Schema,
                roFactory = { chain, ctx ->
                    TestOperationRo(
                        chain.addArray(OperationDto(emptyMap(), TestOperation.Schema)),
                        ctx
                    )
                }
            )
        }

        lateinit var tContext: TContext
        val localRegistry = SchemaRegistry { tContext }.apply {
            registerConfig(coreCommonModule)
            registerConfig(testModule)
        }
        tContext = createTestContext(schemaRegistry = localRegistry)

        val materializer = CallstackMaterializer(
            schemaRegistry = localRegistry,
            tContext = tContext
        )

        val result = materializer.materialize(TestComposite.ID, ChainTemplate<TestComposite, Data> {
            testOperation()
        })

        assertEquals(
            expected = listOf(OperationDto(schema = TestOperation.Schema, params = emptyMap())),
            actual = result,
        )
    }
}
