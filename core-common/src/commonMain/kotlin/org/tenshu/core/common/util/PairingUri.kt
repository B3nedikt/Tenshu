package org.tenshu.core.common.util

/**
 * The canonical device-pairing QR/paste URIs and their role-host dispatch
 * (`docs/proposals/sync-pairing-inverted-direction.md` §3.2 / §5.2,
 * `docs/proposals/sync-pairing-flow-improvements.md` §8).
 *
 * Three sibling forms under the house `tenshu://` scheme, discriminated by a
 * **role host** — different camera geometries, not successor versions:
 *
 *  - `tenshu://join?o=<ownerId>&d=<base32(hostDeviceId)>&k=<base32(PublicKeyA)>&s=<base32(pairingSecret)>`
 *    — the existing realm device displays, the new device scans (the join flow).
 *  - `tenshu://offer?k=<base32(PublicKeyB)>&s=<base32(pairingSecret)>&n=<percent-encoded deviceName>`
 *    — the new device displays, an existing realm device scans (the offer flow).
 *    `o`/`d` are deliberately absent: the offerer doesn't know them.
 *  - `tenshu://hub?d=<base32(hubDeviceId)>&g=<base32(staticVerifyKey)>&s=<base32(pairingSecret)>`
 *    — a headless device's **printed label**, an existing realm device scans (the
 *    hub flow, hub proposal §11). Static material only; the dynamic ephemeral key
 *    rides the relay, signed by the printed key.
 *
 * An unrecognised role host parses to [ParsedPairingUri.UnknownRole] — the UI says "update your
 * app". Never a partial parse.
 * (`tenshu://epc/pair#…` is a different path+fragment form owned by iOS EPC
 * pairing; its `epc` host lands in [ParsedPairingUri.UnknownRole] here, which
 * is correct for this scanner — an EPC code is never a device-pairing code.)
 *
 * The crypto info strings (`tenshu/pairing/{sas,keys}/v1`) carry a separate
 * *crypto* version and are unrelated to the role token.
 */
sealed interface ParsedPairingUri {
    /** `tenshu://join?…` — this device is the new one; the scanned code came from an existing realm device. */
    class Join(
        val ownerId: String,
        val hostDeviceId: String,
        val hostPublicKey: ByteArray,
        val pairingSecret: ByteArray,
    ) : ParsedPairingUri

    /**
     * `tenshu://offer?…` — this device is an existing realm device; the scanned code came
     * from a new device. Carries the offerer's ephemeral X25519 key ([offererPublicKey], for
     * ECDH/SAS) **and** its persistent Ed25519 device signing key ([offererSigningKey]) +
     * [offererDeviceId] — the responder needs the latter two to sign a master/A33 delegation
     * over the offerer's device key so the relay can register it in `KnownDevicesStore` (the
     * offer flow's analogue of the join flow's `PairingRequest.signingPublicKey`; without it
     * the offerer's first JWT-authed sync would 401). As-built delta vs proposal §3.2 —
     * awaiting ratification (`sync-pairing-inverted-direction.md` §6 P3/P4).
     */
    class Offer(
        val offererPublicKey: ByteArray,
        val offererSigningKey: ByteArray,
        val offererDeviceId: String,
        val pairingSecret: ByteArray,
        val deviceName: String,
    ) : ParsedPairingUri

    /**
     * `tenshu://hub?…` — the **printed static-key hub label** (hub proposal §11, H2): the
     * scanned code came off a headless device's physical label, not a screen. Carries the
     * hub's provisioned identity — its [hubDeviceId], its long-term Ed25519 **verify** key
     * ([hubVerifyKey], the anti-MITM anchor: the phone verifies the hub's relay-posted
     * signed ephemeral offer against it), and the long-lived 128-bit [pairingSecret] whose
     * SHA-256 is the `/sync/pairing/hub-offer` rendezvous address. Unlike [Offer] there is
     * no ephemeral key here — the label is static; the ephemeral key arrives over the
     * relay, authenticated by the printed key.
     */
    class Hub(
        val hubDeviceId: String,
        val hubVerifyKey: ByteArray,
        val pairingSecret: ByteArray,
    ) : ParsedPairingUri

    /** A `tenshu://` role host this client doesn't know — the *client* is too old. UI: "update your app". */
    data object UnknownRole : ParsedPairingUri

    /** Not a Tenshu pairing URI, or a known role host with missing/undecodable parameters. */
    data object Malformed : ParsedPairingUri
}

object PairingUri {
    const val SCHEME = "tenshu://"
    const val JOIN_HOST = "join"
    const val OFFER_HOST = "offer"
    const val HUB_HOST = "hub"

    // Real X25519 public keys are 32 raw bytes (52 base32 chars — the spec
    // table in improvements §8). The URI layer deliberately does NOT enforce
    // that: it is transport, and key validity belongs to the crypto layer
    // that consumes the key (test stubs like `BootstrapSyncCryptoManager`
    // mint short placeholder keys and must round-trip).

    /** Canonical join QR emitted by the existing realm device (`SyncManagerImpl.getPairingString`). */
    fun joinUri(
        ownerId: String,
        hostDeviceId: String,
        hostPublicKey: ByteArray,
        pairingSecret: ByteArray,
    ): String {
        require(hostPublicKey.isNotEmpty()) { "hostPublicKey must not be empty" }
        require(pairingSecret.isNotEmpty()) { "pairingSecret must not be empty" }
        return SCHEME + JOIN_HOST +
            "?o=" + percentEncode(ownerId) +
            "&d=" + encodeBase32(hostDeviceId.encodeToByteArray()) +
            "&k=" + encodeBase32(hostPublicKey) +
            "&s=" + encodeBase32(pairingSecret)
    }

    /**
     * Canonical offer QR emitted by the new device (inverted-direction proposal §3.2, with the
     * P3/P4 registration fields): the ephemeral X25519 key `k`, the device signing key `g`, the
     * offerer device id `d`, the pairing secret `s`, and the device name `n`.
     */
    fun offerUri(
        offererPublicKey: ByteArray,
        offererSigningKey: ByteArray,
        offererDeviceId: String,
        pairingSecret: ByteArray,
        deviceName: String,
    ): String {
        require(offererPublicKey.isNotEmpty()) { "offererPublicKey must not be empty" }
        require(offererSigningKey.isNotEmpty()) { "offererSigningKey must not be empty" }
        require(offererDeviceId.isNotEmpty()) { "offererDeviceId must not be empty" }
        require(pairingSecret.isNotEmpty()) { "pairingSecret must not be empty" }
        return SCHEME + OFFER_HOST +
            "?k=" + encodeBase32(offererPublicKey) +
            "&g=" + encodeBase32(offererSigningKey) +
            "&d=" + encodeBase32(offererDeviceId.encodeToByteArray()) +
            "&s=" + encodeBase32(pairingSecret) +
            "&n=" + percentEncode(deviceName)
    }

    /**
     * Canonical printed hub label (hub proposal §11.3): the static Ed25519 verify key `g`,
     * the hub device id `d`, and the long-lived pairing secret `s`. Emitted by the hub's
     * provisioning step and printed/etched onto the device — never displayed dynamically.
     * Field letters deliberately reuse the offer URI's vocabulary (`d` = device id,
     * `g` = Ed25519 signing/verify key, `s` = secret).
     */
    fun hubUri(
        hubDeviceId: String,
        hubVerifyKey: ByteArray,
        pairingSecret: ByteArray,
    ): String {
        require(hubDeviceId.isNotEmpty()) { "hubDeviceId must not be empty" }
        require(hubVerifyKey.isNotEmpty()) { "hubVerifyKey must not be empty" }
        require(pairingSecret.isNotEmpty()) { "pairingSecret must not be empty" }
        return SCHEME + HUB_HOST +
            "?d=" + encodeBase32(hubDeviceId.encodeToByteArray()) +
            "&g=" + encodeBase32(hubVerifyKey) +
            "&s=" + encodeBase32(pairingSecret)
    }

    /**
     * Role-host dispatch over a scanned/pasted payload. Never throws; every
     * failure mode is a [ParsedPairingUri] variant so callers map each to its
     * §3.2 user-visible prompt.
     */
    fun parse(raw: String): ParsedPairingUri {
        val trimmed = raw.trim()
        if (!trimmed.startsWith(SCHEME)) return ParsedPairingUri.Malformed
        val afterScheme = trimmed.removePrefix(SCHEME)
        val hostEnd = afterScheme.indexOfFirst { it == '?' || it == '/' || it == '#' }
        val host = if (hostEnd >= 0) afterScheme.substring(0, hostEnd) else afterScheme
        return when (host) {
            JOIN_HOST -> parseJoin(queryOf(afterScheme, hostEnd))
            OFFER_HOST -> parseOffer(queryOf(afterScheme, hostEnd))
            HUB_HOST -> parseHub(queryOf(afterScheme, hostEnd))
            else -> ParsedPairingUri.UnknownRole
        }
    }

    private fun queryOf(afterScheme: String, hostEnd: Int): Map<String, String> {
        if (hostEnd < 0 || afterScheme.getOrNull(hostEnd) != '?') return emptyMap()
        return afterScheme.substring(hostEnd + 1)
            .split('&')
            .mapNotNull { param ->
                val eq = param.indexOf('=')
                if (eq <= 0) null else param.substring(0, eq) to param.substring(eq + 1)
            }
            .toMap()
    }

    private fun parseJoin(params: Map<String, String>): ParsedPairingUri {
        val o = params["o"]?.takeIf { it.isNotEmpty() } ?: return ParsedPairingUri.Malformed
        val d = params["d"]?.takeIf { it.isNotEmpty() } ?: return ParsedPairingUri.Malformed
        val k = params["k"]?.takeIf { it.isNotEmpty() } ?: return ParsedPairingUri.Malformed
        val s = params["s"]?.takeIf { it.isNotEmpty() } ?: return ParsedPairingUri.Malformed
        return try {
            val publicKey = decodeBase32(k)
            if (publicKey.isEmpty()) return ParsedPairingUri.Malformed
            val secret = decodeBase32(s)
            if (secret.isEmpty()) return ParsedPairingUri.Malformed
            ParsedPairingUri.Join(
                ownerId = percentDecode(o) ?: return ParsedPairingUri.Malformed,
                hostDeviceId = decodeBase32(d).decodeToString(),
                hostPublicKey = publicKey,
                pairingSecret = secret,
            )
        } catch (_: Exception) {
            ParsedPairingUri.Malformed
        }
    }

    private fun parseOffer(params: Map<String, String>): ParsedPairingUri {
        val k = params["k"]?.takeIf { it.isNotEmpty() } ?: return ParsedPairingUri.Malformed
        val g = params["g"]?.takeIf { it.isNotEmpty() } ?: return ParsedPairingUri.Malformed
        val d = params["d"]?.takeIf { it.isNotEmpty() } ?: return ParsedPairingUri.Malformed
        val s = params["s"]?.takeIf { it.isNotEmpty() } ?: return ParsedPairingUri.Malformed
        val n = params["n"]?.takeIf { it.isNotEmpty() } ?: return ParsedPairingUri.Malformed
        return try {
            val publicKey = decodeBase32(k)
            if (publicKey.isEmpty()) return ParsedPairingUri.Malformed
            val signingKey = decodeBase32(g)
            // ⚠ The responder signs a delegation over this key, and a delegation is only
            // ever over a 32-byte Ed25519 key. Refusing here makes a bad QR a typed "not an offer"
            // error at the respond op, instead of an IllegalArgumentException from the builder later.
            if (signingKey.size != OFFER_SIGNING_KEY_BYTES) return ParsedPairingUri.Malformed
            val deviceId = decodeBase32(d).decodeToString()
            if (deviceId.isEmpty()) return ParsedPairingUri.Malformed
            val secret = decodeBase32(s)
            if (secret.isEmpty()) return ParsedPairingUri.Malformed
            ParsedPairingUri.Offer(
                offererPublicKey = publicKey,
                offererSigningKey = signingKey,
                offererDeviceId = deviceId,
                pairingSecret = secret,
                deviceName = percentDecode(n) ?: return ParsedPairingUri.Malformed,
            )
        } catch (_: Exception) {
            ParsedPairingUri.Malformed
        }
    }

    private fun parseHub(params: Map<String, String>): ParsedPairingUri {
        val d = params["d"]?.takeIf { it.isNotEmpty() } ?: return ParsedPairingUri.Malformed
        val g = params["g"]?.takeIf { it.isNotEmpty() } ?: return ParsedPairingUri.Malformed
        val s = params["s"]?.takeIf { it.isNotEmpty() } ?: return ParsedPairingUri.Malformed
        return try {
            val deviceId = decodeBase32(d).decodeToString()
            if (deviceId.isEmpty()) return ParsedPairingUri.Malformed
            val verifyKey = decodeBase32(g)
            if (verifyKey.isEmpty()) return ParsedPairingUri.Malformed
            val secret = decodeBase32(s)
            if (secret.isEmpty()) return ParsedPairingUri.Malformed
            ParsedPairingUri.Hub(
                hubDeviceId = deviceId,
                hubVerifyKey = verifyKey,
                pairingSecret = secret,
            )
        } catch (_: Exception) {
            ParsedPairingUri.Malformed
        }
    }

    // ── minimal RFC 3986 percent-encoding over UTF-8 ─────────────────────────
    // Only for the human-readable fields (`o`, `n`); the byte-valued fields
    // ride base32 and never need escaping.

    private fun isUnreserved(c: Char): Boolean =
        c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '-' || c == '.' || c == '_' || c == '~'

    private const val HEX = "0123456789ABCDEF"

    private fun percentEncode(value: String): String {
        val out = StringBuilder(value.length)
        for (byte in value.encodeToByteArray()) {
            val c = byte.toInt().toChar()
            if (isUnreserved(c)) {
                out.append(c)
            } else {
                val b = byte.toInt() and 0xFF
                out.append('%').append(HEX[b ushr 4]).append(HEX[b and 0xF])
            }
        }
        return out.toString()
    }

    private fun percentDecode(value: String): String? {
        val out = ByteArray(value.length)
        var outIndex = 0
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '%') {
                val hi = hexValue(value.getOrNull(i + 1) ?: return null) ?: return null
                val lo = hexValue(value.getOrNull(i + 2) ?: return null) ?: return null
                out[outIndex++] = ((hi shl 4) or lo).toByte()
                i += 3
            } else if (c == '+') {
                // Not emitted by percentEncode, but tolerate form-style input.
                out[outIndex++] = ' '.code.toByte()
                i++
            } else {
                if (c.code > 0x7F) return null // non-ASCII must arrive percent-encoded
                out[outIndex++] = c.code.toByte()
                i++
            }
        }
        return out.copyOf(outIndex).decodeToString()
    }

    private fun hexValue(c: Char): Int? = when (c) {
        in '0'..'9' -> c - '0'
        in 'A'..'F' -> c - 'A' + 10
        in 'a'..'f' -> c - 'a' + 10
        else -> null
    }
}

/** An offer QR's `g=` key is an Ed25519 public key. */
private const val OFFER_SIGNING_KEY_BYTES = 32
