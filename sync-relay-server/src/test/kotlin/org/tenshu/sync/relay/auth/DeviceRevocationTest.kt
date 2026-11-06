package org.tenshu.sync.relay.auth

import org.tenshu.core.common.module.coreCommonModule
import org.tenshu.core.common.proxy.createDefaultSchemaRegistry
import org.tenshu.core.common.util.HlcStamp
import org.tenshu.core.core.DataFactory
import org.tenshu.core.core.SchemaProvider
import org.tenshu.core.core.TComponent
import org.tenshu.core.core.TContext
import org.tenshu.core.time.HybridLogicalClock
import org.tenshu.identity.common.identityServiceCommonModule
import org.tenshu.sync.DeleteBlock
import org.tenshu.sync.SignedHlcTip
import org.tenshu.sync.common.syncServiceCommonModule
import org.tenshu.sync.relay.SyncMailbox
import org.tenshu.sync.relay.mailbox.HlcTipRegistry
import org.tenshu.sync.relay.mailbox.HlcTipStore
import org.tenshu.sync.relay.storage.RelayBinaryCodec
import org.tenshu.sync.relay.storage.RelayDatabase
import org.tenshu.sync.relay.storage.SqliteDeviceRegistry
import org.tenshu.sync.relay.storage.SqliteHlcTipStore
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * F11 / Phase 4 (b) (`relay-metadata-minimisation.md`): revoking a device forgets its `hlc_tip`
 * row. Every case runs against **both** backends — the SQLite store production runs
 * and the in-memory twin the route tests use — so the two cannot drift on what a revocation leaves.
 */
class DeviceRevocationTest {

    private val owner = "owner-f11"
    private val kept = "device-kept"
    private val gone = "device-gone"

    private lateinit var dbFile: File
    private lateinit var db: RelayDatabase

    @BeforeTest
    fun setup() {
        dbFile = File.createTempFile("relay-f11-revocation", ".db")
        db = RelayDatabase("jdbc:sqlite:${dbFile.absolutePath}")
    }

    @AfterTest
    fun teardown() {
        db.close()
        dbFile.delete()
        File("${dbFile.absolutePath}-wal").delete()
        File("${dbFile.absolutePath}-shm").delete()
    }

    private val ctx: TContext = object : TContext {
        override var components: Map<String, TComponent> = emptyMap()
    }.also { ctx ->
        val registry = createDefaultSchemaRegistry(
            tContextProvider = { ctx },
            extraConfigs = listOf(coreCommonModule, syncServiceCommonModule, identityServiceCommonModule),
        )
        ctx.components = mapOf(SchemaProvider.COMPONENT_ID to registry, DataFactory.COMPONENT_ID to registry)
    }

    private class Backend(
        val name: String,
        val devices: DeviceRegistry,
        val tips: HlcTipRegistry,
        val transaction: (() -> Unit) -> Unit,
    ) {
        fun revocation(
            tips: HlcTipRegistry = this.tips,
        ) = DeviceRevocation(devices, tips, transaction = transaction)
    }

    private fun backends(): List<Backend> = listOf(
        Backend("in-memory", KnownDevicesStore(), HlcTipStore()) { it() },
        Backend(
            "sqlite",
            SqliteDeviceRegistry(db),
            SqliteHlcTipStore(db, RelayBinaryCodec()),
        ) { block -> db.inTransaction { block() } },
    )

    private fun tip(deviceId: String, physical: Long): SignedHlcTip = SignedHlcTip.create(
        context = ctx,
        ownerId = owner,
        deviceId = deviceId,
        hlc = HybridLogicalClock.create(ctx, physical, 0, deviceId),
        signature = byteArrayOf(1),
    )

    /** Two registered devices, each with a tip. */
    private fun Backend.seed(admittedAt: HlcStamp? = null) {
        devices.register(owner, kept, "k".encodeToByteArray())
        devices.register(owner, gone, "g".encodeToByteArray(), admittedAt)
        tips.record(owner, tip(kept, 2_000))
        tips.record(owner, tip(gone, 500))
    }

    private fun Backend.tipDevices(): Set<String> = tips.allTips(owner).map { it.deviceId.get() }.toSet()

    @Test
    fun anAppliedDeviceTombstone_forgetsTheTip_andOnlyThatDevices() {
        for (b in backends()) {
            b.seed()
            assertTrue(b.revocation().applyDeviceTombstone(owner, gone, HlcStamp(1_000, 0, kept)), b.name)

            assertEquals(setOf(kept), b.tipDevices(), "${b.name}: the revoked device's tip must be gone")
            assertEquals(null, b.devices.getPublicKey(owner, gone), b.name)
            assertTrue(b.devices.wasRemoved(owner, gone), "${b.name}: the never-return ledger still records it")
            // Re-delivery of the same tombstone is a no-op, not an error.
            assertTrue(b.revocation().applyDeviceTombstone(owner, gone, HlcStamp(1_000, 0, kept)), b.name)
            assertEquals(setOf(kept), b.tipDevices(), b.name)
        }
    }

    @Test
    fun aStaleDeviceTombstone_revokesNothing_soForgetsNothing() {
        // Authored at 1 000, admission on record at 2 000: the device was re-admitted after the
        // retirement was authored, so it is still in the realm and its sync state is live.
        for (b in backends()) {
            b.seed(admittedAt = HlcStamp(2_000, 0, kept))
            assertFalse(b.revocation().applyDeviceTombstone(owner, gone, HlcStamp(1_000, 0, kept)), b.name)

            assertEquals(setOf(kept, gone), b.tipDevices(), b.name)
            assertNotNull(b.devices.getPublicKey(owner, gone), b.name)
        }
    }

    @Test
    fun sharedRealmMemberRemoval_forgetsTheTip_withoutTheRemovalLedger() {
        for (b in backends()) {
            b.seed()
            b.revocation().revokeMemberDevice(owner, gone)

            assertEquals(setOf(kept), b.tipDevices(), b.name)
            // Member removal is a different fact from an owner's device removal (DeviceRegistry.recordRemoval).
            assertFalse(b.devices.wasRemoved(owner, gone), b.name)
        }
    }

    @Test
    fun theLateWriteGuard_dropsRowsWrittenAfterTheRevocation_andSparesARegisteredDevice() {
        for (b in backends()) {
            b.seed()
            val revocation = b.revocation()
            revocation.applyDeviceTombstone(owner, gone, null)
            // The revoked device's in-flight request lands its writes after the prune…
            b.tips.record(owner, tip(gone, 900))
            assertEquals(setOf(kept, gone), b.tipDevices(), "${b.name}: precondition — the race re-created the row")

            // …and the route's post-write check removes them again.
            assertTrue(revocation.forgetIfUnregistered(owner, gone), b.name)
            assertEquals(setOf(kept), b.tipDevices(), b.name)

            assertFalse(revocation.forgetIfUnregistered(owner, kept), "${b.name}: a live device is never touched")
            assertEquals(setOf(kept), b.tipDevices(), b.name)
        }
    }

    @Test
    fun onSqlite_aFailedPrune_rollsTheRevocationBack() {
        // ⚠ One transaction: if the prune fails, the device must stay registered rather than end up
        // revoked with its tip still served — the half-state this PR exists to remove.
        val b = backends().single { it.name == "sqlite" }
        b.seed()
        val failingTips = object : HlcTipRegistry by b.tips {
            override fun forgetDevice(ownerId: String, deviceId: String): Int = error("disk full")
        }
        assertFailsWith<IllegalStateException> {
            b.revocation(tips = failingTips).applyDeviceTombstone(owner, gone, null)
        }
        assertNotNull(b.devices.getPublicKey(owner, gone), "the registration delete must roll back")
        assertFalse(b.devices.wasRemoved(owner, gone), "the removal ledger write must roll back")
    }

    @Test
    fun theTombstoneGcQuorum_alreadyIgnoredARevokedDevicesTip_andStillDoes() {
        // The trace behind "pruning is safe": `acknowledgementSet` iterates the REGISTRY and only looks
        // tips up, so a revoked device's stale tip (500 < the tombstone's 1 000) never stalled GC —
        // before or after the prune. A *registered* device's stale tip does, and must.
        for (b in backends()) {
            b.seed()
            val mailbox = SyncMailbox()
            val tombstone = DeleteBlock.create(
                context = ctx,
                blockId = "t",
                ownerId = owner,
                hlc = HybridLogicalClock.create(ctx, 1_000, 0, kept),
                targetEntityIdHash = "h",
                targetEntityType = "tenshu/test/entity",
                authTag = ByteArray(32) { 1 },
            )
            assertEquals(
                setOf(kept),
                mailbox.acknowledgementSet(owner, tombstone, b.devices, b.tips),
                "${b.name}: a live device behind the tombstone has not acked",
            )
            assertFalse(
                mailbox.acknowledgementSet(owner, tombstone, b.devices, b.tips).containsAll(b.devices.deviceIds(owner)),
                "${b.name}: a registered stale device blocks the quorum",
            )

            b.devices.revoke(owner, gone) // registry only: the pre-F11 state, tip still present
            assertTrue(
                mailbox.acknowledgementSet(owner, tombstone, b.devices, b.tips).containsAll(b.devices.deviceIds(owner)),
                "${b.name}: a revoked device's leftover tip never counted",
            )

            b.revocation().forgetIfUnregistered(owner, gone) // the F11 prune
            assertEquals(setOf(kept), b.tipDevices(), b.name)
            assertTrue(
                mailbox.acknowledgementSet(owner, tombstone, b.devices, b.tips).containsAll(b.devices.deviceIds(owner)),
                "${b.name}: pruning leaves the quorum unchanged",
            )
        }
    }
}
