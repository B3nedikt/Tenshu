package app.tenshu.drive.android.backup

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.tenshu.drive.android.MainActivity
import app.tenshu.drive.android.R
import app.tenshu.ingest.backup.MediaBucket
import app.tenshu.ingest.backup.NewFolderAnnouncer

/**
 * Android surface for the "a new folder appeared — back it up?" prompt
 * (`NewFolderDetector`): a notification with inline *Back up* / *No thanks*
 * actions, so the common case is answered without ever opening Drive. Tapping the
 * body instead opens the Synced Folders screen, where the same choice can be made
 * alongside every other folder.
 *
 * Its own **default-importance** channel, separate from the low-importance
 * `synced_folders` progress channel: this one is a genuine interruption the user
 * should see, and it must be independently silenceable — a user who mutes backup
 * *progress* has not asked to stop being told about new folders, and vice versa.
 *
 * Notification-permission posture matches the rest of Drive's notifications:
 * `POST_NOTIFICATIONS` is requested by the app shell, and a post without it is a
 * silent no-op rather than a crash (hence [SuppressLint] + the enabled check).
 */
class AndroidNewFolderAnnouncer(
    private val context: Context,
) : NewFolderAnnouncer {

    @SuppressLint("MissingPermission")
    override suspend fun announce(bucket: MediaBucket) {
        val manager = NotificationManagerCompat.from(context)
        // No permission (Android 13+ pre-grant) → drop it. The folder is already in
        // the known-set, so this is a genuine one-shot miss; the Synced Folders
        // screen remains the discovery path.
        if (!manager.areNotificationsEnabled()) return
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                .setName(context.getString(R.string.new_folder_channel))
                .setDescription(context.getString(R.string.new_folder_channel_description))
                .build()
        )
        manager.notify(notificationId(bucket.id), build(bucket))
    }

    private fun build(bucket: MediaBucket) = NotificationCompat.Builder(context, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.stat_sys_upload)
        .setContentTitle(context.getString(R.string.new_folder_title, bucket.displayName))
        .setContentText(
            context.resources.getQuantityString(
                R.plurals.new_folder_body, bucket.itemCount, bucket.itemCount,
            )
        )
        .setAutoCancel(true)
        .setContentIntent(openSyncedFolders())
        .addAction(
            0,
            context.getString(R.string.new_folder_accept),
            action(NewFolderActionReceiver.ACTION_ACCEPT, bucket.id),
        )
        .addAction(
            0,
            context.getString(R.string.new_folder_dismiss),
            action(NewFolderActionReceiver.ACTION_DISMISS, bucket.id),
        )
        .build()

    /** Tap the body → Drive, landing on Synced Folders. */
    private fun openSyncedFolders(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_MAIN)
            .putExtra(MainActivity.EXTRA_OPEN_SYNCED_FOLDERS, true)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(
            context,
            REQUEST_OPEN,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * An inline answer. The request code is derived from (action, bucket) so two
     * folders' pending intents can't collide into one another's extras — the classic
     * `PendingIntent` reuse bug, where every notification ends up acting on whichever
     * bucket was announced last.
     */
    private fun action(action: String, bucketId: String): PendingIntent {
        val intent = Intent(context, NewFolderActionReceiver::class.java)
            .setAction(action)
            .putExtra(NewFolderActionReceiver.EXTRA_BUCKET_ID, bucketId)
            .putExtra(NewFolderActionReceiver.EXTRA_NOTIFICATION_ID, notificationId(bucketId))
        return PendingIntent.getBroadcast(
            context,
            (action + bucketId).hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** One notification per bucket, so two new folders don't overwrite each other. */
    private fun notificationId(bucketId: String): Int = NOTIFICATION_ID_BASE + bucketId.hashCode()

    private companion object {
        const val CHANNEL_ID = "tenshu_drive_new_folder"
        const val NOTIFICATION_ID_BASE = 0xD0BB
        const val REQUEST_OPEN = 0xD0BC
    }
}
