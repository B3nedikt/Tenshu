package org.tenshu.core.common.parser

import org.tenshu.core.Composite
import org.tenshu.core.Data
import org.tenshu.core.common.createSyntheticTestRegistry
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.core.common.module.composite
import org.tenshu.core.common.module.tModule
import org.tenshu.core.common.proxy.SchemaRegistry
import org.tenshu.core.core.TContext
import org.tenshu.core.number.toTInteger
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.boolean
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.int
import org.tenshu.core.schema.string
import org.tenshu.core.toTBoolean
import org.tenshu.core.toTString

/**
 * Shared fixture for the optional-value-field wire-contract tests, which pin the binary
 * parser's handling of a null optional value field.
 *
 * A composite with optional value fields in the **middle** (`b`, `d`) and **trailing** (`e`)
 * positions — the shape that desyncs a parser ignoring the presence marker. Mirrors the real
 * [org.tenshu.core.ingest.IngestSource] layout, where a freshly-registered Android source has
 * `lastRunAt`/`pendingCount` (optional ints) null between populated fields.
 *
 * Used by three tests so neither side of the wire is verified only against the other:
 * - [OptionalValueFieldRoundTripTest] — behavioural round-trip (serializer + parser together);
 * - [BinarySerializerTest] — byte-exact serializer **output**;
 * - [BinaryParserTest] — parser **input** decoded from hand-crafted bytes.
 *
 * ### Optional-field wire contract (non-optimized / tagged top level)
 * ```
 *   composite       = idToUuid(typeId) ++ field*            16-byte type id, then each field
 *   string (mand.)  = len(8) ++ utf8                        length-prefixed
 *   optional value  = 0(8)                  when null       8-byte zero presence marker
 *                   | len(8) ++ valueBytes  when present    len >= 1, so a present 0 / false
 *                                                           (valueBytes = 8 zero bytes) is NEVER
 *                                                           confused with the null marker
 * ```
 * Every optional value field carries an 8-byte leading marker (0 = absent, else the byte length
 * of the value). A parser that ignores this marker for fixed-length value fields and reads the
 * schema length instead shifts every following field by 8 bytes.
 */
internal object OptionalFieldFixture {

    const val ID = "test/optionalValueField"

    object F {
        const val A = "$ID/a" // string  (mandatory)
        const val B = "$ID/b" // int?    (optional, MIDDLE — the trigger)
        const val C = "$ID/c" // string  (mandatory)
        const val D = "$ID/d" // boolean? (optional, middle)
        const val E = "$ID/e" // int?    (optional, trailing)
    }

    val Schema: CompositeSchema = composite(
        id = ID,
        fields = {
            string(F.A)
            int(F.B, isOptional = true)
            string(F.C)
            boolean(F.D, isOptional = true)
            int(F.E, isOptional = true)
        }
    )

    class Dto(fieldValues: List<Data?>) : CompositeDto(fieldValues), Composite {
        override val schema: CompositeSchema get() = Schema
    }

    /** A synthetic test registry with this fixture composite additionally registered. */
    fun registry(): SchemaRegistry = createSyntheticTestRegistry().also { registry ->
        registry.registerConfig(
            tModule {
                composite(
                    interfaceClass = Dto::class,
                    dataSchema = Schema,
                    dtoFactory = ::Dto,
                )
            }
        )
    }

    fun dto(
        context: TContext,
        a: String,
        b: Long?,
        c: String,
        d: Boolean?,
        e: Long?,
    ): Dto = Dto(
        listOf(
            a.toTString(context),
            b?.toTInteger(context),
            c.toTString(context),
            d?.toTBoolean(context),
            e?.toTInteger(context),
        )
    )
}
