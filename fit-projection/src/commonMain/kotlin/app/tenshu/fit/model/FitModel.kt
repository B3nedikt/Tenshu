package app.tenshu.fit.model

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable
import org.tenshu.preference.BiologicalSex

/**
 * Fit domain model — Ph1 of docs/proposals/fit-app.md (§5), ported from the design
 * handoff's `fit-agent.md` §4 / `mockup/fit-data.js`.
 *
 * This is *structure*, not a wire format — the real store is `:health-service`
 * (`org.tenshu.core.health.Workout` is already on master) behind `FitRepository`.
 * Field names align with the service schema (`workoutId`, `activityType`, `source`,
 * `durationSec`, `distanceM`, `activeEnergyKj`, `avgHr`) so the projector maps 1:1.
 * Fields the service defers ride here as UI-model conveniences:
 *
 *  - `maxHr` / `ascentM` graduate to `maybe` fields with the import seam.
 *  - `laps` is the embedded list-of-composite pending a lap-identity decision.
 *  - `route` / `hrSeries` are dense streams that belong on the **content plane**
 *    (ADR 0017) as referenced blobs — the mock inlines them for convenience only.
 *
 * Per the Timeline discipline, this module emits **data, not display strings**: units
 * are SI (`distanceM`, `durationSec`, kJ), teasers/update stamps are sealed types the
 * UI localises, and `FitFormat` (`:fit-shared-logic`) carries only locale-independent decisions.
 */

enum class ActivityType { Running, Walking, Cycling, Strength, Other }

/** Provenance — always rendered (fit-agent.md §0.6); part of the trust story. */
enum class Source { Recorded, HealthConnect, AppleHealth, File, Manual, Detected }

/**
 * A movement Timeline captured on its own — a walk, run or ride the user never pressed start on
 * (`fit-automatic-activity-detection.md` FA-1).
 *
 * ⚠ This is **not** a workout and is deliberately not one. It is Timeline's `MovementTrace`
 * summary, read across the service boundary, and it becomes a `Workout` only when FA-2's
 * promotion policy says so — deterministically, from these fields alone (D2).
 *
 * ⚠ No route. The trace's `polyline` blob is not projected: decoding a path per candidate is
 * exactly what the feed-vs-detail split avoids, and a promoted workout does not copy the
 * geometry anyway — `workoutId` embeds [traceId], so the route is recoverable on demand (D2 §4.1).
 */
data class DetectedMovement(
    val traceId: String,
    /** Timeline's own vocabulary — `walk`/`run`/`cycle`/`drive`/`unknown`, not [ActivityType]. */
    val mode: String,
    /** 0–100. FA-2 gates on this; a low-confidence trace is not workout material. */
    val modeConfidence: Int,
    /**
     * Metres, or `null` when the source cannot know — a step-derived bout has no distance and
     * ⚠ must not be given an estimated one (a stride guess is a number no sensor produced).
     */
    val distanceM: Int?,
    val startAtEpochMs: Long,
    val endAtEpochMs: Long,
    /**
     * Which classifier produced [mode]. Recorded so a re-classification can revise a promotion —
     * ⚠ and deliberately *not* part of any derived workout id, or re-classifying would mint a
     * second workout instead of correcting the first.
     */
    val classifierVersion: Int,
    /**
     * Pedometer steps over the movement (FA-4), or `null` when nobody counted. ⚠ Never `0` for
     * unknown — Timeline's `-1` sentinel is mapped to `null` at the read.
     */
    val steps: Int? = null,
) {
    val durationSec: Int get() = ((endAtEpochMs - startAtEpochMs) / 1000L).toInt()
}

/** One point of an abstract route polyline, normalized to 0..1 — NOT map coordinates. */
data class RoutePoint(
    val x: Float,
    val y: Float,
)

/**
 * One real-world route point in WGS84 degrees — the map-side counterpart to the 0..1 [RoutePoint].
 * Present only for workouts with real GPS ([Workout.geoRoute]); feeds the offline basemap
 * (`:ui-map` `RouteBasemap`). Kept distinct from [RoutePoint] so the schematic (map-less) fallback
 * and the real map never round through each other's form.
 */
data class GeoCoord(
    val latitude: Double,
    val longitude: Double,
)

/** One heart-rate sample: [t] seconds from workout start, [bpm]. */
data class HrPoint(
    val t: Int,
    val bpm: Int,
)

/**
 * One captured GPS fix — **real** coordinates (decimal degrees), the platform-neutral form the
 * first-party recorder produces. [tSec] is whole seconds from the recording start; [altM] is
 * metres above the ellipsoid, or null when the fix carried no altitude. This is the *storage*
 * truth (the real repository encodes it to the SI `WorkoutStreams` blob); the abstract 0..1
 * [RoutePoint] the UI draws is derived from it. Distinct from [RoutePoint] precisely so the
 * capture layer never rounds through a presentation form.
 */
data class GpsFix(
    val tSec: Int,
    val lat: Double,
    val lng: Double,
    val altM: Double? = null,
)

/**
 * The dense capture of a recording — the real GPS route + HR series a [RecordingEngine] gathers,
 * handed to `FitRepository.addWorkout` alongside the summary [Workout]. Kept in the presentation
 * layer's neutral model (not the `:health-service` schema) so `:fit-shared-logic` stays free of
 * the storage module; the real repository maps it onto `WorkoutStreams`. Empty for a recording
 * with no sensors (a manual/strength workout).
 */
data class RecordedTrack(
    val route: List<GpsFix> = emptyList(),
    val hr: List<HrPoint> = emptyList(),
)

data class WorkoutLap(
    val index: Int,
    val distanceM: Int,
    val durationSec: Int,
    val avgHr: Int,
    val paceSecPerKm: Int,
    /** A trailing sub-km lap ("0.2 km"). */
    val partial: Boolean = false,
)

data class Workout(
    val workoutId: String,
    val activityType: ActivityType,
    val source: Source,
    val title: String,
    /** Provenance line for the detail footer ("Recorded in Fit · Polar H10"). */
    val device: String,
    val start: LocalDateTime,
    val end: LocalDateTime,
    val durationSec: Int,
    val distanceM: Int? = null,
    val activeEnergyKj: Int? = null,
    val avgHr: Int? = null,
    val maxHr: Int? = null,
    /** Steps captured during the session (on-device step counter); `null` when unknown. */
    val steps: Int? = null,
    val ascentM: Int? = null,
    /** Stored for run/walk; rides where speed reads better use [avgSpeedKmh]. */
    val avgPaceSecPerKm: Int? = null,
    val avgSpeedKmh: Double? = null,
    val laps: List<WorkoutLap> = emptyList(),
    val hasRoute: Boolean = false,
    val route: List<RoutePoint> = emptyList(),
    /** Real WGS84 route for the offline basemap; empty when the workout carries no GPS (then the
     *  0..1 [route] renders on the schematic surface instead). */
    val geoRoute: List<GeoCoord> = emptyList(),
    val hrSeries: List<HrPoint> = emptyList(),
) {
    /** Pace for the stat grid: explicit if carried, else derived from distance + time. */
    val effectivePaceSecPerKm: Int?
        get() = avgPaceSecPerKm ?: distanceM?.takeIf { it > 0 }?.let {
            (durationSec * 1000L / it).toInt()
        }
}

data class DailyAggregate(
    val date: LocalDate,
    val steps: Int,
    val distanceM: Int,
    val activeEnergyKj: Int,
    /** WHO-derived moderate + vigorous minutes, computed on-device — never imported. */
    val activeMinutes: Int,
)

/** One day's nutrition aggregate — energy consumed (kJ; UI shows kcal) + hydration (mL). */
data class NutritionDay(
    val date: LocalDate,
    val energyKj: Int,
    val hydrationMl: Int,
)

/** Menstrual flow level for a logged day (§12 B3b). */
enum class FlowLevel { Unspecified, Light, Medium, Heavy }

/** One logged menstruation day — flow level + whether it starts a period (the cycle-day anchor). */
data class CycleEntry(
    val date: LocalDate,
    val flow: FlowLevel,
    val periodStart: Boolean,
)

/**
 * A charted body/vitals reading.
 *
 * [Height] joins [Weight] as a *measurement* rather than a profile setting: both platforms record
 * it (Health Connect `HeightRecord`, HealthKit `HKQuantityTypeIdentifierHeight`), so a settings
 * field beside it would diverge from the store on the first import (`fit-app.md` §18.2).
 */
enum class Metric { HeartRate, Weight, Height, BloodOxygen, RespiratoryRate }

/** Latest reading per metric — the Browse/Vitals teaser surface. */
data class SampleLatest(
    val metric: Metric,
    val value: Double,
    val unit: String,
    val time: LocalDateTime,
    val source: Source,
    /** e.g. a resting-rate reading; typed enough for v1's single use. */
    val resting: Boolean = false,
)

enum class SleepStageKind { Awake, Rem, Light, Deep }

data class SleepStage(
    val stage: SleepStageKind,
    val minutes: Int,
)

data class SleepSession(
    val date: LocalDate,
    val start: LocalDateTime,
    val end: LocalDateTime,
    val durationSec: Int,
    val source: Source,
    val stages: List<SleepStage> = emptyList(),
)

enum class MedicalRecordKind { Medication, Immunization, Allergy, Condition }

/** FHIR-backed, import-only + read-only in v1 (fit-agent.md §0.7). */
data class MedicalRecord(
    val kind: MedicalRecordKind,
    val title: String,
    val detail: String,
    val effectiveDate: LocalDate,
    val issuer: String,
    val source: Source,
)

data class Goals(
    val activeMinutes: Int,
    val steps: Int,
)

enum class Units { Metric, Imperial }

/**
 * The user as Profile renders them.
 *
 * ⚠⚠ **Every field but [units] is nullable, and that is load-bearing** (`fit-app.md` §18.5). This
 * type used to be non-null with a hardcoded `You / 178 cm / 74.2 kg / 1992-05-30 / Male` sitting in
 * the *real* repository, so the app confidently displayed a body that was nobody's. Persisting that
 * unchanged would only have made the fabrication durable and synced; `null` is the honest state and
 * the UI renders it as "Not set". [units] alone has a real default — metric is a presentation
 * choice with no wrong answer, not a claim about the user.
 *
 * [heightCm] and [weightKg] come from `HealthSample` measurements, not from settings — see
 * [org.tenshu.preference.OwnerPreferences] for why the person-level fields live outside Fit and
 * §18.2 for why the body ones stay in the health domain.
 */
data class FitProfile(
    val name: String?,
    val heightCm: Int?,
    val weightKg: Double?,
    val birthDate: LocalDate?,
    val sex: BiologicalSex?,
    val units: Units,
)

// ── Browse categories ────────────────────────────────────────────────────────────

@Serializable
enum class CategoryId { Activity, Body, Vitals, Sleep, Nutrition, Cycle, Medical }

/**
 * The latest-value teaser on a Browse card. String-free — the UI picks the matching
 * localized template (`fit_teaser_*`) and fills the numbers.
 */
sealed interface CategoryTeaser {
    data class ActiveMinutesToday(val minutes: Int) : CategoryTeaser
    data class Weight(val kg: Double) : CategoryTeaser
    data class RestingHeartRate(val bpm: Int) : CategoryTeaser
    /** Vitals' fallback when there is no resting heart rate: SpO₂, whole percent. */
    data class BloodOxygen(val percent: Int) : CategoryTeaser
    /** Vitals' second fallback: breaths per minute. */
    data class RespiratoryRate(val perMinute: Double) : CategoryTeaser
    data class SleepDuration(val minutes: Int) : CategoryTeaser
    /** Energy consumed today, in kilojoules (the UI renders kcal). */
    data class Energy(val kj: Int) : CategoryTeaser
    /** Current day of the menstrual cycle (days since the last period start). */
    data class CycleDay(val day: Int) : CategoryTeaser
    data class RecordCount(val count: Int) : CategoryTeaser
    data object NoEntries : CategoryTeaser
    data object NotSetUp : CategoryTeaser
}

/** String-free "last updated" stamp; the UI localises each case. */
sealed interface LastUpdated {
    data object Today : LastUpdated
    data class DaysAgo(val days: Int) : LastUpdated
    data object Imported : LastUpdated
    data object Never : LastUpdated
}

data class CategorySummary(
    val id: CategoryId,
    val teaser: CategoryTeaser,
    val updated: LastUpdated,
) {
    /** Empty categories dim to "Not set up" / "No entries yet" (fit-agent.md §1.6). */
    val isEmpty: Boolean
        get() = teaser is CategoryTeaser.NotSetUp || teaser is CategoryTeaser.NoEntries
}

// ── Onboarding ───────────────────────────────────────────────────────────────────

/**
 * An import source offered on the onboarding welcome screen. Detection is **data, not
 * expect/actual** (D5): the mock returns a fixed list; the real impl consults the
 * platform (Health Connect present? / iOS?). De-Googled paths are first-class — file
 * import and start-fresh are always available.
 */
enum class ImportSource { HealthConnect, AppleHealth, ImportFile, StartFresh }

/**
 * How far back an import reads.
 *
 * ⚠ The platform, not us, sets the default depth: **Health Connect returns nothing older than 30
 * days** unless the app holds `READ_HEALTH_DATA_HISTORY`, so [Recent] is not a policy we chose but
 * the only window an ordinarily-granted app can see. [FullHistory] is meaningful only once
 * [HistoryAccess.Granted] — asking for it without the grant reads exactly the same 30 days.
 */
enum class ImportWindow { Recent, FullHistory }

/**
 * A truthful qualifier on an import that otherwise **succeeded** — the source was reachable, the
 * grants were held, rows came back, and yet what the user was looking for still is not here.
 *
 * Distinct from [ImportStatus.NoData] on purpose: that one says the source is empty, which is a
 * verdict about *us*. A notice says the source answered honestly and the missing data is somewhere
 * else entirely — so the actionable next step is a different import, not a retry.
 */
/**
 * What one *other* app is permitted to do with the platform health store.
 *
 * Three states rather than a `Boolean` because they lead to three different places: an app that
 * is not there is not the user's problem, an app that is there and blocked is one switch away, and
 * an app that is there and allowed has already had its chance — see [ImportNotice].
 */
enum class AppSharingState { NotInstalled, NotPermitted, Permitted }

/**
 * Everything the sharing wizard needs, probed **without running an import** (`fit-app.md` §15.6).
 *
 * A separate read from [ImportStatus] on purpose: an import is expensive and its result describes
 * the *last* read, while the wizard has to describe the world as it is right now — the user has
 * just come back from Health Connect and wants to know whether what they did there took effect.
 *
 * The defaults describe a platform with nothing to configure (iOS, the reference importer), so a
 * shell that never implements the probe simply never offers the wizard.
 */
data class SharingReadiness(
    val storePresent: Boolean = false,
    val canRead: Boolean = false,
    val historyAccess: HistoryAccess = HistoryAccess.Unsupported,
    val googleFit: AppSharingState = AppSharingState.NotInstalled,
) {
    /** Whether there is anything here a user could act on — the gate on offering the wizard at all. */
    val hasAnythingToFix: Boolean
        get() = !storePresent || !canRead ||
            historyAccess == HistoryAccess.Grantable ||
            googleFit == AppSharingState.NotPermitted
}

enum class ImportNotice {
    /**
     * Google Fit is installed and **able** to write to Health Connect, but has not been allowed to
     * — it holds none of the `WRITE_*` grants, so nothing of its ever reaches Health Connect and
     * therefore never reaches us.
     *
     * ⚠⚠ The one genuinely **fixable** case, and the one first assumed impossible. Google Fit
     * declares `WRITE_EXERCISE`, `WRITE_STEPS`, `WRITE_DISTANCE`, `WRITE_EXERCISE_ROUTE` and more:
     * the integration exists, it is simply switched off by default. Telling a user in this state
     * that Google Fit "does not share" would be false *and* would talk them out of the one action
     * that could work — so this notice sends them to Health Connect, not to a Takeout export.
     */
    GoogleFitNotPermitted,

    /**
     * Google Fit is installed and **is** allowed to write to Health Connect, yet wrote none of what
     * we just read — the permission is not the obstacle, so no amount of granting will change it.
     *
     * The remaining explanation is what Google Fit chooses to share: a grant governs data written
     * from that point on, and years of existing history do not retroactively appear. Here — and
     * only here — a Google Takeout export is the answer (`fit-app.md` §16).
     */
    GoogleFitNotSharing,
}

/**
 * Whether this device can read health data from before the platform's default window.
 *
 * Three states rather than a `Boolean` because "we cannot" and "you have not said yes yet" are
 * different answers to the user: the first must not be offered at all, the second is a one-tap
 * action. [Unsupported] covers a platform with no such notion, a Health Connect too old to carry
 * the feature (probed via `HealthConnectFeatures`), and every non-Android target.
 */
enum class HistoryAccess { Unsupported, Grantable, Granted }

/** Per-category consent row on the permissions step, with an estimated record count. */
data class ImportCategory(
    val id: CategoryId,
    val estimatedCount: Int,
)

/** Live per-category progress while the first import runs. */
data class ImportProgress(
    val id: CategoryId,
    val imported: Int,
    val total: Int,
) {
    val done: Boolean get() = imported >= total
}

/**
 * The outcome of the most recent import, as something the **user** can be shown.
 *
 * The app used to render every one of these states identically — as an empty screen. A source that
 * holds no data, a permission the user never granted, a device without Health Connect at all, and a
 * genuine failure were indistinguishable, which is what made "the Google Fit import doesn't work"
 * impossible to act on: the app knew the answer at import time and dropped it on the floor.
 *
 * [Sample] is the sharp one. When no real source is reachable the app falls back to a fixture, so
 * the screens fill with *plausible, entirely fake* health data. That must be labelled wherever it
 * is shown — an unlabelled fixture is worse than an empty screen, because the user believes it.
 */
sealed interface ImportStatus {

    /**
     * Something true about this outcome that the outcome itself cannot express — see [ImportNotice].
     * Defaulted rather than declared per case because only a *completed read* can carry one: an
     * import that never got as far as reading has nothing to qualify.
     */
    val notice: ImportNotice? get() = null

    /** No import has run yet (or the app has no importer at all — the mock and preview surfaces). */
    data object Idle : ImportStatus

    /**
     * An import is in flight. [imported] is a running count of records persisted so far — a Takeout
     * import writes tens of thousands one at a time, and a bare "Importing…" on a minutes-long job
     * reads as a hang, so the count ticks up to show it is alive. It is a count-so-far, not a
     * percentage: the total is not known until the whole archive has been parsed.
     */
    data class Running(val imported: Int = 0) : ImportStatus

    /** The source was read. [itemCount] rows landed; zero is a legitimate, and reportable, answer. */
    data class Imported(
        val source: ImportSource,
        val itemCount: Int,
        override val notice: ImportNotice? = null,
    ) : ImportStatus

    /** The source is reachable and authorised, but holds nothing to import. */
    data class NoData(
        val source: ImportSource,
        override val notice: ImportNotice? = null,
    ) : ImportStatus

    /** The source exists but has not been granted the read permissions it needs. */
    data class PermissionsMissing(val source: ImportSource) : ImportStatus

    /** No live source on this device (no Health Connect, no HealthKit). */
    data object SourceUnavailable : ImportStatus

    /** What is on screen is a built-in **fixture**, not the user's health data. Always say so. */
    data class Sample(val itemCount: Int) : ImportStatus

    /** The import ran and failed. [reason] is a developer-facing detail, not user copy. */
    data class Failed(val reason: String) : ImportStatus

    /**
     * A **manual** import the realm gate refused (`ADR 0025` §8.6): Control's device has no realm
     * yet, or the realm read has not answered. Nothing was written. ⚠⚠ Carries the user's exact
     * request — [selection] and [window] — so "Try again" re-runs *that*, never the all-category
     * recent pass an automatic re-run would substitute. Not re-run automatically: the user asked
     * for this one, so the user retries it.
     */
    data class WaitingForControl(
        val selection: Set<CategoryId>,
        val window: ImportWindow,
    ) : ImportStatus

    /** Whether this state is worth interrupting the user about (drives the Home banner). */
    val needsAttention: Boolean
        get() = this is NoData || this is PermissionsMissing || this is SourceUnavailable ||
            this is Sample || this is Failed || this is WaitingForControl
}
