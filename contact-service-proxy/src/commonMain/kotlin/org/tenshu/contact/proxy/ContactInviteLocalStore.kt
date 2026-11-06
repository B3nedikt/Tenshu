package org.tenshu.contact.proxy

import org.tenshu.contact.ContactInvite
import org.tenshu.contact.ContactService
import org.tenshu.core.Composite
import org.tenshu.core.Data
import org.tenshu.core.TString
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.entity.Entity
import org.tenshu.core.time.Instant
import org.tenshu.core.common.relation.relationReference

/**
 * Proxy-side read/update access to the locally-known [ContactInvite] rows via
 * the [ContactService.Relations.CONTACT_INVITES] relation array (a maintained
 * relation, see `initContactService`). Updates are plain `saveData` of a fresh
 * composite carrying the same `inviteId` — the `DataIdProvider` key makes it
 * an overwrite, and `Syncable(Eager)` propagates the status flip to every
 * inviter device.
 */
internal class ContactInviteLocalStore(
    private val executionContext: ExecutionContext,
) {

    /** Materialised field view of one stored invite row. */
    data class InviteRow(
        val inviteId: String,
        val secretHash: String,
        val status: String,
        val createdAtMs: Long,
        val expiresAtMs: Long,
        val intentKind: String,
        val intentLabel: String,
        val resultFingerprint: String,
        val claimedName: String,
        val claimedFingerprint: String,
        val claimedSas: String,
    )

    // Derived from the relation's declared spec, so this reader cannot disagree
    // with the generic maintainer that writes the array about `isEntity` or
    // `elementSchemaId` (`core-relation-system.md` D4).
    private val invitesRef = executionContext.relationReference(
        ContactService.Relations.CONTACT_INVITES,
    )

    fun all(): List<InviteRow> =
        executionContext.database.getArrayData(invitesRef)
            ?.mapNotNull { it.toInviteRow() }
            ?.toList()
            ?: emptyList()

    fun get(inviteId: String): InviteRow? = all().firstOrNull { it.inviteId == inviteId }

    /**
     * Overwrite the row with [status] (and optionally [resultFingerprint] /
     * the [claimedName]+[claimedFingerprint] pair set on the `REDEEMED` flip
     * so any inviter device can render the completion prompt's header).
     */
    fun updateStatus(
        row: InviteRow,
        status: String,
        resultFingerprint: String = row.resultFingerprint,
        claimedName: String = row.claimedName,
        claimedFingerprint: String = row.claimedFingerprint,
        claimedSas: String = row.claimedSas,
    ) {
        val updated = ContactInvite.create(
            context = executionContext.context,
            inviteId = row.inviteId,
            secretHash = row.secretHash,
            status = status,
            createdAt = row.createdAtMs,
            expiresAt = row.expiresAtMs,
            intentKind = row.intentKind,
            intentLabel = row.intentLabel,
            resultFingerprint = resultFingerprint,
            claimedName = claimedName,
            claimedFingerprint = claimedFingerprint,
            claimedSas = claimedSas,
        )
        executionContext.saveData(updated)
    }

    private fun Data.toInviteRow(): InviteRow? {
        val inviteId = stringField(ContactInvite.Field.INVITE_ID) ?: return null
        return InviteRow(
            inviteId = inviteId,
            secretHash = stringField(ContactInvite.Field.SECRET_HASH) ?: return null,
            status = stringField(ContactInvite.Field.STATUS) ?: return null,
            createdAtMs = longField(ContactInvite.Field.CREATED_AT) ?: 0L,
            expiresAtMs = longField(ContactInvite.Field.EXPIRES_AT) ?: 0L,
            intentKind = stringField(ContactInvite.Field.INTENT_KIND) ?: "",
            intentLabel = stringField(ContactInvite.Field.INTENT_LABEL) ?: "",
            resultFingerprint = stringField(ContactInvite.Field.RESULT_FINGERPRINT) ?: "",
            claimedName = stringField(ContactInvite.Field.CLAIMED_NAME) ?: "",
            claimedFingerprint = stringField(ContactInvite.Field.CLAIMED_FINGERPRINT) ?: "",
            claimedSas = stringField(ContactInvite.Field.CLAIMED_SAS) ?: "",
        )
    }

    private fun Data.stringField(fieldId: String): String? = when (this) {
        is Entity<*> -> runCatching { getFieldValue<TString>(fieldId).get() }.getOrNull()
        is Composite -> runCatching { getField<TString>(fieldId).get() }.getOrNull()
        else -> null
    }

    private fun Data.longField(fieldId: String): Long? = when (this) {
        is Entity<*> -> runCatching { getFieldValue<Instant>(fieldId).get() }.getOrNull()
        is Composite -> runCatching { getField<Instant>(fieldId).get() }.getOrNull()
        else -> null
    }
}
