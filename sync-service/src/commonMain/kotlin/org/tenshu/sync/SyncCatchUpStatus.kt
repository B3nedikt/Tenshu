package org.tenshu.sync

import org.tenshu.core.Composite
import org.tenshu.core.TBoolean
import org.tenshu.core.TString
import org.tenshu.core.core.TContext
import org.tenshu.core.core.create
import org.tenshu.core.entity.CompositeDataIdProvider
import org.tenshu.core.entity.StringDataIdProviderSchema
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.boolean
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.string
import org.tenshu.core.schema.stringDataId

/**
 * Whether one runtime is **pulling data in** right now — the fact behind the iOS "Syncing…"
 * capsule (`ios-sync-activity-indicator.md` §3.1).
 *
 * ## One row per writer, not one row
 *
 * Unlike [ContentUploadProgress], which this is otherwise modelled on, the key is **not** a
 * constant. It is the app id of the binary hosting the runtime that wrote the row.
 *
 * ⚠⚠ That is what makes the row safe on a shared store. Several route-1 processes can open one
 * replica, each with its own scheduler. On a singleton row an idle app's start, or its tick end,
 * would clear the `true` another app had just written. Keyed by writer, a process writes and
 * clears its own row and no other.
 *
 * ⚠ A reader takes **its own runtime's row only**. A row left `true` by a process that was killed
 * and never relaunched cannot be told from a live one without cross-process liveness, so reading
 * across writers is not offered here. A sibling's pull is shown through [SyncCatchUpHeartbeat]
 * instead, whose rows stop counting by themselves (`ios-sync-activity-indicator.md` §12).
 *
 * ## Locally derived, NOT `Syncable`
 *
 * Same posture as [ContentUploadProgress] and [SchemaFenceStatus]: derived by this device's proxy,
 * never in a `SyncBlock`, never on the relay (House Rule 1 untouched). A new non-`Syncable` type
 * reshapes nothing, so it owes no `SyncService` version bump — see the `version` note there.
 */
interface SyncCatchUpStatus : Composite {

    override val schema: CompositeSchema get() = Schema

    /** The writer: the host app id of the runtime this row describes. */
    val key: TString
        get() = getField(Field.KEY)

    /** `true` while that runtime has a pull bringing rows in, or expects one. */
    val catchingUp: TBoolean
        get() = getField(Field.CATCHING_UP)

    companion object {
        const val ID = "tenshu/sync/catchUpStatus"

        /**
         * The key a runtime writes under when its host declared no app id (test wirings, mostly).
         * A reader that asks for this key reads that runtime's row like any other.
         */
        const val UNNAMED_WRITER = "unnamed"

        val Schema: CompositeSchema = composite(
            id = ID,
            parent = Composite.Schema,
            traits = listOf(
                SyncCatchUpStatusDataIdProvider.Schema,
            ),
            fields = {
                string(Field.KEY)
                boolean(Field.CATCHING_UP)
            },
        )

        fun create(
            context: TContext,
            writerId: String,
            catchingUp: Boolean,
        ): SyncCatchUpStatus = context.create(
            type = ID,
            fields = listOf(
                writerId,
                catchingUp,
            ),
        )
    }

    object Field {
        const val KEY = "$ID/key"
        const val CATCHING_UP = "$ID/catchingUp"
    }
}

/**
 * Stable identity for a [SyncCatchUpStatus] row — keyed on [SyncCatchUpStatus.Field.KEY], the
 * writer's id, so each writer's upsert replaces its own row and leaves the others.
 */
interface SyncCatchUpStatusDataIdProvider : CompositeDataIdProvider<SyncCatchUpStatus> {

    override val schema: StringDataIdProviderSchema
        get() = Schema

    companion object {
        const val ID = "tenshu/sync/catchUpStatus/dataIdProvider"

        val Schema: StringDataIdProviderSchema = stringDataId(
            id = ID,
            fieldRef = SyncCatchUpStatus.Field.KEY,
        )
    }
}
