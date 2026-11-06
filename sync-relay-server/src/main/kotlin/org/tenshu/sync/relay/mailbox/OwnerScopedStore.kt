package org.tenshu.sync.relay.mailbox

/**
 * A relay store that keeps records under an owner (= realm) id, and can drop them when that owner's
 * account is closed past grace (`relay-metadata-minimisation.md` F12, `retention-quota-model.md` P3).
 *
 * ⚠⚠ **Every registry that holds owner-keyed state implements this**, so a new store cannot be
 * wired without the compiler asking what closure does to it. A store that deliberately keeps
 * something says so in its [eraseOwner] KDoc — the `erased_realm` tombstone is the only row an
 * erased owner leaves behind, and `AccountErasure`'s KDoc carries the full keep/delete table.
 *
 * Contract:
 *  - **Idempotent.** Closure is re-posted by commerce every sweep (and retried after a partial
 *    failure), so a second call on an erased owner must succeed and drop nothing.
 *  - **Owner-scoped, never broader.** A blank [ownerId] throws rather than degrading into "every
 *    owner" — the same refusal `ContentBlobRegistry.reclaimOwner` makes.
 *  - **Rows only.** It must not touch a `BlobStore`. On SQLite every store's [eraseOwner] runs inside
 *    ONE `RelayDatabase.inTransaction`, so a byte delete in here would be irreversible while the row
 *    delete next to it could still roll back. Stores that own bytes implement [OwnerBytesStore] and
 *    delete them after the commit.
 *
 * ⚠ It acts on exactly the owner id it is given. Whether that owner may be erased at all — in
 * particular P3's "unless an open account still holds that realm" — is decided by commerce before a
 * closure record is ever minted (`CommerceStore.closedAccountsWithOwners`). The relay has no notion
 * of accounts (billing D5) and must not grow one here.
 */
interface OwnerScopedStore {
    /**
     * Drop every record this store keys by [ownerId], except what the implementation documents it
     * keeps. Returns how many rows / entries were dropped (a report figure, not a contract).
     */
    fun eraseOwner(ownerId: String): Int
}

/**
 * An [OwnerScopedStore] that also owns **bytes** in a `BlobStore` — the content plane and the share
 * plane. Erasure is two-phase so that a crash at any point is finished by the next run:
 *
 *  1. [eraseOwner] (inside the row transaction) removes the owner's records from every read path
 *     but leaves a **durable ledger** of the bytes still to delete;
 *  2. [eraseOwnerBytes] (after the commit) deletes those bytes, then the ledger.
 *
 * ⚠⚠ Deleting the ledger *before* the bytes — or keeping none — is the shape that leaks forever:
 * `BlobStore` cannot be enumerated by owner, so bytes nothing names are bytes nothing will delete.
 */
interface OwnerBytesStore : OwnerScopedStore {
    /** Phase 2. Idempotent; returns what was freed in this call. */
    fun eraseOwnerBytes(ownerId: String): ContentBlobRegistry.ReclaimResult
}

/** The shared precondition, so every store refuses a blank owner identically. */
internal fun requireErasableOwner(ownerId: String) {
    require(ownerId.isNotBlank()) { "eraseOwner requires a non-blank ownerId." }
}
