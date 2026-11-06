package org.tenshu.sync.relay.mailbox

/**
 * The relay-side realm registry: `ownerId → RealmRecord` (the realm's master public key,
 * the trust anchor for verifying master-delegated device admissions).
 *
 * Storage SPI (see `docs/proposals/sync-relay-persistence.md`). Two interchangeable
 * backends select at startup by config:
 *   - [RealmStore] — in-memory `ConcurrentHashMap`; the default + test backend.
 *   - `org.tenshu.sync.relay.storage.SqliteRealmStore` — durable; survives restart.
 *
 * The record type stays nested on [RealmStore] (`RealmStore.RealmRecord`) so existing
 * call sites are untouched; both backends use it. Only public material is stored — no
 * plaintext — so persistence does not widen the zero-trust surface (House Rule #1).
 */
interface RealmRegistry : OwnerScopedStore {

    /**
     * Returns true if a new realm record was inserted; false if [RealmStore.RealmRecord.ownerId]
     * already exists (the relay returns 409). Inserts are **atomic** — two clients racing
     * for the same `ownerId` produce exactly one winner.
     */
    fun createIfAbsent(record: RealmStore.RealmRecord): Boolean

    fun get(ownerId: String): RealmStore.RealmRecord?

    fun isErased(ownerId: String): Boolean = false
}
