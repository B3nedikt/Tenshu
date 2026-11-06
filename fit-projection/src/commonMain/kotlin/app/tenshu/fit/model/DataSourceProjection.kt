package app.tenshu.fit.model

/**
 * Turns the Data sources registry into rows (`fit-data-sources-restructure.md` D10).
 *
 * Pure functions over the flattened inputs — no client, no coroutines, no clock — so every rule
 * that can be *quietly wrong* is decided here once and desktop-tested, and Compose (DS-4) and
 * SwiftUI (DS-5) each only render. That split is why DS-5 is expected to be small.
 *
 * ⚠ The raw-string vocabularies (`HEALTH_CONNECT`, `SUCCEEDED`, `FILE`, …) are the schema's, and
 * mapping them lives here rather than in either UI — the same shape as `FitProjection.source(raw)`.
 * An unrecognised value maps to null and the row is dropped rather than guessed at: a source we
 * cannot name is a source we cannot describe.
 */
object DataSourceProjection {

    // The schema's string vocabulary, **restated** because `:fit-shared-logic` cannot see
    // `:health-service` (and should not — the client-free shape is worth more than the
    // duplication).
    //
    // ⚠⚠ Public solely so `DataSourceVocabularyTest` in `:fit-shared-data` can pin each one
    // against the real `HealthSource` / `HealthImportRun` constant. Without that join a rename
    // upstream keeps compiling here, every `when` falls to its else branch, and the Data sources
    // section silently **goes empty** — a failure with no failing test anywhere.
    const val KIND_HEALTH_CONNECT = "HEALTH_CONNECT"
    const val KIND_APPLE_HEALTH = "APPLE_HEALTH"
    const val KIND_TAKEOUT = "TAKEOUT"

    const val STATUS_SUCCEEDED = "SUCCEEDED"
    const val STATUS_FAILED = "FAILED"
    const val STATUS_PERMISSIONS_MISSING = "PERMISSIONS_MISSING"
    const val STATUS_NO_DATA = "NO_DATA"

    const val WINDOW_RECENT = "RECENT"
    const val WINDOW_FULL_HISTORY = "FULL_HISTORY"
    const val WINDOW_FILE = "FILE"

    /**
     * The *Data sources* section, in render order.
     *
     * [liveStatus] is this process's in-flight import and **wins only while it is live** (§4.1) —
     * that is `Running`, plus the states no stored run can express (`PermissionsMissing`,
     * `SourceUnavailable`, `Sample`). Every settled outcome comes from the durable row instead,
     * which is what stops "165 items imported" from vanishing on the next launch.
     *
     * ⚠⚠ The live status describes **this device only**. Applying it to another device's row
     * would render one phone's import progress as though it were the iPhone's — a fabricated
     * claim about hardware the user is not holding.
     *
     * [tenshu] absent omits the Tenshu Health row entirely — a surface that cannot read the
     * detection setting must not claim one (D9).
     */
    fun dataSourceRows(
        sources: List<HealthSourceInput>,
        thisDeviceId: String?,
        liveStatus: ImportStatus = ImportStatus.Idle,
        tenshu: TenshuHealthInput? = null,
    ): List<DataSourceRow> {
        val rows = mutableListOf<DataSourceRow>()

        if (tenshu != null) {
            rows += DataSourceRow(
                kind = DataSourceKind.TenshuHealth,
                deviceId = null,
                deviceLabel = null,
                isThisDevice = true,
                state = DataSourceState.Recording(tenshu.autoDetect),
                autoImport = tenshu.autoDetect,
            )
        }

        rows += sources
            .mapNotNull { source ->
                val kind = kindOf(source.kind) ?: return@mapNotNull null
                val isThisDevice = thisDeviceId != null && source.deviceId == thisDeviceId
                DataSourceRow(
                    kind = kind,
                    deviceId = source.deviceId,
                    deviceLabel = source.deviceLabel.takeIf { !isThisDevice && it.isNotBlank() },
                    isThisDevice = isThisDevice,
                    state = stateOf(source, liveStatus.takeIf { isThisDevice }),
                    autoImport = source.autoImport,
                )
            }
            // Stable order: this device first (it is the one the user can act on), then by kind
            // so the list does not reshuffle as devices sync in and out.
            .sortedWith(compareByDescending<DataSourceRow> { it.isThisDevice }.thenBy { it.kind.ordinal })

        return rows
    }

    /**
     * The state one source row shows.
     *
     * ⚠ Order matters and is the §4.1 rule: a live import outranks a stored one *while it is
     * running*, because a user watching a progress count would otherwise see a stale figure from
     * the previous import sitting there unchanged.
     */
    internal fun stateOf(
        source: HealthSourceInput,
        liveStatus: ImportStatus?,
    ): DataSourceState {
        liveOverride(liveStatus)?.let { return it }

        val at = source.lastRunAt ?: return DataSourceState.NeverRun
        return when (source.lastStatus) {
            STATUS_SUCCEEDED -> DataSourceState.Imported(source.lastItemCount ?: 0L, at)
            STATUS_NO_DATA -> DataSourceState.NoData(at)
            STATUS_FAILED -> DataSourceState.Failed(at)
            STATUS_PERMISSIONS_MISSING -> DataSourceState.PermissionNeeded
            // A run recorded under a status this build does not know: it happened, and the count
            // is still true, so say that rather than claiming the source never ran.
            else -> DataSourceState.Imported(source.lastItemCount ?: 0L, at)
        }
    }

    /**
     * The cases where the *live* channel is the better answer.
     *
     * ⚠⚠ Deliberately not "any non-Idle status". `Imported` / `NoData` also arrive here, and
     * taking them would re-introduce exactly the bug this whole track exists to fix: they are
     * process-scoped, so the row would go blank on the next launch. They are recorded durably,
     * so the stored row already carries them — and carries them for every device, not just this
     * one.
     */
    private fun liveOverride(status: ImportStatus?): DataSourceState? = when (status) {
        is ImportStatus.Running -> DataSourceState.Running(status.imported)
        is ImportStatus.PermissionsMissing -> DataSourceState.PermissionNeeded
        ImportStatus.SourceUnavailable -> DataSourceState.Unavailable
        is ImportStatus.Sample -> DataSourceState.Sample
        else -> null
    }

    /**
     * The *Batch import* row. Null when the shell can pick no file ([canImportFiles]) — the
     * section is then absent rather than empty, since a batch-import row on a device that cannot
     * import files is a dead affordance.
     *
     * ⚠ "Last import" is the newest run by `startedAt`, **whatever its status**. A failed import
     * is still the last thing that happened, and showing the previous success instead would tell
     * the user their import worked.
     */
    fun batchImportSummary(
        runs: List<ImportRunInput>,
        canImportFiles: Boolean,
    ): BatchImportSummary? {
        if (!canImportFiles) return null
        val takeout = runs.filter { it.kind == KIND_TAKEOUT }
        val newest = takeout.maxByOrNull { it.startedAt }
        return BatchImportSummary(
            lastRunAt = newest?.startedAt,
            lastStatus = newest?.let { statusOf(it.status) },
            runCount = takeout.size,
        )
    }

    /**
     * A history list, newest first — the detail screens' body.
     *
     * [kind] null selects the batch-import history ([KIND_TAKEOUT]); otherwise the runs of one
     * platform source **across every device**, which is what makes an iPhone's Apple Health
     * history readable on an Android phone at all.
     */
    fun importHistory(
        runs: List<ImportRunInput>,
        kind: DataSourceKind?,
        thisDeviceId: String?,
        deviceLabels: Map<String, String> = emptyMap(),
    ): List<ImportRunEntry> {
        val wanted = when (kind) {
            null -> KIND_TAKEOUT
            DataSourceKind.HealthConnect -> KIND_HEALTH_CONNECT
            DataSourceKind.AppleHealth -> KIND_APPLE_HEALTH
            // Fit's own recordings are not imports and have no runs — an empty history here is
            // the truth, not a gap.
            DataSourceKind.TenshuHealth -> return emptyList()
        }
        return runs
            .filter { it.kind == wanted }
            .sortedByDescending { it.startedAt }
            .map { run ->
                val isThisDevice = thisDeviceId != null && run.deviceId == thisDeviceId
                ImportRunEntry(
                    startedAt = run.startedAt,
                    finishedAt = run.finishedAt,
                    window = windowOf(run.window),
                    status = statusOf(run.status),
                    itemCount = run.itemCount,
                    workoutCount = run.workoutCount,
                    byteCount = run.byteCount,
                    fileName = run.fileName,
                    isThisDevice = isThisDevice,
                    deviceLabel = deviceLabels[run.deviceId].takeIf { !isThisDevice },
                )
            }
    }

    internal fun kindOf(raw: String): DataSourceKind? = when (raw) {
        KIND_HEALTH_CONNECT -> DataSourceKind.HealthConnect
        KIND_APPLE_HEALTH -> DataSourceKind.AppleHealth
        else -> null
    }

    internal fun statusOf(raw: String): ImportRunStatus = when (raw) {
        STATUS_SUCCEEDED -> ImportRunStatus.Succeeded
        STATUS_NO_DATA -> ImportRunStatus.NoData
        STATUS_PERMISSIONS_MISSING -> ImportRunStatus.PermissionsMissing
        STATUS_FAILED -> ImportRunStatus.Failed
        // ⚠ Unknown maps to Failed, not Succeeded: a run this build cannot interpret must not be
        // rendered as a success the user then relies on.
        else -> ImportRunStatus.Failed
    }

    internal fun windowOf(raw: String): ImportRunWindow = when (raw) {
        WINDOW_FULL_HISTORY -> ImportRunWindow.FullHistory
        WINDOW_FILE -> ImportRunWindow.File
        else -> ImportRunWindow.Recent
    }
}
