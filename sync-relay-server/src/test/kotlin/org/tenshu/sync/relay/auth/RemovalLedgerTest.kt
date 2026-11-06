package org.tenshu.sync.relay.auth

import org.tenshu.core.common.util.HlcStamp
import org.tenshu.sync.relay.storage.RelayDatabase
import org.tenshu.sync.relay.storage.SqliteDeviceRegistry
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * F2′ (`admission-revocation-names-the-capability.md` §7c): the durable record that an owner
 * **removed** a device, so option F's "a removed identity never returns" has something to consult.
 *
 * ⚠⚠ Before this, `revoke` was a hard `DELETE FROM device` — the relay could not answer *"has this
 * deviceId been removed before?"*, which is the exact question the never-return rule asks at admit
 * time.
 */
class RemovalLedgerTest {

    private lateinit var dbFile: File
    private lateinit var url: String

    @BeforeTest
    fun setup() {
        dbFile = File.createTempFile("relay-removal-ledger", ".db")
        url = "jdbc:sqlite:${dbFile.absolutePath}"
    }

    @AfterTest
    fun teardown() {
        dbFile.delete()
        File("${dbFile.absolutePath}-wal").delete()
        File("${dbFile.absolutePath}-shm").delete()
    }

    private fun backends(): List<Pair<String, DeviceRegistry>> = listOf(
        "in-memory" to KnownDevicesStore(),
        "sqlite" to SqliteDeviceRegistry(RelayDatabase(url)),
    )

    @Test
    fun anAppliedTombstoneRecordsTheRemoval_onBothBackends() {
        for ((name, registry) in backends()) {
            registry.register("alice", "dev-1", byteArrayOf(1), admittedAt = null)
            assertFalse(registry.wasRemoved("alice", "dev-1"), "$name: precondition")

            assertTrue(registry.revokeIfNotStale("alice", "dev-1", tombstoneHlc = null), name)

            assertTrue(registry.wasRemoved("alice", "dev-1"), "$name: the removal must be recorded")
        }
    }

    /**
     * ⚠⚠ The ordering guard and the ledger must agree. A retirement authored *before* the current
     * admission is refused — and a refused tombstone must record **nothing**, or a stale instruction
     * delayed by hours would permanently bar a device that was legitimately re-admitted. Under F
     * that bar never lifts, which is why §7c.4 says the guard becomes *more* load-bearing, not less.
     */
    @Test
    fun aStaleTombstoneRecordsNothing() {
        for ((name, registry) in backends()) {
            val admitted = HlcStamp(physicalTime = 2_000L, logicalCounter = 0, deviceId = "dev-1")
            val olderRetirement = HlcStamp(physicalTime = 1_000L, logicalCounter = 0, deviceId = "dev-1")
            registry.register("alice", "dev-1", byteArrayOf(1), admittedAt = admitted)

            assertFalse(
                registry.revokeIfNotStale("alice", "dev-1", tombstoneHlc = olderRetirement),
                "$name: a stale retirement must not apply",
            )
            assertFalse(
                registry.wasRemoved("alice", "dev-1"),
                "$name: and must not enter the ledger — under F that bar would never lift",
            )
        }
    }

    /**
     * ⚠⚠ `revoke` alone must NOT record. `MembershipGrantRouting` calls it for shared-realm
     * **member** removal, which is a different fact; recording it would repeat the conflation that
     * made §7's F2 wrong — one ledger holding two populations needing opposite treatment.
     */
    @Test
    fun aDirectRevokeDoesNotRecord_becauseMemberRemovalIsNotDeviceRemoval() {
        for ((name, registry) in backends()) {
            registry.register("alice", "dev-1", byteArrayOf(1), admittedAt = null)

            registry.revoke("alice", "dev-1")

            assertFalse(
                registry.wasRemoved("alice", "dev-1"),
                "$name: only an applied Device tombstone means the owner removed this device",
            )
        }
    }

    @Test
    fun theLedgerSurvivesAReopen_becauseItOutlivesTheRegistrationItDescribes() {
        SqliteDeviceRegistry(RelayDatabase(url)).let {
            it.register("alice", "dev-1", byteArrayOf(1), admittedAt = null)
            it.revokeIfNotStale("alice", "dev-1", tombstoneHlc = null)
        }

        val reopened = SqliteDeviceRegistry(RelayDatabase(url))

        assertTrue(
            reopened.wasRemoved("alice", "dev-1"),
            "a ledger lost on restart would silently re-admit every removed identity",
        )
    }

    @Test
    fun theLedgerIsScopedPerOwner() {
        for ((name, registry) in backends()) {
            registry.register("alice", "dev-1", byteArrayOf(1), admittedAt = null)
            registry.revokeIfNotStale("alice", "dev-1", tombstoneHlc = null)

            assertFalse(
                registry.wasRemoved("bob", "dev-1"),
                "$name: a removal in one realm says nothing about another",
            )
        }
    }
}
