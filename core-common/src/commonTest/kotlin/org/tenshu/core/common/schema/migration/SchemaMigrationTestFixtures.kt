package org.tenshu.core.common.schema.migration

import org.tenshu.core.Composite
import org.tenshu.core.Data
import org.tenshu.core.TString
import org.tenshu.core.common.createCoreTestRegistry
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.core.common.module.composite
import org.tenshu.core.common.module.tModule
import org.tenshu.core.common.proxy.SchemaRegistry
import org.tenshu.core.number.toTInteger
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.int
import org.tenshu.core.schema.string
import org.tenshu.core.toTString

/*
 * Synthetic fixtures for the SchemaMigration suites: a "thing" migrated
 * v1 → v2 exercising all four mapping rules (rename + drop + derive +
 * convert) plus a to-only optional add, and a "reordered" type whose ids are
 * identical but whose positions swapped between versions.
 */

internal const val THING_SERVICE_ID = "tenshu/test/migration/service"

internal const val THING_ID = "tenshu/test/migration/thing"

internal object ThingFields {
    const val TITLE = "$THING_ID/title" // v1 only — renamed to NAME
    const val LEGACY = "$THING_ID/legacy" // v1 only — dropped
    const val SIZE = "$THING_ID/size" // both — string at v1, int at v2 (convert)
    const val NAME = "$THING_ID/name" // v2 — rename target
    const val KIND = "$THING_ID/kind" // v2 — new REQUIRED (derive)
    const val NOTE = "$THING_ID/note" // v2 — new OPTIONAL (starts absent)
}

internal val THING_AT_V1: CompositeSchema = composite(
    id = THING_ID,
    fields = {
        string(ThingFields.TITLE)
        string(ThingFields.LEGACY)
        string(ThingFields.SIZE)
    }
)

internal val THING_LIVE: CompositeSchema = composite(
    id = THING_ID,
    fields = {
        string(ThingFields.NAME)
        string(ThingFields.KIND)
        int(ThingFields.SIZE)
        string(ThingFields.NOTE, isOptional = true)
    }
)

internal class ThingDto(fields: List<Data?>) : CompositeDto(fields) {
    override val schema: CompositeSchema get() = THING_LIVE
}

internal const val REORDERED_ID = "tenshu/test/migration/reordered"

internal object ReorderedFields {
    const val A = "$REORDERED_ID/a"
    const val B = "$REORDERED_ID/b"
}

internal val REORDERED_AT_V1: CompositeSchema = composite(
    id = REORDERED_ID,
    fields = {
        string(ReorderedFields.A)
        int(ReorderedFields.B)
    }
)

internal val REORDERED_LIVE: CompositeSchema = composite(
    id = REORDERED_ID,
    fields = {
        int(ReorderedFields.B)
        string(ReorderedFields.A)
    }
)

internal class ReorderedDto(fields: List<Data?>) : CompositeDto(fields) {
    override val schema: CompositeSchema get() = REORDERED_LIVE
}

/** Core test registry + the live (v2) fixture types registered. */
internal fun createMigrationTestRegistry(): SchemaRegistry =
    createCoreTestRegistry().apply {
        registerConfig(tModule {
            composite(
                interfaceClass = ThingDto::class,
                dataSchema = THING_LIVE,
                dtoFactory = ::ThingDto,
            )
            composite(
                interfaceClass = ReorderedDto::class,
                dataSchema = REORDERED_LIVE,
                dtoFactory = ::ReorderedDto,
            )
        })
    }

/** The thing's v1→v2 step: rename + drop + derive + convert; NOTE deliberately unmentioned. */
internal fun thingMigrationStep(): SchemaMigration =
    schemaMigration(THING_SERVICE_ID, fromVersion = 1) {
        migration(typeId = THING_ID, from = THING_AT_V1, to = THING_LIVE) {
            rename(ThingFields.TITLE, ThingFields.NAME)
            drop(ThingFields.LEGACY)
            derive(ThingFields.KIND) { old, ctx ->
                val title = (old as Composite).getField<TString>(ThingFields.TITLE).get()
                "kind:$title".toTString(ctx.context)
            }
            convert(ThingFields.SIZE) { oldValue, ctx ->
                (oldValue as TString).get().toInt().toTInteger(ctx.context)
            }
        }
    }
