package app.tenshu.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Receives a notification-button press and hands it to the registered
 * [AlertActionHandler] (CA-5f).
 *
 * ⚠⚠ **App-agnostic on purpose**, like the presenter beside it: it knows an
 * alert id, an action id and an opaque payload, and nothing about calendars. The
 * `PunctualAlarmReceiver` precedent — a receiver in a service module, declared in
 * whichever app's manifest hosts the process.
 *
 * ⚠ `goAsync()` rather than a fire-and-forget `launch`: without it the process
 * may be killed the moment `onReceive` returns and the write never lands. The
 * budget is ~10 s, so the work is bounded well inside it and the result reported
 * either way — a receiver that never calls `finish()` is an ANR.
 *
 * ⚠⚠ **The notification is cancelled *after* the handler succeeds, not before.**
 * Dismissing first would make a failed write look like it worked, and the user's
 * "snooze" would silently become "the reminder is gone".
 */
class AlertActionReceiver : BroadcastReceiver(), KoinComponent {

    private val handler: AlertActionHandler by inject()

    override fun onReceive(context: Context, intent: Intent) {
        val payload = intent.getStringExtra(KEY_TAP_PAYLOAD)?.takeIf { it.isNotBlank() } ?: return
        val actionId = intent.getStringExtra(KEY_ACTION_ID)?.takeIf { it.isNotBlank() } ?: return
        val alertId = intent.getStringExtra(KEY_ALERT_ID).orEmpty()

        val result = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            val handled = withTimeoutOrNull(TIMEOUT_MS) {
                runCatching { handler.handle(payload, actionId) }.getOrDefault(false)
            } ?: false
            if (handled && alertId.isNotBlank()) {
                NotificationManagerCompat.from(context).cancel(alertId.hashCode())
            }
            result.finish()
        }
    }

    companion object {
        const val KEY_TAP_PAYLOAD = "app.tenshu.notify.action.payload"
        const val KEY_ACTION_ID = "app.tenshu.notify.action.id"
        const val KEY_ALERT_ID = "app.tenshu.notify.action.alertId"

        /** ⚠ Comfortably inside the ~10 s a receiver gets, so `finish()` always runs. */
        const val TIMEOUT_MS = 8_000L
    }
}
