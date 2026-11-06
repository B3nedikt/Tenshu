package org.tenshu.core.common.util

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * The contact-invitation link triple and its two wire encodings
 * (`docs/proposals/contact-invitation-handshake.md` §3).
 *
 * Three values are minted per invite by the issuing device (CSPRNG):
 *
 *  - [inviteId] (16 bytes) — the mailbox address at the relay; knowledge is
 *    the capability to post/fetch the sealed blobs.
 *  - [inviteSecret] (`K_link`, 16 bytes) — proof of link possession.
 *    **Never sent to the relay in any form — not even hashed.** Verified
 *    end-to-end inside the sealed payloads; the inviter persists only
 *    [secretHashHex].
 *  - [inviterPublicKey] (32 bytes) — the inviter's A13 public key; pins the
 *    inviter's identity against substitution and is the seal target for the
 *    invite response.
 *
 * Two encodings of the same triple, both versioned:
 *
 *  - [toMessagingLink] — `https://tenshu.app/i#v1.<base64url(id ‖ secret ‖ pub)>`
 *    for the OS share sheet. The payload rides the URL **fragment**, so a
 *    browser never transmits it to the apex server.
 *  - [toQrUri] — `tenshu-invite:v1?i=<base32(id)>&k=<base32(secret)>&p=<base32(pub)>`
 *    for in-person QR, mirroring the base32 short-key convention of the `tenshu://join`
 *    and `tenshu://offer` pairing URIs. (`tenshu-pair:` was a draft scheme, retired.)
 *
 * [parse] accepts either form. An unrecognised version tag is a hard error
 * ([UnsupportedInviteVersionException] — the UI shows "update your app", the
 * pairing-improvements Step 8 rule); anything structurally broken is an
 * [InviteLinkFormatException].
 */
class InviteLinkPayload(
    val inviteId: ByteArray,
    val inviteSecret: ByteArray,
    val inviterPublicKey: ByteArray,
) {
    init {
        require(inviteId.size == INVITE_ID_SIZE) { "inviteId must be $INVITE_ID_SIZE bytes, was ${inviteId.size}" }
        require(inviteSecret.size == INVITE_SECRET_SIZE) { "inviteSecret must be $INVITE_SECRET_SIZE bytes, was ${inviteSecret.size}" }
        require(inviterPublicKey.size == PUBLIC_KEY_SIZE) { "inviterPublicKey must be $PUBLIC_KEY_SIZE bytes, was ${inviterPublicKey.size}" }
    }

    /**
     * Canonical string form of [inviteId] (lowercase hex) — the relay mailbox
     * address and the `ContactInvite` row key. Hex keeps it trivially
     * URL/JSON-safe and debuggable.
     */
    val inviteIdHex: String get() = inviteId.toHexString()

    /**
     * `SHA-256(K_link)` as lowercase hex — the only secret-derived value the
     * **inviter** persists (`ContactInvite.secretHash`); the raw secret exists
     * only in the issuing session's memory and inside the sealed payloads.
     */
    fun secretHashHex(): String = sha256(inviteSecret).toHexString()

    @OptIn(ExperimentalEncodingApi::class)
    fun toMessagingLink(): String =
        MESSAGING_LINK_PREFIX + LINK_VERSION_TAG + "." +
            BASE64_URL_NO_PAD.encode(inviteId + inviteSecret + inviterPublicKey)

    fun toQrUri(): String =
        QR_SCHEME + LINK_VERSION_TAG +
            "?i=" + encodeBase32(inviteId) +
            "&k=" + encodeBase32(inviteSecret) +
            "&p=" + encodeBase32(inviterPublicKey)

    companion object {
        const val INVITE_ID_SIZE = 16
        const val INVITE_SECRET_SIZE = 16
        const val PUBLIC_KEY_SIZE = 32

        const val MESSAGING_LINK_PREFIX = "https://tenshu.app/i#"
        const val QR_SCHEME = "tenshu-invite:"
        const val LINK_VERSION_TAG = "v1"

        @OptIn(ExperimentalEncodingApi::class)
        private val BASE64_URL_NO_PAD = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)

        /** Mint a fresh §3 triple for [inviterPublicKey] (CSPRNG id + secret). */
        fun mint(inviterPublicKey: ByteArray): InviteLinkPayload = InviteLinkPayload(
            inviteId = nextSecureRandomBytes(INVITE_ID_SIZE),
            inviteSecret = nextSecureRandomBytes(INVITE_SECRET_SIZE),
            inviterPublicKey = inviterPublicKey,
        )

        /** Parse either encoding ([toMessagingLink] or [toQrUri]). */
        fun parse(encoded: String): InviteLinkPayload {
            val trimmed = encoded.trim()
            return when {
                trimmed.startsWith(MESSAGING_LINK_PREFIX) ->
                    parseMessagingFragment(trimmed.removePrefix(MESSAGING_LINK_PREFIX))
                trimmed.startsWith(QR_SCHEME) ->
                    parseQrPayload(trimmed.removePrefix(QR_SCHEME))
                else -> throw InviteLinkFormatException("not a Tenshu invite link")
            }
        }

        @OptIn(ExperimentalEncodingApi::class)
        private fun parseMessagingFragment(fragment: String): InviteLinkPayload {
            val dot = fragment.indexOf('.')
            if (dot <= 0) throw InviteLinkFormatException("missing version tag")
            requireVersion(fragment.substring(0, dot))
            val bytes = try {
                BASE64_URL_NO_PAD.decode(fragment.substring(dot + 1))
            } catch (e: IllegalArgumentException) {
                throw InviteLinkFormatException("invalid base64url payload", e)
            }
            if (bytes.size != INVITE_ID_SIZE + INVITE_SECRET_SIZE + PUBLIC_KEY_SIZE) {
                throw InviteLinkFormatException("payload must be 64 bytes, was ${bytes.size}")
            }
            return InviteLinkPayload(
                inviteId = bytes.copyOfRange(0, INVITE_ID_SIZE),
                inviteSecret = bytes.copyOfRange(INVITE_ID_SIZE, INVITE_ID_SIZE + INVITE_SECRET_SIZE),
                inviterPublicKey = bytes.copyOfRange(INVITE_ID_SIZE + INVITE_SECRET_SIZE, bytes.size),
            )
        }

        private fun parseQrPayload(payload: String): InviteLinkPayload {
            val question = payload.indexOf('?')
            if (question <= 0) throw InviteLinkFormatException("missing version tag")
            requireVersion(payload.substring(0, question))
            val params = payload.substring(question + 1)
                .split('&')
                .mapNotNull { param ->
                    val eq = param.indexOf('=')
                    if (eq <= 0) null else param.substring(0, eq) to param.substring(eq + 1)
                }
                .toMap()
            val id = params["i"] ?: throw InviteLinkFormatException("missing 'i' parameter")
            val secret = params["k"] ?: throw InviteLinkFormatException("missing 'k' parameter")
            val pub = params["p"] ?: throw InviteLinkFormatException("missing 'p' parameter")
            return InviteLinkPayload(
                inviteId = decodeBase32(id),
                inviteSecret = decodeBase32(secret),
                inviterPublicKey = decodeBase32(pub),
            )
        }

        private fun requireVersion(tag: String) {
            if (tag != LINK_VERSION_TAG) throw UnsupportedInviteVersionException(tag)
        }
    }
}

/** Structurally malformed invite link/QR payload. */
class InviteLinkFormatException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * The link is recognisably a Tenshu invite but carries an unknown version
 * tag — minted by a newer app. Hard error; the UI tells the user to update
 * (no in-protocol negotiation, same rule as pairing improvements Step 8).
 */
class UnsupportedInviteVersionException(
    val version: String,
) : RuntimeException("unsupported invite link version '$version' — update the app")

// ── RFC 4648 base32, unpadded ────────────────────────────────────────────────
// The QR encoding wants an alphanumeric-mode-friendly alphabet (A–Z, 2–7);
// Bech32's charset is checksum-coupled and the wrong tool here.

private const val BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

internal fun encodeBase32(bytes: ByteArray): String {
    val out = StringBuilder((bytes.size * 8 + 4) / 5)
    var buffer = 0
    var bitsInBuffer = 0
    for (byte in bytes) {
        buffer = (buffer shl 8) or (byte.toInt() and 0xFF)
        bitsInBuffer += 8
        while (bitsInBuffer >= 5) {
            bitsInBuffer -= 5
            out.append(BASE32_ALPHABET[(buffer ushr bitsInBuffer) and 0x1F])
        }
    }
    if (bitsInBuffer > 0) {
        out.append(BASE32_ALPHABET[(buffer shl (5 - bitsInBuffer)) and 0x1F])
    }
    return out.toString()
}

internal fun decodeBase32(encoded: String): ByteArray {
    val out = ByteArray(encoded.length * 5 / 8)
    var buffer = 0
    var bitsInBuffer = 0
    var index = 0
    for (char in encoded) {
        val value = BASE32_ALPHABET.indexOf(char.uppercaseChar())
        if (value < 0) throw InviteLinkFormatException("invalid base32 character '$char'")
        buffer = (buffer shl 5) or value
        bitsInBuffer += 5
        if (bitsInBuffer >= 8) {
            bitsInBuffer -= 8
            out[index++] = ((buffer ushr bitsInBuffer) and 0xFF).toByte()
        }
    }
    return out
}
