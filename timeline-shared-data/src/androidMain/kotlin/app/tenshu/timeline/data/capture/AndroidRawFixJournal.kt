package app.tenshu.timeline.data.capture

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * The Android [RawFixLog] — one append-only text file under app-private `filesDir/raw_fix_journal/`
 * (`timeline-movement-capture.md` §18.9).
 *
 * ⚠ A **plain appending file**, not an `OutboxStore`. The outbox is entry-per-file, and one file per
 * fix would be tens of thousands of inodes a day; the journal is a stream, and a stream wants a
 * stream. It is also why the codec is line-oriented CSV rather than the delta-encoded polyline —
 * see [RawFixCodec].
 *
 * ⚠ **No `write-temp-then-rename`**, unlike [JsonOpenMoveJournal]. That protects a record that is
 * *replaced* in place, where a torn write destroys the previous good copy. Here every write is an
 * append of one line, so the worst a crash can cost is a truncated final line — which
 * [RawFixCodec.decode] drops on its own. Adding rename-per-fix would be pure cost.
 *
 * Threat-model **A35**: app-private storage, platform-encrypted at rest, never synced, never
 * uploaded, deletable from Settings. See [RawFixJournal] for why the posture is stricter than
 * capture's own.
 */
internal class AndroidRawFixLog(context: Context) : RawFixLog {

    private val dir = File(context.applicationContext.filesDir, "raw_fix_journal")
    private val file get() = File(dir, "fixes.csv")

    /**
     * Cached line count. ⚠ Recomputed from the file on first use and then maintained in memory:
     * `record` consults it per fix, and re-reading a 250 000-line file on every location update
     * would turn a diagnostic into a performance problem in the capture loop.
     */
    private var cachedCount: Int = -1

    override fun append(line: String) {
        dir.mkdirs()
        file.appendText("$line\n")
        if (cachedCount >= 0) cachedCount++ else cachedCount = countFromDisk()
    }

    override fun lines(): List<String> =
        if (!file.exists()) emptyList() else file.readLines().filter { it.isNotBlank() }

    override fun clear() {
        file.delete()
        cachedCount = 0
    }

    override fun lineCount(): Int {
        if (cachedCount < 0) cachedCount = countFromDisk()
        return cachedCount
    }

    private fun countFromDisk(): Int =
        if (!file.exists()) 0 else file.useLines { seq -> seq.count { it.isNotBlank() } }
}

/**
 * The Android [RawFixJournalControl] — the raw-fix opt-in, persisted to app-private
 * `SharedPreferences` (the `AndroidWifiFingerprintControl` shape).
 *
 * ⚠⚠ Bound **only in debug builds** (`TimelineAndroidModule`). That is the mechanism that keeps a
 * verbatim location log out of release entirely: with no binding, `koin.getOrNull` returns null, the
 * Settings section does not render, and the journal falls back to [RawFixJournal.None].
 */
class AndroidRawFixJournalControl(context: Context) : RawFixJournalControl {

    private val prefs = context.applicationContext
        .getSharedPreferences("tenshu_timeline_raw_fix_journal", Context.MODE_PRIVATE)

    private val state = MutableStateFlow(prefs.getBoolean(KEY, false))

    override val enabled: StateFlow<Boolean> = state.asStateFlow()

    override fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY, enabled).apply()
        state.value = enabled
    }

    private companion object {
        const val KEY = "enabled"
    }
}

/** The bound journal: consent-gated + capped, over the app-private append log. */
fun androidRawFixJournal(context: Context, control: RawFixJournalControl): RawFixJournal =
    LoggingRawFixJournal(AndroidRawFixLog(context), control)
