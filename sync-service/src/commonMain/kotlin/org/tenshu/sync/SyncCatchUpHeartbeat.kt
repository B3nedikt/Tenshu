package org.tenshu.sync

import org.tenshu.core.Composite
import org.tenshu.core.TBoolean
import org.tenshu.core.TString
import org.tenshu.core.core.TContext
import org.tenshu.core.core.create
import org.tenshu.core.entity.CompositeDataIdProvider
import org.tenshu.core.entity.StringDataIdProviderSchema
import org.tenshu.core.number.TInteger
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.boolean
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.int
import org.tenshu.core.schema.string
import org.tenshu.core.schema.stringDataId

/**
 * One runtime's **pull, with a pulse** — what lets an app on a shared iOS replica see a sibling's
 * pull (`ios-sync-activity-indicator.md` §12).
 *
 * [SyncCatchUpStatus] says *whether* a runtime is pulling, and a reader trusts only its own row of
 * it: a row a killed app left `true` cannot be told from a live one. This row says the same thing
 * **and when it was last said**. While catching up, its writer rewrites it every
 * `CatchUpHeartbeatTiming.intervalMs` with the wall-clock time; a reader counts it only while that
 * time is younger than `CatchUpHeartbeatTiming.staleAfterMs`. A killed app's row therefore stops
 * counting by itself, and nothing has to clear it.
 *
 * ## Its own type, not a field of [SyncCatchUpStatus]
 *
 * A field there would reshape a positional composite, and House Rule 9 would owe a `SyncService`
 * version bump — which moves the realm's epoch and pull-fences every device not yet updated, for a
 * row that never leaves this device. A **new** non-`Syncable` type reshapes nothing, the precedent
 * [SyncCatchUpStatus] and [RuntimeIdentity] set, and leaves the own-row reader byte-for-byte as it
 * was.
 *
 * ## Locally derived, NOT `Syncable`
 *
 * Never in a `SyncBlock`, never on the relay (House Rule 1): the observer emits a block only for a
 * type that carries `Syncable`. A heartbeat every few seconds on a synced type would be constant
 * sync traffic; on this one it is a local store write.
 *
 * ⚠ Wall-clock time, deliberately: every process that reads the row is on the same device, so they
 * share the clock. The reader's rule for a heartbeat from the future (a clock set backwards) is
 * `catchUpHeartbeatFreshForMs`'s.
 */
interface SyncCatchUpHeartbeat : Composite {

    override val schema: CompositeSchema get() = Schema

    /** The writer: the host app id, the same key its [SyncCatchUpStatus] row has. */
    val key: TString
        get() = getField(Field.KEY)

    /** `true` while that runtime is catching up; `false` once it settled, written promptly. */
    val catchingUp: TBoolean
        get() = getField(Field.CATCHING_UP)

    /** Wall-clock milliseconds of the writer's last beat. */
    val beatAtMillis: TInteger
        get() = getField(Field.BEAT_AT_MILLIS)

    companion object {
        const val ID = "tenshu/sync/catchUpHeartbeat"

        val Schema: CompositeSchema = composite(
            id = ID,
            parent = Composite.Schema,
            traits = listOf(
                SyncCatchUpHeartbeatDataIdProvider.Schema,
            ),
            fields = {
                string(Field.KEY)
                boolean(Field.CATCHING_UP)
                int(Field.BEAT_AT_MILLIS)
            },
        )

        fun create(
            context: TContext,
            writerId: String,
            catchingUp: Boolean,
            beatAtMillis: Long,
        ): SyncCatchUpHeartbeat = context.create(
            type = ID,
            fields = listOf(
                writerId,
                catchingUp,
                beatAtMillis,
            ),
        )
    }

    object Field {
        const val KEY = "$ID/key"
        const val CATCHING_UP = "$ID/catchingUp"
        const val BEAT_AT_MILLIS = "$ID/beatAtMillis"
    }
}

/** Stable identity for a [SyncCatchUpHeartbeat] row: the writer's id, so each writer replaces its own. */
interface SyncCatchUpHeartbeatDataIdProvider : CompositeDataIdProvider<SyncCatchUpHeartbeat> {

    override val schema: StringDataIdProviderSchema
        get() = Schema

    companion object {
        const val ID = "tenshu/sync/catchUpHeartbeat/dataIdProvider"

        val Schema: StringDataIdProviderSchema = stringDataId(
            id = ID,
            fieldRef = SyncCatchUpHeartbeat.Field.KEY,
        )
    }
}
