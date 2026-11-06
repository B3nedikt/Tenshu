package org.tenshu.core.common

import org.tenshu.core.Data
import org.tenshu.core.common.SyntheticTestData.Companion.Field
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.core.common.impl.CompositeRo
import org.tenshu.core.common.impl.reference.PropertyTypeReferenceDto
import org.tenshu.core.common.module.composite
import org.tenshu.core.common.module.constant
import org.tenshu.core.common.module.reference
import org.tenshu.core.common.module.tModule
import org.tenshu.core.common.module.trait
import org.tenshu.core.common.proxy.SchemaRegistry
import org.tenshu.core.common.proxy.StringDataIdProviderImpl
import org.tenshu.core.entity.StringDataIdProviderSchema
import org.tenshu.core.schema.reference

fun createSyntheticTestRegistry(): SchemaRegistry {
    val registry = createCoreTestRegistry()
    registry.registerConfig(tModule {
        composite(
            interfaceClass = SyntheticTestData::class,
            dataSchema = SyntheticTestData.Schema,
            dtoFactory = ::SyntheticTestDataDto,
            roFactory = { chain, context ->
                object : CompositeRo(
                    chain,
                    context,
                    SyntheticTestData.Schema
                ), SyntheticTestData {
                    override val schema get() = SyntheticTestData.Schema
                }
            }
        )
        composite(
            interfaceClass = SyntheticSpecialData::class,
            dataSchema = SyntheticSpecialData.Schema,
            dtoFactory = ::SyntheticSpecialDataDto,
            roFactory = { chain, context ->
                object : CompositeRo(
                    chain,
                    context,
                    SyntheticSpecialData.Schema
                ), SyntheticSpecialData {
                    override val schema get() = SyntheticSpecialData.Schema
                }
            }
        )
        // Register field IDs used as references in Entity maps as Constant/Reference schemas
        reference(reference(Field.NAME))
        reference(reference(Field.VALUE))

        trait(
            dataSchema = SyntheticTestData.Schema,
            traitSchema = SyntheticTestData.DataIdTraitSchema,
            traitFactory = { context, schema ->
                StringDataIdProviderImpl(context, schema as StringDataIdProviderSchema)
            }
        )

        // Composite-scalar-field holder — backs the projection-skip dbEntryMapper
        // tests (`DbEntryMapperTest`).
        composite(
            interfaceClass = SyntheticParent::class,
            dataSchema = SyntheticParent.Schema,
            dtoFactory = ::SyntheticParentDto,
            roFactory = { chain, context ->
                object : CompositeRo(
                    chain,
                    context,
                    SyntheticParent.Schema
                ), SyntheticParent {
                    override val schema get() = SyntheticParent.Schema
                }
            }
        )
        reference(reference(SyntheticParent.Companion.Field.NAME))
        reference(reference(SyntheticParent.Companion.Field.CHILD))

        trait(
            dataSchema = SyntheticParent.Schema,
            traitSchema = SyntheticParent.DataIdTraitSchema,
            traitFactory = { context, schema ->
                StringDataIdProviderImpl(context, schema as StringDataIdProviderSchema)
            }
        )

        // Inline-list-field holder + its owning entity — back the nested dangling-member test.
        composite(
            interfaceClass = SyntheticListHolder::class,
            dataSchema = SyntheticListHolder.Schema,
            dtoFactory = ::SyntheticListHolderDto,
            roFactory = { chain, context ->
                object : CompositeRo(chain, context, SyntheticListHolder.Schema), SyntheticListHolder {
                    override val schema get() = SyntheticListHolder.Schema
                }
            }
        )
        reference(reference(SyntheticListHolder.Companion.Field.NAME))
        reference(reference(SyntheticListHolder.Companion.Field.ITEMS))

        trait(
            dataSchema = SyntheticListHolder.Schema,
            traitSchema = SyntheticListHolder.DataIdTraitSchema,
            traitFactory = { context, schema ->
                StringDataIdProviderImpl(context, schema as StringDataIdProviderSchema)
            }
        )

        composite(
            interfaceClass = SyntheticListOwner::class,
            dataSchema = SyntheticListOwner.Schema,
            dtoFactory = ::SyntheticListOwnerDto,
            roFactory = { chain, context ->
                object : CompositeRo(chain, context, SyntheticListOwner.Schema), SyntheticListOwner {
                    override val schema get() = SyntheticListOwner.Schema
                }
            }
        )
        reference(reference(SyntheticListOwner.Companion.Field.NAME))
        reference(reference(SyntheticListOwner.Companion.Field.HOLDER))

        trait(
            dataSchema = SyntheticListOwner.Schema,
            traitSchema = SyntheticListOwner.DataIdTraitSchema,
            traitFactory = { context, schema ->
                StringDataIdProviderImpl(context, schema as StringDataIdProviderSchema)
            }
        )

        // Third level — backs the nested-projection dbEntryMapper tests.
        composite(
            interfaceClass = SyntheticGrandparent::class,
            dataSchema = SyntheticGrandparent.Schema,
            dtoFactory = ::SyntheticGrandparentDto,
            roFactory = { chain, context ->
                object : CompositeRo(
                    chain,
                    context,
                    SyntheticGrandparent.Schema
                ), SyntheticGrandparent {
                    override val schema get() = SyntheticGrandparent.Schema
                }
            }
        )
        reference(reference(SyntheticGrandparent.Companion.Field.NAME))
        reference(reference(SyntheticGrandparent.Companion.Field.CHILD))

        trait(
            dataSchema = SyntheticGrandparent.Schema,
            traitSchema = SyntheticGrandparent.DataIdTraitSchema,
            traitFactory = { context, schema ->
                StringDataIdProviderImpl(context, schema as StringDataIdProviderSchema)
            }
        )

        // genericData-field holder — backs the GenericValue round-trip mapper tests.
        composite(
            interfaceClass = SyntheticGenericHolder::class,
            dataSchema = SyntheticGenericHolder.Schema,
            dtoFactory = ::SyntheticGenericHolderDto,
            roFactory = { chain, context ->
                object : CompositeRo(
                    chain,
                    context,
                    SyntheticGenericHolder.Schema
                ), SyntheticGenericHolder {
                    override val schema get() = SyntheticGenericHolder.Schema
                }
            }
        )
        reference(reference(SyntheticGenericHolder.Companion.Field.NAME))
        reference(reference(SyntheticGenericHolder.Companion.Field.VALUE))

        trait(
            dataSchema = SyntheticGenericHolder.Schema,
            traitSchema = SyntheticGenericHolder.DataIdTraitSchema,
            traitFactory = { context, schema ->
                StringDataIdProviderImpl(context, schema as StringDataIdProviderSchema)
            }
        )

        // Synthetic constant + its two members — backs the constant-field
        // dbEntryMapper round-trip tests. Mirrors FileServiceCommonModule's
        // FileCategory registration (parent + one entry per object).
        constant(
            interfaceClass = SyntheticConstant::class,
            dataSchema = SyntheticConstant.Schema,
            dtoFactory = { PropertyTypeReferenceDto(SyntheticConstant.Schema.id.get()) }
        )
        constant(
            interfaceClass = SyntheticConstant.Alpha::class,
            dataSchema = SyntheticConstant.Alpha.schema,
            dtoFactory = { SyntheticConstant.Alpha }
        )
        constant(
            interfaceClass = SyntheticConstant.Beta::class,
            dataSchema = SyntheticConstant.Beta.schema,
            dtoFactory = { SyntheticConstant.Beta }
        )

        // Constant-field holder (plain + optional constant field).
        composite(
            interfaceClass = SyntheticConstantHolder::class,
            dataSchema = SyntheticConstantHolder.Schema,
            dtoFactory = ::SyntheticConstantHolderDto,
            roFactory = { chain, context ->
                object : CompositeRo(
                    chain,
                    context,
                    SyntheticConstantHolder.Schema
                ), SyntheticConstantHolder {
                    override val schema get() = SyntheticConstantHolder.Schema
                }
            }
        )
        reference(reference(SyntheticConstantHolder.Companion.Field.NAME))
        reference(reference(SyntheticConstantHolder.Companion.Field.KIND))
        reference(reference(SyntheticConstantHolder.Companion.Field.OPTIONAL_KIND))

        trait(
            dataSchema = SyntheticConstantHolder.Schema,
            traitSchema = SyntheticConstantHolder.DataIdTraitSchema,
            traitFactory = { context, schema ->
                StringDataIdProviderImpl(context, schema as StringDataIdProviderSchema)
            }
        )
    })
    return registry
}

class SyntheticTestDataDto(fieldValues: List<Data?>) : CompositeDto(fieldValues), SyntheticTestData

class SyntheticSpecialDataDto(fieldValues: List<Data?>) : CompositeDto(fieldValues), SyntheticSpecialData

class SyntheticParentDto(fieldValues: List<Data?>) : CompositeDto(fieldValues), SyntheticParent

class SyntheticListHolderDto(fieldValues: List<Data?>) : CompositeDto(fieldValues), SyntheticListHolder
class SyntheticListOwnerDto(fieldValues: List<Data?>) : CompositeDto(fieldValues), SyntheticListOwner

class SyntheticGrandparentDto(fieldValues: List<Data?>) : CompositeDto(fieldValues), SyntheticGrandparent

class SyntheticGenericHolderDto(fieldValues: List<Data?>) : CompositeDto(fieldValues), SyntheticGenericHolder

class SyntheticConstantHolderDto(fieldValues: List<Data?>) : CompositeDto(fieldValues), SyntheticConstantHolder
