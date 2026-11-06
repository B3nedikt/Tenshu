package org.tenshu.identity

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

/**
 * When this device last heard from another realm device — **derived locally, never synced.**
 *
 * ## What it is for
 *
 * A [Device] row and the identity it describes have independent lifetimes, in both directions.
 * The direction this row addresses: the identity is destroyed while the row survives, leaving a
 * roster entry that is true about nothing (`tech-debt.md`, *"a `Device` row that outlives the
 * identity it describes"*). Nothing reaps such a row — `Device.create` has five call sites and no
 * counterpart that deletes — so the realm accumulates ghosts that are **indistinguishable from
 * live devices by name alone**, because the name is `getPlatformDeviceName()` and two phones of
 * the same model produce the same string.
 *
 * Diagnosing the 2026-08-09 duplicate needed exactly one fact that the roster did not show: which
 * of the two rows had written anything lately. That fact was already in the store — every
 * `tenshu/core/time/hlc` row carries its **author's** `deviceId` — but only reachable by dumping
 * the container off the device. This row puts it on the screen.
 *
 * ## Why a keyed row and not a field on [Device]
 *
 * Same reasoning as [org.tenshu.sync.common.DeviceSchemaVersion], for the same reasons: appending
 * to [Device] would reshape a load-bearing **synced** type (positional composites ⇒ a peer-breaking
 * change plus a service bump and a migration, HR #9) to carry metadata that is not identity, and it
 * would churn identity rows on every heartbeat. Per-device detail is a new composite id, never an
 * appended field (the E2h precedent).
 *
 * ## Why local-only
 *
 * ⚠⚠ It carries **no [org.tenshu.core.trait.Syncable] trait**, and that is load-bearing rather than
 * an omission. "When did *I* last hear from device X" is a statement about this device's vantage
 * point, not a realm fact: an offline laptop legitimately has a months-old answer while the phone
 * beside it has a fresh one, and neither is wrong. Syncing it would also make every device
 * broadcast a row on every ingest — the shape that produced the 99.8 %-of-realm-traffic
 * reconcile-broadcast loop. Each device derives its own view for free from blocks it is already
 * decrypting.
 *
 * ⚠ **Absence means "never heard from", not "gone".** A device that has been offline since before
 * this row existed has no entry, exactly like one whose identity is dead. That ambiguity is why
 * this drives *display* only and must never drive automatic removal — ADR 0007 already settles the
 * general form of that question in the other direction (an absent device holds the compaction floor
 * rather than being presumed gone).
 */
interface DeviceLastSeen : Composite {

    override val schema: CompositeSchema get() = Schema

    /** The device this observation is about — the row's key. */
    val deviceId: TString
        get() = getField(Fields.DEVICE_ID)

    /**
     * Wall-clock ms of the newest write by that device this replica has ingested — read off the
     * block's own HLC, so it is the **author's** clock, not ours.
     *
     * ⚠ Consequently it inherits the author's clock skew and is not a trustworthy ordering key;
     * it is a human-facing "last seen" and nothing more. HLC ordering stays the ordering
     * authority (`SyncProcessor`), which is unaffected by this row.
     */
    val lastSeenMs: TInteger
        get() = getField(Fields.LAST_SEEN_MS)

    companion object {
        const val ID = "tenshu/identity/deviceLastSeen"

        val DataIdProviderSchema: StringDataIdProviderSchema = stringDataId(
            id = "$ID/dataIdProvider",
            fieldRef = Fields.DEVICE_ID,
        )

        val Schema: CompositeSchema = composite(
            id = ID,
            traits = listOf(DataIdProviderSchema),
            fields = {
                string(Fields.DEVICE_ID)
                int(Fields.LAST_SEEN_MS)
            },
        )

        fun create(
            context: TContext,
            deviceId: String,
            lastSeenMs: Long,
        ): DeviceLastSeen = context.create(
            type = ID,
            fields = listOf(
                deviceId.toTString(context),
                lastSeenMs.toTInteger(context),
            ),
        )
    }

    object Fields {
        const val DEVICE_ID = "$ID/deviceId"
        const val LAST_SEEN_MS = "$ID/lastSeenMs"
    }
}
