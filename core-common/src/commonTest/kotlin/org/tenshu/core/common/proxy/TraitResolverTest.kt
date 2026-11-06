package org.tenshu.core.common.proxy

import org.tenshu.core.Composite
import org.tenshu.core.Data
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.assertNotReached
import org.tenshu.core.common.module.composite
import org.tenshu.core.common.module.tModule
import org.tenshu.core.common.module.trait
import org.tenshu.core.entity.DataIdProvider
import org.tenshu.core.entity.Entity
import org.tenshu.core.entity.ResolveEntityDataId
import org.tenshu.core.schema.composite
import org.tenshu.core.trait.Trait
import org.tenshu.core.trait.ValueTraitRequirementSchema
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TraitResolverTest {

    @Test
    fun getTrait_ResolvesRegisteredTrait() {
        val testTraitSchema = Trait.Schema
        val testSchema = composite(
            id = "test/type",
            traits = listOf(testTraitSchema)
        )

        val testProxy = TestProxy().apply {
            schemaRegistry.registerConfig(
                tModule {
                    composite(
                        interfaceClass = Composite::class,
                        dataSchema = testSchema,
                        dtoFactory = {
                            object : Composite {
                                override val schema = testSchema
                                override fun <T : Data> getField(reference: String): T =
                                    assertNotReached()

                                override fun <T : Data> getOptionalField(reference: String): T? =
                                    null

                            }
                        }
                    )
                    trait(
                        dataSchema = testSchema,
                        traitSchema = testTraitSchema,
                        traitFactory = { _, _ ->
                            object : Trait {
                                override val schema = Trait.Schema
                                override fun <T : Data> getField(reference: String): T =
                                    assertNotReached()

                                override fun <T : Data> getOptionalField(reference: String): T? =
                                    null

                            }
                        }
                    )
                }
            )
        }

        val trait = testProxy.executionContext
            .traitResolver
            .getTrait<Trait>(typeId = "test/type", traitId = Trait.ID)

        assertNotNull(trait)
    }

    @Test
    fun getTrait_ResolvesEntityDataIdProviderForEntity() {
        val testProxy = TestProxy().apply {
            schemaRegistry.registerConfig(tModule {
                composite(
                    interfaceClass = ResolveEntityDataId::class,
                    dataSchema = ResolveEntityDataId.Schema,
                    dtoFactory = {
                        object : ResolveEntityDataId {
                            @Suppress("UNCHECKED_CAST")
                            override val schema: ValueTraitRequirementSchema =
                                ResolveEntityDataId.Schema
                            override val params = emptyMap<String, Data>()
                            override fun <T : Data> getField(reference: String): T =
                                assertNotReached()

                            override fun <T : Data> getOptionalField(reference: String): T? = null
                        }
                    }
                )
            })
        }

        val trait = testProxy.executionContext
            .traitResolver
            .getTrait<DataIdProvider<Data>>(typeId = Entity.ID, traitId = DataIdProvider.ID)

        assertNotNull(trait)
        assertTrue(trait is EntityDataIdProviderImpl)
    }
}
