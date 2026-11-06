package org.tenshu.preference.client

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onStart
import kotlinx.datetime.LocalDate
import org.tenshu.core.common.TenshuClient
import org.tenshu.preference.BiologicalSex
import org.tenshu.preference.MeasurementSystem
import org.tenshu.preference.OwnerPreferences

/**
 * The realm owner's own person, read and written as one value
 * ([OwnerPreferences], `docs/proposals/fit-app.md` §18.5).
 *
 * The rows are separate scalars — so two devices editing different fields converge instead of
 * clobbering, and so each value stays self-describing on the wire for a consumer that has never
 * heard of the app that wrote it — but every consumer so far wants them together, and `combine`
 * tops out at five typed flows in this codebase. Folding them here keeps that budget with the
 * caller.
 *
 * ⚠ **Absent is not empty-string.** A never-set name reads as `null`, not `""`: the difference is
 * "we have not asked yet" versus "the user cleared it", and the UI renders them the same way only
 * by choice, never by accident.
 */
data class OwnerProfileValues(
    val name: String? = null,
    val birthDate: LocalDate? = null,
    val sex: BiologicalSex? = null,
    val units: MeasurementSystem = MeasurementSystem.METRIC,
)

interface OwnerProfile {
    /**
     * Emits the owner's profile, re-emitting on any local write or peer `SyncBlock`.
     *
     * ⚠⚠ **The first emission may be the defaults, even when rows exist.** The underlying
     * `read()` is silent until the store answers, so this seeds defaults on subscribe — without
     * that, a realm holding no rows would emit nothing at all and Profile would render blank
     * forever. The cost is that `observe().first()` is **not** a snapshot read: it races the
     * store and usually loses. Collect continuously (every consumer is a UI flow that should
     * paint immediately and correct itself), and in a test wait for the value you wrote —
     * `first { it.sex != null }`, never a bare `first()`.
     */
    fun observe(): Flow<OwnerProfileValues>

    /** Persists the display name; blank clears it back to unset. */
    suspend fun setName(name: String)

    /** Persists the date of birth; `null` clears it. */
    suspend fun setBirthDate(date: LocalDate?)

    /** Persists the physiological sex; `null` clears it back to "not set". */
    suspend fun setSex(sex: BiologicalSex?)

    /** Persists metric-vs-imperial for every app on this realm. */
    suspend fun setUnits(units: MeasurementSystem)

    /**
     * Fills [OwnerProfileValues.birthDate] / [OwnerProfileValues.sex] from a platform that knows
     * them, **once ever** — see [OwnerPreferences.DemographicsSeeded] for why once and not
     * "whenever unset". Returns true if this call was the one that consumed the one-shot.
     *
     * ⚠ Per field: a value the user has already given is never replaced. A platform that knows
     * only one of the two still consumes the marker — the rest is then the user's to give. Callers
     * that learned *nothing* should not call at all, so a grant obtained later can still seed.
     *
     * [beforeEachWrite] is a caller's write boundary (ADR 0025 §8.6): it runs before EACH of the
     * three preference writes and throws to refuse it. ⚠ The one-shot marker is written LAST, so a
     * refusal half-way leaves the marker unconsumed and the seed can run again in full later — the
     * fields are fill-if-blank, so re-running is harmless.
     */
    suspend fun seedDemographics(
        birthDate: LocalDate?,
        sex: BiologicalSex?,
        beforeEachWrite: suspend () -> Unit,
    ): Boolean
}

class TenshuOwnerProfile(private val client: TenshuClient) : OwnerProfile {

    override fun observe(): Flow<OwnerProfileValues> {
        val prefs = client.preferenceService
        // ⚠ `read()` is silent for a realm that has never held the row (core-preference-service
        // §6.5), so each source seeds its default or a cold device strands its screen on nothing.
        // REALM scope is what makes this the whole story — unlike the DEVICE-scoped readers there
        // is no sync identity to await first, so there is no "seed, then await" ordering hazard.
        return combine(
            prefs.read(OwnerPreferences.Name).onStart { emit(OwnerPreferences.Name.default) },
            prefs.read(OwnerPreferences.BirthDate).onStart { emit(OwnerPreferences.BirthDate.default) },
            prefs.read(OwnerPreferences.Sex).onStart { emit(OwnerPreferences.Sex.default) },
            prefs.read(OwnerPreferences.Units).onStart { emit(OwnerPreferences.Units.default) },
        ) { name, birthDate, sex, units ->
            OwnerProfileValues(
                name = name.takeIf { it.isNotBlank() },
                birthDate = parseBirthDate(birthDate),
                sex = BiologicalSex.fromToken(sex),
                units = MeasurementSystem.fromToken(units),
            )
        }
    }

    override suspend fun setName(name: String) {
        client.preferenceService.set(OwnerPreferences.Name, name.trim())
    }

    override suspend fun setBirthDate(date: LocalDate?) {
        client.preferenceService.set(OwnerPreferences.BirthDate, date?.toString() ?: "")
    }

    override suspend fun setSex(sex: BiologicalSex?) {
        client.preferenceService.set(OwnerPreferences.Sex, sex?.token ?: "")
    }

    override suspend fun setUnits(units: MeasurementSystem) {
        client.preferenceService.set(OwnerPreferences.Units, units.token)
    }

    override suspend fun seedDemographics(
        birthDate: LocalDate?,
        sex: BiologicalSex?,
        beforeEachWrite: suspend () -> Unit,
    ): Boolean {
        val prefs = client.preferenceService
        // ⚠⚠ `readOnce`, never `observe().first()` — this whole method is a decision that must be
        // made once and correctly, and `observe()` seeds defaults on subscribe, so its first
        // emission would report "unset" for values the store actually holds. Seeding over a
        // user's own answer is exactly the failure this method exists to avoid.
        if (prefs.readOnce(OwnerPreferences.DemographicsSeeded)) return false

        // Only fills what is still absent. A user who answered during onboarding — before the
        // platform grant landed — keeps their answer.
        if (birthDate != null && prefs.readOnce(OwnerPreferences.BirthDate).isBlank()) {
            beforeEachWrite()
            setBirthDate(birthDate)
        }
        if (sex != null && prefs.readOnce(OwnerPreferences.Sex).isBlank()) {
            beforeEachWrite()
            setSex(sex)
        }
        // ⚠ Last, not first (ADR 0025 §8.6): a refused field write above must not burn the one-shot.
        beforeEachWrite()
        prefs.set(OwnerPreferences.DemographicsSeeded, true)
        return true
    }
}

/** For previews, tests, and any surface with no proxy to reach. */
object NoopOwnerProfile : OwnerProfile {
    override fun observe(): Flow<OwnerProfileValues> = flowOf(OwnerProfileValues())
    override suspend fun setName(name: String) = Unit
    override suspend fun setBirthDate(date: LocalDate?) = Unit
    override suspend fun setSex(sex: BiologicalSex?) = Unit
    override suspend fun setUnits(units: MeasurementSystem) = Unit
    override suspend fun seedDemographics(
        birthDate: LocalDate?,
        sex: BiologicalSex?,
        beforeEachWrite: suspend () -> Unit,
    ) = false
}

/**
 * A stored date of birth, or `null` when unset or unparseable. A malformed row resolves to "not
 * set" rather than throwing — the same posture the preference adapters take, and the only one that
 * keeps a bad write from bricking every screen that reads the profile.
 */
internal fun parseBirthDate(stored: String): LocalDate? =
    stored.takeIf { it.isNotBlank() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
