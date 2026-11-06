package org.tenshu.sync.relay.storage

import java.io.File
import org.tenshu.sync.relay.mailbox.AdmissionRevocationRegistry
import org.tenshu.sync.relay.mailbox.AdmissionRevocationStore
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Durable admission revocation list (`delegated-device-admission-authority.md`
 * §5): SPI parity with the in-memory [AdmissionRevocationStore] and persistence
 * across a reopen — durability is the point of the store (a list lost on
 * restart would silently restore a revoked device's power to admit).
 */
class SqliteAdmissionRevocationStoreTest {

    private lateinit var dbFile: File
    private lateinit var url: String

    @BeforeTest
    fun setup() {
        dbFile = File.createTempFile("relay-admission-test", ".db")
        url = "jdbc:sqlite:${dbFile.absolutePath}"
    }

    @AfterTest
    fun teardown() {
        dbFile.delete()
        File("${dbFile.absolutePath}-wal").delete()
        File("${dbFile.absolutePath}-shm").delete()
    }

    private fun record(deviceId: String, owner: String = "alice") =
        AdmissionRevocationRegistry.RevocationRecord(
            ownerId = owner,
            deviceId = deviceId,
            epoch = 4L,
            revokedAt = 9_000L,
            signature = byteArrayOf(1, 2, 3, 4),
            deviceA33PublicKey = ByteArray(32) { 3 },
        )

    private fun backends(): List<Pair<String, AdmissionRevocationRegistry>> = listOf(
        "in-memory" to AdmissionRevocationStore(),
        "sqlite" to SqliteAdmissionRevocationStore(RelayDatabase(url)),
    )

    /**
     * F1′: the A33 capability key must survive the store, on **both** backends and across a reopen.
     *
     * ⚠⚠ Losing it here is silent and total: since F5b the key is the *only* thing a revocation
     * matches on, so a key accepted by the route, written, and then dropped on the way back would
     * make every revocation strip nothing — which reads exactly like a working system.
     */
    @Test
    fun theCapabilityKeySurvivesBothBackends() {
        val key = ByteArray(32) { (it + 7).toByte() }
        for ((name, store) in backends()) {
            store.appendRevocation(record("device-keyed").copy(deviceA33PublicKey = key))

            assertContentEquals(
                key,
                store.revocations("alice").single { it.deviceId == "device-keyed" }.deviceA33PublicKey,
                "$name: the key-scoped revocation must keep its key",
            )
        }
    }

    /**
     * ⚠⚠ F5b: the column is `NOT NULL`, so the durable ledger cannot hold a keyless revocation even
     * if a future route forgot to require one. Written straight at the table to bypass the Kotlin
     * type, which is the only other thing standing in the way.
     */
    @Test
    fun theDurableLedgerRefusesAKeylessRow() {
        val db = RelayDatabase(url)
        val refused = runCatching {
            db.withConnection { conn ->
                conn.prepareStatement(
                    """
                    INSERT INTO admission_revocation(owner_id, device_id, epoch, revoked_at, signature, device_a33_public_key)
                    VALUES('alice', 'device-keyless', 1, 1, x'01', NULL)
                    """.trimIndent()
                ).use { it.executeUpdate() }
            }
        }
        assertTrue(refused.isFailure, "a NULL device_a33_public_key was stored")
        assertEquals(0, SqliteAdmissionRevocationStore(db).recordCount())
    }

    @Test
    fun theCapabilityKeySurvivesAReopen() {
        val key = ByteArray(32) { (it + 11).toByte() }
        SqliteAdmissionRevocationStore(RelayDatabase(url))
            .appendRevocation(record("device-keyed").copy(deviceA33PublicKey = key))

        // A fresh store over the same file — durability is the point (see class KDoc).
        val reopened = SqliteAdmissionRevocationStore(RelayDatabase(url))

        assertContentEquals(
            key,
            reopened.revocations("alice").single().deviceA33PublicKey,
            "a key lost on restart would silently restore the device's minting power",
        )
    }

    @Test
    fun appendAndRead_parityAcrossBackends() {
        for ((name, store) in backends()) {
            store.appendRevocation(record("device-x"))
            store.appendRevocation(record("device-y"))
            store.appendRevocation(record("device-z", owner = "bob"))

            val alice = store.revocations("alice")
            assertEquals(2, alice.size, name)
            assertTrue(alice.any { it.deviceId == "device-x" }, name)
            val x = alice.first { it.deviceId == "device-x" }
            assertEquals(4L, x.epoch, name)
            assertEquals(9_000L, x.revokedAt, name)
            assertContentEquals(byteArrayOf(1, 2, 3, 4), x.signature, name)

            assertEquals(1, store.revocations("bob").size, name)
            assertTrue(store.revocations("nobody").isEmpty(), name)
            assertEquals(3, store.recordCount(), name)
        }
    }

    @Test
    fun revocations_surviveReopen() {
        SqliteAdmissionRevocationStore(RelayDatabase(url)).appendRevocation(record("device-x"))

        val reopened = SqliteAdmissionRevocationStore(RelayDatabase(url))
        assertEquals(1, reopened.revocations("alice").size)
        assertEquals("device-x", reopened.revocations("alice")[0].deviceId)
    }
}
