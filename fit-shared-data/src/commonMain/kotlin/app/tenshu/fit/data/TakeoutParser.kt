package app.tenshu.fit.data

/**
 * Turns a **Google Takeout** export into the neutral [ImportedHealth] the shared [HealthImportWriter]
 * persists — the only route by which a user's real Google Fit *history* can reach this app.
 *
 * It has to exist because Health Connect is not that route: Google Fit holds none of Health Connect's
 * write permissions and never feeds it, so a phone's Health Connect store contains whatever the
 * platform pedometer wrote and nothing else (see `docs/proposals/fit-app.md` §9.5b). Years of Fit
 * history live only in the export.
 *
 * **Content-routed, not path-routed.** Takeout's folder names are localised and have been renamed
 * across versions (`Fit/Activities`, `Fit/Daily activity metrics`, …), so matching on paths would
 * quietly import nothing the day Google renames a directory — the same silent-empty failure this
 * whole track has been about. Entries are routed by extension and, for CSVs, by their header.
 *
 * Handles the two things Takeout actually carries that we can use:
 *  - **Activity files** (`.tcx` / `.gpx` / `.fit`) → one workout each, via [ActivityFileParser],
 *    including the dense route + heart-rate streams.
 *  - **The daily-metrics CSV** → a row per day (steps / distance / calories / move minutes) via
 *    [DailyMetricsCsv] — the long tail of the history, and the part Health Connect can never give back.
 *
 * Everything else in the archive (the raw per-datatype JSON dumps, session metadata) is ignored: it
 * restates the above in a bulkier form. Nothing here throws — a corrupt member yields no rows.
 */
class TakeoutParser(
    private val activities: ActivityFileParser,
) {

    /**
     * Whether an archive member is worth extracting at all. Called *before* the bytes are read, so a
     * multi-gigabyte Takeout (photos, mail, and everything else the user ticked) never materialises
     * in memory — only the handful of members this parser can actually use.
     */
    fun isRelevant(name: String): Boolean {
        if (name.endsWith('/')) return false
        // Takeout ships a __MACOSX/ shadow tree of AppleDouble stubs that mirror every real name.
        if (name.startsWith("__MACOSX/") || name.substringAfterLast('/').startsWith("._")) return false
        return when (name.substringAfterLast('.', "").lowercase()) {
            "tcx", "gpx", "fit" -> true
            // Takeout writes one date-named CSV *per day* (`2016-04-26.csv`) of intra-day segments,
            // alongside the single aggregate `Daily activity metrics.csv`. The per-day files restate
            // the same totals and are skipped in the parse anyway — but a real export has thousands of
            // them, and extracting them all wastes memory *and* burns through the member cap, which on
            // one real 10-year export pushed the aggregate CSV (sorted last) past the ceiling and
            // silently dropped the entire daily history. Rejected here by name: a bare `YYYY-MM-DD.csv`
            // is always a segment (dates aren't localised), while the aggregate never is.
            "csv" -> !isPerDaySegmentName(name)
            else -> false
        }
    }

    /** `…/2016-04-26.csv` → true; `…/Daily activity metrics.csv` → false. */
    private fun isPerDaySegmentName(name: String): Boolean {
        val base = name.substringAfterLast('/').removeSuffix(".csv").removeSuffix(".CSV")
        return base.length == 10 && base[4] == '-' && base[7] == '-' &&
            base.all { it.isDigit() || it == '-' }
    }

    /** Parse the extracted members. Order-independent; unusable members contribute nothing. */
    fun parse(files: List<ActivityFile>): ImportedHealth =
        files.fold(ImportedHealth()) { acc, file -> acc + parse(file) }

    /**
     * Parse **one** member. This is the unit the streaming import works in: the shell reads a file,
     * hands it here, the caller writes and discards it, and the file's bytes are collectable before
     * the next one is read — so a 2,700-file export never sits in memory whole. Files are independent
     * (a workout file is one activity; the aggregate CSV is self-contained), so per-file parsing is
     * exactly equivalent to parsing the list.
     */
    fun parse(file: ActivityFile): ImportedHealth = runCatching {
        if (file.name.endsWith(".csv", ignoreCase = true)) {
            if (DailyMetricsCsv.matches(file.bytes)) {
                DailyMetricsCsv.parse(file.bytes)
            } else {
                // A CSV we cannot read is the likeliest way this import silently under-delivers —
                // Google renames these columns between export versions — so it stays logged even
                // though the per-file success chatter is gone (streaming makes that a flood).
                fitLog("takeout: ${file.name} — no Date column, skipped. Columns: ${DailyMetricsCsv.columnNames(file.bytes)}")
                ImportedHealth()
            }
        } else {
            activities.parse(file)
        }
    }.onFailure { fitLog("takeout: ${file.name} FAILED to parse — skipped", it) }
        .getOrDefault(ImportedHealth())
}
