package app.tenshu.drive.android.backup

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import app.tenshu.ingest.backup.NewFolderDecisions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Handles the inline *Back up* / *No thanks* actions on the new-folder prompt
 * ([AndroidNewFolderAnnouncer]) so the user never has to open Drive to answer.
 *
 * The write is a preference round-trip over IPC to Control, so it can't complete
 * inside `onReceive`'s synchronous window — it runs on a `goAsync()` result held
 * until the coroutine finishes. The notification is cancelled immediately (before
 * the write) so the UI feels instant; the write is idempotent, and a killed
 * process at worst loses one answer, which re-reads as "the folder was already
 * marked known" — i.e. it is never re-announced either way.
 */
class NewFolderActionReceiver : BroadcastReceiver(), KoinComponent {

    private val decisions: NewFolderDecisions by inject()

    override fun onReceive(context: Context, intent: Intent) {
        val bucketId = intent.getStringExtra(EXTRA_BUCKET_ID) ?: return
        val action = intent.action ?: return
        if (action != ACTION_ACCEPT && action != ACTION_DISMISS) return

        intent.getIntExtra(EXTRA_NOTIFICATION_ID, -1)
            .takeIf { it != -1 }
            ?.let { NotificationManagerCompat.from(context).cancel(it) }

        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                if (action == ACTION_ACCEPT) decisions.accept(bucketId)
                else decisions.dismiss(bucketId)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_ACCEPT = "app.tenshu.drive.NEW_FOLDER_ACCEPT"
        const val ACTION_DISMISS = "app.tenshu.drive.NEW_FOLDER_DISMISS"
        const val EXTRA_BUCKET_ID = "bucketId"
        const val EXTRA_NOTIFICATION_ID = "notificationId"
    }
}
