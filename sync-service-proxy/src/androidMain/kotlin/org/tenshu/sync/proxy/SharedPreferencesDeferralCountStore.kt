package org.tenshu.sync.proxy

import android.content.Context

/**
 * [DeferralCountStore] over a private `SharedPreferences` file, keyed by WorkManager work id.
 *
 * ⚠ Device-local bookkeeping, not Tenshu data: it is not in the object store, never syncs, and
 * [ScheduledRetryAccounting] bounds what a wrong value can do. Every call is best-effort for the
 * same reason — a preferences failure must not turn a scheduled run into a crash.
 *
 * `commit()`, not `apply()`: the worker returns right after, and a process the OS woke only for
 * this job may be gone before an asynchronous write lands. Already off the main thread.
 */
internal class SharedPreferencesDeferralCountStore(context: Context) : DeferralCountStore {

    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    override fun deferrals(workId: String): Int =
        runCatching { prefs.getInt(workId, 0) }.getOrDefault(0)

    override fun setDeferrals(workId: String, count: Int) {
        runCatching {
            val editor = prefs.edit()
            // A chain that is cancelled or replaced while deferred never clears its entry. Bound the
            // file rather than track them: dropping every count only costs the pre-P4 behaviour.
            if (!prefs.contains(workId) && prefs.all.size >= MAX_ENTRIES) editor.clear()
            editor.putInt(workId, count).commit()
        }
    }

    override fun clear(workId: String) {
        runCatching { if (prefs.contains(workId)) prefs.edit().remove(workId).commit() }
    }

    private companion object {
        const val FILE = "tenshu_scheduled_work_deferrals"
        const val MAX_ENTRIES = 64
    }
}
