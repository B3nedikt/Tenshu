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
 * How much **file content** is waiting to upload, and whether the user's `WifiOnly` policy is what
 * is holding it (`sync-metered-plane-split.md` §8, D3) — the figures behind Drive's
 * *"Waiting for Wi-Fi. N files"* banner.
 *
 * ## Why a new row rather than a field on [SyncHealthStatus]
 *
 * [SyncHealthStatus] already carries a `pendingBlockCount`, and the banner used to read it. That
 * count is the **realm-wide `PENDING_BLOCKS` queue** — every app's `Syncable` writes, not files —
 * so on any multi-app device it is non-zero even when Drive has uploaded everything, which is
 * exactly how the banner came to claim files were waiting when none were. Since the plane split
 * that queue is not metered-gated at all, so it cannot be "deferred" by this policy either.
 *
 * The honest figure therefore had to come from somewhere else, and [SyncHealthStatus] **cannot
 * grow a field**: it is a positional composite consumed over IPC by a separately-installed Drive,
 * so appending shifts the layout for a version-skewed peer. A new composite id is the documented
 * escape hatch (that type's own "Adding to it later" note), and `SchemaFenceStatus`
 * (`core-schema-evolution.md` §7.7) is the precedent this mirrors exactly.
 *
 * ## Locally-derived, **NOT `Syncable`**
 *
 * Like [SyncHealthStatus] and [SchemaFenceStatus]: each device's proxy derives its own single row,
 * keyed on the constant [SINGLETON_KEY] so the relation upsert replaces rather than appends. It
 * describes *this* device's upload queue, so it is meaningless on another one; it never enters a
 * `SyncBlock` and never reaches the relay (House Rule #1 untouched), crossing only the
 * already-authenticated app↔Control IPC channel.
 *
 * ⚠ It owes **no `TENSHU_STORAGE_FORMAT_VERSION` bump of its own** (it reshapes no stored row and
 * adds no wire shape) and **no `SyncService` version bump** — the per-service version is the *data*
 * version that fences peers, and raising it over a purely local status row would make the epoch
 * writer pull-fence every not-yet-updated device (the §7.1 reasoning `SchemaFenceStatus` recorded).
 * The slice it ships in does bump the format version, but for the deferral journal, not for this.
 */
interface ContentTransferStatus : Composite {

    override val schema: CompositeSchema get() = Schema

    /** Stable single-row key; always [SINGLETON_KEY]. */
    val key: TString
        get() = getField(Field.KEY)

    /**
     * `true` while the metered content gate is closed **and** at least one file is waiting —
     * i.e. while "Waiting for Wi-Fi" is the truthful thing to say. A closed gate with nothing
     * queued is just idle, and an open gate with a queue is ordinary uploading.
     */
    val deferred: TBoolean
        get() = getField(Field.DEFERRED)

    /** Files waiting to upload — the banner's "N". Zero when nothing is queued. */
    val pendingFileCount: TInteger
        get() = getField(Field.PENDING_FILE_COUNT)

    /**
     * Total bytes those files will transfer, best-effort (a file deleted between the count and the
     * upload contributes here and then silently drops). Carried from v1 even though the v1 banner
     * shows only [pendingFileCount], for the same reason [SyncHealthStatus] reserved its fields:
     * this is a positional composite, so a field added later would shift the layout and break a
     * version-skewed Drive↔Control pair. "12 files (340 MB)" is the obvious next copy, and it must
     * not cost a wire break.
     */
    val pendingBytes: TInteger
        get() = getField(Field.PENDING_BYTES)

    companion object {
        const val ID = "tenshu/sync/contentTransferStatus"

        /** The one and only row's key. */
        const val SINGLETON_KEY = "current"

        val Schema: CompositeSchema = composite(
            id = ID,
            parent = Composite.Schema,
            traits = listOf(
                ContentTransferStatusDataIdProvider.Schema,
            ),
            fields = {
                string(Field.KEY)
                boolean(Field.DEFERRED)
                int(Field.PENDING_FILE_COUNT)
                int(Field.PENDING_BYTES)
            },
        )

        fun create(
            context: TContext,
            deferred: Boolean,
            pendingFileCount: Long,
            pendingBytes: Long,
        ): ContentTransferStatus = context.create(
            type = ID,
            fields = listOf(
                SINGLETON_KEY,
                deferred,
                pendingFileCount,
                pendingBytes,
            ),
        )
    }

    object Field {
        const val KEY = "$ID/key"
        const val DEFERRED = "$ID/deferred"
        const val PENDING_FILE_COUNT = "$ID/pendingFileCount"
        const val PENDING_BYTES = "$ID/pendingBytes"
    }
}

/**
 * Stable identity for the single [ContentTransferStatus] row — keyed on
 * [ContentTransferStatus.Field.KEY] (always [ContentTransferStatus.SINGLETON_KEY]) so the proxy's
 * relation upsert dedupes to one row. Mirrors [SyncHealthStatusDataIdProvider].
 */
interface ContentTransferStatusDataIdProvider : CompositeDataIdProvider<ContentTransferStatus> {

    override val schema: StringDataIdProviderSchema
        get() = Schema

    companion object {
        const val ID = "tenshu/sync/contentTransferStatus/dataIdProvider"

        val Schema: StringDataIdProviderSchema = stringDataId(
            id = ID,
            fieldRef = ContentTransferStatus.Field.KEY,
        )
    }
}
