package org.tenshu.sync.relay.storage

import org.tenshu.core.common.module.coreCommonModule
import org.tenshu.core.common.proxy.createDefaultSchemaRegistry
import org.tenshu.core.core.DataFactory
import org.tenshu.core.core.SchemaProvider
import org.tenshu.core.core.TComponent
import org.tenshu.core.core.TContext
import org.tenshu.core.time.HybridLogicalClock
import org.tenshu.identity.common.identityServiceCommonModule
import org.tenshu.sync.SignedHlcTip
import org.tenshu.sync.common.syncServiceCommonModule
import org.tenshu.sync.relay.mailbox.RealmStore
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import org.tenshu.core.common.util.HlcStamp
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Persistence: the device registry + realm store survive a process restart
 * (modelled as close + reopen on the same file). Without it, a paired device gets a 401
 * after every relay restart.
 */
class RelayPersistenceTest {

    private lateinit var dbFile: File
    private lateinit var url: String

    @BeforeTest
    fun setup() {
        dbFile = File.createTempFile("relay-persistence-test", ".db")
        url = "jdbc:sqlite:${dbFile.absolutePath}"
    }

    @AfterTest
    fun teardown() {
        dbFile.delete()
        File("${dbFile.absolutePath}-wal").delete()
        File("${dbFile.absolutePath}-shm").delete()
    }

    @Test
    fun deviceRegistry_roundTrip_deviceIds_and_revoke() {
        RelayDatabase(url).use { db ->
            val reg = SqliteDeviceRegistry(db)
            reg.register("owner-a", "device-1", byteArrayOf(1, 2, 3))
            reg.register("owner-a", "device-2", byteArrayOf(4, 5, 6))
            reg.register("owner-b", "device-9", byteArrayOf(7))

            assertContentEquals(byteArrayOf(1, 2, 3), reg.getPublicKey("owner-a", "device-1"))
            assertNull(reg.getPublicKey("owner-a", "missing"))
            assertEquals(setOf("device-1", "device-2"), reg.deviceIds("owner-a"))

            reg.revoke("owner-a", "device-1")
            assertNull(reg.getPublicKey("owner-a", "device-1"))
            assertEquals(setOf("device-2"), reg.deviceIds("owner-a"))
        }
    }

    @Test
    fun aTombstoneAuthoredBeforeTheAdmissionDoesNotRevoke() {
        // ⚠⚠ A "retire this device" tombstone authored in the morning that could not push (401 on a
        // wiped relay, then 402 with no entitlement) sits in the client's queue and arrives that
        // evening — AFTER the device was legitimately re-admitted. Applying `revokedDeviceId`
        // unconditionally would revoke it. Delivery order is not authorship order: this registry
        // respects the HLC ordering every peer does.
        RelayDatabase(url).use { db ->
            val reg = SqliteDeviceRegistry(db)
            val morning = HlcStamp(physicalTime = 1_000, logicalCounter = 0, deviceId = "phone")
            val evening = HlcStamp(physicalTime = 9_000, logicalCounter = 0, deviceId = "phone")

            reg.register("owner-a", "device-1", byteArrayOf(1), admittedAt = evening)

            val applied = reg.revokeIfNotStale("owner-a", "device-1", tombstoneHlc = morning)

            assertFalse(applied, "a tombstone older than the admission must not revoke it")
            assertContentEquals(
                byteArrayOf(1),
                reg.getPublicKey("owner-a", "device-1"),
                "the re-admitted device must still be registered",
            )
        }
    }

    @Test
    fun aTombstoneAuthoredAfterTheAdmissionStillRevokes() {
        // The other half, and the one that matters for security: the guard must not turn into
        // "never revoke". A retirement authored after the admission it supersedes still applies.
        RelayDatabase(url).use { db ->
            val reg = SqliteDeviceRegistry(db)
            val admitted = HlcStamp(1_000, 0, "phone")
            val laterRetirement = HlcStamp(9_000, 0, "phone")

            reg.register("owner-a", "device-1", byteArrayOf(1), admittedAt = admitted)

            assertTrue(reg.revokeIfNotStale("owner-a", "device-1", laterRetirement))
            assertNull(reg.getPublicKey("owner-a", "device-1"))
        }
    }

    @Test
    fun tiesGoToTheTombstone_matchingTheClients() {
        // `SyncProcessor.resolveConflictAndSave` short-circuits an update when
        // `tombstone.compare(update) >= 0` — the tombstone wins ties. The relay must agree: two
        // replicas that break ties differently disagree about whether a device is a member.
        RelayDatabase(url).use { db ->
            val reg = SqliteDeviceRegistry(db)
            val sameInstant = HlcStamp(5_000, 7, "phone")
            reg.register("owner-a", "device-1", byteArrayOf(1), admittedAt = sameInstant)

            assertTrue(reg.revokeIfNotStale("owner-a", "device-1", sameInstant))
            assertNull(reg.getPublicKey("owner-a", "device-1"))
        }
    }

    @Test
    fun anUnstampedAdmissionRevokesAsBefore() {
        // Rows admitted before this shipped (and any route that sends no stamp) have no basis for
        // ordering, so the revocation must apply exactly as it always did. The guard may make the
        // relay stricter about STALE instructions; it must never make it forget a device.
        RelayDatabase(url).use { db ->
            val reg = SqliteDeviceRegistry(db)
            reg.register("owner-a", "device-1", byteArrayOf(1))

            assertNull(reg.admittedAt("owner-a", "device-1"))
            assertTrue(reg.revokeIfNotStale("owner-a", "device-1", HlcStamp(1, 0, "phone")))
            assertNull(reg.getPublicKey("owner-a", "device-1"))
        }
    }

    @Test
    fun aReAdmissionWithoutAStampClearsTheOldOne() {
        // A stamp that outlived the admission it described would let the guard reject a later,
        // legitimate revocation — the failure mode that fails OPEN, so it is the dangerous one.
        RelayDatabase(url).use { db ->
            val reg = SqliteDeviceRegistry(db)
            reg.register("owner-a", "device-1", byteArrayOf(1), admittedAt = HlcStamp(9_000, 0, "phone"))
            reg.register("owner-a", "device-1", byteArrayOf(2))

            assertNull(reg.admittedAt("owner-a", "device-1"), "the superseded stamp must not survive")
            assertTrue(reg.revokeIfNotStale("owner-a", "device-1", HlcStamp(1_000, 0, "phone")))
        }
    }

    @Test
    fun theStampSurvivesReopen() {
        val stamp = HlcStamp(4_242, 3, "phone")
        RelayDatabase(url).use { db ->
            SqliteDeviceRegistry(db).register("owner-a", "device-1", byteArrayOf(1), admittedAt = stamp)
        }
        RelayDatabase(url).use { db ->
            assertEquals(stamp, SqliteDeviceRegistry(db).admittedAt("owner-a", "device-1"))
        }
    }

    @Test
    fun deviceRegistry_register_replacesExistingKey() {
        RelayDatabase(url).use { db ->
            val reg = SqliteDeviceRegistry(db)
            reg.register("owner-a", "device-1", byteArrayOf(1))
            reg.register("owner-a", "device-1", byteArrayOf(9, 9, 9))
            assertContentEquals(byteArrayOf(9, 9, 9), reg.getPublicKey("owner-a", "device-1"))
            assertEquals(setOf("device-1"), reg.deviceIds("owner-a"))
        }
    }

    @Test
    fun deviceRegistry_survivesReopen() {
        val key = byteArrayOf(10, 20, 30, 40)
        RelayDatabase(url).use { db -> SqliteDeviceRegistry(db).register("owner-a", "device-1", key) }
        // New process: fresh connection + registry instance on the same file.
        RelayDatabase(url).use { db ->
            assertContentEquals(key, SqliteDeviceRegistry(db).getPublicKey("owner-a", "device-1"))
        }
    }

    @Test
    fun realmStore_createIfAbsent_isAtomic_and_get() {
        RelayDatabase(url).use { db ->
            val store = SqliteRealmStore(db)
            assertTrue(store.createIfAbsent(RealmStore.RealmRecord("owner-a", byteArrayOf(1, 2), 1000L)))
            // Second insert for the same ownerId loses — exactly one winner (relay → 409).
            assertFalse(store.createIfAbsent(RealmStore.RealmRecord("owner-a", byteArrayOf(9), 9999L)))

            val rec = store.get("owner-a")!!
            assertEquals("owner-a", rec.ownerId)
            assertContentEquals(byteArrayOf(1, 2), rec.masterPublicKey)
            assertEquals(1000L, rec.createdAt)
            assertNull(store.get("owner-unknown"))
        }
    }

    @Test
    fun realmStore_survivesReopen() {
        RelayDatabase(url).use { db ->
            SqliteRealmStore(db).createIfAbsent(RealmStore.RealmRecord("owner-a", byteArrayOf(5, 6, 7), 4242L))
        }
        RelayDatabase(url).use { db ->
            val rec = SqliteRealmStore(db).get("owner-a")!!
            assertContentEquals(byteArrayOf(5, 6, 7), rec.masterPublicKey)
            assertEquals(4242L, rec.createdAt)
        }
    }

    /** F8: the baseline has no `recovery_blob` — the relay holds no A9 server-side blob. */
    @Test
    fun freshDatabase_hasNoRecoveryBlobTable() {
        RelayDatabase(url).close()
        java.sql.DriverManager.getConnection(url).use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery("SELECT count(*) FROM sqlite_master WHERE name = 'recovery_blob'").use { rs ->
                    rs.next()
                    assertEquals(0, rs.getInt(1))
                }
            }
        }
    }

    @Test
    fun hlcTip_roundTrip_peerTips_and_allTips() {
        RelayDatabase(url).use { db ->
            val ctx = ctx()
            val store = SqliteHlcTipStore(db, RelayBinaryCodec())
            store.record("alice", tip(ctx, "alice", "deviceA", 100L))
            store.record("alice", tip(ctx, "alice", "deviceB", 200L))

            assertEquals(
                setOf("deviceB"),
                store.getPeerTips("alice", excludingDeviceId = "deviceA").map { it.deviceId.get() }.toSet(),
            )
            assertEquals(
                setOf("deviceA", "deviceB"),
                store.allTips("alice").map { it.deviceId.get() }.toSet(),
            )
        }
    }

    @Test
    fun hlcTip_record_replacesEarlierTipForSameDevice() {
        RelayDatabase(url).use { db ->
            val ctx = ctx()
            val store = SqliteHlcTipStore(db, RelayBinaryCodec())
            store.record("alice", tip(ctx, "alice", "deviceA", 100L))
            store.record("alice", tip(ctx, "alice", "deviceA", 200L))

            val tips = store.allTips("alice")
            assertEquals(1, tips.size)
            assertEquals(200L, tips.single().hlc.physicalTime.get())
        }
    }

    @Test
    fun hlcTip_survivesReopen() {
        RelayDatabase(url).use { db ->
            SqliteHlcTipStore(db, RelayBinaryCodec()).record("alice", tip(ctx(), "alice", "deviceA", 777L))
        }
        // New process: fresh connection + fresh codec; the serialized tip must round-trip.
        RelayDatabase(url).use { db ->
            val tips = SqliteHlcTipStore(db, RelayBinaryCodec()).allTips("alice")
            assertEquals(1, tips.size)
            assertEquals("deviceA", tips.single().deviceId.get())
            assertEquals(777L, tips.single().hlc.physicalTime.get())
        }
    }

    @Test
    fun migrations_areIdempotent_acrossReopen() {
        // Opening the same file twice must not re-run V1 or throw (schema_version gate).
        RelayDatabase(url).use { /* migrates to V1 */ }
        RelayDatabase(url).use { db ->
            // Still usable after the second open.
            SqliteDeviceRegistry(db).register("o", "d", byteArrayOf(1))
            assertContentEquals(byteArrayOf(1), SqliteDeviceRegistry(db).getPublicKey("o", "d"))
        }
    }

    /**
     * ⚠⚠ Each re-cut folds its steps into a new v1 and does not migrate. A relay file written by an
     * earlier chain records version 1–5 — all ≥ the new baseline — so the runner would
     * otherwise skip everything and serve a schema it never created. It must refuse instead, naming
     * the fix. (Version 1 is the sharp case: the number alone cannot tell the two chains apart.)
     */
    @Test
    fun aPreSquashDatabase_isRefused_notSkipped() {
        for (oldVersion in 1..5) {
            val file = File.createTempFile("relay-presquash-$oldVersion", ".db").apply { delete() }
            try {
                java.sql.DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { conn ->
                    conn.createStatement().use { st ->
                        st.execute("CREATE TABLE schema_version (version INTEGER NOT NULL)")
                        for (v in 1..oldVersion) st.execute("INSERT INTO schema_version(version) VALUES($v)")
                    }
                }
                val e = assertFailsWith<IllegalStateException>("version $oldVersion") {
                    RelayDatabase("jdbc:sqlite:${file.absolutePath}")
                }
                assertTrue("PRE-SQUASH" in e.message.orEmpty() && "wipe it" in e.message.orEmpty(), e.message)
            } finally {
                file.delete()
                File("${file.absolutePath}-wal").delete()
                File("${file.absolutePath}-shm").delete()
            }
        }
    }

    /**
     * ⚠⚠ A database from an earlier cut of the v1 baseline carries an earlier marker and a schema the
     * runner would never fix: `2026-10-reset` (a NULLABLE `device_a33_public_key`) and
     * `2026-10-reset-b` (at version 5, with v2–v5 as separate steps). Both refused.
     */
    @Test
    fun aDatabaseFromAnEarlierCutOfTheBaseline_isRefused() {
        for ((marker, version) in listOf("2026-10-reset" to 1, "2026-10-reset-b" to 5)) {
            refusesEarlierCut(marker, version)
        }
    }

    private fun refusesEarlierCut(marker: String, version: Int) {
        val file = File.createTempFile("relay-earlier-cut", ".db").apply { delete() }
        try {
            java.sql.DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { conn ->
                conn.createStatement().use { st ->
                    st.execute("CREATE TABLE schema_version (version INTEGER NOT NULL)")
                    for (v in 1..version) st.execute("INSERT INTO schema_version(version) VALUES($v)")
                    st.execute("CREATE TABLE schema_baseline (cut TEXT NOT NULL)")
                    st.execute("INSERT INTO schema_baseline(cut) VALUES('$marker')")
                }
            }
            val e = assertFailsWith<IllegalStateException> { RelayDatabase("jdbc:sqlite:${file.absolutePath}") }
            assertTrue("'$marker'" in e.message.orEmpty() && "wipe it" in e.message.orEmpty(), e.message)
        } finally {
            file.delete()
            File("${file.absolutePath}-wal").delete()
            File("${file.absolutePath}-shm").delete()
        }
    }

    // --- HLC-tip helpers (mirror HlcTipStoreTest): build SignedHlcTip wire objects. ---

    private fun ctx(): TContext {
        val ctx = object : TContext {
            override var components: Map<String, TComponent> = emptyMap()
        }
        val registry = createDefaultSchemaRegistry(
            tContextProvider = { ctx },
            extraConfigs = listOf(coreCommonModule, syncServiceCommonModule, identityServiceCommonModule),
        )
        ctx.components = mapOf(
            SchemaProvider.COMPONENT_ID to registry,
            DataFactory.COMPONENT_ID to registry,
        )
        return ctx
    }

    private fun tip(ctx: TContext, owner: String, deviceId: String, physicalTime: Long) =
        SignedHlcTip.create(
            context = ctx,
            ownerId = owner,
            deviceId = deviceId,
            hlc = HybridLogicalClock.create(ctx, physicalTime, 0, deviceId),
            signature = byteArrayOf(0x01),
        )
}
