package org.tenshu.core.file.system

import org.tenshu.core.ExpectedOutcome
import org.tenshu.core.TError
import org.tenshu.core.TString
import org.tenshu.core.core.TContext
import org.tenshu.core.core.create
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.string
import org.tenshu.core.toTString

/**
 * This row is still **provisional** — shown from a joiner's slice snapshot and not yet reached by the
 * log — so it cannot be moved or renamed yet (`content-plane-cr2-b5-spec.md` §4). Retry once the device
 * has caught up.
 *
 * ⚠⚠ Why a refusal rather than letting the move through. A snapshot row names its content id as of the
 * snapshot; a move pushes that id under the new path at a fresh HLC and tombstones the old path, so every
 * peer follows the stale copy over the newer one. Parking the move instead would replay it with the same
 * stale row unless the replay re-checked, and D3's parking machinery exists for a different question
 * (offline repairability), so the move answers now and the user retries.
 *
 * ⚠ [ExpectedOutcome], like [ContentPinnedError]: the ordinary answer during a joiner's catch-up, not a
 * fault, so it is not recorded as a diagnostic.
 */
interface StillSyncingError : ExpectedOutcome {
    override val schema: CompositeSchema
        get() = Schema

    val message: TString
        get() = getField(Fields.MESSAGE)

    companion object {
        const val ID = "tenshu/file/system/error/stillSyncing"

        val Schema: CompositeSchema = composite(
            id = ID,
            parent = TError.Schema,
            fields = {
                string(Fields.MESSAGE)
            }
        )

        fun create(
            context: TContext,
            message: String
        ): StillSyncingError = context.create(
            type = ID,
            fields = listOf(message.toTString(context))
        )
    }

    object Fields {
        const val MESSAGE = "$ID/message"
    }
}
