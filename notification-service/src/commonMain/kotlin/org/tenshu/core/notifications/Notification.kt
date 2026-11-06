package org.tenshu.core.notifications

import org.tenshu.core.Composite
import org.tenshu.core.TString
import org.tenshu.core.entity.CompositeDataIdProvider
import org.tenshu.core.entity.Entity
import org.tenshu.core.entity.StringDataIdProviderSchema
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.string
import org.tenshu.core.schema.stringDataId
import org.tenshu.core.schema.timestamp
import org.tenshu.core.time.UnixInstant
import org.tenshu.core.trait.SyncStrategy
import org.tenshu.core.trait.Syncable

/**
 * Base interface for all notifications.
 * The frontend uses the specific type (e.g., FileAddedNotification) to generate messages.
 */
interface Notification : Composite {

    override val schema: CompositeSchema get() = Schema

    /**
     * Unique identifier for this notification instance.
     */
    val uuid: TString
        get() = getField(Fields.UUID)

    val timestamp: UnixInstant
        get() = getField(Fields.TIMESTAMP)

    companion object {
        const val ID = "tenshu/notifications/notification"

        val Schema: CompositeSchema = composite(
            id = ID,
            parent = Composite.Schema,
            // `Syncable` so notifications produced on one device (e.g. the host
            // authoring a `FolderAddedNotification` via `CreateFolder`) propagate
            // to every paired device's notification stream. Without this trait
            // `SyncObserver.onDataSaved` short-circuits on the schema check and
            // no block is emitted — so prior to this, notifications never reached
            // the joiner side at all. See
            // `system-test-utils/.../NotificationSyncSystemTest`.
            //
            // `Eager` (push on save) + default `LastWriterWins` conflict, mirroring
            // `Device`: notifications are individual events keyed by unique uuid,
            // so semantic merge has nothing useful to do — receivers either have
            // the entry or they don't.
            traits = listOf(NotificationDataIdProvider.Schema, Syncable(SyncStrategy.Eager)),
            fields = {
                string(Fields.UUID)
                timestamp(Fields.TIMESTAMP)
            }
        )
    }

    object Fields {
        const val UUID = "$ID/uuid"
        const val TIMESTAMP = "$ID/timestamp"
    }
}

val Entity<out Notification>.uuid: TString
    get() = getFieldValue(Notification.Fields.UUID)

val Entity<out Notification>.timestamp: UnixInstant
    get() = getFieldValue(Notification.Fields.TIMESTAMP)

interface NotificationDataIdProvider : CompositeDataIdProvider<Notification> {

    override val schema: StringDataIdProviderSchema
        get() = Schema

    companion object {
        const val ID = "tenshu/notifications/notification/dataIdProvider"

        val Schema: StringDataIdProviderSchema = stringDataId(
            id = ID,
            fieldRef = Notification.Fields.UUID
        )
    }
}
