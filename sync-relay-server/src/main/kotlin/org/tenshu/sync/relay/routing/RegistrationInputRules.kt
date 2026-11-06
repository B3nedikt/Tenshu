package org.tenshu.sync.relay.routing

import org.tenshu.core.common.RelayRefusal
import org.tenshu.sync.common.DEVICE_ID_HEX_CHARS
import org.tenshu.sync.common.DEVICE_ID_TAG
import org.tenshu.sync.common.ED25519_PUBLIC_KEY_BYTES
import org.tenshu.sync.common.deviceIdMatchesKey

/**
 * Input rules for every relay route that **registers** a device key or names one in a signed record
 * (B1, `admission-revocation-names-the-capability.md` §7d.3).
 *
 * ⚠⚠ Signature checks alone are not enough on these routes: before the signed messages were tagged
 * and length-prefixed, a valid signature over *some other* message could be re-presented by choosing
 * a junk key and a `deviceId` such as `"\u0000\u0000\u0000\b"`. The tags close that, and these rules
 * are the second wall — a device id is a printable identifier, and an Ed25519 key is exactly 32
 * bytes, so neither can be stretched to make one message's bytes line up with another's.
 */
internal object RegistrationInputRules {

    /**
     * 1–[MAX_PAIRING_REQUEST_FIELD_LENGTH] characters from `[A-Za-z0-9_-]`. Since workstream B every
     * real id is `deviceIdForPublicKey` (32 lower-case hex), which [isKeyDerivedDeviceId] checks on
     * every registering route; this looser rule still guards the routes that only *name* an id
     * (`/admission/revoke`, a chain's links). Pinned by `RegistrationInputRulesTest`. ⚠ A new id
     * scheme must stay inside this alphabet — the offer store also joins chain ids with `,`.
     */
    fun isWellFormedDeviceId(deviceId: String): Boolean =
        deviceId.isNotEmpty() &&
            deviceId.length <= MAX_PAIRING_REQUEST_FIELD_LENGTH &&
            deviceId.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '_' || it == '-' }

    /**
     * ⚠⚠ Workstream B: [deviceId] is exactly the id derived from [publicKey] — the key this route is
     * about to register for it. Run on every route that registers a device, **before** the admission /
     * master signature is verified, the realm looked up, any challenge spent or anything written
     * (caller authentication may precede it), so a squatted or re-used id costs the relay nothing.
     * Without it, whoever signed the admission chose the id: an id could be registered under a second
     * key, and a removed id could return under a new one.
     */
    fun isKeyDerivedDeviceId(deviceId: String, publicKey: ByteArray): Boolean = deviceIdMatchesKey(deviceId, publicKey)

    /** The `400` body for a failed [isKeyDerivedDeviceId]. */
    val DEVICE_ID_NOT_KEY_DERIVED_REFUSAL: String = RelayRefusal.body(
        RelayRefusal.DEVICE_ID_NOT_KEY_DERIVED,
        "deviceId must be deviceIdForPublicKey of the device key it is registered with — " +
            "lowerhex(SHA-256(LP(\"$DEVICE_ID_TAG\") ‖ LP(key)))[0:$DEVICE_ID_HEX_CHARS]",
    )

    /** Exactly an Ed25519 public key's length. */
    fun isEd25519PublicKey(key: ByteArray): Boolean = key.size == ED25519_PUBLIC_KEY_BYTES

    const val DEVICE_ID_REFUSAL =
        "deviceId must be 1–$MAX_PAIRING_REQUEST_FIELD_LENGTH characters from [A-Za-z0-9_-]"

    const val KEY_REFUSAL = "public keys must be exactly $ED25519_PUBLIC_KEY_BYTES bytes"
}
