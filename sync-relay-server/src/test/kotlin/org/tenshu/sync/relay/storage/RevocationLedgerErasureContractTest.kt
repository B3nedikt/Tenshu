package org.tenshu.sync.relay.storage

import org.tenshu.sync.relay.AccountErasure
import org.tenshu.sync.relay.auth.DeviceRegistry
import org.tenshu.sync.relay.auth.KnownDevicesStore
import org.tenshu.sync.relay.erasureTransaction
import org.tenshu.sync.relay.mailbox.AdmissionRevocationRegistry
import org.tenshu.sync.relay.mailbox.AdmissionRevocationStore
import org.tenshu.sync.relay.mailbox.MembershipRegistry
import org.tenshu.sync.relay.mailbox.MembershipStore
import org.tenshu.sync.relay.mailbox.RealmRegistry
import org.tenshu.sync.relay.mailbox.RealmStore
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * One closure contract for the revocation ledgers, run against **both** twins — SQLite and in-memory —
 * so they cannot drift apart (`relay-metadata-minimisation.md` F12).
 *
 * ⚠⚠ Closure erases `removed_device`, `admission_revocation` and `member_revocation` (the
 * `erased_realm` tombstone replaces them) in SQLite, and the in-memory `KnownDevicesStore` and
 * `MembershipStore` must do the same; a twin that keeps its ledgers passes every SQLite-only test.
 * `AccountErasureSchemaTest` checks SQLite against the schema; this pins the twins to each other
 * through the registries' own read API.
 */
class RevocationLedgerErasureContractTest {

    private lateinit var dbFile: File
    private lateinit var db: RelayDatabase

    @BeforeTest
    fun setup() {
        dbFile = File.createTempFile("relay-ledger-erasure-test", ".db")
        db = RelayDatabase("jdbc:sqlite:${dbFile.absolutePath}")
    }

    @AfterTest
    fun teardown() {
        db.close()
        dbFile.delete()
        File("${dbFile.absolutePath}-wal").delete()
        File("${dbFile.absolutePath}-shm").delete()
    }

    private class Twin(
        val name: String,
        val devices: DeviceRegistry,
        val realms: RealmRegistry,
        val membership: MembershipRegistry,
        val admissions: AdmissionRevocationRegistry,
        transaction: (() -> Unit) -> Unit,
    ) {
        val erasure = AccountErasure(listOf(devices, realms, membership, admissions), transaction)
    }

    private fun sqlite() = Twin(
        "sqlite",
        SqliteDeviceRegistry(db), SqliteRealmStore(db), SqliteMembershipStore(db), SqliteAdmissionRevocationStore(db),
        db.erasureTransaction(),
    )

    private fun inMemory() = Twin(
        "in-memory",
        KnownDevicesStore(), RealmStore(), MembershipStore(), AdmissionRevocationStore(),
        { it() },
    )

    private fun Twin.seed(owner: String) {
        realms.createIfAbsent(RealmStore.RealmRecord(owner, ByteArray(32), NOW))
        devices.register(owner, "phone", ByteArray(32))
        devices.recordRemoval(owner, "old-phone")
        membership.appendRevocation(MembershipRegistry.RevocationRecord(owner, byteArrayOf(6), 1, NOW, byteArrayOf(0)))
        admissions.appendRevocation(AdmissionRevocationRegistry.RevocationRecord(owner, "old-phone", 1, NOW, byteArrayOf(0), ByteArray(32) { 5 }))
    }

    /** What a registry says about [owner] after closure — the observable outcome both twins must share. */
    private fun Twin.observe(owner: String) = listOf(
        realms.isErased(owner),
        realms.get(owner) != null,
        devices.getPublicKey(owner, "phone") != null,
        devices.wasRemoved(owner, "old-phone"),
        membership.revocations(owner).size,
        admissions.revocations(owner).size,
    )

    @Test
    fun `both twins erase the revocation ledgers and leave only the erased-realm tombstone`() {
        val outcomes = listOf(sqlite(), inMemory()).associate { twin ->
            twin.seed(CLOSED)
            twin.seed(OPEN)
            twin.erasure.eraseOwner(CLOSED)

            // The closed owner: tombstoned, and nothing else left — fences included.
            assertTrue(twin.realms.isErased(CLOSED), "${twin.name}: no erased_realm tombstone")
            assertFalse(twin.devices.wasRemoved(CLOSED, "old-phone"), "${twin.name}: kept removed_device")
            assertTrue(twin.membership.revocations(CLOSED).isEmpty(), "${twin.name}: kept member_revocation")
            assertTrue(twin.admissions.revocations(CLOSED).isEmpty(), "${twin.name}: kept admission_revocation")

            // The open owner: untouched, fences included.
            assertFalse(twin.realms.isErased(OPEN), "${twin.name}: tombstoned the open owner")
            assertTrue(twin.devices.wasRemoved(OPEN, "old-phone"), "${twin.name}: erased the open owner's ledger")
            assertEquals(1, twin.membership.revocations(OPEN).size, twin.name)
            assertEquals(1, twin.admissions.revocations(OPEN).size, twin.name)

            twin.name to (twin.observe(CLOSED) to twin.observe(OPEN))
        }
        assertEquals(outcomes.getValue("sqlite"), outcomes.getValue("in-memory"), "the twins disagree after closure")
        // No revocation fence is kept; `share_takedown` is the operator's abuse-notice record, not a fence.
        assertEquals(setOf("erased_realm", "share_takedown"), AccountErasure.KEPT_TABLES)
    }

    private companion object {
        const val CLOSED = "owner-closed"
        const val OPEN = "owner-open"
        const val NOW = 1_000_000L
    }
}
