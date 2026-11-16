package org.tenshu.sync.proxy

/**
 * Whether an owner device may send a shared realm's key to a member: the one rule every hand-out obeys (rotation
 * re-grant, catch-up, key-request answer, acceptance re-key, all through `SharedRealmGrantManager.dropRekey`).
 *
 * ⚠⚠ T-GR-02 revocation window. A removal's revocation records and its rotation's rows are separate blocks, and a
 * push pass keeps going after a transient failure, so an owner device can hold the new key without either record.
 * The key's `RealmKeyGeneration` row closes that: it names the members revoked as of the key and is needed to hand
 * the key out at all. So:
 * - a member revoked by the realm's signed records, or named by the key's generation row, gets nothing;
 * - a key this device holds no generation row for is sent to nobody: the row may still be in flight, and its absence
 *   must never read as "nobody was revoked". ⚠ Not "unless the realm never rotated": that signal (a retired-key row)
 *   travels in yet another block and can be missing for the same reason. Nothing legitimate is lost — every rotated
 *   key gets a row, and a never-rotated realm owes no re-key: its members hold its only key from their grant.
 *
 * The member-side refusal of an unsigned generation only protects an honest member; a removed member's own
 * client would unwrap whatever it is sent. So this gate is on the sender.
 */
internal object KeyHandOutPolicy {

    fun mayReceive(
        memberId: String,
        revokedByRecords: Boolean,
        /** The key's generation row's revoked members, or null when this device holds no row for the key. */
        revokedAtGeneration: Set<String>?,
    ): Boolean = !revokedByRecords && revokedAtGeneration != null && memberId !in revokedAtGeneration
}
