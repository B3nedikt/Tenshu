package app.tenshu.control.data

import org.tenshu.core.file.notifications.ChangeLostNotification
import org.tenshu.core.file.notifications.FileAddedNotification
import org.tenshu.core.file.notifications.FileDeletedNotification
import org.tenshu.core.file.notifications.FolderAddedNotification
import org.tenshu.core.file.notifications.FolderDeletedNotification
import org.tenshu.core.notifications.AppRegisteredNotification
import org.tenshu.core.notifications.AppRemovedNotification
import org.tenshu.core.notifications.Notification
import kotlin.time.Instant

class NotificationMapper {

    /**
     * Maps a single [Notification] to its display [Event], or `null` for a type
     * this client doesn't understand. Returning `null` (rather than throwing)
     * keeps one forward-incompatible notification from blanking the entire feed
     * — a newer device could sync a notification subtype this build predates.
     */
    fun mapToEventOrNull(notification: Notification): Event? {
        val timestamp = Instant.fromEpochMilliseconds(notification.timestamp.get())
        val id = notification.uuid.get()

        return when (notification) {
            // FolderAddedNotification is-a FileAddedNotification, so it must be
            // matched first.
            is FolderAddedNotification -> Event.FileEvent.Added(
                id = id,
                timestamp = timestamp,
                fileName = notification.path.get(),
                preview = null,
                isFolder = true
            )

            // The producer coalesces a bulk upload into ONE row whose `count` is
            // bumped in place (`FileAddedNotificationCoalescer`, proxy-side) —
            // `count > 1` renders as the "N files added" event directly, no
            // read-side folding involved. A `null` count is a row minted before
            // the field existed (pre-coalescing build) → a single file.
            is FileAddedNotification -> {
                val count = notification.count?.get() ?: 1L
                if (count > 1L) {
                    Event.FileEvent.MultipleAdded(
                        id = id,
                        timestamp = timestamp,
                        firstFileName = notification.path.get(),
                        otherFilesCount = (count - 1L).toInt(),
                    )
                } else {
                    Event.FileEvent.Added(
                        id = id,
                        timestamp = timestamp,
                        fileName = notification.path.get(),
                        preview = null,
                        isFolder = false
                    )
                }
            }

            // FolderDeletedNotification is-a FileDeletedNotification, so it must be
            // matched first.
            is FolderDeletedNotification -> Event.FileEvent.Deleted(
                id = id,
                timestamp = timestamp,
                fileName = notification.path.get(),
                preview = null,
                isFolder = true
            )

            is FileDeletedNotification -> Event.FileEvent.Deleted(
                id = id,
                timestamp = timestamp,
                fileName = notification.path.get(),
                preview = null
            )

            // CR-2 B4 P3 (`content-plane-cr2-b4-spec.md` §2.4).
            is ChangeLostNotification -> Event.FileEvent.ChangeLost(
                id = id,
                timestamp = timestamp,
                fileName = notification.fileName.get(),
                path = notification.path.get(),
            )

            is AppRegisteredNotification -> Event.AppEvent.Registered(
                id = id,
                timestamp = timestamp,
                appName = notification.appName.get()
            )

            is AppRemovedNotification -> Event.AppEvent.Removed(
                id = id,
                timestamp = timestamp,
                appName = notification.appName.get()
            )

            else -> null
        }
    }

    // NOTE: the former `aggregate(events)` client-side burst fold (and its
    // BURST_WINDOW) is gone — burst-collapsing happens at the PRODUCER now
    // (`FileAddedNotificationCoalescer`, `:file-service-proxy`): a bulk upload
    // mints ONE FileAddedNotification row whose `count` is bumped in place,
    // and the `count > 1` branch above renders it. See
    // `docs/proposals/notification-burst-aggregation.md`.
}
