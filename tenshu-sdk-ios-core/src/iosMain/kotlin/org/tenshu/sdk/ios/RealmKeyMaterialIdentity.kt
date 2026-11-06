package org.tenshu.sdk.ios

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import org.tenshu.core.common.epc.RealmKeyMaterial
import org.tenshu.sync.common.deviceIdForPublicKey

/**
 * The device id the handed-over A4 key implies (workstream B: `deviceIdForPublicKey`), or null when the
 * blob does not hold a decodable public half.
 *
 * ⚠⚠ Use this, never [RealmKeyMaterial.deviceId], wherever the id is paired with the key. The bridge
 * writes its Keychain items one at a time, so a partial publish can leave an older id beside a newer
 * key — and the relay refuses any id that is not its key's derivation.
 * ⚠ Null, never a throw: this runs inside route-1 assembly, where an exception is an `abort()` on every
 * launch of every co-resident app.
 */
@OptIn(ExperimentalEncodingApi::class)
internal val RealmKeyMaterial.keyDerivedDeviceIdOrNull: String?
    get() {
        val publicHalf = deviceSigningBlob.decodeToString().split("|").takeIf { it.size == 2 }?.get(0)
            ?: return null
        val publicKey = try {
            Base64.decode(publicHalf)
        } catch (_: IllegalArgumentException) {
            return null
        }
        return deviceIdForPublicKey(publicKey)
    }

/** [keyDerivedDeviceIdOrNull] for callers that have already checked it (the relay probes). */
internal val RealmKeyMaterial.keyDerivedDeviceId: String
    get() = keyDerivedDeviceIdOrNull ?: error("malformed A4 blob in the handed-over realm key material")
