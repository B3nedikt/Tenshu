package app.tenshu.notify

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * Reading and requesting Android's exact-alarm permission (`calendar-app.md`
 * §4.4, **Q6**).
 *
 * ⚠⚠ **Nothing in the repo asked for this before CA-5c.**
 * `PunctualAlarmScheduler` only ever *read* `canScheduleExactAlarms()` and
 * silently degraded to an inexact alarm when it was false — correct as a
 * fallback, and invisible as a user experience: a reminder set for 09:00 arrives
 * whenever the OS feels like it, with nothing anywhere explaining why.
 *
 * ## ⚠⚠ Why the permission is asked for by *Control*
 *
 * Gap 4: `USE_EXACT_ALARM` grants exactness without asking, but Play policy
 * restricts it to apps whose **core function** is alarms or calendars.
 * **Calendar is exactly that app** — and the alarm is scheduled by **Control**,
 * which owns the proxy and therefore `AlarmManager`, and which is a
 * sync/permissions app with no such claim. Declaring `USE_EXACT_ALARM` in
 * Control would make the claim from the one app that cannot justify it, so Q6
 * settled on the version that needs no policy argument:
 * `SCHEDULE_EXACT_ALARM`, **asked contextually**, at the moment the user sets
 * their first reminder.
 *
 * ⚠ That timing is the whole point. The permission lives in a Settings screen
 * rather than a dialog, so a request is a *trip out of the app* — and a user
 * sent there cold, at launch, has no idea what they are being asked for.
 */
object ExactAlarmPermission {

    /** Whether the OS will honour an *exact* alarm from this app right now. */
    fun isGranted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val alarms = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
        return alarms?.canScheduleExactAlarms() ?: false
    }

    /**
     * Whether asking is even possible — false below Android 12, where the
     * permission does not exist and exact alarms are simply allowed.
     *
     * ⚠ Distinct from [isGranted] returning `true`, and a caller must not
     * conflate them: "already granted" and "cannot be asked" look the same at
     * the API and mean opposite things in a UI. Showing "Allow exact alarms" to
     * someone on Android 11 is offering a setting that does not exist.
     */
    fun isRequestable(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    /**
     * The intent that opens the OS screen where the user can grant it.
     *
     * ⚠ Returns `null` below Android 12 rather than a no-op intent, so a caller
     * cannot accidentally launch nothing and leave the user staring at an
     * unchanged screen.
     *
     * ⚠⚠ Requires `FLAG_ACTIVITY_NEW_TASK` when launched from a non-activity
     * context, which is the usual case here — the decision to ask is made deep
     * in a settings screen or a service, not in an `Activity` that happens to be
     * foreground.
     */
    fun requestIntent(context: Context): Intent? {
        if (!isRequestable()) return null
        return Intent(
            Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
            // ⚠ The package uri targets *this* app's entry. Without it the
            // intent opens the global list and the user has to find us in it.
            Uri.fromParts("package", context.packageName, null),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
