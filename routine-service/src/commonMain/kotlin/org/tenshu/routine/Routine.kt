package org.tenshu.routine

import org.tenshu.core.Composite
import org.tenshu.core.Data
import org.tenshu.core.TBoolean
import org.tenshu.core.TString
import org.tenshu.core.core.TContext
import org.tenshu.core.core.create
import org.tenshu.core.entity.StringDataIdProviderSchema
import org.tenshu.core.number.TInteger
import org.tenshu.core.number.toTInteger
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.boolean
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.genericData
import org.tenshu.core.schema.int
import org.tenshu.core.schema.string
import org.tenshu.core.schema.stringDataId
import org.tenshu.core.toTBoolean
import org.tenshu.core.toTString
import org.tenshu.core.trait.RealmData
import org.tenshu.core.trait.SyncStrategy
import org.tenshu.core.trait.Syncable

/**
 * One automation, as realm data (`routines-engine.md` RE-D10). Every proxy in
 * the realm holds every routine — which is the whole design problem the engine
 * solves (RE-D1/RE-D2), not an accident.
 *
 * ⭐ **The program — trigger + conditions + actions — rides as ONE bounded,
 * versioned string ([programJson] + [programVersion]), not as nested composite
 * lists.** Three independently sufficient reasons, per RE-D10:
 *
 *  1. ⚠⚠ Positional composites make *every* future trigger or action kind a
 *     peer-breaking change, and the vocabulary is **guaranteed** to grow —
 *     `home-and-mia-apps.md` D3 requires the action vocabulary to stay
 *     extensible so post-Rei Skills can contribute actions. A shape that turns
 *     its own roadmap into a format bump each time is the wrong shape.
 *  2. Native list fields on a keyed row read back empty over the chain (the
 *     HD-1 `capabilityIds` finding), and `TList` members need a
 *     `DataIdProvider` (the E2d-1 finding).
 *  3. `genericData` stores inline primitives and composite refs, **not
 *     arrays**, so it does not solve this either.
 *
 * ⚠ The cost, stated plainly: the program is opaque to the chain DSL, so
 * "every routine targeting device X" cannot be a server-side query. Accepted —
 * a household has tens of routines, so the engine loads them all and filters in
 * memory. (`routines-engine.md` OQ-1 revisits this once schema-evolution E2 has
 * carried a real production migration.)
 *
 * ⚠⚠ [timeZoneId] / [homeLatE5] / [homeLonE5] belong to the **routine**, never
 * to the executing device (RE-D3). A laptop travelling in another timezone must
 * not compute a different instant for "22:30", and a desktop 400 km north must
 * not compute a different sunset. This is the single most likely way to build a
 * subtly broken engine, which is why the coordinates live here.
 *
 * ⚠ [state] is deliberately separate from [enabled]: engine-driven suspension
 * (RE-D9) must **never** be written as `enabled = false`, which would be
 * indistinguishable from the user's own toggle, silently destroy their intent,
 * and leave them re-enabling a routine with no idea why it stopped.
 */
interface Routine : Composite {

    override val schema: CompositeSchema get() = Schema

    /** Unique id of this routine — the row key. */
    val routineId: TString
        get() = getField(Fields.ROUTINE_ID)

    /** User-facing name ("Porch light at sunset"). */
    val name: TString
        get() = getField(Fields.NAME)

    /** The user's own on/off switch. Never written by the engine (class KDoc). */
    val enabled: TBoolean
        get() = getField(Fields.ENABLED)

    /** [STATE_ACTIVE] | [STATE_SUSPENDED] — the engine's third state (RE-D9). */
    val state: TString
        get() = getField(Fields.STATE)

    /** Why the engine suspended this routine; `""` when [state] is active. */
    val suspendedReason: TString
        get() = getField(Fields.SUSPENDED_REASON)

    /** IANA zone id the schedule is expressed in — the routine's, not the device's. */
    val timeZoneId: TString
        get() = getField(Fields.TIME_ZONE_ID)

    /** Latitude ×10⁵ for sun maths; [NO_COORDINATE] when the routine needs none. */
    val homeLatE5: TInteger
        get() = getField(Fields.HOME_LAT_E5)

    /** Longitude ×10⁵ for sun maths; [NO_COORDINATE] when the routine needs none. */
    val homeLonE5: TInteger
        get() = getField(Fields.HOME_LON_E5)

    /** Realm device that authored this routine (audit + the T-RE-02 suspend rule). */
    val authorDeviceId: TString
        get() = getField(Fields.AUTHOR_DEVICE_ID)

    val createdAtEpochMs: TInteger
        get() = getField(Fields.CREATED_AT_EPOCH_MS)

    val updatedAtEpochMs: TInteger
        get() = getField(Fields.UPDATED_AT_EPOCH_MS)

    /** The program (class KDoc). Bounded by [MAX_PROGRAM_BYTES]. */
    val programJson: TString
        get() = getField(Fields.PROGRAM_JSON)

    /**
     * Vocabulary version of [programJson]. ⭐ A device whose engine does not
     * understand this version must neither execute the routine **nor claim the
     * executor slot for it** — it falls through to the RE-D2 grace fallback on a
     * device that does. Without this fence, a routine authored on an updated
     * phone gets silently mangled by an older desktop, which is the HR#9
     * silent-garbling class re-created inside a single row.
     *
     * ⚠ This is *not* a re-implementation of schema-evolution E2: E2's epoch
     * fences *readability* of realm data; this fences *execution eligibility*
     * for one row, and the vocabulary moves far more often than the format does.
     */
    val programVersion: TInteger
        get() = getField(Fields.PROGRAM_VERSION)

    /** Forward-compatibility escape hatch (the preference-service precedent). */
    val extras: Data?
        get() = getOptionalField(Fields.EXTRAS)

    companion object {
        const val ID = "tenshu/routine/routine"

        /** [state]: the engine may run this routine. */
        const val STATE_ACTIVE = "active"

        /**
         * [state]: the engine suspended it (RE-D9 runaway, or the T-RE-02
         * author-revocation rule). Distinct from `enabled = false` — see class KDoc.
         */
        const val STATE_SUSPENDED = "suspended"

        /** [homeLatE5]/[homeLonE5] sentinel — chosen over `isOptional` per HD-1. */
        const val NO_COORDINATE = -1_000_000_000L

        /**
         * Bound on [programJson]. A routine is authored by a human through a
         * form; anything larger is a bug or an attempt to use the row as
         * storage, and an unbounded string on a `Syncable(Eager)` row is a sync
         * hazard.
         */
        const val MAX_PROGRAM_BYTES = 16 * 1024

        /** Keys the row by [Fields.ROUTINE_ID]. */
        val DataIdProviderSchema: StringDataIdProviderSchema = stringDataId(
            id = "$ID/dataIdProvider",
            fieldRef = Fields.ROUTINE_ID
        )

        val Schema: CompositeSchema = composite(
            id = ID,
            traits = listOf(
                DataIdProviderSchema,
                Syncable(SyncStrategy.Eager),
                RealmData.Schema,
            ),
            fields = {
                string(Fields.ROUTINE_ID)
                string(Fields.NAME)
                boolean(Fields.ENABLED)
                string(Fields.STATE)
                string(Fields.SUSPENDED_REASON)
                string(Fields.TIME_ZONE_ID)
                int(Fields.HOME_LAT_E5)
                int(Fields.HOME_LON_E5)
                string(Fields.AUTHOR_DEVICE_ID)
                int(Fields.CREATED_AT_EPOCH_MS)
                int(Fields.UPDATED_AT_EPOCH_MS)
                string(Fields.PROGRAM_JSON)
                int(Fields.PROGRAM_VERSION)
                genericData(Fields.EXTRAS, isOptional = true)
            }
        )

        fun create(
            context: TContext,
            routineId: String,
            name: String,
            enabled: Boolean = true,
            state: String = STATE_ACTIVE,
            suspendedReason: String = "",
            timeZoneId: String,
            homeLatE5: Long = NO_COORDINATE,
            homeLonE5: Long = NO_COORDINATE,
            authorDeviceId: String = "",
            createdAtEpochMs: Long,
            updatedAtEpochMs: Long,
            programJson: String,
            programVersion: Long,
            extras: Data? = null,
        ): Routine = context.create(
            type = ID,
            fields = listOf(
                routineId.toTString(context),
                name.toTString(context),
                enabled.toTBoolean(context),
                state.toTString(context),
                suspendedReason.toTString(context),
                timeZoneId.toTString(context),
                homeLatE5.toTInteger(context),
                homeLonE5.toTInteger(context),
                authorDeviceId.toTString(context),
                createdAtEpochMs.toTInteger(context),
                updatedAtEpochMs.toTInteger(context),
                programJson.toTString(context),
                programVersion.toTInteger(context),
                extras,
            )
        )
    }

    object Fields {
        const val ROUTINE_ID = "$ID/routineId"
        const val NAME = "$ID/name"
        const val ENABLED = "$ID/enabled"
        const val STATE = "$ID/state"
        const val SUSPENDED_REASON = "$ID/suspendedReason"
        const val TIME_ZONE_ID = "$ID/timeZoneId"
        const val HOME_LAT_E5 = "$ID/homeLatE5"
        const val HOME_LON_E5 = "$ID/homeLonE5"
        const val AUTHOR_DEVICE_ID = "$ID/authorDeviceId"
        const val CREATED_AT_EPOCH_MS = "$ID/createdAtEpochMs"
        const val UPDATED_AT_EPOCH_MS = "$ID/updatedAtEpochMs"
        const val PROGRAM_JSON = "$ID/programJson"
        const val PROGRAM_VERSION = "$ID/programVersion"
        const val EXTRAS = "$ID/extras"
    }
}
