package org.tenshu.sync

import org.tenshu.core.Composite
import org.tenshu.core.Data
import org.tenshu.core.TString
import org.tenshu.core.core.TContext
import org.tenshu.core.core.create
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.string
import org.tenshu.core.toTString

/**
 * The shape of every [SyncBlock]'s decrypted payload.
 *
 * `propertyId == null` — entity update. [value] is the entity composite
 * (e.g., a `File`, `Folder`, `Device`); the joiner merges it into its
 * local database at the entity's stable DataId.
 *
 * `propertyId != null` — property update. [value] is the new value for
 * the named property of the parent entity (identified by the wrapping
 * [SyncBlock.targetEntityIdHash]). [value] may itself be a primitive
 * (`TByteArray` for file bytes), a composite, or another entity —
 * `Data` covers all cases.
 *
 * Living *inside* the encrypted payload (not on `SyncBlock`'s wire
 * metadata) is load-bearing for the zero-trust property: the relay
 * sees only the opaque encrypted blob + the `targetEntityIdHash`
 * scope. It does not learn whether a given block is an entity or a
 * property update, nor which property — that vocabulary stays on the
 * client side after decryption. See `docs/sync/threat-model.md`
 * §"Property-ID exposure to relay" for the carve-out this closes.
 */
interface DataBlockPayload : Composite {

    override val schema: CompositeSchema
        get() = Schema

    /**
     * The slot this update writes to. `null` means entity-level (write
     * [value] as the entity itself); non-null names the property on the
     * parent entity to attach [value] to.
     */
    val propertyId: TString?
        get() = getOptionalField(Fields.PROPERTY_ID)

    /**
     * The payload data. Could be a primitive ([org.tenshu.core.TByteArray]
     * for bytes), a composite, or an entity — the consumer dispatches by
     * type after decryption.
     */
    val value: Data
        get() = getField(Fields.VALUE)

    companion object {
        const val ID = "tenshu/sync/dataBlockPayload"

        val Schema: CompositeSchema = composite(
            id = ID,
            fields = {
                string(Fields.PROPERTY_ID, isOptional = true)
                composite(Fields.VALUE, type = Data.Schema)
            }
        )

        fun create(
            context: TContext,
            propertyId: String?,
            value: Data
        ): DataBlockPayload = context.create(
            type = ID,
            fields = listOf(
                propertyId?.toTString(context),
                value
            )
        )
    }

    object Fields {
        const val PROPERTY_ID = "$ID/propertyId"
        const val VALUE = "$ID/value"
    }
}
