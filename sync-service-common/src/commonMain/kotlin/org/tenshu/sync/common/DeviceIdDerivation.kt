package org.tenshu.sync.common

import org.tenshu.core.common.util.sha256

/**
 * A device id is a **function of the device's signing key** — never a free choice
 * (`admission-revocation-names-the-capability.md` §7i, threat model A4 "workstream B" amendment):
 *
 * ```
 * deviceId = lowerhex( SHA-256( LP("tenshu-device-id-v1") ‖ LP(publicKey) ) )[0 : 32]
 * ```
 *
 * with `LP` the [lengthPrefixedSignedMessage] encoding. `publicKey` is the key the relay registers for
 * the id: the device's A4 on its personal realm (and on a shared realm it owns), the per-realm device
 * key on a shared realm it joined as a member (D2 unlinkability is kept — a fresh key gives a fresh,
 * unlinkable id).
 *
 * ⚠⚠ What this buys: the relay can check **every** registration's `(deviceId, key)` pair without
 * trusting anyone, so an id cannot be squatted (registered under another key) and cannot come back
 * under a new key once removed — F2′'s removal ledger then means *"this key never returns"*. Without
 * it, an admitter (A1, any A33 holder, a shared-realm master) chose the id freely, and the delegation
 * bound the two only as far as the admitter was honest.
 *
 * ⚠ The id **changes with the key**. There is no A4 rotation (`tech-debt.md` *"Public-Key Rotation
 * Without Re-Pairing — Deferred"*); a device that ever gets a new key is a new device, admitted again.
 *
 * Why these choices:
 * * **Hex, lower-case** — inside `RegistrationInputRules`' `[A-Za-z0-9_-]`, with no separator a
 *   tokenizer could split on (`HlcStamp`'s `:`, the offer store's `,`) and no case to fold away on a
 *   case-insensitive file system. base64url would be 22 characters, but would carry `-`/`_` and two
 *   cases.
 * * **128 bits** — the attack is a *second preimage* (find a key whose id is a victim's), which costs
 *   2^128 at this length; a collision between two keys the attacker made himself (2^64) gains him
 *   nothing, since both ids are his. 32 characters is also shorter than the 36-character UUID it
 *   replaces, so no stored HLC, row or log line grows.
 * * **Tagged** — a digest of a bare key would equal any other protocol's `SHA-256(key)` (the per-realm
 *   ids used to be exactly that), and the tag versions the scheme.
 *
 * Total over any input: a non-32-byte "key" derives an id too, but no relay route accepts one
 * ([deviceIdMatchesKey] says no, and `RegistrationInputRules.KEY_REFUSAL` comes first anyway). Test fakes
 * that hand out canned keys of other lengths therefore still get a stable id.
 */
fun deviceIdForPublicKey(publicKey: ByteArray): String =
    sha256(lengthPrefixedSignedMessage(DEVICE_ID_TAG, publicKey)).toHexString().substring(0, DEVICE_ID_HEX_CHARS)

/**
 * `true` iff [deviceId] is exactly [deviceIdForPublicKey] of a 32-byte [publicKey] — the check every
 * relay route that registers a device runs, and every client admitter runs before it signs.
 */
fun deviceIdMatchesKey(deviceId: String, publicKey: ByteArray): Boolean =
    publicKey.size == ED25519_PUBLIC_KEY_BYTES && deviceId == deviceIdForPublicKey(publicKey)

/** Domain tag of [deviceIdForPublicKey]. ⚠ Never reuse a retired suffix. */
const val DEVICE_ID_TAG = "tenshu-device-id-v1"

/** Length of a derived device id: 128 bits as lower-case hex. */
const val DEVICE_ID_HEX_CHARS = 32
