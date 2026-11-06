package org.tenshu.core.file.notifications

import org.tenshu.core.Composite
import org.tenshu.core.TString
import org.tenshu.core.core.TContext
import org.tenshu.core.core.create
import org.tenshu.core.entity.CompositeDataIdProvider
import org.tenshu.core.entity.StringDataIdProviderSchema
import org.tenshu.core.number.TInteger
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.int
import org.tenshu.core.schema.string
import org.tenshu.core.schema.stringDataId
import org.tenshu.core.schema.timestamp
import org.tenshu.core.time.UnixInstant

/**
 * Durable, local-only pointer to the current [FileAddedNotification] burst row —
 * the producer-side coalescing state (`docs/proposals/notification-burst-aggregation.md`
 * §2.2). One record per proxy, keyed by the constant [KEY_CURRENT]: on every
 * upload, `FileAddedNotificationCoalescer` reads it to decide "same burst →
 * bump the existing notification row in place" vs "window expired → mint a
 * fresh row", then rewrites it.
 *
 * Local-only in the [ResolvedDataId][org.tenshu.sync.common] sense: saved via
 * `Database.saveData` (no observers), **not** `Syncable` — it never rides a
 * `SyncBlock`. Being durable (rather than an in-memory tracker) means a burst
 * survives a proxy restart mid-backup, and the coalescer needs no DI-threaded
 * per-proxy state — the record lives in the same store the decision is about.
 */
interface FileAddedBurst : Composite {

    override val schema: CompositeSchema
        get() = Schema

    /** Constant [KEY_CURRENT] — the record's fixed StringDataId key. */
    val key: TString
        get() = getField(Fields.KEY)

    /** `uuid` of the burst's [FileAddedNotification] row. */
    val uuid: TString
        get() = getField(Fields.UUID)

    /** When the burst row was last minted/bumped (proxy clock, epoch ms). */
    val lastTimestamp: UnixInstant
        get() = getField(Fields.LAST_TIMESTAMP)

    /** The burst row's current file count. */
    val count: TInteger
        get() = getField(Fields.COUNT)

    companion object {
        const val ID = "tenshu/notifications/file/addedBurst"

        /** The single record's key — there is only ever one current burst per proxy. */
        const val KEY_CURRENT = "current"

        val Schema: CompositeSchema = composite(
            id = ID,
            traits = listOf(FileAddedBurstDataIdProvider.Schema),
            fields = {
                string(Fields.KEY)
                string(Fields.UUID)
                timestamp(Fields.LAST_TIMESTAMP)
                int(Fields.COUNT)
            }
        )

        fun create(
            context: TContext,
            uuid: String,
            lastTimestamp: Long,
            count: Long,
        ): FileAddedBurst = context.create(
            type = ID,
            fields = listOf(KEY_CURRENT, uuid, lastTimestamp, count)
        )
    }

    object Fields {
        const val KEY = "$ID/key"
        const val UUID = "$ID/uuid"
        const val LAST_TIMESTAMP = "$ID/lastTimestamp"
        const val COUNT = "$ID/count"
    }
}

interface FileAddedBurstDataIdProvider : CompositeDataIdProvider<FileAddedBurst> {

    override val schema: StringDataIdProviderSchema
        get() = Schema

    companion object {
        const val ID = "tenshu/notifications/file/addedBurst/dataIdProvider"

        val Schema: StringDataIdProviderSchema = stringDataId(
            id = ID,
            fieldRef = FileAddedBurst.Fields.KEY
        )
    }
}
