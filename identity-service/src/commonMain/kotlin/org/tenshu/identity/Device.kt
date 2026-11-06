package org.tenshu.identity

import org.tenshu.core.Composite
import org.tenshu.core.TBoolean
import org.tenshu.core.TByteArray
import org.tenshu.core.TString
import org.tenshu.core.core.TContext
import org.tenshu.core.core.create
import org.tenshu.core.entity.StringDataIdProviderSchema
import org.tenshu.core.number.TInteger
import org.tenshu.core.number.toTInteger
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.boolean
import org.tenshu.core.schema.byteArray
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.int
import org.tenshu.core.schema.string
import org.tenshu.core.schema.stringDataId
import org.tenshu.core.toTBoolean
import org.tenshu.core.toTByteArray
import org.tenshu.core.toTString
import org.tenshu.core.trait.SyncStrategy
import org.tenshu.core.trait.Syncable

/**
 * Represents one of the user's realm devices — a device that holds the user's master identity
 * and volume keys, and that the user has explicitly added to their realm via pairing.
 */
interface Device : Composite {

    override val schema: CompositeSchema get() = Schema

    val deviceId: TString
        get() = getField(Fields.DEVICE_ID)

    val name: TString
        get() = getField(Fields.NAME)

    val publicKey: TByteArray
        get() = getField(Fields.PUBLIC_KEY)

    val isAuthorized: TBoolean
        get() = getField(Fields.IS_AUTHORIZED)

    /**
     * Realm epoch under which this device was admitted. Load-bearing for T-K-02 — blocks
     * issued by a device whose admission epoch is older than the current realm epoch are
     * rejected on merge after the realm has rotated past a revocation.
     */
    val admittedAtEpoch: TInteger
        get() = getField(Fields.ADMITTED_AT_EPOCH)

    /**
     * `deviceId` of the device that admitted this one — the admission lineage
     * (`delegated-device-admission-authority.md` §5): revoking a device's
     * admission capability conservatively revokes the subtree it vouched for,
     * computed from this field. Empty for the genesis/recovered device (nobody
     * admitted it — it held A1) and for rows written before this field existed.
     * Optional (hence nullable) so pre-P1 rows parse unchanged — treat `null`
     * and empty the same.
     */
    val admittedByDeviceId: TString?
        get() = getOptionalField(Fields.ADMITTED_BY_DEVICE_ID)

    /**
     * The A33 **admission capability public key** minted for this device when it was admitted
     * (`admission-revocation-names-the-capability.md` A′ / F0).
     *
     * ⚠⚠ Captured here because the alternative does not work: a revocation has to name the
     * capability it revokes, and the only other source is the target's own
     * `AdmissionKeyAnnouncement` — which the revoker frequently cannot resolve. Measured on device
     * (§3.1 R1): `a33KeyResolvable=false`. The admitter, by contrast, *mints* this keypair
     * (`PairingAdmission.admit`), so it has the public half for free at the one moment it is
     * guaranteed to exist.
     *
     * Optional, and null for A1-rooted devices that were never admitted by anyone (the realm's
     * first device) — on a realm cut after the protocol-1 reset there are no pre-F0 rows. ⚠ Since
     * F5b there is no epoch rule to fall back to: `RevokeDeviceImplementation` resolves such a
     * device's key from its `AdmissionKeyAnnouncement` instead, and skips the relay strip (loudly)
     * when neither source has one.
     */
    val admissionPublicKey: TByteArray?
        get() = getOptionalField(Fields.ADMISSION_PUBLIC_KEY)

    companion object {
        const val ID = "tenshu/identity/device"

        /**
         * `DataIdProvider` schema for `Device` — keys the entity by its `deviceId` field.
         * Allows `executionContext.saveData(device)` to compute the right `StringDataId`
         * without manual construction at every call site.
         */
        val DataIdProviderSchema: StringDataIdProviderSchema = stringDataId(
            id = "$ID/dataIdProvider",
            fieldRef = Fields.DEVICE_ID
        )

        val Schema: CompositeSchema = composite(
            id = ID,
            traits = listOf(DataIdProviderSchema, Syncable(SyncStrategy.Eager)),
            fields = {
                string(Fields.DEVICE_ID)
                string(Fields.NAME)
                byteArray(Fields.PUBLIC_KEY)
                boolean(Fields.IS_AUTHORIZED)
                int(Fields.ADMITTED_AT_EPOCH)
                string(Fields.ADMITTED_BY_DEVICE_ID, isOptional = true)
                byteArray(Fields.ADMISSION_PUBLIC_KEY, isOptional = true)
            }
        )

        fun create(
            context: TContext,
            deviceId: String,
            name: String,
            publicKey: ByteArray,
            isAuthorized: Boolean = true,
            admittedAtEpoch: Long = 0L,
            admittedByDeviceId: String = "",
            admissionPublicKey: ByteArray = ByteArray(0)
        ): Device = context.create(
            type = ID,
            fields = listOf(
                deviceId.toTString(context),
                name.toTString(context),
                publicKey.toTByteArray(context),
                isAuthorized.toTBoolean(context),
                admittedAtEpoch.toTInteger(context),
                admittedByDeviceId.toTString(context),
                admissionPublicKey.toTByteArray(context)
            )
        )
    }

    object Fields {
        const val DEVICE_ID = "$ID/deviceId"
        const val NAME = "$ID/name"
        const val PUBLIC_KEY = "$ID/publicKey"
        const val IS_AUTHORIZED = "$ID/isAuthorized"
        const val ADMITTED_AT_EPOCH = "$ID/admittedAtEpoch"
        const val ADMITTED_BY_DEVICE_ID = "$ID/admittedByDeviceId"
        const val ADMISSION_PUBLIC_KEY = "$ID/admissionPublicKey"
    }
}
