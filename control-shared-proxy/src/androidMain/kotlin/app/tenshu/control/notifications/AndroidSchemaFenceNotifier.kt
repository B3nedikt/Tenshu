package app.tenshu.control.notifications

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.tenshu.control.proxy.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow

/**
 * The Android effect behind [SchemaFenceAlertHandle]: one "Update Control to keep
 * syncing" system notification when the schema pull fence engages, withdrawn when it lifts.
 *
 * Driven by the in-process `SyncHealthHolder` verdict, which the scheduler writes inside this
 * process — so it fires from the foreground service and from a `DrainSync` background wake
 * alike, with no app open. ⚠ That second case is why the "already alerted" memory is
 * **persisted**: each wake is a new process, a fenced device stays fenced across all of them,
 * and an in-memory memory would re-alert on every wake.
 *
 * Its own DEFAULT-importance channel: not a request someone is waiting on (the HIGH
 * actionable-requests channel), and not the LOW task-liveness channel either, because this
 * one needs the user to do something.
 */
class AndroidSchemaFenceNotifier(
    private val context: Context,
) : SchemaFenceAlertHandle {

    private val manager get() = NotificationManagerCompat.from(context)

    /** Alert on [verdicts] for the life of [scope]. */
    fun start(scope: CoroutineScope, verdicts: Flow<List<String>?>) {
        SchemaFenceAlerts(this, PreferencesMemory(context)).launchIn(scope, verdicts)
    }

    // notify() no-ops when POST_NOTIFICATIONS is denied; the home banner still says it.
    @SuppressLint("MissingPermission")
    override fun onFenceEngaged(fencedServiceIds: List<String>) {
        createChannel()
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(context.getString(R.string.schema_fence_title))
            .setContentText(context.getString(R.string.schema_fence_body))
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(context.getString(R.string.schema_fence_body)),
            )
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setContentIntent(openControlIntent())
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }

    override fun onFenceCleared() {
        manager.cancel(NOTIFICATION_ID)
    }

    /** Opens Control's home, where the same message stays as a banner until the fence lifts. */
    private fun openControlIntent(): PendingIntent? {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: return null
        return PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun createChannel() {
        val channel = NotificationChannelCompat.Builder(
            CHANNEL_ID,
            NotificationManagerCompat.IMPORTANCE_DEFAULT,
        )
            .setName(context.getString(R.string.schema_fence_channel))
            .build()
        manager.createNotificationChannel(channel)
    }

    /** Device-local and never synced: whether *this* device has been told about its fence. */
    private class PreferencesMemory(context: Context) : SchemaFenceAlertMemory {
        private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        override var alerted: Boolean
            get() = prefs.getBoolean(KEY_ALERTED, false)
            set(value) {
                prefs.edit().putBoolean(KEY_ALERTED, value).apply()
            }
    }

    private companion object {
        const val CHANNEL_ID = "tenshu_sync_problems"
        const val NOTIFICATION_ID = 0x2531
        const val PREFS = "tenshu_schema_fence_alert"
        const val KEY_ALERTED = "alerted"
    }
}
