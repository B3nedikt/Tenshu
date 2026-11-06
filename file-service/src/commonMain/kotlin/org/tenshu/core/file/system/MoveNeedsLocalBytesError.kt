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
 * This device is **offline** and does not hold the bytes of the file being moved — or of a file
 * inside the folder being moved — so the move is refused (`content-plane-cr2-b6-spec.md` D3, §3).
 * Download it first, or move it once the device is online.
 *
 * ⚠⚠ Why a refusal. A move is pure metadata: it writes the same content id under the new path and
 * tombstones the old one. If a peer's concurrent write supersedes that content before this device's
 * move reaches the relay, the relay frees what the move still names, and only a device holding the
 * bytes can put them back (B4's repair). An offline device without the bytes is the one case B4
 * cannot repair, so it is told before the move rather than losing the file after it
 * (`content-plane-cr2-quorumless.md` §3 P1). A device that holds the bytes may move offline.
 *
 * ⚠ It replaced D3's *deferral* (`MoveFencePolicy` / `PendingMoveReplay`, removed in B6 D4): with
 * B4's repair and B5's provisional-row guard, a deferred intent bought nothing a refusal does not.
 *
 * ⚠ [ExpectedOutcome], like [StillSyncingError]: the ordinary answer to an offline gesture, not a
 * fault, so it is not recorded as a diagnostic.
 */
interface MoveNeedsLocalBytesError : ExpectedOutcome {
    override val schema: CompositeSchema
        get() = Schema

    val message: TString
        get() = getField(Fields.MESSAGE)

    companion object {
        const val ID = "tenshu/file/system/error/moveNeedsLocalBytes"

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
        ): MoveNeedsLocalBytesError = context.create(
            type = ID,
            fields = listOf(message.toTString(context))
        )
    }

    object Fields {
        const val MESSAGE = "$ID/message"
    }
}
