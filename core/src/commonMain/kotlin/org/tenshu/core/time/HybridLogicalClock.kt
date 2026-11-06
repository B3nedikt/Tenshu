package org.tenshu.core.time

import org.tenshu.core.Composite
import org.tenshu.core.TString
import org.tenshu.core.core.TContext
import org.tenshu.core.core.create
import org.tenshu.core.number.TInteger
import org.tenshu.core.number.toTInteger
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.int
import org.tenshu.core.schema.string
import org.tenshu.core.toTString

/**
 * Hybrid Logical Clock (HLC) for event ordering in distributed systems.
 *
 * HLCs combine physical time (wall clock) with a logical counter to ensure a strict
 * partial ordering of events across multiple devices, even in the presence of clock skew.
 */
interface HybridLogicalClock : Composite {

    override val schema: CompositeSchema get() = Schema

    /**
     * Physical time component (wall clock time in milliseconds).
     */
    val physicalTime: TInteger
        get() = getField(Fields.PHYSICAL_TIME)

    /**
     * Logical counter to break ties for events with the same physical time.
     */
    val logicalCounter: TInteger
        get() = getField(Fields.LOGICAL_COUNTER)

    /**
     * Unique identifier for the device that generated this clock.
     */
    val deviceId: TString
        get() = getField(Fields.DEVICE_ID)

    companion object {
        const val ID = "tenshu/core/time/hlc"

        val Schema: CompositeSchema = composite(
            id = ID,
            fields = {
                int(Fields.PHYSICAL_TIME)
                int(Fields.LOGICAL_COUNTER)
                string(Fields.DEVICE_ID)
            }
        )

        /**
         * Creates a new [HybridLogicalClock] instance.
         */
        fun create(
            context: TContext,
            physicalTime: Long,
            logicalCounter: Long,
            deviceId: String
        ): HybridLogicalClock = context.create(
            type = ID,
            fields = listOf(
                physicalTime.toTInteger(context),
                logicalCounter.toTInteger(context),
                deviceId.toTString(context)
            )
        )
    }

    object Fields {
        const val PHYSICAL_TIME = "$ID/physicalTime"
        const val LOGICAL_COUNTER = "$ID/logicalCounter"
        const val DEVICE_ID = "$ID/deviceId"
    }
}
