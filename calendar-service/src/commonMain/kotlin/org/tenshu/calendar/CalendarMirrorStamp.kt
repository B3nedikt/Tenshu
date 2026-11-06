package org.tenshu.calendar

import org.tenshu.core.Composite
import org.tenshu.core.TString
import org.tenshu.core.core.TContext
import org.tenshu.core.core.create
import org.tenshu.core.entity.CompositeDataIdProvider
import org.tenshu.core.entity.Entity
import org.tenshu.core.entity.StringDataIdProviderSchema
import org.tenshu.core.number.TInteger
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.int
import org.tenshu.core.schema.string
import org.tenshu.core.schema.stringDataId
import org.tenshu.core.trait.SyncStrategy
import org.tenshu.core.trait.Syncable

/**
 * When one device last confirmed a [CalendarMirror] matches its source
 * (`calendar-external-sources.md` §11 P2-a).
 *
 * ⚠⚠ **A row of its own, never a field of the mirror.** The stamp used to live on
 * [CalendarMirror], so recording a refresh re-saved the whole mirror row. A bound phone that was
 * offline while another device stopped the sync then wrote that row back with a newer HLC, and a
 * block newer than a tombstone is a recreate (`docs/sync/design.md` §2.6): the stopped mirror came
 * back. This row carries no part of the mirror's lifecycle, so the bound device's routine writes
 * can no longer undo a stop, a reminder switch or a rebind made elsewhere.
 *
 * ⚠ **Keyed by mirror *and* device** ([idFor]). Only the bound device's row is read
 * ([CalendarMirror.deviceId]); a device rebound away keeps writing to its own row until it hears
 * of the rebind, and that cannot touch the new device's freshness.
 *
 * ⚠ Stopping a mirror deletes its stamps, but a stale phone may stamp once more before it hears of
 * the stop. That row is an orphan — nothing reads a stamp without its mirror — and a restarted
 * mirror writes its own device's row first (`startMirror`), so an orphan never claims freshness.
 */
interface CalendarMirrorStamp : Composite {

    override val schema: CompositeSchema get() = Schema

    /** The row key — [idFor] of [mirrorId] and [deviceId]. */
    val stampId: TString get() = getField(Field.STAMP_ID)

    /** The [CalendarMirror.mirrorId] this stamp is for. */
    val mirrorId: TString get() = getField(Field.MIRROR_ID)

    /** The sync id of the device that refreshed. */
    val deviceId: TString get() = getField(Field.DEVICE_ID)

    /**
     * When [deviceId] last confirmed the mirror matches its source; `0` for "not yet, or a setting
     * changed since" — which `MirrorRefresher` reads as "reconcile, whatever the digest says".
     *
     * ⚠ **Coarse by design**: an unchanged refresh only re-stamps once an hour has passed, so a phone
     * whose calendar provider changes constantly does not turn this row into a stream of sync
     * writes. ⚠ A failed refresh leaves it alone, so other devices can see how stale the mirror
     * really is.
     */
    val lastRefreshedEpochMs: TInteger get() = getField(Field.LAST_REFRESHED_EPOCH_MS)

    companion object {
        const val ID = "tenshu/calendar/mirrorStamp"

        val Schema: CompositeSchema = composite(
            id = ID,
            parent = Composite.Schema,
            traits = listOf(
                CalendarMirrorStampDataIdProvider.Schema,
                Syncable(SyncStrategy.Eager),
            ),
            fields = {
                string(Field.STAMP_ID)
                string(Field.MIRROR_ID)
                string(Field.DEVICE_ID)
                int(Field.LAST_REFRESHED_EPOCH_MS)
            }
        )

        fun create(
            context: TContext,
            mirrorId: String,
            deviceId: String,
            lastRefreshedEpochMs: Long,
        ): CalendarMirrorStamp = context.create(
            type = ID,
            fields = listOf(idFor(mirrorId, deviceId), mirrorId, deviceId, lastRefreshedEpochMs),
        )

        /** The row key: one stamp per mirror per device. */
        fun idFor(mirrorId: String, deviceId: String): String = "$mirrorId@$deviceId"
    }

    object Field {
        const val STAMP_ID = "$ID/stampId"
        const val MIRROR_ID = "$ID/mirrorId"
        const val DEVICE_ID = "$ID/deviceId"
        const val LAST_REFRESHED_EPOCH_MS = "$ID/lastRefreshedEpochMs"
    }
}

interface CalendarMirrorStampDataIdProvider : CompositeDataIdProvider<CalendarMirrorStamp> {

    override val schema: StringDataIdProviderSchema get() = Schema

    companion object {
        const val ID = "tenshu/calendar/mirrorStamp/dataIdProvider"

        val Schema: StringDataIdProviderSchema = stringDataId(
            id = ID,
            fieldRef = CalendarMirrorStamp.Field.STAMP_ID,
        )
    }
}

val Entity<out CalendarMirrorStamp>.stampMirrorId: TString
    get() = getFieldValue(CalendarMirrorStamp.Field.MIRROR_ID)

val Entity<out CalendarMirrorStamp>.stampDeviceId: TString
    get() = getFieldValue(CalendarMirrorStamp.Field.DEVICE_ID)

val Entity<out CalendarMirrorStamp>.stampedEpochMs: TInteger
    get() = getFieldValue(CalendarMirrorStamp.Field.LAST_REFRESHED_EPOCH_MS)
