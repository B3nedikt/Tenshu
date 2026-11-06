package app.tenshu.fit.model

/**
 * The Data sources surface's model (`docs/proposals/fit-data-sources-restructure.md` §2, D10).
 *
 * ⚠ **Flattened inputs, string-free outputs, and both live here on purpose.** `:fit-shared-logic`
 * is deliberately client-free — it cannot see `HealthSource` / `HealthImportRun` at all — so the
 * repository in `:fit-shared-data` extracts these plain classes from the entities it reads, the
 * same seam `FitProjection.WorkoutInput` uses. And per the module's standing rule this emits
 * *data, not display strings*: the UI localises, so the parts that can be **quietly wrong** —
 * which state wins, whether a source needs attention, what "last import" means when the newest
 * run failed — are decided once here and desktop-tested, rather than twice in Compose and SwiftUI.
 */

// ── Inputs (extracted from the store by `:fit-shared-data`) ─────────────────────────

/**
 * One `HealthSource` row, flattened. [kind] / [lastStatus] stay **raw strings** — the schema's
 * vocabulary is string constants, and mapping them is [DataSourceProjection]'s job, matching
 * `FitProjection.source(raw)`.
 */
data class HealthSourceInput(
    val kind: String,
    val deviceId: String,
    val deviceLabel: String,
    val autoImport: Boolean,
    val lastRunAt: Long? = null,
    val lastStatus: String? = null,
    val lastItemCount: Long? = null,
)

/** One `HealthImportRun` row, flattened. */
data class ImportRunInput(
    val deviceId: String,
    val kind: String,
    val startedAt: Long,
    val finishedAt: Long,
    val window: String,
    val status: String,
    val itemCount: Long,
    val workoutCount: Long,
    val byteCount: Long? = null,
    val fileName: String? = null,
    val error: String? = null,
)

/**
 * Fit itself as a source (D9) — the workouts the user records, enters by hand, or that are
 * auto-detected. Not a stored registry row: there is no connection to describe.
 *
 * ⚠⚠ **Carries the detection setting rather than a count, and that is a finding, not a
 * shortcut.** A count of *Fit's own* workouts is not cheaply available: the maintained
 * `WorkoutActivityRollup` keys on activity type with no provenance, `count()` loses its O(1)
 * short-circuit behind any filter and has no client-callable terminal, the bounded
 * `workouts()` read is capped at 300 and recency-shaped (so the figure would **shrink as
 * imports arrive**), and paging exposes no total. The disqualifier is smaller and worse:
 * `FitProjection.source(raw)` maps `Manual` as its `else` branch, so a store-side
 * `equalTo "Manual"` cannot reproduce what the UI calls Manual — a counted figure and a
 * rendered one would disagree by construction.
 *
 * So the row describes what this source *is configured to do*, which for Fit is the meaningful
 * fact anyway: there is no import to report on. A real count needs provenance in the rollup —
 * a schema change and a re-derive on every device (DS-6 §16).
 */
data class TenshuHealthInput(
    val autoDetect: Boolean = false,
)

// ── Outputs ────────────────────────────────────────────────────────────────────────

/** The three things that can feed Fit. ⚠ Takeout is **not** one — see [BatchImportSummary]. */
enum class DataSourceKind { TenshuHealth, HealthConnect, AppleHealth }

/**
 * A source's one-line state, string-free — the UI maps each case to copy.
 *
 * Mirrors [ImportStatus]'s vocabulary but is **not** the same type, and the difference is the
 * whole point of §4.1: `ImportStatus` describes *this process's* last import and resets to
 * `Idle` on launch, while this describes the source. [Running] is the one case that can only
 * come from the live channel, and [NeverRun] the one that can only come from the durable one.
 */
sealed interface DataSourceState {

    /** Registered, but nothing has ever imported. Not the same as "imported nothing". */
    data object NeverRun : DataSourceState

    /** An import is in flight *on this device*. [imported] is a count-so-far, not a total. */
    data class Running(val imported: Int) : DataSourceState

    data class Imported(val itemCount: Long, val at: Long) : DataSourceState

    /** Reachable and authorised, and it holds nothing. A legitimate answer, not an error. */
    data class NoData(val at: Long) : DataSourceState

    data class Failed(val at: Long) : DataSourceState

    /** The store is there but has not been granted the reads it needs. */
    data object PermissionNeeded : DataSourceState

    /** No such store on this device. */
    data object Unavailable : DataSourceState

    /** ⚠ What is shown is a built-in fixture, not the user's data. Always say so. */
    data object Sample : DataSourceState

    /**
     * Fit's own recordings (D9) — [DataSourceKind.TenshuHealth] only.
     *
     * ⚠ Describes the **setting**, not a quantity: see [TenshuHealthInput] for why a count of
     * this source's workouts cannot be produced honestly.
     */
    data class Recording(val autoDetect: Boolean) : DataSourceState

    /** Whether this state is worth the row's attention emphasis. */
    val needsAttention: Boolean
        get() = this is PermissionNeeded || this is Failed || this is Unavailable || this is Sample
}

/**
 * One row of the *Data sources* section.
 *
 * [deviceLabel] is non-null only when the row describes **another** device — naming this device
 * back to the user is noise, and the absence is what makes the multi-device case read as the
 * exception it is.
 */
data class DataSourceRow(
    val kind: DataSourceKind,
    val deviceId: String?,
    val deviceLabel: String?,
    val isThisDevice: Boolean,
    val state: DataSourceState,
    val autoImport: Boolean,
) {
    val needsAttention: Boolean get() = state.needsAttention

    /**
     * ⚠ Whether *this* device can trigger an import for this row. False for another device's
     * source: we cannot make an iPhone read HealthKit from here (D4), and offering a button that
     * silently does nothing is worse than not offering it.
     */
    val canImportNow: Boolean get() = isThisDevice && kind != DataSourceKind.TenshuHealth
}

enum class ImportRunStatus { Succeeded, NoData, PermissionsMissing, Failed }

enum class ImportRunWindow { Recent, FullHistory, File }

/** One row of a source's — or the batch importer's — history list. */
data class ImportRunEntry(
    val startedAt: Long,
    val finishedAt: Long,
    val window: ImportRunWindow,
    val status: ImportRunStatus,
    val itemCount: Long,
    val workoutCount: Long,
    /** ⚠ File imports only (D6): a platform store hands us objects, not bytes. */
    val byteCount: Long? = null,
    val fileName: String? = null,
    val isThisDevice: Boolean = true,
    val deviceLabel: String? = null,
)

/**
 * The *Batch import* section's one row. Derived entirely from `TAKEOUT` runs — a file import is
 * an act, not a standing connection, so it has no source row to read (D11).
 *
 * `null` where the platform can pick no file: the section is absent rather than empty, because
 * an empty batch-import section on a phone that cannot import files is a dead affordance.
 */
data class BatchImportSummary(
    val lastRunAt: Long?,
    val lastStatus: ImportRunStatus?,
    val runCount: Int,
)
