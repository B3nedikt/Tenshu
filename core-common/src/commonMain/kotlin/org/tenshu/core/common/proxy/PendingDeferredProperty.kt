package org.tenshu.core.common.proxy

import org.tenshu.core.Composite
import org.tenshu.core.Data
import org.tenshu.core.TString
import org.tenshu.core.collection.list.TList
import org.tenshu.core.core.TContext
import org.tenshu.core.core.create
import org.tenshu.core.entity.DataId
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.list
import org.tenshu.core.schema.string
import org.tenshu.core.toTString

/**
 * Placeholder that [SelectImplementation] writes for a **deferred** dynamic
 * property (`docs/proposals/core-deferred-properties.md`): the property's
 * schema declares `deferred = true`, or the select forced it via
 * `deferred(handle)`. The row emits immediately with this marker in the
 * property slot instead of paying the resolver inline.
 *
 * Semantics differ from [PendingOnDemandProperty] by terminal (§4):
 *
 *  - **one-shot** (`Maybe.get()`, paging's `.flow().first()`): the marker is
 *    *left alone* — it crosses IPC as inert wire-safe data and the client's
 *    `Property.decode` (`as? T`) collapses it to `null`. The caller opted
 *    into deferral; re-request on demand or force `eager(...)`.
 *  - **streaming** (`.flow()`): `StreamRegistry` schedules a background
 *    resolution per marker and re-emits the row as a per-item patch when the
 *    value lands.
 *
 * A deliberate **sibling type** of [PendingOnDemandProperty], not a `reason`
 * field on it: composite fields serialise positionally, so an added optional
 * field would change the bytes of every existing OnDemand marker crossing
 * IPC (old peers desync). A separate composite id leaves the OnDemand wire
 * untouched, and the two markers genuinely differ — OnDemand implies a relay
 * fetch the terminal must await; deferred is local compute the terminal must
 * NOT await. The `is PendingOnDemandProperty` checks in
 * [MaybeGetImplementation] / [OnDemandMaterialiser] skip this type without
 * modification, which is exactly the one-shot semantics.
 */
interface PendingDeferredProperty : Composite {

    override val schema: CompositeSchema
        get() = Schema

    val entityId: DataId
        get() = getField(Fields.ENTITY_ID)

    val propertyId: TString
        get() = getField(Fields.PROPERTY_ID)

    val params: TList<Data>?
        get() = getOptionalField(Fields.PARAMS)

    companion object {
        const val ID = "tenshu/core/proxy/pendingDeferredProperty"

        val Schema: CompositeSchema = composite(
            id = ID,
            fields = {
                composite(Fields.ENTITY_ID, type = DataId.Schema)
                string(Fields.PROPERTY_ID)
                list(Fields.PARAMS, isOptional = true)
            }
        )

        fun create(
            context: TContext,
            entityId: DataId,
            propertyId: String,
            params: TList<Data>?
        ): PendingDeferredProperty = context.create(
            type = ID,
            fields = listOf(
                entityId,
                propertyId.toTString(context),
                params
            )
        )
    }

    object Fields {
        const val ENTITY_ID = "$ID/entityId"
        const val PROPERTY_ID = "$ID/propertyId"
        const val PARAMS = "$ID/params"
    }
}
