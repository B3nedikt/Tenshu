package org.tenshu.routine

import org.tenshu.core.Composite
import org.tenshu.core.TString
import org.tenshu.core.core.TContext
import org.tenshu.core.core.create
import org.tenshu.core.entity.StringDataIdProviderSchema
import org.tenshu.core.number.TInteger
import org.tenshu.core.number.toTInteger
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.int
import org.tenshu.core.schema.string
import org.tenshu.core.schema.stringDataId
import org.tenshu.core.toTString
import org.tenshu.core.trait.RealmData
import org.tenshu.core.trait.SyncStrategy
import org.tenshu.core.trait.Syncable

/**
 * Whether one location-capable device is at home (`routines-engine.md` RE-D7 /
 * RE-4), authored by that device off its own geofence plumbing.
 *
 * ⚠⚠ **This is a new sensitive artifact, and the threat model says so
 * (T-RE-04).** Per-device inside/outside is a coarse location signal about
 * household members — E2E, but genuinely new for a household that never enabled
 * Timeline capture. It deserves its own consent surface rather than arriving as
 * a side effect of writing a routine.
 *
 * ⭐ **Deliberately coarse.** The row carries *inside/outside*, never
 * coordinates, never a place name. A routine only ever needs the boolean, and a
 * synced trail of exact positions would be a far larger disclosure for no
 * additional capability.
 *
 * ⚠ **Staleness is a first-class outcome, not an inference.** A device that
 * stopped reporting is `UNKNOWN` after [STALE_WINDOW_MS], and an unknown fails
 * the condition **closed** — "arm the alarm when nobody's home" firing because
 * someone's phone battery died is the worst failure mode of the whole engine.
 *
 * ⚠ Rows exist only for devices that have *ever* reported, which is the honest
 * definition of "location-capable" available without a new capability field on
 * `Device`. A device with location turned off is therefore not part of the
 * calculation at all — see `PresenceResolver` for what that means for the copy.
 */
interface PresenceState : Composite {

    override val schema: CompositeSchema get() = Schema

    /** The realm `Device.deviceId` this row describes — the row key. */
    val deviceId: TString
        get() = getField(Fields.DEVICE_ID)

    /** [INSIDE] | [OUTSIDE] | [UNKNOWN]. */
    val state: TString
        get() = getField(Fields.STATE)

    /** When the authoring device last established this; drives the staleness rule. */
    val freshAsOfEpochMs: TInteger
        get() = getField(Fields.FRESH_AS_OF_EPOCH_MS)

    companion object {
        const val ID = "tenshu/routine/presenceState"

        const val INSIDE = "inside"
        const val OUTSIDE = "outside"

        /** Explicitly unknown — the device is reporting that it does not know. */
        const val UNKNOWN = "unknown"

        /**
         * Beyond this, a row is treated as [UNKNOWN] regardless of what it says.
         *
         * ⚠ Two hours rather than minutes: a geofence crossing is an *event*, so
         * a stationary phone legitimately reports nothing for a long time. Too
         * tight a window would make presence permanently unknown; too loose and
         * a phone that died this morning still counts as "out" tonight.
         */
        const val STALE_WINDOW_MS = 2L * 60 * 60 * 1000

        /** Keys the row by [Fields.DEVICE_ID]. */
        val DataIdProviderSchema: StringDataIdProviderSchema = stringDataId(
            id = "$ID/dataIdProvider",
            fieldRef = Fields.DEVICE_ID
        )

        val Schema: CompositeSchema = composite(
            id = ID,
            traits = listOf(
                DataIdProviderSchema,
                Syncable(SyncStrategy.Eager),
                RealmData.Schema,
            ),
            fields = {
                string(Fields.DEVICE_ID)
                string(Fields.STATE)
                int(Fields.FRESH_AS_OF_EPOCH_MS)
            }
        )

        fun create(
            context: TContext,
            deviceId: String,
            state: String,
            freshAsOfEpochMs: Long,
        ): PresenceState = context.create(
            type = ID,
            fields = listOf(
                deviceId.toTString(context),
                state.toTString(context),
                freshAsOfEpochMs.toTInteger(context),
            )
        )
    }

    object Fields {
        const val DEVICE_ID = "$ID/deviceId"
        const val STATE = "$ID/state"
        const val FRESH_AS_OF_EPOCH_MS = "$ID/freshAsOfEpochMs"
    }
}
