package org.tenshu.sync.relay.storage

import java.sql.Connection
import java.sql.DriverManager
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Thin JDBC wrapper for the relay's durable backend
 * (`docs/proposals/sync-relay-persistence.md`).
 *
 * **SQLite only** — single-node, zero-container. SQLite serialises writers, so we
 * hold ONE connection guarded by a [lock]; every access goes through [withConnection].
 * That's correct and ample for the relay's modest write volume; a connection pool /
 * Postgres backend is the multi-node scale-up (a future SPI backend, not built here).
 *
 * WAL mode + a `busy_timeout` keep concurrent readers from tripping over the writer. A
 * tiny hand-rolled, versioned migration runner owns the schema (no Flyway dependency,
 * per the proposal's §9 lean).
 *
 * Stores only public keys + opaque ciphertext (House Rule #1) — the file is never a
 * plaintext exposure; at-rest encryption is availability/defence-in-depth, not required.
 */
class RelayDatabase(
    jdbcUrl: String,
    private val connection: Connection = DriverManager.getConnection(jdbcUrl),
) : AutoCloseable {

    private val lock = ReentrantLock()

    init {
        // PRAGMAs are connection-scoped; set once on the single connection.
        connection.createStatement().use { st ->
            st.execute("PRAGMA journal_mode=WAL")
            st.execute("PRAGMA busy_timeout=5000")
            st.execute("PRAGMA foreign_keys=ON")
        }
        migrate()
    }

    /** Run [block] against the connection under the single-writer lock. */
    fun <T> withConnection(block: (Connection) -> T): T = lock.withLock { block(connection) }

    /**
     * Run [block] as ONE transaction, holding the lock throughout — so every store call made inside it
     * (each one's own [withConnection] re-enters the lock on this same connection) commits or rolls
     * back together. A push appends a block and writes its edge registers this way.
     *
     * ⚠ Nests: inside an outer transaction this simply runs [block], and the outer one commits.
     * ⚠⚠ A store that commits on its own inside [block] would commit the outer transaction early —
     * keep such calls out of it. `declareRefs` nests through this method, which is what lets a
     * revive and a describe run inside a push.
     */
    fun <T> inTransaction(block: (Connection) -> T): T = lock.withLock {
        if (!connection.autoCommit) return@withLock block(connection)
        connection.autoCommit = false
        try {
            val result = block(connection)
            connection.commit()
            result
        } catch (t: Throwable) {
            runCatching { connection.rollback() }
            throw t
        } finally {
            connection.autoCommit = true
        }
    }

    override fun close() = lock.withLock { connection.close() }

    private fun migrate() = lock.withLock {
        connection.createStatement().use { st ->
            st.execute("CREATE TABLE IF NOT EXISTS schema_version (version INTEGER NOT NULL)")
        }
        val current = connection.createStatement().use { st ->
            st.executeQuery("SELECT COALESCE(MAX(version), 0) FROM schema_version").use { rs ->
                if (rs.next()) rs.getInt(1) else 0
            }
        }
        refusePreSquashLadder(current)
        for (migration in MIGRATIONS.filter { it.version > current }) {
            // Each migration is its own transaction: all-or-nothing per version.
            connection.autoCommit = false
            try {
                connection.createStatement().use { st ->
                    migration.statements.forEach { st.execute(it) }
                }
                connection.prepareStatement("INSERT INTO schema_version(version) VALUES(?)").use { ps ->
                    ps.setInt(1, migration.version)
                    ps.executeUpdate()
                }
                connection.commit()
            } catch (t: Throwable) {
                connection.rollback()
                throw t
            } finally {
                connection.autoCommit = true
            }
        }
    }

    /**
     * ⚠⚠ A database written by an earlier cut of the baseline must not be "upgraded" by this
     * list. Its recorded version (from an earlier chain) can be ≥ the baseline's, so the loop above
     * would skip every statement and run against a schema this build never created. Such a database
     * has a version but not the current [BASELINE_MARKER] row — only the latest baseline writes it — and
     * is refused, loudly.
     */
    private fun refusePreSquashLadder(current: Int) {
        if (current == 0) return
        val cut = connection.createStatement().use { st ->
            val hasMarkerTable = st.executeQuery(
                "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'schema_baseline'",
            ).use { it.next() }
            if (!hasMarkerTable) {
                null
            } else {
                st.executeQuery("SELECT cut FROM schema_baseline").use { rs -> if (rs.next()) rs.getString(1) else null }
            }
        }
        check(cut == BASELINE_MARKER) {
            "Relay database is at schema_version $current from a PRE-SQUASH migration chain or an earlier " +
                "cut of the v1 baseline (marker '${cut ?: "none"}', expected '$BASELINE_MARKER'). The 2026-11 re-cut folded every earlier step into a new baseline " +
                "v1 and does not migrate old databases: wipe it (the SQLite file and its -wal/-shm) and start empty " +
                "(local dev: scripts/local-commerce-stack.sh --reset)."
        }
    }

    private data class Migration(val version: Int, val statements: List<String>)

    internal companion object {
        /**
         * Written by the current version-1 baseline; a versioned database without it holds an older
         * chain or an older cut of version 1, and is refused.
         *
         * ⚠ Change it whenever version 1 is edited in place: a database written by the previous cut
         * (marker `2026-10-reset` or `2026-10-reset-b`) can carry the same or a higher version number
         * with a different schema (a NULLABLE `admission_revocation.device_a33_public_key`, or the
         * pre-fold v2–v5 steps of `core-schema-evolution.md` Appendix A.6), which the loop above
         * would never fix.
         */
        const val BASELINE_MARKER = "2026-11-reset"

        // Append-only. NEVER edit a shipped migration — add a new version.
        //
        // ⚠⚠ Version 1 is a SQUASHED baseline: the final schema of the earlier migration chain
        // (summarised in `docs/proposals/core-schema-evolution.md` Appendix A.2 and A.6), not a
        // replay of it. Column order matches that chain (ALTER-added columns stay last).
        //
        // ⚠⚠ A relay holding a database from an earlier chain or cut is wiped, never migrated:
        // version 1 writes the `schema_baseline` marker, and `refusePreSquashLadder` refuses any
        // versioned database without it. ⚠ `deploy/reset-test-data.sh` derives the expected version
        // as the max `version = N,` here — keep that literal shape.
        //
        // From here the rule above holds: append version 2, 3, … and NEVER edit version 1.
        private val MIGRATIONS = listOf(
            Migration(
                version = 1,
                statements = listOf(
                    "CREATE TABLE schema_baseline (cut TEXT NOT NULL)",
                    "INSERT INTO schema_baseline(cut) VALUES ('$BASELINE_MARKER')",
                    // ── Identity ──────────────────────────────────────────────────────────────
                    """
                    CREATE TABLE IF NOT EXISTS realm (
                        owner_id          TEXT PRIMARY KEY,
                        master_public_key BLOB NOT NULL,
                        created_at        INTEGER NOT NULL
                    )
                    """.trimIndent(),
                    // `admitted_hlc_*`: tombstone-ordering guard — the HLC at which each device was
                    // admitted. ⚠⚠ Without it a `Device` tombstone authored BEFORE an admission would
                    // revoke it on arrival (delivery order is not authorship order). Nullable, and not
                    // for legacy rows: recovery, membership grants and the shared-realm admitter carry no
                    // stamp — absent means "unknown" ⇒ apply the revocation. A logical clock is
                    // routing metadata, not content (HR #1).
                    """
                    CREATE TABLE IF NOT EXISTS device (
                        owner_id              TEXT NOT NULL,
                        device_id             TEXT NOT NULL,
                        public_key            BLOB NOT NULL,
                        admitted_hlc_physical INTEGER,
                        admitted_hlc_counter  INTEGER,
                        admitted_hlc_device   TEXT,
                        PRIMARY KEY (owner_id, device_id)
                    )
                    """.trimIndent(),
                    // No `recovery_blob`: the relay holds no A9 server-side blob
                    // (`relay-metadata-minimisation.md` F8, threat model T-RC-08).
                    // Per-device HLC tips — the serialized SignedHlcTip wire envelope.
                    """
                    CREATE TABLE IF NOT EXISTS hlc_tip (
                        owner_id  TEXT NOT NULL,
                        device_id TEXT NOT NULL,
                        tip       BLOB NOT NULL,
                        PRIMARY KEY (owner_id, device_id)
                    )
                    """.trimIndent(),

                    // ── Mailbox: ordered sync-block + tombstone logs ──────────────────────────
                    // A single shared monotonic counter (continues across restart) so block +
                    // tombstone cursors are comparable. ⚠ The seed row is required on a fresh DB.
                    "CREATE TABLE IF NOT EXISTS relay_seq (id INTEGER PRIMARY KEY CHECK(id = 0), value INTEGER NOT NULL)",
                    "INSERT OR IGNORE INTO relay_seq(id, value) VALUES(0, 0)",
                    """
                    CREATE TABLE IF NOT EXISTS sync_block (
                        seq                   INTEGER PRIMARY KEY,
                        owner_id              TEXT NOT NULL,
                        block_id              TEXT NOT NULL,
                        target_entity_id_hash TEXT,
                        hlc_physical          INTEGER NOT NULL,
                        envelope              BLOB NOT NULL,
                        body_dropped          INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent(),
                    "CREATE INDEX IF NOT EXISTS idx_sync_block_owner_seq ON sync_block(owner_id, seq)",
                    "CREATE INDEX IF NOT EXISTS idx_sync_block_owner_hash ON sync_block(owner_id, target_entity_id_hash)",
                    """
                    CREATE TABLE IF NOT EXISTS delete_block (
                        seq                   INTEGER PRIMARY KEY,
                        owner_id              TEXT NOT NULL,
                        block_id              TEXT NOT NULL,
                        target_entity_id_hash TEXT NOT NULL,
                        target_entity_type    TEXT NOT NULL,
                        hlc_physical          INTEGER NOT NULL,
                        envelope              BLOB NOT NULL
                    )
                    """.trimIndent(),
                    "CREATE INDEX IF NOT EXISTS idx_delete_block_owner_seq ON delete_block(owner_id, seq)",

                    // ── Content plane index ───────────────────────────────────────────────────
                    // Bytes live in a BlobStore; SQL holds the last-reference time (retention/orphan
                    // GC) and each blob's length so `bytesUsed(owner)` sums cheaply for the quota.
                    """
                    CREATE TABLE IF NOT EXISTS content_blob (
                        owner_id           TEXT NOT NULL,
                        ref                TEXT NOT NULL,
                        last_referenced_at INTEGER NOT NULL,
                        byte_len           INTEGER NOT NULL,
                        PRIMARY KEY (owner_id, ref)
                    )
                    """.trimIndent(),
                    "CREATE INDEX IF NOT EXISTS idx_content_blob_lastref ON content_blob(last_referenced_at)",
                    "CREATE INDEX IF NOT EXISTS idx_content_blob_owner ON content_blob(owner_id)",

                    // Mode-A share descriptors. Chunk bytes live in a BlobStore (key
                    // `share/<shareId>/<idx>`); chunk_hashes is a length-framed BLOB. ⚠ No file name,
                    // MIME type or size: those live only inside the client-sealed `sealed_meta` (F3,
                    // `relay-metadata-minimisation.md`, ADR 0018).
                    """
                    CREATE TABLE IF NOT EXISTS share_record (
                        share_id     TEXT PRIMARY KEY,
                        owner_id     TEXT NOT NULL,
                        chunk_count  INTEGER NOT NULL,
                        chunk_hashes BLOB NOT NULL,
                        expires_at   INTEGER,
                        max_opens    INTEGER,
                        created_at   INTEGER NOT NULL,
                        opens_so_far INTEGER NOT NULL,
                        revoked_at   INTEGER,
                        sealed_meta  BLOB NOT NULL
                    )
                    """.trimIndent(),

                    // ── Contacts & shared-realm membership ────────────────────────────────────
                    // Contact-invitation mailbox (contact-invitation-handshake.md §6): routing
                    // metadata + the two sealed blobs; never K_link or any derivative (HR #1).
                    """
                    CREATE TABLE IF NOT EXISTS invite (
                        invite_id       TEXT PRIMARY KEY,
                        owner_id        TEXT NOT NULL,
                        created_at      INTEGER NOT NULL,
                        expires_at      INTEGER NOT NULL,
                        state           TEXT NOT NULL,
                        sealed_response BLOB,
                        sealed_ack      BLOB,
                        responded_at    INTEGER
                    )
                    """.trimIndent(),
                    "CREATE INDEX IF NOT EXISTS idx_invite_owner ON invite(owner_id)",
                    // Grant mailbox (shared-realm-membership-grant.md §4): one sealed grant blob,
                    // addressed by the HKDF grant address; single-pickup + TTL (HR #1 / D3).
                    """
                    CREATE TABLE IF NOT EXISTS grant_mailbox (
                        address      TEXT PRIMARY KEY,
                        owner_id     TEXT NOT NULL,
                        sealed_grant BLOB NOT NULL,
                        created_at   INTEGER NOT NULL,
                        expires_at   INTEGER NOT NULL
                    )
                    """.trimIndent(),
                    "CREATE INDEX IF NOT EXISTS idx_grant_owner ON grant_mailbox(owner_id)",
                    // Per-realm member revocation list (append-only) — the admit path checks it (D6).
                    """
                    CREATE TABLE IF NOT EXISTS member_revocation (
                        realm_id             TEXT NOT NULL,
                        member_authority_pub BLOB NOT NULL,
                        epoch                INTEGER NOT NULL,
                        revoked_at           INTEGER NOT NULL,
                        signature            BLOB NOT NULL
                    )
                    """.trimIndent(),
                    "CREATE INDEX IF NOT EXISTS idx_member_revocation_realm ON member_revocation(realm_id)",
                    // Admitted-device associations — a revocation purges exactly the member
                    // authority's registered per-realm device keys (D6 immediacy).
                    """
                    CREATE TABLE IF NOT EXISTS member_admission (
                        realm_id             TEXT NOT NULL,
                        member_authority_pub BLOB NOT NULL,
                        device_id            TEXT NOT NULL,
                        PRIMARY KEY (realm_id, member_authority_pub, device_id)
                    )
                    """.trimIndent(),

                    // ── App↔proxy EPC rendezvous (ios-proxy-architecture.md §5) ────────────────
                    // Owner-bound, short-TTL sessions, each with two directional FIFOs of opaque
                    // sealed frames. Routing metadata + ciphertext only (HR #1).
                    """
                    CREATE TABLE IF NOT EXISTS epc_session (
                        session_id TEXT PRIMARY KEY,
                        owner_id   TEXT NOT NULL,
                        expires_at INTEGER NOT NULL
                    )
                    """.trimIndent(),
                    "CREATE INDEX IF NOT EXISTS idx_epc_session_owner ON epc_session(owner_id)",
                    """
                    CREATE TABLE IF NOT EXISTS epc_frame (
                        session_id TEXT NOT NULL,
                        direction  TEXT NOT NULL,
                        seq        INTEGER NOT NULL,
                        payload    BLOB NOT NULL,
                        PRIMARY KEY (session_id, direction, seq)
                    )
                    """.trimIndent(),

                    // ── Device admission & pairing ────────────────────────────────────────────
                    // Per-owner admission revocation list (append-only; delegated-device-admission-
                    // authority.md §5). ⚠ Durability is load-bearing: losing it silently restores a
                    // revoked device's power to admit. `device_a33_public_key` (F1′) names the A33
                    // capability revoked — NOT NULL: there is no keyless, epoch-rule form (F5b).
                    """
                    CREATE TABLE IF NOT EXISTS admission_revocation (
                        owner_id              TEXT NOT NULL,
                        device_id             TEXT NOT NULL,
                        epoch                 INTEGER NOT NULL,
                        revoked_at            INTEGER NOT NULL,
                        signature             BLOB NOT NULL,
                        device_a33_public_key BLOB NOT NULL
                    )
                    """.trimIndent(),
                    "CREATE INDEX IF NOT EXISTS idx_admission_revocation_owner ON admission_revocation(owner_id)",
                    // Offer-flow pairing rendezvous (sync-pairing-inverted-direction.md §3.4). ⚠ The
                    // row itself is the single-use consumed marker (T-P3) for its secret hash.
                    """
                    CREATE TABLE IF NOT EXISTS pairing_offer_response (
                        secret_hash  TEXT PRIMARY KEY,
                        owner_id     TEXT NOT NULL,
                        status       TEXT NOT NULL,
                        public_key_a BLOB NOT NULL,
                        payload      BLOB,
                        expires_at   INTEGER NOT NULL,
                        -- The offer flow registers the offerer when it FETCHES its approval, not when
                        -- the responder posts it (pairing-flow.md): the verified registration waits on
                        -- the terminal row. All NULL on a denial.
                        reg_device_id       TEXT,
                        reg_public_key      BLOB,
                        reg_admitted_at     TEXT,
                        -- The encoded admission chain the queued registration was verified through,
                        -- re-verified whole at delivery (S1). NULL for an A1-direct admission.
                        reg_admission_chain BLOB
                    )
                    """.trimIndent(),
                    // F2′: durable record that an owner REMOVED a device (never-return rule).
                    // ⚠⚠ Written ONLY from `revokeIfNotStale` (an applied `Device` tombstone), never
                    // for shared-realm member removal. ⚠ Never garbage-collected — deleting a row
                    // re-admits that identity.
                    """
                    CREATE TABLE IF NOT EXISTS removed_device (
                        owner_id   TEXT NOT NULL,
                        device_id  TEXT NOT NULL,
                        removed_at INTEGER NOT NULL,
                        PRIMARY KEY (owner_id, device_id)
                    )
                    """.trimIndent(),
                    // Responder's pre-approval announce (sync-pairing-scanned-announce.md A1).
                    // ⚠⚠ Separate from `pairing_offer_response`: a row THERE consumes the hash, so an
                    // announce stored there would kill its own handshake. ⚠ No `payload` column.
                    """
                    CREATE TABLE IF NOT EXISTS pairing_offer_announce (
                        secret_hash  TEXT PRIMARY KEY,
                        owner_id     TEXT NOT NULL,
                        status       TEXT NOT NULL,
                        public_key_a BLOB NOT NULL,
                        expires_at   INTEGER NOT NULL
                    )
                    """.trimIndent(),
                    // ── Content refcount / reclaim (content-plane-refcount.md, CR-2) ──────────
                    // contentId → blob refs. Chunk refs live only inside the encrypted
                    // manifest (HR #1), so the client declares them. ⚠ Keyed by CONTENT, not holder:
                    // immutable, written once.
                    """
                    CREATE TABLE IF NOT EXISTS content_ref (
                        owner_id   TEXT NOT NULL,
                        content_id TEXT NOT NULL,
                        ref        TEXT NOT NULL,
                        PRIMARY KEY (owner_id, content_id, ref)
                    )
                    """.trimIndent(),
                    "CREATE INDEX IF NOT EXISTS content_ref_by_ref ON content_ref (owner_id, ref)",
                    // Durable deletion queue (content-plane-cr2-quorumless.md §10.2). ⚠⚠ Keyed
                    // `(owner_id, ref)`, never `ref` alone. A row = committed to deletion, not yet
                    // physically deleted. ⚠ A PUT of `(owner, ref)` deletes its row. `byte_len` is
                    // copied at enqueue so a retried drain can still say what it freed.
                    """
                    CREATE TABLE IF NOT EXISTS content_free_queue (
                        owner_id    TEXT    NOT NULL,
                        ref         TEXT    NOT NULL,
                        enqueued_at INTEGER NOT NULL,
                        byte_len    INTEGER NOT NULL,
                        PRIMARY KEY (owner_id, ref)
                    )
                    """.trimIndent(),
                    // Edge REGISTERS (content-plane-cr2-b3-wire-spec.md §2/§4): one row per
                    // (owner, holder, property), stamped with the HLC of the write that set it;
                    // `content_id` NULL = cleared. ⚠ `stamp` is the HlcStamp encoding and is NEVER
                    // compared as a string — stores decode it and order with `compareHlc`.
                    """
                    CREATE TABLE IF NOT EXISTS content_edge (
                        owner_id    TEXT NOT NULL,
                        holder_hash TEXT NOT NULL,
                        property_id TEXT NOT NULL,
                        content_id  TEXT,
                        stamp       TEXT NOT NULL,
                        PRIMARY KEY (owner_id, holder_hash, property_id)
                    )
                    """.trimIndent(),
                    "CREATE INDEX IF NOT EXISTS content_edge_by_content ON content_edge (owner_id, content_id)",
                    // Highest HLC at which each holder was removed. ⚠⚠ What makes a late, OLDER push
                    // lose (rule 1).
                    """
                    CREATE TABLE IF NOT EXISTS holder_watermark (
                        owner_id    TEXT NOT NULL,
                        holder_hash TEXT NOT NULL,
                        stamp       TEXT NOT NULL,
                        PRIMARY KEY (owner_id, holder_hash)
                    )
                    """.trimIndent(),
                    // A row means the content is FREED (content-plane-cr2-b4-spec.md §1.1). ⭐ No
                    // `put_seq`: "PUT since the free" is absence from `content_free_queue`.
                    """
                    CREATE TABLE IF NOT EXISTS content_freed (
                        owner_id   TEXT    NOT NULL,
                        content_id TEXT    NOT NULL,
                        freed_at   INTEGER NOT NULL,
                        PRIMARY KEY (owner_id, content_id)
                    )
                    """.trimIndent(),
                    // The G2 clock (content-plane-cr2-b5-spec.md §1.1): live-edge count went 1→0 at
                    // `since` (or seeded at arming, D4). ⚠⚠ Never set by a describe (G4).
                    """
                    CREATE TABLE IF NOT EXISTS content_unreferenced (
                        owner_id   TEXT    NOT NULL,
                        content_id TEXT    NOT NULL,
                        since      INTEGER NOT NULL,
                        PRIMARY KEY (owner_id, content_id)
                    )
                    """.trimIndent(),
                    "CREATE INDEX IF NOT EXISTS content_unreferenced_by_since ON content_unreferenced (since)",
                    // ⚠ One row, written once by the first `armed` sweep (D4) — the seed must not
                    // re-run on every restart.
                    """
                    CREATE TABLE IF NOT EXISTS content_sweep_state (
                        id        INTEGER PRIMARY KEY CHECK (id = 0),
                        seeded_at INTEGER NOT NULL
                    )
                    """.trimIndent(),
                    // Account erasure (retention-quota-model.md): a realm the relay erased. Kept
                    // when everything else of the owner is dropped (`AccountErasure.KEPT_TABLES`).
                    """
                    CREATE TABLE IF NOT EXISTS erased_realm (
                        owner_id  TEXT PRIMARY KEY,
                        erased_at INTEGER NOT NULL
                    )
                    """.trimIndent(),
                    // Operator takedown of a share link (notice-and-action, acceptable-use-and-limits.md
                    // §3; runbook docs/how-to/take-down-a-share-link.md). Append-only: the triggers
                    // refuse UPDATE and DELETE, so the record of having acted on a notice cannot be
                    // edited after the fact. ⚠ No user plaintext: opaque ids, a fixed reason code, the
                    // operator's own note. Account closure keeps it (AccountErasure.KEPT_TABLES).
                    // `owner_id` is NULL for an unknown shareId.
                    """
                    CREATE TABLE share_takedown (
                        id            INTEGER PRIMARY KEY AUTOINCREMENT,
                        share_id      TEXT NOT NULL,
                        owner_id      TEXT,
                        taken_down_at INTEGER NOT NULL,
                        reason_code   TEXT NOT NULL,
                        operator_note TEXT,
                        outcome       TEXT NOT NULL
                    )
                    """.trimIndent(),
                    "CREATE INDEX share_takedown_share ON share_takedown(share_id)",
                    // Repeat-infringer lookups are by owner (§3).
                    "CREATE INDEX share_takedown_owner ON share_takedown(owner_id)",
                    """
                    CREATE TRIGGER share_takedown_no_update BEFORE UPDATE ON share_takedown
                    BEGIN SELECT RAISE(ABORT, 'share_takedown is append-only'); END
                    """.trimIndent(),
                    """
                    CREATE TRIGGER share_takedown_no_delete BEFORE DELETE ON share_takedown
                    BEGIN SELECT RAISE(ABORT, 'share_takedown is append-only'); END
                    """.trimIndent(),
                ),
            ),
        )
    }
}
