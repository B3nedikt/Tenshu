package org.tenshu.core.file

import org.tenshu.core.Composite
import org.tenshu.core.TString
import org.tenshu.core.core.TContext
import org.tenshu.core.core.create
import org.tenshu.core.entity.CompositeDataIdProvider
import org.tenshu.core.entity.Entity
import org.tenshu.core.entity.StringDataIdProviderSchema
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.string
import org.tenshu.core.schema.stringDataId
import org.tenshu.core.schema.timestamp
import org.tenshu.core.time.Instant
import org.tenshu.core.trait.AppendOnly
import org.tenshu.core.trait.SyncStrategy
import org.tenshu.core.trait.Syncable
import org.tenshu.core.relation.Property
import org.tenshu.core.relation.valueProperty
import org.tenshu.identity.Device

/**
 * Abstract super-composite for a recorded change to a file
 * (`docs/proposals/file-service-history.md` §2.4). Concrete subtypes —
 * [CreatedFileModification], [ContentChangedFileModification],
 * [RenamedFileModification], [MovedFileModification] — describe *what* happened.
 *
 * Modifications form a realm-wide, append-only collection (one row per change),
 * not an inline list on `File` (§3.1). The common fields live here on the
 * parent; subtypes add their own. The single [FileModificationDataIdProvider]
 * (keyed by the derived [compositeKey]) is declared on the parent and inherited
 * by every subtype — the same parent-trait inheritance `File` → `Folder` uses.
 *
 * `deviceId` is modelled as a plain `TString` rather than the sync layer's
 * `DeviceId` type to keep `:file-service` free of a `:sync-service` dependency;
 * it carries the authoring device's stable id for "modified on <device>"
 * attribution.
 *
 * `timestamp` is a `UnixInstant`; HLC tie-breaking for same-millisecond events
 * is a merge-layer concern wired with the append-only merger in Phase 2.
 */
interface FileModification : Composite {

    override val schema: CompositeSchema get() = Schema

    val fileId: FilePath
        get() = getField(Field.FILE_ID)

    val timestamp: Instant
        get() = getField(Field.TIMESTAMP)

    val deviceId: TString
        get() = getField(Field.DEVICE_ID)

    val compositeKey: TString
        get() = getField(Field.COMPOSITE_KEY)

    companion object {
        const val ID = "tenshu/file/modification"

        /**
         * Per-file retention cap (`docs/proposals/file-service-history.md` §4.6):
         * keep at most this many modifications per file. When a write pushes a
         * file's history over the cap, the originating device trims its oldest
         * **non-[CreatedFileModification]** rows (Created is the pinned floor
         * record) and propagates the trim via per-row tombstones. Lives here
         * until the preference service can make it per-app configurable.
         */
        const val RETENTION_CAP = 50

        val Schema: CompositeSchema = composite(
            id = ID,
            parent = Composite.Schema,
            traits = listOf(
                FileModificationDataIdProvider.Schema,
                Syncable(SyncStrategy.Eager),
                AppendOnly.Schema,
            ),
            fields = {
                string(id = Field.FILE_ID, type = FilePath.Schema)
                timestamp(id = Field.TIMESTAMP)
                string(id = Field.DEVICE_ID)
                string(id = Field.COMPOSITE_KEY)
            }
        )

        /**
         * Derived unique key for a modification row. `(deviceId, timestamp)` is
         * globally unique (HLC is monotonic per device); `fileId` is included
         * for legibility. The `|` separator avoids ambiguity with `/` in paths.
         */
        fun compositeKey(fileId: String, deviceId: String, timestamp: Long): String =
            "$fileId|$deviceId|$timestamp"
    }

    object Field {
        const val FILE_ID = "$ID/fileId"
        const val TIMESTAMP = "$ID/timestamp"
        const val DEVICE_ID = "$ID/deviceId"
        const val COMPOSITE_KEY = "$ID/compositeKey"
    }
}

val Entity<out FileModification>.fileId: FilePath
    get() = getFieldValue(FileModification.Field.FILE_ID)

val Entity<out FileModification>.timestamp: Instant
    get() = getFieldValue(FileModification.Field.TIMESTAMP)

val Entity<out FileModification>.deviceId: TString
    get() = getFieldValue(FileModification.Field.DEVICE_ID)

// ── reference property: deviceId → Device ────────────────────────────────────
// A reference value property (docs/proposals/core-reference-property.md): resolves the
// authoring Device live from the plain `deviceId` field. The wire selector is the uniform
// StaticPropertyRelation; `deviceId` stays a plain TString (no identity coupling on the
// field). Registered via `referenceProperty(...)` in `fileServiceCommonModule`.
private val fileModificationDevice: Property.Value<Device> =
    valueProperty(id = "${FileModification.ID}/device", schema = Device.Schema)

/**
 * The [Device] that authored this modification — resolved live by `deviceId` for the
 * "modified on <device>" attribution. Projected via `relations(FileModification.Device)`,
 * read via `entity.get(FileModification.Device)`; `null` when the device is unknown
 * locally (the UI falls back to the raw `deviceId`).
 */
val FileModification.Companion.Device: Property.Value<Device> get() = fileModificationDevice

interface FileModificationDataIdProvider : CompositeDataIdProvider<FileModification> {

    override val schema: StringDataIdProviderSchema
        get() = Schema

    companion object {
        const val ID = "tenshu/file/modification/dataIdProvider"

        val Schema: StringDataIdProviderSchema = stringDataId(
            id = ID,
            fieldRef = FileModification.Field.COMPOSITE_KEY
        )
    }
}

/** The first record for a file — emitted by `CreateFile`. No extra payload. */
interface CreatedFileModification : FileModification {

    override val schema: CompositeSchema get() = Schema

    companion object {
        const val ID = "tenshu/file/modification/created"

        val Schema: CompositeSchema = composite(
            id = ID,
            parent = FileModification.Schema,
            fields = { }
        )

        fun create(
            context: TContext,
            fileId: String,
            timestamp: Long,
            deviceId: String,
        ): CreatedFileModification = context.create(
            type = ID,
            fields = listOf(
                fileId,
                timestamp,
                deviceId,
                FileModification.compositeKey(fileId, deviceId, timestamp),
            )
        )
    }
}

/** A new file content manifest was written — emitted by `WriteFileContent`. */
interface ContentChangedFileModification : FileModification {

    override val schema: CompositeSchema get() = Schema

    /** Reference to the new content manifest, for future "restore to version". */
    val manifestId: TString
        get() = getField(Field.MANIFEST_ID)

    companion object {
        const val ID = "tenshu/file/modification/contentChanged"

        val Schema: CompositeSchema = composite(
            id = ID,
            parent = FileModification.Schema,
            fields = {
                string(id = Field.MANIFEST_ID)
            }
        )

        fun create(
            context: TContext,
            fileId: String,
            timestamp: Long,
            deviceId: String,
            manifestId: String,
        ): ContentChangedFileModification = context.create(
            type = ID,
            fields = listOf(
                fileId,
                timestamp,
                deviceId,
                FileModification.compositeKey(fileId, deviceId, timestamp),
                manifestId,
            )
        )
    }

    object Field {
        const val MANIFEST_ID = "$ID/manifestId"
    }
}

val Entity<out ContentChangedFileModification>.manifestId: TString
    get() = getFieldValue(ContentChangedFileModification.Field.MANIFEST_ID)

/** A file was renamed in place — emitted by `RenameFile`. Carries both names. */
interface RenamedFileModification : FileModification {

    override val schema: CompositeSchema get() = Schema

    val oldName: TString
        get() = getField(Field.OLD_NAME)

    val newName: TString
        get() = getField(Field.NEW_NAME)

    companion object {
        const val ID = "tenshu/file/modification/renamed"

        val Schema: CompositeSchema = composite(
            id = ID,
            parent = FileModification.Schema,
            fields = {
                string(id = Field.OLD_NAME)
                string(id = Field.NEW_NAME)
            }
        )

        fun create(
            context: TContext,
            fileId: String,
            timestamp: Long,
            deviceId: String,
            oldName: String,
            newName: String,
        ): RenamedFileModification = context.create(
            type = ID,
            fields = listOf(
                fileId,
                timestamp,
                deviceId,
                FileModification.compositeKey(fileId, deviceId, timestamp),
                oldName,
                newName,
            )
        )
    }

    object Field {
        const val OLD_NAME = "$ID/oldName"
        const val NEW_NAME = "$ID/newName"
    }
}

val Entity<out RenamedFileModification>.oldName: TString
    get() = getFieldValue(RenamedFileModification.Field.OLD_NAME)

val Entity<out RenamedFileModification>.newName: TString
    get() = getFieldValue(RenamedFileModification.Field.NEW_NAME)

/**
 * A file moved to a different folder — emitted by `MoveFile`. Distinct from a
 * rename because the parent-folder relation changes (§2.2).
 */
interface MovedFileModification : FileModification {

    override val schema: CompositeSchema get() = Schema

    val oldParentPath: TString
        get() = getField(Field.OLD_PARENT_PATH)

    val newParentPath: TString
        get() = getField(Field.NEW_PARENT_PATH)

    companion object {
        const val ID = "tenshu/file/modification/moved"

        val Schema: CompositeSchema = composite(
            id = ID,
            parent = FileModification.Schema,
            fields = {
                string(id = Field.OLD_PARENT_PATH)
                string(id = Field.NEW_PARENT_PATH)
            }
        )

        fun create(
            context: TContext,
            fileId: String,
            timestamp: Long,
            deviceId: String,
            oldParentPath: String,
            newParentPath: String,
        ): MovedFileModification = context.create(
            type = ID,
            fields = listOf(
                fileId,
                timestamp,
                deviceId,
                FileModification.compositeKey(fileId, deviceId, timestamp),
                oldParentPath,
                newParentPath,
            )
        )
    }

    object Field {
        const val OLD_PARENT_PATH = "$ID/oldParentPath"
        const val NEW_PARENT_PATH = "$ID/newParentPath"
    }
}

val Entity<out MovedFileModification>.oldParentPath: TString
    get() = getFieldValue(MovedFileModification.Field.OLD_PARENT_PATH)

val Entity<out MovedFileModification>.newParentPath: TString
    get() = getFieldValue(MovedFileModification.Field.NEW_PARENT_PATH)
