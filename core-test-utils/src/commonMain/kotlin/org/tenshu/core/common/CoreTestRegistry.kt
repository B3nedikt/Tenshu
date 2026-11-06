package org.tenshu.core.common

import org.tenshu.core.Composite
import org.tenshu.core.Data
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.impl.EntityDto
import org.tenshu.core.common.module.coreCommonModule
import org.tenshu.core.common.module.composite
import org.tenshu.core.common.module.tModule
import org.tenshu.core.common.proxy.SchemaRegistry
import org.tenshu.core.request.RoProvider
import org.tenshu.core.core.DataFactory
import org.tenshu.core.core.LazyDataMaterializer
import org.tenshu.core.core.OperationResolver
import org.tenshu.core.core.SchemaProvider
import org.tenshu.core.core.TComponent
import org.tenshu.core.core.TContext
import org.tenshu.core.entity.Entity
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.Field

fun createCoreTestRegistry(): SchemaRegistry {
    lateinit var registry: SchemaRegistry
    val context = object : TContext {
        override val components: Map<String, TComponent> by lazy {
            mapOf(
                DataFactory.COMPONENT_ID to registry,
                SchemaProvider.COMPONENT_ID to registry,
                RoProvider.COMPONENT_ID to registry,
                OperationResolver.COMPONENT_ID to registry,
                LazyDataMaterializer.ID to registry
            )
        }
    }
    registry = SchemaRegistry { context }

    registry.registerConfig(coreCommonModule)

    registry.registerConfig(tModule {
        composite(
            interfaceClass = Composite::class,
            dataSchema = Composite.Schema,
            dtoFactory = { fields ->
                object : Composite {
                    override val schema = Composite.Schema
                    override fun <T : Data> getField(reference: String): T =
                        fields[schema.values.toList()
                            .indexOfFirst { field: Field -> field.id.get() == reference }] as T

                    override fun <T : Data> getOptionalField(reference: String): T? =
                        fields.getOrNull(
                            schema.values.toList()
                                .indexOfFirst { field: Field -> field.id.get() == reference }) as T?
                }
            }
        )
        composite(
            interfaceClass = CompositeSchema::class,
            dataSchema = CompositeSchema.Schema,
            dtoFactory = { fields ->
                object : CompositeSchema {
                    override val schema = CompositeSchema.Schema
                    override fun <T : Data> getField(reference: String): T =
                        fields[schema.values.toList()
                            .indexOfFirst { field: Field -> field.id.get() == reference }] as T

                    override fun <T : Data> getOptionalField(reference: String): T? =
                        fields.getOrNull(
                            schema.values.toList()
                                .indexOfFirst { field: Field -> field.id.get() == reference }) as T?
                }
            }
        )
        composite(
            interfaceClass = Entity::class,
            dataSchema = Entity.Schema,
            dtoFactory = { fields -> EntityDto<Data>(fields) }
        )
    })

    return registry
}
