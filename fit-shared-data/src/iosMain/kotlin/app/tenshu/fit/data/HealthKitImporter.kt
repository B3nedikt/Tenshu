@file:OptIn(ExperimentalForeignApi::class)

package app.tenshu.fit.data

import app.tenshu.fit.model.CategoryId
import app.tenshu.fit.model.ImportSource
import app.tenshu.fit.model.ImportWindow
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSBundle
import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSSortDescriptor
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.dateWithTimeIntervalSinceNow
import platform.Foundation.timeIntervalSince1970
import platform.Foundation.NSNumber
import platform.HealthKit.HKCategorySample
import platform.HealthKit.HKCategoryTypeIdentifierMenstrualFlow
import platform.HealthKit.HKCategoryTypeIdentifierSleepAnalysis
import platform.HealthKit.HKCategoryValueMenstrualFlowHeavy
import platform.HealthKit.HKCategoryValueMenstrualFlowLight
import platform.HealthKit.HKCategoryValueMenstrualFlowMedium
import platform.HealthKit.HKMetadataKeyMenstrualCycleStart
import platform.HealthKit.HKCategoryValueSleepAnalysisAsleepCore
import platform.HealthKit.HKCategoryValueSleepAnalysisAsleepDeep
import platform.HealthKit.HKCategoryValueSleepAnalysisAsleepREM
import platform.HealthKit.HKCategoryValueSleepAnalysisAsleepUnspecified
import platform.HealthKit.HKCategoryValueSleepAnalysisAwake
import platform.HealthKit.HKAuthorizationRequestStatusUnnecessary
import platform.HealthKit.HKHealthStore
import platform.HealthKit.HKObjectType
import platform.HealthKit.HKQuantitySample
import platform.HealthKit.HKQuantityTypeIdentifierActiveEnergyBurned
import platform.HealthKit.HKQuantityTypeIdentifierDietaryEnergyConsumed
import platform.HealthKit.HKQuantityTypeIdentifierDietaryWater
import platform.HealthKit.HKQuantityTypeIdentifierAppleExerciseTime
import platform.HealthKit.HKQuantityTypeIdentifierBodyMass
import platform.HealthKit.HKQuantityTypeIdentifierDistanceWalkingRunning
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlinx.datetime.LocalDate
import org.tenshu.preference.BiologicalSex
import platform.Foundation.NSError
import platform.HealthKit.HKCharacteristicTypeIdentifierBiologicalSex
import platform.HealthKit.HKCharacteristicTypeIdentifierDateOfBirth
import platform.HealthKit.HKBiologicalSexFemale
import platform.HealthKit.HKBiologicalSexMale
import platform.HealthKit.HKBiologicalSexOther
import platform.HealthKit.HKQuantityTypeIdentifierHeight
import platform.HealthKit.HKQuantityTypeIdentifierOxygenSaturation
import platform.HealthKit.HKQuantityTypeIdentifierRespiratoryRate
import platform.HealthKit.HKQuantityTypeIdentifierRestingHeartRate
import platform.HealthKit.HKQuantityTypeIdentifierStepCount
import platform.HealthKit.HKQuery
import platform.HealthKit.HKQueryOptionStrictStartDate
import platform.HealthKit.HKSampleQuery
import platform.HealthKit.HKSampleSortIdentifierStartDate
import platform.HealthKit.HKSampleType
import platform.HealthKit.HKUnit
import platform.HealthKit.HKWorkout
import platform.HealthKit.HKWorkoutActivityTypeCycling
import platform.HealthKit.HKWorkoutActivityTypeFunctionalStrengthTraining
import platform.HealthKit.HKWorkoutActivityTypeRunning
import platform.HealthKit.HKWorkoutActivityTypeTraditionalStrengthTraining
import platform.HealthKit.HKWorkoutActivityTypeWalking
import platform.HealthKit.predicateForSamplesWithStartDate
import kotlin.coroutines.resume
import org.tenshu.core.common.util.runCatchingCancellable

/**
 * Whether the read request for [read] has already been answered — `.unnecessary` means asking again
 * would show no sheet. It says nothing about *what* was answered (HealthKit hides read denial).
 */
private suspend fun isReadRequestAnswered(hk: HKHealthStore, read: Set<HKObjectType>): Boolean {
    if (read.isEmpty()) return true
    return suspendCancellableCoroutine { cont ->
        hk.getRequestStatusForAuthorizationToShareTypes(emptySet<Any>(), readTypes = read) { status, error ->
            if (error != null) fitLog("getRequestStatusForAuthorization error: ${error.localizedDescription}")
            cont.resume(error == null && status == HKAuthorizationRequestStatusUnnecessary)
        }
    }
}

/**
 * The **iOS live-import actual** (P-iOS-2, `docs/proposals/fit-app.md` §9.12): a [HealthImporter]
 * over **HealthKit** — the platform twin of the Android [HealthConnectImporter] (P5.2), mapping
 * the same neutral record set into [ImportedHealth] for the shared [HealthImportWriter]: workouts,
 * daily steps/distance/energy/exercise-minutes, sleep (+ stages), and the latest
 * weight/resting-HR/SpO₂/respiratory-rate samples.
 *
 * ⚠⚠ **No fixture fallback.** When HealthKit is *unavailable* (an iPad on iPadOS 16, or any device
 * class without a health store) this importer offers **no source** and [import] answers
 * [ImportOutcome.SourceUnavailable], so the UI says "Apple Health isn't available on this device".
 * It used to delegate to the [ReferenceHealthImporter] fixture, labelled `Sample` — which a manual
 * import then wrote into the user's real, synced realm, and which App Review would have seen as
 * fabricated health data. The fixture stays a test/preview tool; it is never a shipping fallback.
 * When HealthKit *is* available, [import] first requests read authorization (the system consent
 * sheet, shown once per app+type by iOS) and then reads.
 *
 * ⚠⚠ **The consent sheet follows a tap, never a launch** (HealthKit guideline 5.1.3, HIG). Every
 * unattended pass is gated on [autoRefreshReady], which stays false until the read request has
 * already been answered once — i.e. until the user has tapped *Import from Apple Health*. Before
 * this gate the launch bootstrap's automatic import put the all-types sheet up on first launch.
 *
 * ⚠ **HealthKit hides read denial by design** (unlike Health Connect): a denied read permission is
 * indistinguishable from an empty store — queries simply return no samples. So this importer never
 * returns [ImportOutcome.PermissionsRequired]; an empty `Success` honestly means "HealthKit gave
 * us nothing", which covers both "no data" and "not authorised". The A34 consent property still
 * holds — *iOS* enforces the grant; we just can't observe its state.
 *
 * ⚠ Health data is GDPR Article 9 (threat-model **A34**): read only under the HealthKit consent
 * sheet; values never logged (count-level logging only); clinical/FHIR records need Apple's
 * restricted health-records entitlement and are **not** read here (Medical stays a file import).
 *
 * Active minutes use HealthKit's own `appleExerciseTime` (Apple's moderate+vigorous metric) rather
 * than Android's HR-zone derivation — a direct read beats a derived estimate where the platform
 * provides one.
 */
class HealthKitImporter(
    private val historyDays: Long = 30,
    /** Test seam: whether this device class has a health store at all. */
    private val healthDataAvailable: () -> Boolean = { HKHealthStore.isHealthDataAvailable() },
    /** Test seam: whether the host app declares `NSHealthShareUsageDescription` (see [autoRefreshReady]). */
    private val usageDescriptionDeclared: () -> Boolean = {
        NSBundle.mainBundle.objectForInfoDictionaryKey("NSHealthShareUsageDescription") != null
    },
    /**
     * Test seam: whether the read request for [readTypes] has **already been answered**, so asking
     * again would show no sheet (`HKAuthorizationRequestStatusUnnecessary`). See [autoRefreshReady].
     */
    private val readRequestAnswered: suspend (HKHealthStore, Set<HKObjectType>) -> Boolean = ::isReadRequestAnswered,
) : HealthImporter {

    private val store: HKHealthStore? by lazy {
        if (healthDataAvailable()) HKHealthStore() else null
    }

    override fun availableSources(): List<ImportSource> =
        if (store != null) listOf(ImportSource.AppleHealth) else emptyList()

    /**
     * The one thing Health Connect structurally cannot do (`fit-app.md` §18.12): read the user's
     * date of birth and biological sex from the platform.
     *
     * ⚠ **Throws when not authorised** — `dateOfBirthComponentsWithError:` and
     * `biologicalSexWithError:` report a refusal as an error, not as a null. That is
     * indistinguishable from "the user left it blank in Health", and neither is a failure worth
     * surfacing, so both resolve to "not known" and the profile simply stays unset.
     */
    override suspend fun readCharacteristics(): HealthCharacteristics {
        val hk = store ?: return HealthCharacteristics()
        return HealthCharacteristics(birthDate = readBirthDate(hk), sex = readBiologicalSex(hk))
    }

    private fun readBirthDate(hk: HKHealthStore): LocalDate? = memScoped {
        val error = alloc<ObjCObjectVar<NSError?>>()
        val components = hk.dateOfBirthComponentsWithError(error.ptr) ?: return@memScoped null
        if (error.value != null) return@memScoped null
        // `NSDateComponents` reports NSNotFound for an unset field; a year of 0 is likewise no
        // answer. Either way there is nothing to seed.
        val year = components.year.toInt()
        val month = components.month.toInt()
        val day = components.day.toInt()
        if (year <= 0 || month !in 1..12 || day !in 1..31) return@memScoped null
        runCatching { LocalDate(year, month, day) }.getOrNull()
    }

    private fun readBiologicalSex(hk: HKHealthStore): BiologicalSex? = memScoped {
        val error = alloc<ObjCObjectVar<NSError?>>()
        val value = hk.biologicalSexWithError(error.ptr) ?: return@memScoped null
        if (error.value != null) return@memScoped null
        // HKBiologicalSexNotSet maps to null, not to a value — "not set" is the same answer here
        // as never having been asked (§18.4).
        when (value.biologicalSex) {
            HKBiologicalSexFemale -> BiologicalSex.FEMALE
            HKBiologicalSexMale -> BiologicalSex.MALE
            HKBiologicalSexOther -> BiologicalSex.OTHER
            else -> null
        }
    }

    /** Whether a real HealthKit store backs this importer. */
    fun isHealthKitAvailable(): Boolean = store != null

    /**
     * Unattended refresh is only safe where the HOST APP declares the HealthKit usage string:
     * [import]'s `requestAuthorization` **aborts the process** (`NSInvalidArgumentException`)
     * when `NSHealthShareUsageDescription` is missing from the Info.plist. The Fit app declares
     * it; bare test binaries and non-Fit hosts assembling this importer (the umbrella's
     * `EpcFitClient`) do not — for them the launch-time auto-refresh must stay off, exactly
     * like Health Connect's grant-less gate on Android (fit-app.md §9.13). A user-driven
     * `startImport` from the Fit app is unaffected (its plist carries the key).
     *
     * ⚠⚠ **And only once the user has answered the read request.** An unattended pass runs at
     * launch and on every foreground, and [import] asks for authorization first — so without this
     * clause a fresh install showed the all-types HealthKit sheet before the user had tapped
     * anything (App Review guideline 5.1.3 wants the request tied to a user action). HealthKit
     * hides *what* was answered but not *whether*: `.unnecessary` means the sheet would not show,
     * so the refresh can run silently. Until then the first import is the user's explicit
     * *Import from Apple Health*. Same shape as Android, where the gate is `hasAllPermissions()`.
     * ⚠ A new read type in a later build reads as "not yet answered" — the automatic refresh then
     * pauses until the next manual import asks for it, which is the honest outcome.
     */
    override suspend fun autoRefreshReady(): Boolean {
        val hk = store ?: return false
        if (!usageDescriptionDeclared()) return false
        return runCatchingCancellable { readRequestAnswered(hk, readTypesFor(CategoryId.entries.toSet())) }
            .onFailure { fitLog("autoRefreshReady: request-status read failed — treating as not answered", it) }
            .getOrDefault(false)
    }

    override suspend fun import(source: ImportSource, categories: Set<CategoryId>, window: ImportWindow): ImportOutcome {
        fitLog("import(source=$source, categories=${categories.joinToString { it.name }}) — window=$window")
        val hk = store ?: run {
            fitLog("import: no HealthKit on this device → SourceUnavailable")
            return ImportOutcome.SourceUnavailable
        }

        // One consent sheet for every type the selection needs. iOS shows it only the first time a
        // type is requested; afterwards this resolves immediately with the (invisible) prior answer.
        requestAuthorization(hk, readTypesFor(categories))

        // HealthKit has no Health-Connect-style 30-day floor: the authorisation grant is the only
        // gate, so a full-history read is simply a wider window and needs no extra consent. That is
        // why [historyAccess] stays Unsupported here — there is nothing for the user to grant, and
        // an entry offering one would be a button with nothing behind it. (Surfacing "import
        // everything" on iOS is a separate slice; the depth is honoured here so the plumbing is not
        // silently lying about `window`.)
        val days = if (window == ImportWindow.FullHistory) FULL_HISTORY_DAYS else historyDays
        val end = NSDate()
        val start = NSDate.dateWithTimeIntervalSinceNow(-days.toDouble() * SECONDS_PER_DAY)
        val predicate = HKQuery.predicateForSamplesWithStartDate(start, end, HKQueryOptionStrictStartDate)

        val workouts = if (CategoryId.Activity in categories) readWorkouts(hk, predicate) else emptyList()
        val dailyActivities = if (CategoryId.Activity in categories) readDailyActivities(hk, predicate) else emptyList()
        val dailyNutritions = if (CategoryId.Nutrition in categories) readNutrition(hk, predicate) else emptyList()
        val menstruationDays = if (CategoryId.Cycle in categories) readMenstruation(hk, predicate) else emptyList()
        val sleepSessions = if (CategoryId.Sleep in categories) readSleep(hk, predicate) else emptyList()
        val samples = readSamples(hk, predicate, categories)

        val health = ImportedHealth(
            workouts = workouts,
            dailyActivities = dailyActivities,
            dailyNutritions = dailyNutritions,
            menstruationDays = menstruationDays,
            sleepSessions = sleepSessions,
            samples = samples,
            medicalRecords = emptyList(), // FHIR needs the restricted health-records entitlement
        )
        fitLog(
            "import: DONE — ${health.count} item(s): workouts=${health.workouts.size} " +
                "daily=${health.dailyActivities.size} nutrition=${health.dailyNutritions.size} " +
                "cycle=${health.menstruationDays.size} sleep=${health.sleepSessions.size} samples=${health.samples.size}",
        )
        // Empty Success is a real answer: HealthKit holds nothing readable in the window (which,
        // by iOS design, also covers a denied read grant — see the class doc).
        return ImportOutcome.Success(health)
    }

    // ── authorization ──────────────────────────────────────────────────────────

    private fun readTypesFor(categories: Set<CategoryId>): Set<HKObjectType> = buildSet {
        if (CategoryId.Activity in categories) {
            add(HKObjectType.workoutType())
            addQuantity(HKQuantityTypeIdentifierStepCount)
            addQuantity(HKQuantityTypeIdentifierDistanceWalkingRunning)
            addQuantity(HKQuantityTypeIdentifierActiveEnergyBurned)
            addQuantity(HKQuantityTypeIdentifierAppleExerciseTime)
        }
        if (CategoryId.Nutrition in categories) {
            addQuantity(HKQuantityTypeIdentifierDietaryEnergyConsumed)
            addQuantity(HKQuantityTypeIdentifierDietaryWater)
        }
        if (CategoryId.Cycle in categories) {
            HKObjectType.categoryTypeForIdentifier(HKCategoryTypeIdentifierMenstrualFlow)?.let { add(it) }
        }
        if (CategoryId.Sleep in categories) {
            HKObjectType.categoryTypeForIdentifier(HKCategoryTypeIdentifierSleepAnalysis)?.let { add(it) }
        }
        if (CategoryId.Body in categories) {
            addQuantity(HKQuantityTypeIdentifierBodyMass)
            addQuantity(HKQuantityTypeIdentifierHeight)
            // Demographics ride the Body grant, like height and weight (fit-app.md §18.12). They
            // are read once, to seed the profile — the HealthKit sheet names them explicitly, so
            // the user sees exactly what is being asked for.
            HKObjectType.characteristicTypeForIdentifier(HKCharacteristicTypeIdentifierDateOfBirth)?.let { add(it) }
            HKObjectType.characteristicTypeForIdentifier(HKCharacteristicTypeIdentifierBiologicalSex)?.let { add(it) }
        }
        if (CategoryId.Vitals in categories) {
            addQuantity(HKQuantityTypeIdentifierRestingHeartRate)
            addQuantity(HKQuantityTypeIdentifierOxygenSaturation)
            addQuantity(HKQuantityTypeIdentifierRespiratoryRate)
        }
    }

    private fun MutableSet<HKObjectType>.addQuantity(identifier: String?) {
        identifier?.let { id -> HKObjectType.quantityTypeForIdentifier(id)?.let { add(it) } }
    }

    private suspend fun requestAuthorization(hk: HKHealthStore, read: Set<HKObjectType>) {
        if (read.isEmpty()) return
        suspendCancellableCoroutine { cont ->
            hk.requestAuthorizationToShareTypes(null, readTypes = read) { _, error ->
                // A thrown authorization request (not a denial — iOS hides those) fails the import
                // loudly downstream only if the queries themselves fail; the request erroring is
                // log-worthy but not fatal (queries against unauthorised types return empty).
                if (error != null) fitLog("requestAuthorization error: ${error.localizedDescription}")
                cont.resume(Unit)
            }
        }
    }

    // ── reads (each resilient: a per-type failure yields empty, not a dead import — but is LOGGED) ──

    /**
     * All samples of [type] under [predicate], oldest-first, **bounded** to [limit] (the Android
     * importer's lesson: unbounded materialisation trades silent truncation for an OOM on dense
     * stores). Logs count only — never a value (A34).
     */
    private suspend fun readAllSamples(
        hk: HKHealthStore,
        type: HKSampleType,
        predicate: platform.Foundation.NSPredicate,
        limit: Int = MAX_RECORDS_PER_TYPE,
    ): List<HKQuantitySample> = querySamples(hk, type, predicate, limit).filterIsInstance<HKQuantitySample>()

    private suspend fun querySamples(
        hk: HKHealthStore,
        type: HKSampleType,
        predicate: platform.Foundation.NSPredicate,
        limit: Int,
        ascending: Boolean = true,
    ): List<*> = suspendCancellableCoroutine { cont ->
        val sort = NSSortDescriptor.sortDescriptorWithKey(HKSampleSortIdentifierStartDate, ascending)
        val query = HKSampleQuery(
            sampleType = type,
            predicate = predicate,
            limit = limit.toULong(),
            sortDescriptors = listOf(sort),
        ) { _, samples, error ->
            if (error != null) {
                fitLog("read ${type.identifier}: FAILED (${error.localizedDescription}) — contributes nothing")
                cont.resume(emptyList<Any>())
            } else {
                val list = samples.orEmpty()
                if (list.size >= limit) {
                    fitLog("read ${type.identifier}: hit the $limit-record ceiling — older records were skipped")
                }
                fitLog("read ${type.identifier}: ${list.size} sample(s)")
                cont.resume(list)
            }
        }
        hk.executeQuery(query)
        cont.invokeOnCancellation { hk.stopQuery(query) }
    }

    /** The most recent sample of the quantity type named by [identifier], or null. */
    private suspend fun readLatest(
        hk: HKHealthStore,
        identifier: String?,
        predicate: platform.Foundation.NSPredicate,
    ): HKQuantitySample? {
        val type = identifier?.let { HKObjectType.quantityTypeForIdentifier(it) } ?: return null
        return querySamples(hk, type, predicate, limit = 1, ascending = false)
            .filterIsInstance<HKQuantitySample>()
            .firstOrNull()
    }

    private suspend fun readWorkouts(
        hk: HKHealthStore,
        predicate: platform.Foundation.NSPredicate,
    ): List<ImportedWorkout> =
        querySamples(hk, HKObjectType.workoutType(), predicate, MAX_RECORDS_PER_TYPE)
            .filterIsInstance<HKWorkout>()
            // Skip workouts Fit itself wrote back to Apple Health (R-iOS-6): the recorder already
            // persisted them to the realm as `Recorded`, so re-importing them here — under a fresh
            // HealthKit UUID, as `AppleHealth` — would duplicate every recorded workout.
            .filterNot { it.sourceRevision.source.bundleIdentifier == NSBundle.mainBundle.bundleIdentifier }
            .map { w ->
                ImportedWorkout(
                    workoutId = w.UUID.UUIDString,
                    activityType = activityTypeName(w.workoutActivityType),
                    source = SOURCE,
                    startMs = w.startDate.ms(),
                    endMs = w.endDate.ms(),
                    durationSec = w.duration.toLong(),
                    distanceM = w.totalDistance?.doubleValueForUnit(HKUnit.unitFromString("m"))?.toLong() ?: 0,
                    activeEnergyKj = w.totalEnergyBurned
                        ?.doubleValueForUnit(HKUnit.unitFromString("kcal"))?.let { (it * KJ_PER_KCAL).toLong() } ?: 0,
                )
            }

    private suspend fun readDailyActivities(
        hk: HKHealthStore,
        predicate: platform.Foundation.NSPredicate,
    ): List<ImportedDailyActivity> {
        val stepsByDay = sumByDay(hk, HKQuantityTypeIdentifierStepCount, predicate, HKUnit.unitFromString("count"))
        val distanceByDay = sumByDay(hk, HKQuantityTypeIdentifierDistanceWalkingRunning, predicate, HKUnit.unitFromString("m"))
        val energyByDay = sumByDay(hk, HKQuantityTypeIdentifierActiveEnergyBurned, predicate, HKUnit.unitFromString("kcal"))
            .mapValues { (_, kcal) -> kcal * KJ_PER_KCAL }
        val exerciseByDay = sumByDay(hk, HKQuantityTypeIdentifierAppleExerciseTime, predicate, HKUnit.unitFromString("min"))

        return (stepsByDay.keys + distanceByDay.keys + energyByDay.keys + exerciseByDay.keys).map { day ->
            ImportedDailyActivity(
                date = day,
                steps = (stepsByDay[day] ?: 0.0).toLong(),
                distanceM = (distanceByDay[day] ?: 0.0).toLong(),
                activeEnergyKj = (energyByDay[day] ?: 0.0).toLong(),
                activeMinutes = (exerciseByDay[day] ?: 0.0).toLong(),
            )
        }.also {
            fitLog(
                "readDailyActivities: ${it.size} day(s) — steps=${stepsByDay.size} distance=${distanceByDay.size} " +
                    "energy=${energyByDay.size} exerciseMin=${exerciseByDay.size}",
            )
        }
    }

    private suspend fun readNutrition(
        hk: HKHealthStore,
        predicate: platform.Foundation.NSPredicate,
    ): List<ImportedDailyNutrition> {
        val energyByDay = sumByDay(hk, HKQuantityTypeIdentifierDietaryEnergyConsumed, predicate, HKUnit.unitFromString("kcal"))
            .mapValues { (_, kcal) -> kcal * KJ_PER_KCAL }
        val hydrationByDay = sumByDay(hk, HKQuantityTypeIdentifierDietaryWater, predicate, HKUnit.unitFromString("mL"))
        return (energyByDay.keys + hydrationByDay.keys).map { day ->
            ImportedDailyNutrition(
                date = day,
                energyKj = (energyByDay[day] ?: 0.0).toLong(),
                hydrationMl = (hydrationByDay[day] ?: 0.0).toLong(),
            )
        }.also { fitLog("readNutrition: ${it.size} day(s) — energy=${energyByDay.size} hydration=${hydrationByDay.size}") }
    }

    private suspend fun readMenstruation(
        hk: HKHealthStore,
        predicate: platform.Foundation.NSPredicate,
    ): List<ImportedMenstruationDay> {
        val type = HKObjectType.categoryTypeForIdentifier(HKCategoryTypeIdentifierMenstrualFlow) ?: return emptyList()
        return querySamples(hk, type, predicate, MAX_RECORDS_PER_TYPE)
            .filterIsInstance<HKCategorySample>()
            .map { s ->
                val start = (s.metadata?.get(HKMetadataKeyMenstrualCycleStart) as? NSNumber)?.boolValue ?: false
                ImportedMenstruationDay(date = s.startDate.day(), flow = flowName(s.value), periodStart = start)
            }
            .also { fitLog("readMenstruation: ${it.size} day(s)") }
    }

    private fun flowName(value: Long): String = when (value) {
        HKCategoryValueMenstrualFlowLight -> "Light"
        HKCategoryValueMenstrualFlowMedium -> "Medium"
        HKCategoryValueMenstrualFlowHeavy -> "Heavy"
        else -> "Unspecified"
    }

    private suspend fun sumByDay(
        hk: HKHealthStore,
        identifier: String?,
        predicate: platform.Foundation.NSPredicate,
        unit: HKUnit,
    ): Map<String, Double> {
        val type = identifier?.let { HKObjectType.quantityTypeForIdentifier(it) } ?: return emptyMap()
        return readAllSamples(hk, type, predicate)
            .groupBy { it.startDate.day() }
            .mapValues { (_, l) -> l.sumOf { it.quantity.doubleValueForUnit(unit) } }
    }

    /**
     * HealthKit sleep is per-stage category samples, not sessions — one night is many samples with
     * `sleepAnalysis` values. Grouped by the day they *end* on (a night belongs to the morning it
     * ends), each day folds to one [ImportedSleepSession] spanning min(start)..max(end) with the
     * per-stage minutes summed. `asleepCore` maps onto the schema's `light` (Apple's "core" is the
     * light/N1-N2 band).
     */
    private suspend fun readSleep(
        hk: HKHealthStore,
        predicate: platform.Foundation.NSPredicate,
    ): List<ImportedSleepSession> {
        val type = HKObjectType.categoryTypeForIdentifier(HKCategoryTypeIdentifierSleepAnalysis) ?: return emptyList()
        val samples = querySamples(hk, type, predicate, MAX_RECORDS_PER_TYPE)
            .filterIsInstance<HKCategorySample>()
            .map { SleepNights.Sample(it.startDate.ms(), it.endDate.ms(), it.value) }
        // ⚠⚠ Grouped into real nights, never by each sample's own end day: that split every night
        // starting before midnight and made the next one span evening to evening (SleepNights).
        return SleepNights.group(samples, dayOf = { ms -> NSDate.dateWithTimeIntervalSince1970(ms / 1000.0).day() })
            .map { n ->
                fun stageMin(vararg values: Long): Long = n.samples
                    .filter { it.value in values }
                    .sumOf { (it.endMs - it.startMs) / 60_000 }
                ImportedSleepSession(
                    date = n.date,
                    startMs = n.startMs,
                    endMs = n.endMs,
                    durationSec = (n.endMs - n.startMs) / 1000,
                    source = SOURCE,
                    awakeMin = stageMin(HKCategoryValueSleepAnalysisAwake),
                    remMin = stageMin(HKCategoryValueSleepAnalysisAsleepREM),
                    lightMin = stageMin(HKCategoryValueSleepAnalysisAsleepCore, HKCategoryValueSleepAnalysisAsleepUnspecified),
                    deepMin = stageMin(HKCategoryValueSleepAnalysisAsleepDeep),
                )
            }
            .also { fitLog("readSleep: ${it.size} night(s)") }
    }

    private suspend fun readSamples(
        hk: HKHealthStore,
        predicate: platform.Foundation.NSPredicate,
        categories: Set<CategoryId>,
    ): List<ImportedSample> {
        val samples = mutableListOf<ImportedSample>()
        // Weight + resting HR ride as a time-series (§12 B2) — read the whole (bounded) window so the
        // Body/Vitals detail can chart history, not just one latest reading.
        if (CategoryId.Body in categories) {
            HKObjectType.quantityTypeForIdentifier(HKQuantityTypeIdentifierBodyMass)?.let { type ->
                readAllSamples(hk, type, predicate).forEach { s ->
                    val kg = s.quantity.doubleValueForUnit(HKUnit.unitFromString("kg"))
                    samples += ImportedSample("Weight", kg.roundedString(), "kg", s.startDate.ms(), SOURCE)
                }
            }
            // Height (FP-2) — the Android twin. Newest reading only: an adult's height is
            // effectively constant, so a chart of it would be noise and Profile wants one value.
            HKObjectType.quantityTypeForIdentifier(HKQuantityTypeIdentifierHeight)?.let { type ->
                readAllSamples(hk, type, predicate).maxByOrNull { it.startDate.ms() }?.let { s ->
                    val cm = s.quantity.doubleValueForUnit(HKUnit.unitFromString("cm"))
                    samples += ImportedSample("Height", cm.roundedString(), "cm", s.startDate.ms(), SOURCE)
                }
            }
        }
        if (CategoryId.Vitals in categories) {
            val bpm = HKUnit.unitFromString("count/min")
            HKObjectType.quantityTypeForIdentifier(HKQuantityTypeIdentifierRestingHeartRate)?.let { type ->
                readAllSamples(hk, type, predicate).forEach { s ->
                    samples += ImportedSample(
                        "HeartRate", s.quantity.doubleValueForUnit(bpm).toLong().toString(), "bpm",
                        s.startDate.ms(), SOURCE, resting = true,
                    )
                }
            }
            // SpO₂ / respiratory rate: one latest reading each, shown on the Vitals detail (its switcher).
            readLatest(hk, HKQuantityTypeIdentifierOxygenSaturation, predicate)?.let { s ->
                val pct = s.quantity.doubleValueForUnit(HKUnit.unitFromString("%")) * 100
                samples += ImportedSample("BloodOxygen", pct.roundedString(), "%", s.startDate.ms(), SOURCE)
            }
            readLatest(hk, HKQuantityTypeIdentifierRespiratoryRate, predicate)?.let { s ->
                samples += ImportedSample(
                    "RespiratoryRate", s.quantity.doubleValueForUnit(bpm).roundedString(), "/min",
                    s.startDate.ms(), SOURCE,
                )
            }
        }
        return samples
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private val dayFormatter = NSDateFormatter().apply { dateFormat = "yyyy-MM-dd" }

    private fun NSDate.day(): String = dayFormatter.stringFromDate(this)

    private fun NSDate.ms(): Long = (timeIntervalSince1970 * 1000).toLong()

    private fun Double.roundedString(): String =
        if (this % 1.0 == 0.0) toLong().toString() else ((this * 10).toLong() / 10.0).toString()

    private fun activityTypeName(type: ULong): String = when (type) {
        HKWorkoutActivityTypeRunning -> "Running"
        HKWorkoutActivityTypeWalking -> "Walking"
        HKWorkoutActivityTypeCycling -> "Cycling"
        HKWorkoutActivityTypeTraditionalStrengthTraining,
        HKWorkoutActivityTypeFunctionalStrengthTraining -> "Strength"
        else -> "Other"
    }

    companion object {
        private const val SOURCE = "AppleHealth"
        private const val SECONDS_PER_DAY = 24L * 60 * 60

        /** Depth of an [ImportWindow.FullHistory] read — see the Android twin. */
        private const val FULL_HISTORY_DAYS = 3_650L
        private const val KJ_PER_KCAL = 4.184

        /** Per-type ceiling on a read (the Android importer's bounded-materialisation lesson). */
        private const val MAX_RECORDS_PER_TYPE = 10_000
    }
}
