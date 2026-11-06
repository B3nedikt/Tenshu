package org.tenshu.core.file.notifications

import org.tenshu.core.TString
import org.tenshu.core.core.TContext
import org.tenshu.core.core.create
import org.tenshu.core.entity.Entity
import org.tenshu.core.notifications.Notification
import org.tenshu.core.number.TInteger
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.int
import org.tenshu.core.schema.string
import kotlin.jvm.JvmName

interface FileNotification : Notification {

    override val schema: CompositeSchema get() = Schema

    companion object {
        const val ID = "tenshu/notifications/file"

        val Schema = composite(
            id = ID,
            parent = Notification.Schema
        )
    }
}

interface FileAddedNotification : FileNotification {

    override val schema: CompositeSchema get() = Schema

    val path: TString
        get() = getField(Fields.PATH)

    /**
     * How many files this row stands for. A bulk upload does **not** mint one
     * row per file: the producer (`FileAddedNotificationCoalescer` in
     * `:file-service-proxy`) folds a burst into a single row whose count is
     * bumped in place — [path] then holds the *newest* file of the burst (the
     * display headline). `1` for a single upload. Optional so rows minted
     * before the field existed still read (`null` → treat as 1); the producer
     * always writes it. See `docs/proposals/notification-burst-aggregation.md`.
     */
    val count: TInteger?
        get() = getOptionalField(Fields.COUNT)

    companion object {
        const val ID = "tenshu/notifications/file/added"

        val Schema = composite(
            id = ID,
            parent = FileNotification.Schema,
            fields = {
                string(Fields.PATH)
                int(Fields.COUNT, isOptional = true)
            }
        )

        fun create(
            context: TContext,
            uuid: String,
            timestamp: Long,
            path: String,
            count: Long = 1L,
        ): FileAddedNotification = context.create(
            type = ID,
            fields = listOf(uuid, timestamp, path, count)
        )
    }

    object Fields {
        const val PATH = "$ID/path"
        const val COUNT = "$ID/count"
    }
}

@get:JvmName("getFileAddedPath")
val Entity<out FileAddedNotification>.path: TString
    get() = getFieldValue(FileAddedNotification.Fields.PATH)

interface FileDeletedNotification : FileNotification {

    override val schema: CompositeSchema get() = Schema

    val path: TString
        get() = getField(Fields.PATH)

    companion object {
        const val ID = "tenshu/notifications/file/deleted"

        val Schema = composite(
            id = ID,
            parent = FileNotification.Schema,
            fields = {
                string(Fields.PATH)
            }
        )

        fun create(
            context: TContext,
            uuid: String,
            timestamp: Long,
            path: String
        ): FileDeletedNotification = context.create(
            type = ID,
            fields = listOf(uuid, timestamp, path)
        )
    }

    object Fields {
        const val PATH = "$ID/path"
    }
}

@get:JvmName("getFileDeletedPath")
val Entity<out FileDeletedNotification>.path: TString
    get() = getFieldValue(FileDeletedNotification.Fields.PATH)

interface FolderAddedNotification : FileAddedNotification {

    override val schema: CompositeSchema get() = Schema

    companion object {
        const val ID = "tenshu/notifications/folder/added"

        val Schema = composite(
            id = ID,
            parent = FileAddedNotification.Schema
        )

        fun create(
            context: TContext,
            uuid: String,
            timestamp: Long,
            path: String
        ): FolderAddedNotification = context.create(
            type = ID,
            fields = listOf(uuid, timestamp, path)
        )
    }
}

@get:JvmName("getFolderAddedPath")
val Entity<out FolderAddedNotification>.path: TString
    get() = getFieldValue(FileAddedNotification.Fields.PATH)

interface FolderDeletedNotification : FileDeletedNotification {

    override val schema: CompositeSchema get() = Schema

    companion object {
        const val ID = "tenshu/notifications/folder/deleted"

        val Schema = composite(
            id = ID,
            parent = FileDeletedNotification.Schema
        )

        fun create(
            context: TContext,
            uuid: String,
            timestamp: Long,
            path: String
        ): FolderDeletedNotification = context.create(
            type = ID,
            fields = listOf(uuid, timestamp, path)
        )
    }
}

@get:JvmName("getFolderDeletedPath")
val Entity<out FolderDeletedNotification>.path: TString
    get() = getFieldValue(FileDeletedNotification.Fields.PATH)

/**
 * ⭐ CR-2 B4 P3 (`content-plane-cr2-b4-spec.md` §2.4, `content-plane-cr2-quorumless.md` §9 Q2): a change
 * this device made to [path] could not be delivered, because another device deleted the file and the
 * relay has freed its content, and this device holds no bytes to put it back. The row was retired here.
 *
 * "[fileName] was deleted on another device while your change was waiting."
 *
 * ⚠⚠ Sent through `SendNotification`, so it is a **synced** row, deliberately: the user sees it on
 * whichever device they look at, not only the one that lost the change.
 */
interface ChangeLostNotification : FileNotification {

    override val schema: CompositeSchema get() = Schema

    /** The file's display name, the last segment of [path]. */
    val fileName: TString
        get() = getField(Fields.FILE_NAME)

    val path: TString
        get() = getField(Fields.PATH)

    companion object {
        const val ID = "tenshu/notifications/file/changeLost"

        val Schema = composite(
            id = ID,
            parent = FileNotification.Schema,
            fields = {
                string(Fields.FILE_NAME)
                string(Fields.PATH)
            }
        )

        fun create(
            context: TContext,
            uuid: String,
            timestamp: Long,
            fileName: String,
            path: String,
        ): ChangeLostNotification = context.create(
            type = ID,
            fields = listOf(uuid, timestamp, fileName, path)
        )

        /** The one wording every surface shows (Control's feed, Drive's message, the OS alert). */
        fun message(fileName: String): String =
            "$fileName was deleted on another device while your change was waiting"
    }

    object Fields {
        const val FILE_NAME = "$ID/fileName"
        const val PATH = "$ID/path"
    }
}
