package org.tenshu.sync.relay.mailbox

/**
 * The shape every `shareId` must have, enforced at `POST /share` and at takedown alike. A client mints
 * 16 random bytes base64url (22 characters); the bound is looser, but an id the takedown route would
 * refuse must never be creatable, or an abuser could publish a link no operator can take down.
 */
val SHARE_ID_SHAPE = Regex("^[A-Za-z0-9_-]{1,128}$")

/**
 * Operator **takedown** of one share link by `shareId` — the "action" half of notice-and-action
 * (`docs/policy/acceptable-use-and-limits.md` §3, runbook `docs/how-to/take-down-a-share-link.md`).
 *
 * Content-blind by construction (House Rule #1): the relay acts on the `shareId` a report names and
 * never on what the link holds, which it cannot read. A takedown
 * 1. revokes the share record in the same transaction that writes the audit row, so from the commit
 *    on the descriptor `GET` and every chunk `GET` answer 410 — the same answer as an owner's revoke
 *    or an expiry, so a recipient learns nothing about *why* a link is gone;
 * 2. then deletes the share's chunk ciphertext at once instead of after the 30-day revoke window.
 *    Only the share's own copy (`share/<shareId>/<n>`) is touched: the owner's durable content in
 *    the content plane is a different tier and stays (`retention-quota-model.md` §2 — a share copy
 *    is not the user's file).
 *
 * The revoked record row stays until the ordinary share GC drops it one retention window later, so
 * the owner's own `DELETE /share/{id}` still answers 204 (no error loop on the owner's device) and
 * no further chunk can be uploaded under the id. After that the audit row alone keeps the id: `create`
 * refuses any id with a takedown on record, so the reported URL can never be brought back.
 */
enum class TakedownReason(val code: String) {
    ILLEGAL_CONTENT("illegal_content"),
    CSAM("csam"),
    IP_INFRINGEMENT("ip_infringement"),
    MALWARE_OR_PHISHING("malware_or_phishing"),
    TERMS_VIOLATION("terms_violation"),
    AUTHORITY_ORDER("authority_order"),
    OTHER("other"),
    ;

    companion object {
        fun fromCode(code: String?): TakedownReason? = entries.firstOrNull { it.code == code }

        /** For reading the audit table back: an unknown stored code is a fault, never silently `OTHER`. */
        fun stored(code: String): TakedownReason =
            fromCode(code) ?: error("unknown takedown reason in the audit table: '$code'")
    }
}

enum class TakedownOutcome(val code: String) {
    /** The share existed and was not taken down before: revoked now, bytes deleted. */
    TAKEN_DOWN("taken_down"),

    /** A takedown of this id is already on record: nothing new is revoked, the byte delete is retried. */
    ALREADY_TAKEN_DOWN("already_taken_down"),

    /** No record under this id (never existed, or already collected after a revoke/expiry). */
    NOT_FOUND("not_found"),
    ;

    companion object {
        fun fromCode(code: String): TakedownOutcome =
            entries.firstOrNull { it.code == code } ?: error("unknown takedown outcome in the audit table: '$code'")
    }
}

/**
 * One row of the append-only `share_takedown` audit table. ⚠⚠ Holds no user plaintext: the
 * `shareId` and `ownerId` are opaque ids, the reason is a fixed code, and [operatorNote] is the
 * operator's own words (a ticket reference — never a description of the content, never the
 * reporter's personal data; the runbook says so, the relay cannot check it).
 */
data class TakedownRecord(
    val id: Long,
    val shareId: String,
    /** The share's owner when the record still existed; null for [TakedownOutcome.NOT_FOUND]. */
    val ownerId: String?,
    val takenDownAtMs: Long,
    val reason: TakedownReason,
    val operatorNote: String?,
    val outcome: TakedownOutcome,
)

data class TakedownResult(
    val record: TakedownRecord,
    /** Stored chunk ciphertext bytes this call deleted (0 when there was nothing left). */
    val bytesDeleted: Long,
    /**
     * False when the byte delete threw. The link is dead regardless (the revoke committed first);
     * running the takedown again retries the delete, and the share GC removes the keys in any case.
     */
    val bytesDeleteCompleted: Boolean,
)
