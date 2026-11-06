package org.tenshu.contact.common

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The two sealed-box plaintexts of the handshake
 * (`contact-invitation-handshake.md` §5.2). Serialised as JSON bytes — a
 * documented deviation from the proposal's CBOR sketch: the project's wire
 * codec for control-plane payloads is kotlinx JSON throughout, the payloads
 * are tiny (well under the 4 KiB relay cap), and the security binding comes
 * from the AEAD (`info` + `inviteId` AAD), not the codec. Each payload
 * carries an explicit [InviteResponsePayload.version]; bumping the payload
 * shape bumps the sealed-box `info` version segment too (§5.2 version rule —
 * no in-protocol negotiation).
 *
 * Seal targets and domains:
 *
 * | Payload | Sealed to | `info` | extra AAD |
 * |---|---|---|---|
 * | [InviteResponsePayload] | inviter A13 (from the link) | [INVITE_RESPONSE_INFO] | `inviteId` (16 raw bytes) |
 * | [InviteAckPayload] | invitee A13 (from the response) | [INVITE_ACK_INFO] | `inviteId` (16 raw bytes) |
 *
 * `inviteSecret` rides inside **both** plaintexts — end-to-end proof of link
 * possession in both directions; the relay never holds it or its hash, so a
 * compromised relay cannot forge either message (T-CI-02/03).
 */
@Serializable
data class InviteResponsePayload(
    val version: Int = 1,
    /** `K_link` — proof the responder actually holds the link. */
    val inviteSecret: ByteArray,
    /** The invitee's A13 public key — what a later membership grant wraps to. */
    val identityPublicKey: ByteArray,
    /** What the invitee claims to be called (≤ 64 chars; quarantined on `TenshuIdentity`). */
    val selfAssertedName: String,
)

@Serializable
data class InviteAckPayload(
    val version: Int = 1,
    /** [STATUS_ACCEPTED] or [STATUS_REJECTED]. */
    val status: String,
    /** `K_link` echoed back — the invitee's end-to-end forgery check. */
    val inviteSecret: ByteArray,
    /**
     * The inviter's A13 public key. The invitee checks it equals the
     * link-pinned key, closing ack forgery by anyone who learned the
     * invitee's A13 pub but not the inviter's private key (§5.2).
     */
    val identityPublicKey: ByteArray,
    /** The inviter's self-asserted display name (≤ 64 chars). */
    val selfAssertedName: String = "",
    /** Advisory share intent (§2) — rides the sealed ack, never the link. */
    val intentKind: String = "",
    val intentLabel: String = "",
) {
    companion object {
        const val STATUS_ACCEPTED = "accepted"
        const val STATUS_REJECTED = "rejected"
    }
}

/** Sealed-box HKDF/AAD domain for the invite response (§5.2). */
val INVITE_RESPONSE_INFO: ByteArray = "tenshu/invite/response/v1".encodeToByteArray()

/** Sealed-box HKDF/AAD domain for the invite ack (§5.2). */
val INVITE_ACK_INFO: ByteArray = "tenshu/invite/ack/v1".encodeToByteArray()

/** §5.2: self-asserted names are capped at 64 chars at encode time. */
const val MAX_SELF_ASSERTED_NAME_LENGTH = 64

private val json = Json { ignoreUnknownKeys = true }

fun InviteResponsePayload.encode(): ByteArray =
    json.encodeToString(
        InviteResponsePayload.serializer(),
        copy(selfAssertedName = selfAssertedName.take(MAX_SELF_ASSERTED_NAME_LENGTH)),
    ).encodeToByteArray()

fun decodeInviteResponsePayload(bytes: ByteArray): InviteResponsePayload =
    json.decodeFromString(InviteResponsePayload.serializer(), bytes.decodeToString())

fun InviteAckPayload.encode(): ByteArray =
    json.encodeToString(
        InviteAckPayload.serializer(),
        copy(selfAssertedName = selfAssertedName.take(MAX_SELF_ASSERTED_NAME_LENGTH)),
    ).encodeToByteArray()

fun decodeInviteAckPayload(bytes: ByteArray): InviteAckPayload =
    json.decodeFromString(InviteAckPayload.serializer(), bytes.decodeToString())
