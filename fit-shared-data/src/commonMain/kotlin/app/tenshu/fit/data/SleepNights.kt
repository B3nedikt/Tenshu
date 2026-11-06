package app.tenshu.fit.data

/**
 * Groups a health store's loose sleep samples (Apple Health hands back one sample per stage, plus an
 * "in bed" span) into **nights**: one per wake day, the shape `SleepSession` is keyed by.
 *
 * ⚠⚠ A night is the run of samples with no gap longer than [gapMs] between them, attributed to the
 * day it **ends**. Grouping samples by their own end day instead (as the iOS importer did) splits
 * every night that starts before midnight: its first hours end the evening before, land in the
 * previous day's group, and that "night" then spans evening to evening — ~24 h in bed.
 *
 * ⚠ One night per day, because `SleepSession` is keyed by date: when a day ends two runs (a nap
 * and the night), the longer one is kept.
 */
object SleepNights {
    const val DEFAULT_GAP_MS: Long = 2 * 60 * 60 * 1000L

    data class Sample<T>(val startMs: Long, val endMs: Long, val value: T)

    data class Night<T>(val date: String, val startMs: Long, val endMs: Long, val samples: List<Sample<T>>)

    fun <T> group(samples: List<Sample<T>>, dayOf: (Long) -> String, gapMs: Long = DEFAULT_GAP_MS): List<Night<T>> {
        val runs = mutableListOf<MutableList<Sample<T>>>()
        var runEnd = Long.MIN_VALUE
        for (s in samples.sortedBy { it.startMs }) {
            if (runs.isEmpty() || s.startMs - runEnd > gapMs) {
                runs += mutableListOf(s)
                runEnd = s.endMs
            } else {
                runs.last() += s
                runEnd = maxOf(runEnd, s.endMs)
            }
        }
        return runs
            .map { run ->
                val end = run.maxOf { it.endMs }
                Night(date = dayOf(end), startMs = run.minOf { it.startMs }, endMs = end, samples = run)
            }
            .groupBy { it.date }
            .map { (_, nights) -> nights.maxBy { it.endMs - it.startMs } }
            .sortedBy { it.date }
    }
}
