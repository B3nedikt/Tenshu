package org.tenshu.preference

/**
 * The realm owner's own person — name, date of birth, sex and preferred units.
 *
 * **Platform vocabulary, not one app's data** (`docs/proposals/fit-app.md` §18.2). These describe
 * *the user*, not any app's domain, and the repo already shows what happens when an app claims
 * them: Drive stored the user's own display name as `drive.contact.selfName` because Drive needed
 * it first, its KDoc deferred to a Control `ownerMetadata.displayName` seam that **was never
 * built**, and Timeline hardcodes kilometres because no units preference existed to read. So they
 * live here in `:preference-service` beside [ControlPreferences], on the same reasoning: an
 * always-installed module any consumer can read without depending on the app that happened to ask
 * first.
 *
 * All REALM-scoped — it is the same person on every device.
 *
 * **Body measurements are deliberately absent.** Height and weight are measurements, not settings:
 * they are `HealthSample` rows fed by Health Connect / HealthKit, and a preference row beside them
 * would diverge from the store on the first import (§18.2). Ask for them through the health
 * domain, never here.
 */
object OwnerPreferences {

    /**
     * The user's own display name. Supersedes Drive's `drive.contact.selfName`, which was the same
     * value in an app-private namespace.
     *
     * Default `""` = never set, which callers render as unset rather than as a name.
     */
    val Name = Preference(
        id = "owner.name",
        scope = PreferenceScope.REALM,
        default = "",
        adapter = StringAdapter,
    )

    /**
     * Date of birth as an ISO-8601 `yyyy-MM-dd` string; `""` = not set.
     *
     * A string rather than a richer type because [Preference] is the pure storage contract and the
     * adapter set is scalar by design; [org.tenshu.preference.client.OwnerProfile] is where it
     * becomes a `LocalDate`.
     *
     * ⚠ On Android this can only ever come from the user — **Health Connect has no demographics
     * API at all** (verified against connect-client 1.1.0: no characteristic type, no
     * `dateOfBirth`, no `biologicalSex`). HealthKit does expose both, so iOS can seed them.
     */
    val BirthDate = Preference(
        id = "owner.birthDate",
        scope = PreferenceScope.REALM,
        default = "",
        adapter = StringAdapter,
    )

    /** [BiologicalSex] as its lowercase token; `""` = not set. See that type for why it is closed. */
    val Sex = Preference(
        id = "owner.sex",
        scope = PreferenceScope.REALM,
        default = "",
        adapter = StringAdapter,
    )

    /**
     * Whether the platform has already been asked for the owner's demographics (`fit-app.md`
     * §18.12). Set once the seed has run, **whether or not it found anything**.
     *
     * ⚠⚠ This marker is what makes the seed a *seed*. Without it the only available rule would be
     * "fill any field that is currently unset", which would silently re-fill a value the user had
     * deliberately **cleared** — and FP-3 made "Not set" reachable precisely so that clearing is a
     * real choice. One-shot by construction: the platform gets one chance to answer, and after
     * that the value is the user's.
     */
    val DemographicsSeeded = Preference(
        id = "owner.demographicsSeeded",
        scope = PreferenceScope.REALM,
        default = false,
        adapter = BooleanAdapter,
    )

    /** [MeasurementSystem] as its lowercase token. Metric by default. */
    val Units = Preference(
        id = "owner.units",
        scope = PreferenceScope.REALM,
        default = MeasurementSystem.METRIC.token,
        adapter = StringAdapter,
    )
}

/**
 * Metric vs imperial presentation, realm-wide.
 *
 * Platform-level rather than per-app because the user's answer is the same everywhere: today Fit
 * owns the only toggle while Timeline renders kilometres unconditionally, so an imperial user gets
 * an imperial Fit and a metric Timeline (§18.2).
 */
enum class MeasurementSystem(val token: String) {
    METRIC("metric"),
    IMPERIAL("imperial");

    companion object {
        /** Unknown / malformed tokens resolve to [METRIC] rather than throwing. */
        fun fromToken(token: String?): MeasurementSystem =
            entries.firstOrNull { it.token == token } ?: METRIC
    }
}

/**
 * The user's sex, as a **physiological parameter** — the input to energy-expenditure and
 * resting-rate estimates, which is the only thing any consumer uses it for.
 *
 * **Closed, and shaped by the one import source that exists.** HealthKit's `HKBiologicalSex` is a
 * four-value enum (`NotSet` / `Female` / `Male` / `Other`) and Health Connect has no equivalent at
 * all, so this must round-trip Apple's set losslessly and has nothing else to be compatible with.
 * Free text was the alternative and fails the actual use: a physiological formula has to *branch*,
 * so a free string pushes fuzzy matching into every consumer.
 *
 * Stored as a stable lowercase [token], never an ordinal — the wire stays readable and adding a
 * value later never renumbers an existing one. Absent (`""`) is "not set", which is a real answer:
 * consumers fall back to a sex-neutral formula rather than guessing.
 *
 * ⚠ **This is not gender identity.** Apple separates the two for the same reason we do — ask
 * narrowly, for the stated purpose, and let it be skipped. If identity is ever wanted for
 * presentation it is a *different* field, added then, with no migration owed here.
 *
 * ⚠ **Never a gate.** `fit-app.md` §12.9 deliberately does not sex-gate Cycle; this type does not
 * reopen that.
 */
enum class BiologicalSex(val token: String) {
    FEMALE("female"),
    MALE("male"),
    OTHER("other");

    companion object {
        /** `null` for absent, unknown or malformed — all of which mean "not set". */
        fun fromToken(token: String?): BiologicalSex? =
            entries.firstOrNull { it.token == token }
    }
}
