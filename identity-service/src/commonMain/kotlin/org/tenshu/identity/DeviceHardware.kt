package org.tenshu.identity

import org.tenshu.core.Composite
import org.tenshu.core.TString
import org.tenshu.core.core.TContext
import org.tenshu.core.core.create
import org.tenshu.core.entity.StringDataIdProviderSchema
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.string
import org.tenshu.core.schema.stringDataId
import org.tenshu.core.toTString
import org.tenshu.core.trait.SyncStrategy
import org.tenshu.core.trait.Syncable

/**
 * What a realm device **is** — its platform-derived hardware model (e.g. "Google Pixel 8",
 * "iPhone") — published by that device about itself and synced to the realm.
 *
 * The other half of `tech-debt.md` *"Device Naming Conflict"*: [Device.name] is the label the user
 * chose (or accepted) at pairing; this is the immutable identity beside it, so the roster can say
 * "Work Laptop · Mac OS X" and two phones the user named alike stay distinguishable.
 *
 * ## Why a row of its own and not a field on [Device]
 *
 * ⚠⚠ **Authorship, not reshape cost.** Only the device itself knows its hardware, but its [Device]
 * row has two writers — the admitter (`ApprovePairingRequest` / `ApprovePairingOffer`) and the
 * device's own self-publish (`PairingKeyInstaller`) — merged **whole-entity last-writer-wins**. A
 * field only one of them can fill is erased whenever the other's write carries the later HLC, and
 * the pairing handshake's clock skew decides which. The admitter could only learn the value over
 * the pairing channel, inside the sealed, signed messages; this row needs neither. One writer per
 * row is the same rule [org.tenshu.sync.common.DeviceSchemaVersion] follows.
 *
 * ⚠ **Display only.** Any realm device can write any row, so this is a claim, as [Device.name] is;
 * nothing may authorise, deduplicate or revoke by it.
 *
 * ⚠ **Retired by `RevokeDevice`** (and so by `ForgetThisDevice`), together with the device's
 * `DeviceSchemaVersion`: the device is the only writer, so nothing else would ever delete it.
 *
 * ⚠ **Absent is ordinary**: a device that has not run since this row existed, or one still locked.
 * Render the name alone, never a placeholder model.
 */
interface DeviceHardware : Composite {

    override val schema: CompositeSchema get() = Schema

    /** The device this row describes — the row's key. */
    val deviceId: TString
        get() = getField(Fields.DEVICE_ID)

    /** `getPlatformHardwareName()` on that device. */
    val hardwareName: TString
        get() = getField(Fields.HARDWARE_NAME)

    companion object {
        const val ID = "tenshu/identity/deviceHardware"

        val DataIdProviderSchema: StringDataIdProviderSchema = stringDataId(
            id = "$ID/dataIdProvider",
            fieldRef = Fields.DEVICE_ID,
        )

        val Schema: CompositeSchema = composite(
            id = ID,
            traits = listOf(DataIdProviderSchema, Syncable(SyncStrategy.Eager)),
            fields = {
                string(Fields.DEVICE_ID)
                string(Fields.HARDWARE_NAME)
            },
        )

        fun create(
            context: TContext,
            deviceId: String,
            hardwareName: String,
        ): DeviceHardware = context.create(
            type = ID,
            fields = listOf(
                deviceId.toTString(context),
                hardwareName.toTString(context),
            ),
        )
    }

    object Fields {
        const val DEVICE_ID = "$ID/deviceId"
        const val HARDWARE_NAME = "$ID/hardwareName"
    }
}

/**
 * The label to show for a device: its user-chosen [name], followed by its [hardwareName] when that
 * is known and says something the name does not. ⭐ The one place the two are joined, so every
 * roster (Control on Compose, Control on iOS, the web) renders the pair the same way.
 */
fun deviceDisplayLabel(name: String, hardwareName: String?): String {
    val hardware = hardwareName?.trim().orEmpty()
    val label = name.trim()
    return when {
        hardware.isEmpty() || hardware.equals(label, ignoreCase = true) -> label
        label.isEmpty() -> hardware
        else -> "$label · $hardware"
    }
}
