package org.tenshu.identity

import org.tenshu.core.Composite
import org.tenshu.core.TByteArray
import org.tenshu.core.TString
import org.tenshu.core.core.TContext
import org.tenshu.core.core.create
import org.tenshu.core.entity.StringDataIdProviderSchema
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.byteArray
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.string
import org.tenshu.core.schema.stringDataId
import org.tenshu.core.toTByteArray
import org.tenshu.core.toTString
import org.tenshu.core.trait.SyncStrategy
import org.tenshu.core.trait.Syncable

/**
 * One **signed device record** — a removal or a re-admission — synced in the realm it is about
 * (`sync-entity-identity.md` §5b R6; verified by `DeviceRecordVerifier` in `:sync-service-common`).
 *
 * The set of these rows is the realm's authority for **"is this device removed?"** on every client:
 * the device list, and anything that treats the roster as membership. ⚠ Not for relay access — the
 * relay's own registry decides that, and the removal's identical signed bytes went to it as
 * `POST /sync/device-access/revoke`.
 *
 * ⚠⚠ **Append-only by its key**: [key] includes the kind and the authored stamp, so each record is its
 * own row and nothing ever overwrites one; readers take the newest valid record per device. A row
 * that fails verification is ignored, never acted on — anyone holding the realm key can write a row,
 * and only the signature makes it a record.
 *
 * Self-certifying: [signerPublicKey] is checked against the realm id (A1) or the device id (self), each
 * of which is the hash of its key, so no stored key is needed to verify a record.
 */
interface DeviceRecord : Composite {

    override val schema: CompositeSchema get() = Schema

    /** `"$kind|$deviceId|$authoredAt"` — this row's id. */
    val key: TString get() = getField(Fields.KEY)

    /** [Kind.REMOVAL] or [Kind.READMISSION]. */
    val kind: TString get() = getField(Fields.KIND)

    val deviceId: TString get() = getField(Fields.DEVICE_ID)

    /** `"master"` (A1) or `"self"` — `DeviceAccessRevocation.Authority.wire`. */
    val authority: TString get() = getField(Fields.AUTHORITY)

    /** `HlcStamp.encode()` of the authored stamp. */
    val authoredAt: TString get() = getField(Fields.AUTHORED_AT)

    /** The public key [signature] verifies under: the realm master's, or the device's own. */
    val signerPublicKey: TByteArray get() = getField(Fields.SIGNER_PUBLIC_KEY)

    val signature: TByteArray get() = getField(Fields.SIGNATURE)

    object Kind {
        const val REMOVAL = "removal"
        const val READMISSION = "readmission"
    }

    companion object {
        const val ID = "tenshu/identity/deviceRecord"

        val DataIdProviderSchema: StringDataIdProviderSchema = stringDataId(
            id = "$ID/dataIdProvider",
            fieldRef = Fields.KEY,
        )

        val Schema: CompositeSchema = composite(
            id = ID,
            traits = listOf(DataIdProviderSchema, Syncable(SyncStrategy.Eager)),
            fields = {
                string(Fields.KEY)
                string(Fields.KIND)
                string(Fields.DEVICE_ID)
                string(Fields.AUTHORITY)
                string(Fields.AUTHORED_AT)
                byteArray(Fields.SIGNER_PUBLIC_KEY)
                byteArray(Fields.SIGNATURE)
            },
        )

        fun keyOf(kind: String, deviceId: String, authoredAt: String): String = "$kind|$deviceId|$authoredAt"

        fun create(
            context: TContext,
            kind: String,
            deviceId: String,
            authority: String,
            authoredAt: String,
            signerPublicKey: ByteArray,
            signature: ByteArray,
        ): DeviceRecord = context.create(
            type = ID,
            fields = listOf(
                keyOf(kind, deviceId, authoredAt).toTString(context),
                kind.toTString(context),
                deviceId.toTString(context),
                authority.toTString(context),
                authoredAt.toTString(context),
                signerPublicKey.toTByteArray(context),
                signature.toTByteArray(context),
            ),
        )
    }

    object Fields {
        const val KEY = "$ID/key"
        const val KIND = "$ID/kind"
        const val DEVICE_ID = "$ID/deviceId"
        const val AUTHORITY = "$ID/authority"
        const val AUTHORED_AT = "$ID/authoredAt"
        const val SIGNER_PUBLIC_KEY = "$ID/signerPublicKey"
        const val SIGNATURE = "$ID/signature"
    }
}
