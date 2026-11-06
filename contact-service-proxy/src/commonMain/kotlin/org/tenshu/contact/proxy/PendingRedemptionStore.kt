package org.tenshu.contact.proxy

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.tenshu.core.common.delegate.SecureStorageDelegate

/**
 * Invitee-side redemption state (`contact-invitation-handshake.md` §7.2;
 * threat-model A25): `{ inviteId, K_link, peerA13Pub, redeemedAt }` in
 * [SecureStorageDelegate], **never synced** — it holds the raw capability,
 * redemption is a single-device interactive flow, and keeping it local
 * minimises the at-rest surface (I8; T-CI-08).
 *
 * Unlike pairing's in-memory A8 this state legitimately outlives the app
 * session (the inviter may complete hours later, ≤ 72 h), hence secure
 * storage. Cleared on every terminal state — completion, rejection, expiry,
 * user cancel ([remove]). An index key tracks live inviteIds so the next
 * session can resume `AwaitInviteAck` for each ([all]).
 */
@OptIn(ExperimentalEncodingApi::class)
class PendingRedemptionStore(
    private val storage: SecureStorageDelegate,
) {

    @Serializable
    data class PendingRedemption(
        val inviteId: String,
        val inviteSecret: ByteArray,
        val peerA13PublicKey: ByteArray,
        val redeemedAtMs: Long,
    )

    fun save(redemption: PendingRedemption) {
        storage.storeSecret(
            entryKey(redemption.inviteId),
            json.encodeToString(PendingRedemption.serializer(), redemption),
        )
        updateIndex { it + redemption.inviteId }
    }

    fun get(inviteId: String): PendingRedemption? {
        val raw = storage.getSecret(entryKey(inviteId)) ?: return null
        return runCatching {
            json.decodeFromString(PendingRedemption.serializer(), raw)
        }.getOrNull()
    }

    fun remove(inviteId: String) {
        storage.remove(entryKey(inviteId))
        updateIndex { it - inviteId }
    }

    /** Live redemptions, for next-session `AwaitInviteAck` resume. */
    fun all(): List<PendingRedemption> = readIndex().mapNotNull { get(it) }

    private fun readIndex(): List<String> {
        val raw = storage.getString(INDEX_KEY) ?: return emptyList()
        return runCatching {
            json.decodeFromString(ListSerializer(String.serializer()), raw)
        }.getOrElse { emptyList() }
    }

    private fun updateIndex(transform: (Set<String>) -> Set<String>) {
        val updated = transform(readIndex().toSet()).toList()
        storage.storeString(
            INDEX_KEY,
            json.encodeToString(ListSerializer(String.serializer()), updated),
        )
    }

    private fun entryKey(inviteId: String) = "$ENTRY_PREFIX$inviteId"

    private companion object {
        const val INDEX_KEY = "tenshu/contact/pendingRedemptions"
        const val ENTRY_PREFIX = "tenshu/contact/pendingRedemption/"
        val json = Json { ignoreUnknownKeys = true }
    }
}
