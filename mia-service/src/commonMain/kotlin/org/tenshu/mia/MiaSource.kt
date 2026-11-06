package org.tenshu.mia

import org.tenshu.calendar.CalendarService
import org.tenshu.core.file.FileService
import org.tenshu.core.health.HealthService
import org.tenshu.core.place.PlaceService
import org.tenshu.core.timeline.TimelineService
import org.tenshu.inference.InferenceService
import org.tenshu.home.device.HomeDeviceService
import org.tenshu.routine.RoutineService
import org.tenshu.preference.BooleanAdapter
import org.tenshu.preference.Preference
import org.tenshu.preference.PreferenceScope

/**
 * One thing Mia is allowed to read, and the switch that turns it off
 * (`mia-query-layer.md` MQ-D6).
 *
 * ⭐ **The grant applies to Mia itself, not only to future Skills.** `milestones.md` framed
 * "semantic permissions governing exactly what data they can see" as a Skills concern; making
 * the first-party assistant live under it means that when third-party skills arrive post-Rei
 * they slot into a model that already exists and has been exercised — instead of Rei having
 * to invent one for code we do not control. The first consumer of the mechanism is one we can
 * fix.
 *
 * ⚠ **Per service, and the UI must say so plainly.** There is no per-file, per-row or
 * per-property capability in Tenshu (that is the track flagged in `camera-service.md` CD-D8
 * and `ios-first-party-route-1-migration.md` §4). Implying finer control than "Mia may read
 * your Timeline" would be a lie the system cannot enforce.
 *
 * ⚠⚠ **Since MQ-3b a grant is `(service, access)`** — MQ-D6 as amended. [writePreference] is
 * non-null only where a write intent actually exists, because a switch nothing consults is
 * worse than no switch: it implies a capability the system does not have. Calendar is the only
 * writable source today.
 *
 * ⚠ **Write is opt-in and read is not.** Reading was the shipped behaviour and revoking it
 * would break a working assistant; *writing* has never happened before, so it starts off and
 * the first `CreateEvent` is where asking for it belongs. ⚠ And write without read is not
 * offered — every write intent resolves slots against existing data first, so a grant that
 * cannot be satisfied is worse than an absent one.
 */
enum class MiaSource(
    /** The service this source reads. */
    val serviceId: String,
    /**
     * What the chain's `ServiceTypeReference` actually carries, which is **not** always
     * [serviceId].
     *
     * ⚠⚠ `ServiceRo.serviceRelation` prepends the service's own id, but a *service-to-service*
     * relation like `FileService.defaultFileSystem` puts that **relation's** id in the
     * callstack and no bare service id at all. The validator matches on what is in the chain,
     * so the two are recorded separately here rather than being assumed equal — assuming it
     * refused every MQ-2a source on its first run.
     */
    val chainReferenceId: String,
    /** The switch. Realm-scoped: revoking on the phone revokes everywhere. */
    val preference: Preference<Boolean>,
    /** The `read + write` half, or `null` where nothing can write this source. */
    val writePreference: Preference<Boolean>? = null,
) {
    FILES(
        serviceId = FileService.ID,
        chainReferenceId = FileService.Relations.DEFAULT_FILE_SYSTEM,
        preference = grant("files"),
    ),
    TIMELINE(
        serviceId = TimelineService.ID,
        chainReferenceId = TimelineService.ID,
        preference = grant("timeline"),
    ),
    FITNESS(
        serviceId = HealthService.ID,
        chainReferenceId = HealthService.ID,
        preference = grant("fitness"),
    ),
    PLACES(
        serviceId = PlaceService.ID,
        chainReferenceId = PlaceService.ID,
        preference = grant("places"),
    ),
    DEVICES(
        serviceId = HomeDeviceService.ID,
        chainReferenceId = HomeDeviceService.ID,
        preference = grant("devices"),
    ),

    /**
     * The user's routines — read to resolve "run my good morning routine" to one row, and fired
     * through `RunRoutineNow` once they confirm (RE-5).
     *
     * ⚠⚠ **Its own grant, not folded into [DEVICES]**, on the [LABELS] precedent. "Mia may see my
     * lamps" and "Mia may run a routine that turns half the house on" are different permissions,
     * and the second is the one a user is likelier to want off. Reusing the devices grant would
     * have made enabling one enable the other silently.
     *
     * ⚠ Read grant only, like [DEVICES]: firing is a *write*, and what stands between an utterance
     * and the house changing is MQ-D7's confirmation cell showing the resolved routine, not a
     * second preference. A routine names itself; a cell that says "Run Good morning" is a better
     * guard than a switch buried in settings.
     */
    ROUTINES(
        serviceId = RoutineService.ID,
        chainReferenceId = RoutineService.ID,
        preference = grant("routines"),
    ),
    /**
     * What models have said about the user's files (IN-4).
     *
     * ⚠⚠ **Its own grant, not folded into `FILES`.** "Mia may read my file names" and "Mia may read
     * what a model thinks is *in* my photos" are different permissions, and the second is the one a
     * user is more likely to want off. Reusing the files grant would have made enabling one enable
     * the other silently.
     *
     * ⚠ Granted by default like every other read source, because the rows only exist at all if the
     * user separately consented to the *inference* that produced them (IN-D3's two switches). A
     * revoked capability writes no labels, so there is nothing here to read.
     */
    LABELS(
        serviceId = InferenceService.ID,
        chainReferenceId = InferenceService.ID,
        preference = grant("labels"),
    ),
    /**
     * Searching photos by **what they look like** — the IN-4b `photosLike` op (MQ-4).
     *
     * ⚠⚠ **A third grant, not a facet of [LABELS].** A label is something a model already wrote
     * down; this runs a model *on demand* against the user's question and ranks their whole photo
     * library against it. "Mia may read the tags on my photos" and "Mia may search my photos by
     * what is in them" are different enough that folding them together would have made enabling
     * the cheap one enable the expensive one.
     *
     * ⚠⚠ **It shares [LABELS]' `chainReferenceId`, and that is the first time two sources have.**
     * The validator's service allowlist is derived from granted sources, so on its own it can no
     * longer tell these two apart — a chain reaching `InferenceService` passes the service check
     * with *either* grant. What separates them is the **op** allowlist, which `policyFor` now
     * narrows per grant. Adding a second source over one service without that would have made this
     * switch decorative.
     *
     * ⚠ Granted by default like every other read source, and for [LABELS]' reason: there is
     * nothing to search unless the user separately consented to the *inference* that produced the
     * vectors (IN-D3's two switches), and a device with no vectors answers "not indexed".
     */
    VISUAL_SEARCH(
        serviceId = InferenceService.ID,
        chainReferenceId = InferenceService.ID,
        preference = grant("visualSearch"),
    ),
    CALENDAR(
        serviceId = CalendarService.ID,
        chainReferenceId = CalendarService.ID,
        preference = grant("calendar"),
        writePreference = writeGrant("calendar"),
    );

    /** Whether this source can be written at all — i.e. whether the second line exists. */
    val writable: Boolean get() = writePreference != null

    companion object
}

/**
 * ⚠⚠ **Granted by default, deliberately.** Mia shipped able to read all five in MQ-1/MQ-2a,
 * so defaulting to revoked would silently break a working assistant on upgrade and present
 * the user with a broken thing to fix rather than a working thing to restrict. MQ-D6's own
 * wording is that `read` is the default. The control is that revocation is one switch away
 * and takes effect on the next question.
 *
 * ⚠ Top-level rather than in the companion: enum entries are initialised **before** the
 * companion object, so a factory living there is uninitialised at exactly the moment every
 * entry needs it.
 */
private fun grant(key: String) = Preference(
    id = "mia.grant.$key",
    scope = PreferenceScope.REALM,
    default = true,
    adapter = BooleanAdapter,
)

/**
 * ⚠⚠ **Default FALSE, unlike the read grant.** Reading was already happening when the switch
 * appeared, so defaulting it off would have broken a working assistant; writing has never
 * happened, so it starts off and is asked for at the moment it is load-bearing — the
 * `SCHEDULE_EXACT_ALARM` posture from `routines-engine.md` RE-D5, one layer up.
 */
private fun writeGrant(key: String) = Preference(
    id = "mia.grant.$key.write",
    scope = PreferenceScope.REALM,
    default = false,
    adapter = BooleanAdapter,
)
