package org.tenshu.sync

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

/**
 * Progress of the upload pass currently running — the *"3 of 12"* in Drive's
 * *"Uploading 3 of 12 · 45 Mb left"* (`drive-upload-progress.md` P2).
 *
 * ## Why this is a second row rather than two more fields on [ContentTransferStatus]
 *
 * That type already carries `deferred` / `pendingFileCount` / `pendingBytes` and is the obvious
 * home — but it is a **positional composite crossing the Drive↔Control IPC boundary**, i.e. two
 * separately-installed apps, so appending shifts the layout for a version-skewed pair. Its own
 * KDoc reserved `pendingBytes` from v1 for exactly that reason.
 *
 * Reshaping it was tried and the E2j-1 shape baseline correctly rejected it, demanding a
 * `SyncService` version bump. That bump would be **actively harmful here**: the per-service version
 * is the *data* version, so raising it makes the epoch writer pull-fence every not-yet-updated
 * device (`core-schema-evolution.md` §7.2) — over a purely local, never-synced UI row. That is the
 * precise reasoning E2d-2 recorded when it declined to bump for `SchemaFenceStatus`.
 *
 * So this follows the documented escape hatch instead: **a new composite id, never new fields**.
 * The cost is one more single-row relation; the saving is no service bump, no epoch rise, no
 * pull-fence, and no `TENSHU_STORAGE_FORMAT_VERSION` bump (hence no store wipe for users).
 *
 * ## Locally-derived, NOT `Syncable`
 *
 * Same posture as [ContentTransferStatus] and [SchemaFenceStatus]: one row keyed on
 * [SINGLETON_KEY], derived by this device's proxy, crossing only the authenticated app↔Control IPC
 * channel. It never enters a `SyncBlock` and never reaches the relay (House Rule #1 untouched).
 */
interface ContentUploadProgress : Composite {

    override val schema: CompositeSchema get() = Schema

    /** Stable single-row key; always [SINGLETON_KEY]. */
    val key: TString
        get() = getField(Field.KEY)

    /**
     * Files in the pass currently running; `0` when none is.
     *
     * ⚠ The **denominator is fixed for the pass** and is deliberately not the live queue size:
     * the queue counts down as files finish, so using it would render "3 of 9", then "4 of 8" — a
     * progress indicator whose denominator chases its numerator. Entries queued mid-pass belong to
     * the next one.
     */
    val passTotal: TInteger
        get() = getField(Field.PASS_TOTAL)

    /**
     * Files **resolved** so far in the current pass; `0` when none is running.
     *
     * Counts resolved rather than successfully-uploaded entries: an intent dropped because its file
     * no longer exists has left the queue, and a bar that stalls on work which can never succeed is
     * worse than one that moves past it.
     */
    val passCompleted: TInteger
        get() = getField(Field.PASS_COMPLETED)

    companion object {
        const val ID = "tenshu/sync/contentUploadProgress"

        /** The one and only row's key. */
        const val SINGLETON_KEY = "current"

        val Schema: CompositeSchema = composite(
            id = ID,
            parent = Composite.Schema,
            traits = listOf(
                ContentUploadProgressDataIdProvider.Schema,
            ),
            fields = {
                string(Field.KEY)
                int(Field.PASS_TOTAL)
                int(Field.PASS_COMPLETED)
            },
        )

        fun create(
            context: TContext,
            passTotal: Long,
            passCompleted: Long,
        ): ContentUploadProgress = context.create(
            type = ID,
            fields = listOf(
                SINGLETON_KEY,
                passTotal,
                passCompleted,
            ),
        )
    }

    object Field {
        const val KEY = "$ID/key"
        const val PASS_TOTAL = "$ID/passTotal"
        const val PASS_COMPLETED = "$ID/passCompleted"
    }
}

/**
 * Stable identity for the single [ContentUploadProgress] row — keyed on
 * [ContentUploadProgress.Field.KEY] so the proxy's relation upsert dedupes to one row. Mirrors
 * [ContentTransferStatusDataIdProvider].
 */
interface ContentUploadProgressDataIdProvider : CompositeDataIdProvider<ContentUploadProgress> {

    override val schema: StringDataIdProviderSchema
        get() = Schema

    companion object {
        const val ID = "tenshu/sync/contentUploadProgress/dataIdProvider"

        val Schema: StringDataIdProviderSchema = stringDataId(
            id = ID,
            fieldRef = ContentUploadProgress.Field.KEY,
        )
    }
}
