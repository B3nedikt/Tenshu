package org.tenshu.sync.proxy

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.workDataOf

/**
 * The Android half of RE-D5's punctual class: a one-shot that must land **at an
 * instant** goes to `AlarmManager`, not `WorkManager`.
 *
 * ⚠⚠ **Why not WorkManager.** It batches deliberately — that is its job, and the
 * right behaviour for maintenance. For "turn the porch light on at sunset" it is
 * the wrong tool: a routine batched into the next convenient wake-up has not
 * been slightly late, it has failed. The two schedulers coexist; `punctual` in
 * the plan is the switch.
 *
 * ⚖️ **Permission posture, and a decision that is easy to get wrong.** We use
 * `setExactAndAllowWhileIdle` **only when the user has already granted
 * `SCHEDULE_EXACT_ALARM`**, and fall back to `setAndAllowWhileIdle` otherwise —
 * inexact but Doze-aware, which is still far better than WorkManager's
 * 15-minute floor.
 *
 * ⚠⚠ **`USE_EXACT_ALARM` is deliberately NOT declared.** It grants exactness
 * without asking, but Play policy restricts it to apps whose *core* function is
 * alarms or calendars; claiming it for a smart-home app is a review risk that
 * would put the whole app at stake for one feature. `SCHEDULE_EXACT_ALARM` is
 * the honest route, and it should be requested **only** at the moment a user
 * creates a time routine in a household with no always-on device — the one
 * configuration where it is genuinely load-bearing. Everywhere else the
 * inexact path is fine, because a hub or a resident desktop wins the RE-D2
 * claim and fires on time anyway.
 */
class PunctualAlarmScheduler(
    private val context: Context,
) {

    private val alarms: AlarmManager
        get() = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    /** True when the OS will honour an *exact* alarm from this app right now. */
    fun canScheduleExact(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            alarms.canScheduleExactAlarms()
        } else {
            // Pre-12 there is no permission gate; exact alarms are simply allowed.
            true
        }

    fun schedule(dedupKey: String, opId: String, triggerAtEpochMs: Long) {
        val pending = pendingIntent(dedupKey, opId)
        // Wall-clock, not elapsed-realtime: a routine is anchored to a time of
        // day, so it must follow the clock across a reboot or a timezone move.
        val type = AlarmManager.RTC_WAKEUP
        if (canScheduleExact()) {
            alarms.setExactAndAllowWhileIdle(type, triggerAtEpochMs, pending)
        } else {
            // ⚠ Inexact, but still Doze-aware — the OS may slide it, and the run
            // record's own catch-up window (RE-D6) is what stops a very late
            // wake from acting at 3 a.m.
            alarms.setAndAllowWhileIdle(type, triggerAtEpochMs, pending)
        }
    }

    fun cancel(dedupKey: String) {
        alarms.cancel(pendingIntent(dedupKey, opId = "", mutable = false))
    }

    private fun pendingIntent(
        dedupKey: String,
        opId: String,
        mutable: Boolean = false,
    ): PendingIntent {
        val intent = Intent(context, PunctualAlarmReceiver::class.java).apply {
            action = ACTION_FIRE
            // The key rides in the data URI so two dedup keys never collide into
            // one PendingIntent (extras are NOT part of PendingIntent identity —
            // a classic way to have every alarm overwrite the last).
            data = android.net.Uri.parse("tenshu-punctual://$dedupKey")
            putExtra(PunctualAlarmReceiver.KEY_OP_ID, opId)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (mutable) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context, 0, intent, flags)
    }

    private companion object {
        const val ACTION_FIRE = "app.tenshu.PUNCTUAL_ALARM"
    }
}

/**
 * Receives a punctual alarm and hands the op to the ordinary scheduled-op path.
 *
 * ⚠ A broadcast receiver has ~10 seconds and no guarantee the proxy is up, so it
 * does **not** run the op inline: it enqueues an *expedited* WorkManager one-shot,
 * which is the existing mechanism for "run this now, app possibly closed"
 * (`enqueueExpeditedOneShot`). The alarm supplies the *punctuality*; WorkManager
 * supplies the *execution context*. Using each for what it is good at is the
 * whole trick.
 */
class PunctualAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val opId = intent.getStringExtra(KEY_OP_ID)?.takeIf { it.isNotBlank() } ?: return
        val request = OneTimeWorkRequestBuilder<TenshuOperationWorker>()
            .setInputData(workDataOf(TenshuOperationWorker.KEY_OP_ID to opId))
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .addTag(TenshuOperationWorker.TAG)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork("punctual:$opId", ExistingWorkPolicy.REPLACE, request)
    }

    companion object {
        const val KEY_OP_ID = "tenshu.punctual.opId"
    }
}
