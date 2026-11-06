package org.tenshu.sync.relay.storage

import org.tenshu.sync.relay.AccountErasure
import org.tenshu.sync.relay.SyncMailbox
import org.tenshu.sync.relay.erasureTransaction
import org.tenshu.sync.relay.mailbox.OwnerScopedStore
import org.tenshu.sync.relay.mailbox.ShareStore
import org.tenshu.sync.relay.ownerScopedStoresOf
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Account closure erases **every** owner-scoped relay table (`relay-metadata-minimisation.md` F12,
 * `retention-quota-model.md` P3) — proven against the schema itself, not against a list.
 *
 * ⚠⚠ The tables are enumerated from `sqlite_master` and seeded generically from `PRAGMA table_info`,
 * so a table added in a later migration is seeded and checked here **without anyone editing this
 * test**: if no store's `eraseOwner` clears it, [closure_erasesEveryOwnerScopedTable] fails and names
 * it. A table with no `owner_id`/`realm_id` column fails [everyTable_hasAClosureDecision] until it is
 * declared global or joined below — the decision cannot be skipped.
 *
 * Three owners throughout: [CLOSED] (erased), [OPEN] (another account), and [HELD] — a shared realm
 * an open account still holds. P3's "unless an open account holds it" is commerce's call, made before
 * a closure record exists; the relay's half is that erasing [CLOSED] reaches nothing keyed by any
 * other realm id, including [CLOSED]'s own admissions *into* [HELD].
 */
class AccountErasureSchemaTest {

    private lateinit var dbFile: File
    private lateinit var blobDir: File
    private lateinit var db: RelayDatabase
    private lateinit var files: FileBlobStore

    @BeforeTest
    fun setup() {
        dbFile = File.createTempFile("relay-erasure-test", ".db")
        blobDir = File.createTempFile("relay-erasure-blobs", "").also { it.delete(); it.mkdirs() }
        db = RelayDatabase("jdbc:sqlite:${dbFile.absolutePath}")
        files = FileBlobStore(blobDir)
    }

    @AfterTest
    fun teardown() {
        db.close()
        dbFile.delete()
        File("${dbFile.absolutePath}-wal").delete()
        File("${dbFile.absolutePath}-shm").delete()
        blobDir.deleteRecursively()
    }

    // ---- the relay, as production wires it -------------------------------------------------------

    private inner class Relay(blobs: BlobStore = files) {
        val codec = RelayBinaryCodec()
        val refs = SqliteContentRefStore(db)
        val mailbox = SqliteSyncMailbox(db, codec)
        val content = SqliteContentBlobStore(db, blobs)
        val shares = SqliteShareStore(db, blobs)
        val stores: List<OwnerScopedStore> = ownerScopedStoresOf(
            knownDevices = SqliteDeviceRegistry(db),
            realmStore = SqliteRealmStore(db),
            hlcTipStore = SqliteHlcTipStore(db, codec),
            syncMailbox = mailbox,
            contentEdgeStore = SqliteContentEdgeStore(db),
            contentRefStore = refs,
            contentFreeing = SqliteContentFreedStore(db, refs),
            contentBlobStore = content,
            shareStore = shares,
            inviteStore = SqliteInviteStore(db),
            grantStore = SqliteGrantStore(db),
            membershipStore = SqliteMembershipStore(db),
            rendezvousStore = SqliteRendezvousStore(db),
            pairingOfferStore = SqlitePairingOfferStore(db),
            admissionRevocationStore = SqliteAdmissionRevocationStore(db),
        )
        val erasure = AccountErasure(stores, db.erasureTransaction())
    }

    // ---- schema introspection ---------------------------------------------------------------------

    private data class Col(val name: String, val type: String)

    private fun tables(): List<String> = db.withConnection { c ->
        c.createStatement().use { st ->
            st.executeQuery(
                "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' ORDER BY name",
            ).use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
        }
    }

    private fun columns(table: String): List<Col> = db.withConnection { c ->
        c.createStatement().use { st ->
            st.executeQuery("PRAGMA table_info($table)").use { rs ->
                buildList { while (rs.next()) add(Col(rs.getString("name"), rs.getString("type").uppercase())) }
            }
        }
    }

    private fun ownerColumn(table: String): String? =
        columns(table).map { it.name }.firstOrNull { it in OWNER_COLUMNS }

    private fun ownerScopedTables(): List<String> = tables().filter { ownerColumn(it) != null }

    /** Rows of [table] belonging to [owner]; session-joined tables go through their session id. */
    private fun count(table: String, owner: String): Int = db.withConnection { c ->
        val (sql, arg) = when (table) {
            in SESSION_JOINED -> "SELECT COUNT(*) FROM $table WHERE session_id = ?" to sessionOf(owner)
            else -> "SELECT COUNT(*) FROM $table WHERE ${ownerColumn(table)} = ?" to owner
        }
        c.prepareStatement(sql).use { ps ->
            ps.setString(1, arg)
            ps.executeQuery().use { rs -> rs.next(); rs.getInt(1) }
        }
    }

    private fun countsFor(owner: String): Map<String, Int> =
        (ownerScopedTables() + SESSION_JOINED).associateWith { count(it, owner) }

    private var n = 0

    /** One generic row in every owner-scoped and session-joined table, for [owner]. */
    private fun seedEveryTable(owner: String) {
        for (table in ownerScopedTables() + SESSION_JOINED) {
            val cols = columns(table)
            val sql = "INSERT INTO $table(${cols.joinToString { it.name }}) VALUES(${cols.joinToString { "?" }})"
            db.withConnection { c ->
                c.prepareStatement(sql).use { ps ->
                    cols.forEachIndexed { i, col ->
                        val idx = i + 1
                        when {
                            col.name in OWNER_COLUMNS -> ps.setString(idx, owner)
                            col.name == "session_id" -> ps.setString(idx, sessionOf(owner))
                            // A small chunk count keeps the share byte phase to a couple of keys.
                            col.name == "chunk_count" -> ps.setLong(idx, 1)
                            // Unique, because `seq` columns are INTEGER PRIMARY KEYs shared across owners.
                            col.type.startsWith("INTEGER") -> ps.setLong(idx, (++n).toLong())
                            col.type.startsWith("BLOB") -> ps.setBytes(idx, byteArrayOf(1, 2, 3))
                            else -> ps.setString(idx, "$table-${col.name}-$owner-${++n}")
                        }
                    }
                    ps.executeUpdate()
                }
            }
        }
    }

    private fun sessionOf(owner: String) = "sess-$owner"

    private fun shareRecord(shareId: String, owner: String) = ShareStore.ShareRecord(
        shareId = shareId, ownerId = owner, sealedMeta = byteArrayOf(4),
        chunkCount = 2, chunkHashes = listOf(byteArrayOf(1), byteArrayOf(2)),
        expiresAtMillis = null, maxOpens = null, createdAtMs = 1_000,
    )

    private fun pairingRequest(secretHash: String) = SyncMailbox.PairingRequestData(
        deviceId = "joiner", sealedDeviceName = byteArrayOf(3), publicKey = ByteArray(32),
        signingPublicKey = ByteArray(32), pairingSecretHash = secretHash,
    )

    /** Generic rows everywhere, plus real bytes and the in-memory state generic SQL cannot reach. */
    private fun seedOwner(relay: Relay, owner: String) {
        seedEveryTable(owner)
        relay.content.put(owner, "ref-$owner", byteArrayOf(9, 9, 9), nowMs = 1_000)
        relay.shares.create(shareRecord("share-$owner", owner))
        relay.shares.putChunk("share-$owner", 0, byteArrayOf(7))
        relay.shares.putChunk("share-$owner", 1, byteArrayOf(8))
        relay.mailbox.addPairingRequest(owner, pairingRequest("secret-$owner"))
    }

    // ---- the tests --------------------------------------------------------------------------------

    @Test
    fun everyTable_hasAClosureDecision() {
        val undecided = tables().filter { ownerColumn(it) == null && it !in GLOBAL && it !in SESSION_JOINED }
        if (undecided.isNotEmpty()) {
            fail(
                "Tables with no owner_id/realm_id column and no closure decision: $undecided. Decide what " +
                    "account closure does with them (AccountErasure's table), then list them as GLOBAL or " +
                    "SESSION_JOINED here.",
            )
        }
        // The kept set must name real, owner-scoped tables — a rename would otherwise pass vacuously.
        assertTrue(ownerScopedTables().containsAll(AccountErasure.KEPT_TABLES), "KEPT_TABLES drifted from the schema")
    }

    @Test
    fun closure_erasesEveryOwnerScopedTable() {
        val relay = Relay()
        seedOwner(relay, CLOSED)
        seedOwner(relay, OPEN)
        seedOwner(relay, HELD)
        val closedBefore = countsFor(CLOSED)
        val openBefore = countsFor(OPEN)
        val heldBefore = countsFor(HELD)
        assertTrue(closedBefore.values.all { it > 0 }, "every table seeded for the closed owner: $closedBefore")

        val report = relay.erasure.eraseOwner(CLOSED)

        val leftovers = countsFor(CLOSED).filter { (table, rows) ->
            rows != if (table in AccountErasure.KEPT_TABLES) closedBefore.getValue(table) else 0
        }
        assertTrue(leftovers.isEmpty(), "closure left the closed owner's rows in: $leftovers")
        assertEquals(openBefore, countsFor(OPEN), "another open account is untouched")
        assertEquals(heldBefore, countsFor(HELD), "the shared realm an open account holds is untouched")

        // Bytes: content and share chunks of the closed owner are gone, everyone else's are there.
        assertFalse(files.exists("$CLOSED/ref-$CLOSED"))
        assertFalse(files.exists("share/share-$CLOSED/0") || files.exists("share/share-$CLOSED/1"))
        for (owner in listOf(OPEN, HELD)) {
            assertTrue(files.exists("$owner/ref-$owner"))
            assertTrue(files.exists("share/share-$owner/0") && files.exists("share/share-$owner/1"))
        }

        // In-memory state no SQL table holds.
        assertTrue(relay.mailbox.getPairingRequests(CLOSED).isEmpty())
        assertEquals(1, relay.mailbox.getPairingRequests(OPEN).size)

        assertTrue(report.rowsErased > 0 && report.blobsDropped > 0, "the report reflects the erasure: $report")
    }

    @Test
    fun closure_isIdempotent_aSecondRunErasesNothingAndBreaksNothing() {
        val relay = Relay()
        seedOwner(relay, CLOSED)
        seedOwner(relay, OPEN)
        relay.erasure.eraseOwner(CLOSED)
        val closedAfterFirst = countsFor(CLOSED)
        val openAfterFirst = countsFor(OPEN)

        // Commerce re-posts every sweep while the account stays closed: the rerun must be a clean no-op.
        val second = relay.erasure.eraseOwner(CLOSED)

        assertEquals(AccountErasure.Report.NOTHING, second)
        assertEquals(closedAfterFirst, countsFor(CLOSED))
        assertEquals(openAfterFirst, countsFor(OPEN))
    }

    @Test
    fun aByteDeleteFailure_leavesTheLedger_andTheNextRunFinishes() {
        var failDeletes = true
        val flaky = object : BlobStore by files {
            override fun deleteAll(keys: Collection<String>) {
                if (failDeletes) error("object store unavailable")
                files.deleteAll(keys)
            }
        }
        val relay = Relay(flaky)
        seedOwner(relay, CLOSED)

        assertFailsWith<IllegalStateException> { relay.erasure.eraseOwner(CLOSED) }

        // ⚠⚠ The rows committed (closure took effect), and the bytes are still NAMED — by the free queue
        // and the revoked share rows. Without that ledger these bytes could never be found again.
        assertEquals(0, count("realm", CLOSED))
        assertEquals(0, count("content_blob", CLOSED))
        assertTrue(count("content_free_queue", CLOSED) > 0)
        assertTrue(files.exists("$CLOSED/ref-$CLOSED"))

        failDeletes = false
        relay.erasure.eraseOwner(CLOSED)

        assertEquals(0, count("content_free_queue", CLOSED))
        assertEquals(0, count("share_record", CLOSED))
        assertFalse(files.exists("$CLOSED/ref-$CLOSED"))
        assertFalse(files.exists("share/share-$CLOSED/0"))
    }

    @Test
    fun aRowPhaseFailure_rollsBackEveryStore() {
        val relay = Relay()
        seedOwner(relay, CLOSED)
        val before = countsFor(CLOSED)
        val failing = object : OwnerScopedStore {
            override fun eraseOwner(ownerId: String): Int = error("store failed mid-closure")
        }
        // Appended last, so every real store has already deleted inside the transaction when it throws.
        val erasure = AccountErasure(relay.stores + failing, db.erasureTransaction())

        assertFailsWith<IllegalStateException> { erasure.eraseOwner(CLOSED) }

        assertEquals(before, countsFor(CLOSED), "nothing committed: a half-erased owner is never visible")
        assertTrue(files.exists("$CLOSED/ref-$CLOSED"), "and no byte was touched")
    }

    @Test
    fun aBlankOwner_isRefusedRatherThanMatchingEverything() {
        val relay = Relay()
        seedOwner(relay, OPEN)
        assertFailsWith<IllegalArgumentException> { relay.erasure.eraseOwner("") }
        assertFailsWith<IllegalArgumentException> { relay.erasure.eraseOwner("  ") }
        assertTrue(countsFor(OPEN).values.all { it > 0 })
    }

    private companion object {
        const val CLOSED = "owner-closed"
        const val OPEN = "owner-open"
        const val HELD = "realm-shared-held-by-open-account"

        val OWNER_COLUMNS = setOf("owner_id", "realm_id")

        /**
         * Not owner data: migration bookkeeping (incl. the 2026-10 re-cut's `schema_baseline` marker),
         * the global seq counter, the one-row sweep marker.
         */
        val GLOBAL = setOf("schema_version", "schema_baseline", "relay_seq", "content_sweep_state")

        /** Owner data reached only through an owner's `epc_session` row. */
        val SESSION_JOINED = listOf("epc_frame")
    }
}
