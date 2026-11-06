package org.tenshu.core.time

import org.tenshu.core.number.TInteger
import org.tenshu.core.schema.ValueSchema
import org.tenshu.core.schema.value

/**
 * A point in time. Wire encoding is a 64-bit integer.
 *
 * **Unit: milliseconds since the Unix epoch (UTC).** This matches
 * [kotlin.time.Instant.toEpochMilliseconds] and the input unit of every
 * platform-formatter the codebase uses. Sub-millisecond precision is not stored;
 * the HLC's logical counter is the canonical tie-breaker for events that occur
 * within the same millisecond on the same device.
 */
interface Instant : TInteger {

    override val schema: ValueSchema
        get() = Schema

    fun toEpochMilliseconds(): Long

    companion object {
        const val ID = "tenshu/time/instant"

        val Schema: ValueSchema = value(
            id = ID,
            parent = TInteger.Schema
        )
    }
}

/**
 * A standard Unix epoch instant. Wire-compatible with the parent [Instant] schema;
 * the `UnixInstant` distinction is a marker for "interpret this as wall-clock time"
 * vs. e.g. an HLC physical-time field that happens to share the integer encoding.
 */
interface UnixInstant : Instant {

    override val schema: ValueSchema
        get() = Schema

    companion object {
        const val ID = "tenshu/time/instant/unix"

        val Schema: ValueSchema = value(
            id = ID,
            parent = Instant.Schema
        )
    }
}
