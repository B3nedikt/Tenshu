package app.tenshu.fit.data

import kotlin.math.roundToLong

/**
 * Reads Google Fit's **"Daily activity metrics" CSV** out of a Takeout export — the one file in the
 * archive that carries the long tail of a user's history (a row per day: steps, distance, calories,
 * move minutes), which Health Connect will never hold because Google Fit does not feed it.
 *
 * **Column-driven, not positional.** Google has added, removed and reordered these columns across
 * export versions, and a positional reader silently maps the wrong number into the wrong metric —
 * the worst possible failure for health data, because it produces plausible garbage instead of an
 * error. Everything here keys off the header text and a missing column simply yields zero.
 *
 * Only the *aggregate* CSV is read (the one with a `Date` column). Takeout also writes per-day files
 * of intra-day segments; those restate the same totals, so parsing both would double-count.
 */
object DailyMetricsCsv {

    /** Whether [bytes] looks like the aggregate daily-metrics CSV (i.e. it has a `Date` column). */
    fun matches(bytes: ByteArray): Boolean = header(bytes)?.let { DATE in it } == true

    /**
     * The days (and the whole weight series the export carries) in this CSV. A row that has no
     * usable date, or no metric worth keeping, is skipped rather than written as an empty day.
     */
    fun parse(bytes: ByteArray): ImportedHealth {
        val lines = bytes.decodeToString().lineSequence().filter { it.isNotBlank() }.toList()
        if (lines.isEmpty()) return ImportedHealth()

        val columns = splitCsvLine(lines.first()).map { normalise(it) }
        val index = columns.withIndex().associate { (i, name) -> name to i }
        val dateAt = index[DATE] ?: return ImportedHealth()

        val days = mutableListOf<ImportedDailyActivity>()
        val weights = mutableListOf<Pair<String, Double>>()

        for (line in lines.drop(1)) {
            val cells = splitCsvLine(line)
            val date = cells.getOrNull(dateAt)?.trim().orEmpty()
            if (!isIsoDate(date)) continue

            val steps = cells.number(index[STEPS]).roundToLong()
            val distanceM = cells.number(index[DISTANCE]).roundToLong()
            val kcal = cells.number(index[CALORIES])
            val moveMinutes = cells.number(index[MOVE_MINUTES]).roundToLong()

            // A day Google exported with nothing in it is not a day the user did nothing — it is a day
            // the phone was not carried. Writing it as an all-zero row would fabricate a real zero.
            if (steps == 0L && distanceM == 0L && kcal == 0.0 && moveMinutes == 0L) continue

            days += ImportedDailyActivity(
                date = date,
                steps = steps,
                distanceM = distanceM,
                activeEnergyKj = (kcal * KCAL_TO_KJ).roundToLong(),
                activeMinutes = moveMinutes,
            )
            cells.number(index[WEIGHT]).takeIf { it > 0 }?.let { weights += date to it }
        }

        // Weight is a point-in-time metric, not a daily total — emit the whole series (§12 B2), one
        // reading per day it was recorded, so the Body detail can chart the trend. Distinct dates →
        // distinct sampleIds, so the time-series store retains each (a re-import folds by date+metric).
        val weightSamples = weights.map { (date, kg) ->
            ImportedSample(
                metric = "Weight",
                value = if (kg % 1.0 == 0.0) kg.toLong().toString() else ((kg * 10).roundToLong() / 10.0).toString(),
                unit = "kg",
                timeMs = isoDateToEpochMillis(date),
                source = SOURCE,
            )
        }

        return ImportedHealth(dailyActivities = days, samples = weightSamples)
    }

    /** The header line's raw (un-normalised) column names — for diagnostics when a column is missed. */
    fun columnNames(bytes: ByteArray): List<String> =
        bytes.decodeToString().lineSequence().firstOrNull { it.isNotBlank() }
            ?.let { splitCsvLine(it) }.orEmpty()

    private fun header(bytes: ByteArray): Set<String>? =
        bytes.decodeToString().lineSequence().firstOrNull { it.isNotBlank() }
            ?.let { line -> splitCsvLine(line).map { normalise(it) }.toSet() }

    private fun List<String>.number(at: Int?): Double =
        at?.let { getOrNull(it) }?.trim()?.takeIf { it.isNotEmpty() }?.toDoubleOrNull() ?: 0.0

    /**
     * Header text → a stable key. Google writes `Step count`, `Distance (m)`, `Calories (kcal)`; the
     * units in parentheses and the casing both drift between exports, so both are stripped.
     */
    private fun normalise(column: String): String =
        column.substringBefore('(').trim().lowercase().removePrefix("﻿")

    /** Splits one CSV line, honouring `"…"` quoting (a quoted field may contain commas). */
    internal fun splitCsvLine(line: String): List<String> {
        val out = mutableListOf<String>()
        val cell = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                quoted && c == '"' && line.getOrNull(i + 1) == '"' -> { cell.append('"'); i++ }   // "" → literal "
                c == '"' -> quoted = !quoted
                c == ',' && !quoted -> { out += cell.toString(); cell.clear() }
                else -> cell.append(c)
            }
            i++
        }
        out += cell.toString()
        return out
    }

    private fun isIsoDate(s: String): Boolean =
        s.length == 10 && s[4] == '-' && s[7] == '-' && s.all { it.isDigit() || it == '-' }

    /** `YYYY-MM-DD` → epoch millis at UTC midnight. The stored date string is what the app keys on. */
    private fun isoDateToEpochMillis(date: String): Long =
        runCatching { kotlin.time.Instant.parse("${date}T00:00:00Z").toEpochMilliseconds() }.getOrDefault(0L)

    private const val SOURCE = "File"
    private const val KCAL_TO_KJ = 4.184

    private const val DATE = "date"
    private const val STEPS = "step count"
    private const val DISTANCE = "distance"
    private const val CALORIES = "calories"
    private const val MOVE_MINUTES = "move minutes count"
    private const val WEIGHT = "average weight"
}
