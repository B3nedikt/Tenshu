package org.tenshu.core.common

import org.tenshu.core.Composite
import org.tenshu.core.Data
import org.tenshu.core.TConstant
import org.tenshu.core.TString
import org.tenshu.core.collection.list.TList
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.core.core.TContext
import org.tenshu.core.core.createDto
import org.tenshu.core.entity.Entity
import org.tenshu.core.number.TInteger
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.ConstantSchema
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.constant
import org.tenshu.core.schema.genericData
import org.tenshu.core.schema.int
import org.tenshu.core.schema.list
import org.tenshu.core.schema.string
import org.tenshu.core.schema.stringDataId
import org.tenshu.core.trait.Syncable

interface SyntheticTestData : Composite {
    companion object {
        const val ID = "test/synthetic"
        const val TRAIT_ID = "test/synthetic/dataIdProvider"

        object Field {
            const val NAME = "$ID/name"
            const val VALUE = "$ID/value"
        }

        val DataIdTraitSchema = stringDataId(TRAIT_ID, Field.NAME)
        val Schema = composite(
            id = ID,
            traits = listOf(
                Syncable(),
                DataIdTraitSchema
            ),
            fields = {
                string(Field.NAME)
                int(Field.VALUE)
            }
        )
    }

    override val schema: CompositeSchema get() = Schema
    val name: TString get() = getField(Field.NAME)
    val value: TInteger get() = getField(Field.VALUE)

    class Dto(fieldValues: List<Data?>) : CompositeDto(fieldValues), SyntheticTestData

    object Factory {
        fun create(context: TContext, name: String, value: Long): SyntheticTestData {
            return context.createDto(ID, listOf(name, value))
        }
    }
}

/**
 * A **subtype** of [SyntheticTestData] (`parent = SyntheticTestData.Schema`, no fields of its
 * own) — the `Folder : File` shape. Stored in the same arrays as its parent so a type test
 * (`isA<SyntheticSpecialData>()`) has something to discriminate, both as a predicate and as the
 * ordered-window fusion's `TypeIs` sort key. Identity comes from the parent's `name` trait via
 * the type-hierarchy walk, exactly as a `Folder` takes `File`'s.
 */
interface SyntheticSpecialData : SyntheticTestData {
    companion object {
        const val ID = "test/synthetic/special"

        val Schema = composite(
            id = ID,
            parent = SyntheticTestData.Schema,
        )
    }

    override val schema: CompositeSchema get() = Schema

    object Factory {
        fun create(context: TContext, name: String, value: Long): SyntheticSpecialData {
            return context.createDto(ID, listOf(name, value))
        }
    }
}

val Entity<out SyntheticTestData>.name: TString get() = getFieldValue(SyntheticTestData.Companion.Field.NAME)
val Entity<out SyntheticTestData>.value: TInteger get() = getFieldValue(SyntheticTestData.Companion.Field.VALUE)

/**
 * A synthetic [TConstant] (mirrors `FileCategory`) — a fieldless, self-describing
 * singleton stored by its schema id. Lets the dbEntryMapper tests exercise the
 * constant-in-a-field path (both a plain composite field and a `genericData`
 * field) without depending on a feature-module constant.
 */
sealed interface SyntheticConstant : TConstant {
    object Alpha : SyntheticConstant {
        override val schema: ConstantSchema = constant("${ID}/alpha", Schema)
    }

    object Beta : SyntheticConstant {
        override val schema: ConstantSchema = constant("${ID}/beta", Schema)
    }

    companion object {
        const val ID = "test/synthetic-constant"
        val Schema: ConstantSchema = constant(ID)
    }
}

/**
 * A synthetic composite carrying a **constant** field ([Field.KIND], a
 * [SyntheticConstant]) plus an **optional** constant field ([Field.OPTIONAL_KIND]).
 * `ConstantSchema` is not a `CompositeSchema`, so a constant field takes the
 * dbEntryMapper's dedicated `isConstantField` positional path — this holder
 * exercises it (present + absent optional) without perturbing the other synthetic
 * types' field layouts.
 */
interface SyntheticConstantHolder : Composite {
    companion object {
        const val ID = "test/synthetic-constant-holder"
        const val TRAIT_ID = "test/synthetic-constant-holder/dataIdProvider"

        object Field {
            const val NAME = "$ID/name"
            const val KIND = "$ID/kind"
            const val OPTIONAL_KIND = "$ID/optionalKind"
        }

        val DataIdTraitSchema = stringDataId(TRAIT_ID, Field.NAME)
        val Schema = composite(
            id = ID,
            traits = listOf(
                Syncable(),
                DataIdTraitSchema
            ),
            fields = {
                string(Field.NAME)
                composite(Field.KIND, type = SyntheticConstant.Schema)
                composite(Field.OPTIONAL_KIND, type = SyntheticConstant.Schema, isOptional = true)
            }
        )
    }

    override val schema: CompositeSchema get() = Schema
    val name: TString get() = getField(Field.NAME)
    val kind: SyntheticConstant get() = getField(Field.KIND)
    val optionalKind: SyntheticConstant? get() = getOptionalField(Field.OPTIONAL_KIND)

    class Dto(fieldValues: List<Data?>) : CompositeDto(fieldValues), SyntheticConstantHolder

    object Factory {
        fun create(
            context: TContext,
            name: String,
            kind: SyntheticConstant,
            optionalKind: SyntheticConstant? = null,
        ): SyntheticConstantHolder =
            context.createDto(ID, listOf(name, kind, optionalKind))
    }
}

/**
 * A synthetic composite carrying a [genericData] field ([Field.VALUE]) — its
 * runtime value type isn't known statically, so the DB layer stores it as a
 * `DbValue.GenericValue`. Lets the mapper tests exercise the generic-field path
 * with both a primitive value and a composite value.
 */
interface SyntheticGenericHolder : Composite {
    companion object {
        const val ID = "test/synthetic-generic"
        const val TRAIT_ID = "test/synthetic-generic/dataIdProvider"

        object Field {
            const val NAME = "$ID/name"
            const val VALUE = "$ID/value"
        }

        val DataIdTraitSchema = stringDataId(TRAIT_ID, Field.NAME)
        val Schema = composite(
            id = ID,
            traits = listOf(
                Syncable(),
                DataIdTraitSchema
            ),
            fields = {
                string(Field.NAME)
                genericData(Field.VALUE)
            }
        )
    }

    override val schema: CompositeSchema get() = Schema
    val name: TString get() = getField(Field.NAME)
    val value: Data get() = getField(Field.VALUE)

    class Dto(fieldValues: List<Data?>) : CompositeDto(fieldValues), SyntheticGenericHolder

    object Factory {
        fun create(context: TContext, name: String, value: Data): SyntheticGenericHolder {
            return context.createDto(ID, listOf(name, value))
        }
    }
}

/**
 * A synthetic composite carrying a *composite scalar field* ([Field.CHILD] of type
 * [SyntheticTestData]) — the shape the dbEntryMapper's projection skip operates on.
 * [SyntheticTestData] itself has only value fields, so a dedicated holder is needed
 * to exercise the composite-field path without perturbing the many tests that pin
 * [SyntheticTestData]'s exact field layout.
 */
interface SyntheticParent : Composite {
    companion object {
        const val ID = "test/synthetic-parent"
        const val TRAIT_ID = "test/synthetic-parent/dataIdProvider"

        object Field {
            const val NAME = "$ID/name"
            const val CHILD = "$ID/child"
        }

        val DataIdTraitSchema = stringDataId(TRAIT_ID, Field.NAME)
        val Schema = composite(
            id = ID,
            traits = listOf(
                Syncable(),
                DataIdTraitSchema
            ),
            fields = {
                string(Field.NAME)
                composite(Field.CHILD, type = SyntheticTestData.Schema)
            }
        )
    }

    override val schema: CompositeSchema get() = Schema
    val name: TString get() = getField(Field.NAME)
    val child: SyntheticTestData get() = getField(Field.CHILD)

    class Dto(fieldValues: List<Data?>) : CompositeDto(fieldValues), SyntheticParent

    object Factory {
        fun create(context: TContext, name: String, child: SyntheticTestData): SyntheticParent {
            return context.createDto(ID, listOf(name, child))
        }
    }
}

/**
 * A **non-entity** composite carrying an inline list field ([Field.ITEMS]), owned by
 * [SyntheticListOwner].
 *
 * ⚠ The no-traits part is load-bearing, not incidental. `DbEntryMapper` only takes the *inline*
 * list path when the enclosing composite has no `EntityDataId` — an entity's list field is deferred
 * as an `ArrayDatabaseReference` instead and never recurses. This mirrors production exactly:
 * `tenshu/file` (entity) → `tenshu/file/folder` (plain composite) → its `files` list, which is the
 * shape that made one dangling file take out a whole Drive tree.
 */
interface SyntheticListHolder : Composite {
    companion object {
        const val ID = "test/synthetic-list-holder"
        const val TRAIT_ID = "test/synthetic-list-holder/dataIdProvider"

        object Field {
            const val NAME = "$ID/name"
            const val ITEMS = "$ID/items"
        }

        // ⚠ A DataId provider but NOT `Syncable`: an inline list field cannot be written without an
        // identity for its enclosing composite (`writeValuesToDb` -> "Cannot determine parent ID"),
        // while adding `Syncable` would make it an entity and defer the list as an
        // `ArrayDatabaseReference` instead of inlining it — which is the wrong shape for this test.
        val DataIdTraitSchema = stringDataId(TRAIT_ID, Field.NAME)
        val Schema = composite(
            id = ID,
            traits = listOf(DataIdTraitSchema),
            fields = {
                string(Field.NAME)
                list(Field.ITEMS, of = SyntheticTestData.Schema)
            }
        )
    }

    override val schema: CompositeSchema get() = Schema
    val name: TString get() = getField(Field.NAME)
    val items: TList<SyntheticTestData> get() = getField(Field.ITEMS)

    class Dto(fieldValues: List<Data?>) : CompositeDto(fieldValues), SyntheticListHolder

    object Factory {
        fun create(context: TContext, name: String, items: List<SyntheticTestData>): SyntheticListHolder =
            context.createDto(ID, listOf(name, items))
    }
}

/** The entity that owns a [SyntheticListHolder] as a composite field — the `tenshu/file` half. */
interface SyntheticListOwner : Composite {
    companion object {
        const val ID = "test/synthetic-list-owner"
        const val TRAIT_ID = "test/synthetic-list-owner/dataIdProvider"

        object Field {
            const val NAME = "$ID/name"
            const val HOLDER = "$ID/holder"
        }

        val DataIdTraitSchema = stringDataId(TRAIT_ID, Field.NAME)
        val Schema = composite(
            id = ID,
            traits = listOf(
                Syncable(),
                DataIdTraitSchema
            ),
            fields = {
                string(Field.NAME)
                composite(Field.HOLDER, type = SyntheticListHolder.Schema)
            }
        )
    }

    override val schema: CompositeSchema get() = Schema
    val name: TString get() = getField(Field.NAME)
    val holder: SyntheticListHolder get() = getField(Field.HOLDER)

    class Dto(fieldValues: List<Data?>) : CompositeDto(fieldValues), SyntheticListOwner

    object Factory {
        fun create(context: TContext, name: String, holder: SyntheticListHolder): SyntheticListOwner =
            context.createDto(ID, listOf(name, holder))
    }
}

/**
 * A third level: a composite field ([Field.CHILD] of type [SyntheticParent], which *itself*
 * carries a composite field). Lets the dbEntryMapper tests exercise **nested** projection —
 * keep the parent but prune the grandchild — i.e. that pruning applies at every level instead
 * of resolving in depth (`core-nested-projection.md`).
 */
interface SyntheticGrandparent : Composite {
    companion object {
        const val ID = "test/synthetic-grandparent"
        const val TRAIT_ID = "test/synthetic-grandparent/dataIdProvider"

        object Field {
            const val NAME = "$ID/name"
            const val CHILD = "$ID/child"
        }

        val DataIdTraitSchema = stringDataId(TRAIT_ID, Field.NAME)
        val Schema = composite(
            id = ID,
            traits = listOf(
                Syncable(),
                DataIdTraitSchema
            ),
            fields = {
                string(Field.NAME)
                composite(Field.CHILD, type = SyntheticParent.Schema)
            }
        )
    }

    override val schema: CompositeSchema get() = Schema
    val name: TString get() = getField(Field.NAME)
    val child: SyntheticParent get() = getField(Field.CHILD)

    class Dto(fieldValues: List<Data?>) : CompositeDto(fieldValues), SyntheticGrandparent

    object Factory {
        fun create(context: TContext, name: String, child: SyntheticParent): SyntheticGrandparent {
            return context.createDto(ID, listOf(name, child))
        }
    }
}
