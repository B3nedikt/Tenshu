package org.tenshu.core.common.app

import android.app.ActivityOptions
import android.app.PendingIntent
import android.os.Build
import android.util.Log

/**
 * Fire the launch Control resolved for us (`control-cross-app-open.md` §5c, Option B).
 *
 * ⚠⚠ **Call this from the foreground, on the user's gesture — that is the entire point.** Android
 * refuses an activity start from a background process, and Control is one, so the ticket exists to
 * move the *act* to the app that has the privilege while leaving the *decision* with Control. Fire
 * it from a background worker and it is refused exactly as Control's own attempt was.
 *
 * ⚠⚠ **The refusal is silent.** `PendingIntent.send()` throws only [PendingIntent.CanceledException];
 * a start the framework aborts under its background-activity-launch rules comes back as an ordinary
 * return. So a `true` here means *"the ticket was not cancelled"* and never *"the app came up"* —
 * CO-D9's honest signal is the handler reporting in, not this.
 */
suspend fun fireLaunchTicket(
    ticketId: String,
    timeoutMs: Long = LaunchTicketInbox.DEFAULT_TIMEOUT_MS,
): Boolean {
    val ticket = LaunchTicketInbox.await(ticketId, timeoutMs) ?: run {
        Log.w(TAG, "No launch ticket arrived for $ticketId")
        return false
    }
    return ticket.fire()
}

private fun PendingIntent.fire(): Boolean = try {
    // ⚠ The sender grants its OWN background-activity-start privileges to the ticket. Since
    // Android 14 the creator's are not granted by default, and Control — a background proxy —
    // has none to give anyway; the foreground caller's are the only ones in play.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        val options = ActivityOptions.makeBasic()
            .setPendingIntentBackgroundActivityStartMode(
                ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED,
            )
        send(null, 0, null, null, null, null, options.toBundle())
    } else {
        send()
    }
    true
} catch (e: PendingIntent.CanceledException) {
    // The handler was uninstalled or the ticket superseded between mint and tap.
    Log.w(TAG, "Launch ticket was cancelled", e)
    false
}

private const val TAG = "LaunchTicket"
