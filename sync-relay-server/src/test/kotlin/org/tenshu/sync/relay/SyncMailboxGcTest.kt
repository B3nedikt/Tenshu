package org.tenshu.sync.relay

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.parser.BinaryParser
import org.tenshu.core.common.parser.BinarySerializer
import org.tenshu.core.common.module.coreCommonModule
import org.tenshu.core.common.proxy.createDefaultSchemaRegistry
import org.tenshu.core.core.DataFactory
import org.tenshu.core.core.SchemaProvider
import org.tenshu.core.core.TComponent
import org.tenshu.core.core.TContext
import org.tenshu.core.security.stubEd25519Signature
import org.tenshu.core.time.HybridLogicalClock
import org.tenshu.identity.common.identityServiceCommonModule
import org.tenshu.sync.ChangesResponse
import org.tenshu.sync.DeleteBlock
import org.tenshu.sync.SignedHlcTip
import org.tenshu.sync.SyncBlock
import org.tenshu.sync.common.hlcTipCanonicalMessage
import org.tenshu.sync.common.syncServiceCommonModule
import org.tenshu.sync.relay.auth.KnownDevicesStore
import org.tenshu.sync.relay.mailbox.HlcTipStore
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * GC-sweep coverage for `docs/adr/0006-sync-tombstones.md` Phase 4 (the second half;
 * the push-rejection half landed 2026-05-05).
 *
 * Pins:
 *  - retention-deadline computation (default + per-owner override),
 *  - ack-set tracking from `HlcTipStore` (no separate parallel data structure — the
 *    sweep reads tip physical-time directly),
 *  - body drop after both quorum AND retention conditions are met,
 *  - tombstone preservation (the `DeleteBlock` itself stays; only the target
 *    `SyncBlock`'s `encryptedPayload` gets emptied),
 *  - end-to-end: Device A pushes a tombstone, Device B acks via `/sync/changes`,
 *    retention passes in test time, sweep drops body, subsequent `/sync/changes`
 *    returns the envelope (with empty payload) and `/sync/tombstones` returns the
 *    intact tombstone.
 *
 * Each test constructs its own [SyncMailbox] (one-per-relay shape, mirroring
 * `Application.module(syncMailbox = ...)`) — the previous singleton-with-unique-
 * ownerId pattern was a workaround for the now-removed `object SyncMailbox`.
 */
class SyncMailboxGcTest {

    private val mailbox = SyncMailbox()

    private fun makeContext(): Triple<TContext, BinarySerializer, BinaryParser> {
        val ctx = object : TContext {
            override var components: Map<String, TComponent> = emptyMap()
        }
        val registry = createDefaultSchemaRegistry(
            tContextProvider = { ctx },
            extraConfigs = listOf(
                coreCommonModule,
                syncServiceCommonModule,
                identityServiceCommonModule
            )
        )
        ctx.components = mapOf(
            SchemaProvider.COMPONENT_ID to registry,
            DataFactory.COMPONENT_ID to registry
        )
        return Triple(ctx, BinarySerializer(registry), BinaryParser(registry, registry))
    }

    private fun makeSyncBlock(
        ctx: TContext,
        ownerId: String,
        blockId: String,
        physicalTime: Long,
        targetIdHash: String,
        payload: ByteArray = "real-payload-${physicalTime}".encodeToByteArray()
    ): SyncBlock = SyncBlock.create(
        context = ctx,
        blockId = blockId,
        ownerId = ownerId,
        hlc = HybridLogicalClock.create(ctx, physicalTime, 0, "device-A"),
        encryptedPayload = payload,
        targetEntityIdHash = targetIdHash
    )

    private fun makeTombstone(
        ctx: TContext,
        ownerId: String,
        physicalTime: Long,
        targetIdHash: String,
        targetType: String = "tenshu/test/entity"
    ): DeleteBlock = DeleteBlock.create(
        context = ctx,
        blockId = "tomb-$ownerId-$physicalTime",
        ownerId = ownerId,
        hlc = HybridLogicalClock.create(ctx, physicalTime, 0, "device-A"),
        targetEntityIdHash = targetIdHash,
        targetEntityType = targetType,
        // Protocol 5: `/sync/push-tombstone` requires a 32-byte tag (format only).
        authTag = ByteArray(32) { 1 },
    )

    private fun makeTip(
        ctx: TContext,
        ownerId: String,
        deviceId: String,
        physicalTime: Long
    ): SignedHlcTip = SignedHlcTip.create(
        context = ctx,
        ownerId = ownerId,
        deviceId = deviceId,
        hlc = HybridLogicalClock.create(ctx, physicalTime, 0, deviceId),
        signature = byteArrayOf(0x01)
    )

    @Test
    fun retentionWindow_defaultIs30Days() {
        // 30 days * 24 h * 60 min * 60 s * 1000 ms.
        assertEquals(30L * 24L * 60L * 60L * 1_000L, RelayConfig.DEFAULT_RETENTION_WINDOW_MS)
        // No override set -> default applies.
        val owner = "default-retention-${System.nanoTime()}"
        assertEquals(RelayConfig.DEFAULT_RETENTION_WINDOW_MS, mailbox.retentionWindowMs(owner))
    }

    @Test
    fun retentionWindow_overrideIsRespectedPerOwner() {
        val owner = "override-retention-${System.nanoTime()}"
        mailbox.setRetentionWindowMs(owner, 5_000L)
        assertEquals(5_000L, mailbox.retentionWindowMs(owner))
        // Other owners still get the default.
        val otherOwner = "default-still-${System.nanoTime()}"
        assertEquals(
            RelayConfig.DEFAULT_RETENTION_WINDOW_MS,
            mailbox.retentionWindowMs(otherOwner)
        )
    }

    @Test
    fun acknowledgementSet_emptyWhenNoTipsRecorded() {
        val (ctx, _, _) = makeContext()
        val owner = "ack-empty-${System.nanoTime()}"
        val knownDevices = KnownDevicesStore().apply {
            register(owner, "device-A", byteArrayOf(0x0A))
            register(owner, "device-B", byteArrayOf(0x0B))
        }
        val tipStore = HlcTipStore()  // no tips recorded
        val tomb = makeTombstone(ctx, owner, 1_000L, "victim")
        val ack = mailbox.acknowledgementSet(owner, tomb, knownDevices, tipStore)
        assertEquals(emptySet(), ack)
    }

    @Test
    fun acknowledgementSet_includesDeviceAtOrAboveTombstoneHlc() {
        val (ctx, _, _) = makeContext()
        val owner = "ack-above-${System.nanoTime()}"
        val knownDevices = KnownDevicesStore().apply {
            register(owner, "device-A", byteArrayOf(0x0A))
            register(owner, "device-B", byteArrayOf(0x0B))
        }
        val tipStore = HlcTipStore().apply {
            // device-A is below tombstone; device-B is above.
            record(owner, makeTip(ctx, owner, "device-A", 500L))
            record(owner, makeTip(ctx, owner, "device-B", 2_000L))
        }
        val tomb = makeTombstone(ctx, owner, 1_000L, "victim")
        val ack = mailbox.acknowledgementSet(owner, tomb, knownDevices, tipStore)
        assertEquals(setOf("device-B"), ack)
    }

    @Test
    fun acknowledgementSet_includesDeviceWhenTipExactlyEqualsTombstoneHlc() {
        // The threshold is `>= tombstone.hlc.physicalTime` per the spec wording: a
        // tip equal to the tombstone HLC means the device has observed events at
        // least up to that height.
        val (ctx, _, _) = makeContext()
        val owner = "ack-equal-${System.nanoTime()}"
        val knownDevices = KnownDevicesStore().apply {
            register(owner, "device-A", byteArrayOf(0x0A))
        }
        val tipStore = HlcTipStore().apply {
            record(owner, makeTip(ctx, owner, "device-A", 1_000L))
        }
        val tomb = makeTombstone(ctx, owner, 1_000L, "victim")
        assertEquals(
            setOf("device-A"),
            mailbox.acknowledgementSet(owner, tomb, knownDevices, tipStore)
        )
    }

    @Test
    fun gcSweep_doesNotDropBeforeQuorum() {
        // Two realm devices, only one has acked. Even with retention long-elapsed the
        // sweep must not drop the body — quorum is the security-relevant condition.
        val (ctx, _, _) = makeContext()
        val owner = "no-quorum-${System.nanoTime()}"
        val targetHash = "victim-no-quorum"
        mailbox.setRetentionWindowMs(owner, 0L)  // retention trivially satisfied

        val knownDevices = KnownDevicesStore().apply {
            register(owner, "device-A", byteArrayOf(0x0A))
            register(owner, "device-B", byteArrayOf(0x0B))
        }
        val tipStore = HlcTipStore().apply {
            // Only device-A has caught up.
            record(owner, makeTip(ctx, owner, "device-A", 5_000L))
        }

        val payload = "important-bytes".encodeToByteArray()
        mailbox.pushBlock(
            owner,
            makeSyncBlock(ctx, owner, "block-1", 1_000L, targetHash, payload)
        )
        mailbox.pushTombstone(owner, makeTombstone(ctx, owner, 2_000L, targetHash))

        val dropped = mailbox.gcSweep(knownDevices, tipStore, nowMs = Long.MAX_VALUE / 2)
        assertEquals(0, dropped, "no quorum -> no drops")
        // Body is still there.
        val blocks = mailbox.getChanges(owner, sinceTimestamp = 0L).blocks
        assertEquals(1, blocks.size)
        assertContentEquals(payload, blocks.single().encryptedPayload.get())
    }

    @Test
    fun gcSweep_doesNotDropBeforeRetention() {
        // Quorum reached; retention NOT yet elapsed (nowMs <= deadline).
        val (ctx, _, _) = makeContext()
        val owner = "no-retention-${System.nanoTime()}"
        val targetHash = "victim-no-retention"
        mailbox.setRetentionWindowMs(owner, 10_000L)

        val knownDevices = KnownDevicesStore().apply {
            register(owner, "device-A", byteArrayOf(0x0A))
        }
        val tipStore = HlcTipStore().apply {
            record(owner, makeTip(ctx, owner, "device-A", 5_000L))  // >= tombstone
        }

        val payload = "important-bytes".encodeToByteArray()
        mailbox.pushBlock(
            owner,
            makeSyncBlock(ctx, owner, "block-1", 1_000L, targetHash, payload)
        )
        mailbox.pushTombstone(owner, makeTombstone(ctx, owner, 2_000L, targetHash))

        // tombstone HLC = 2_000; retention = 10_000 -> deadline = 12_000. nowMs = 12_000
        // is exactly the deadline (not strictly past), so still no GC.
        val dropped = mailbox.gcSweep(knownDevices, tipStore, nowMs = 12_000L)
        assertEquals(0, dropped, "retention deadline not strictly past -> no drops")
        val blocks = mailbox.getChanges(owner, sinceTimestamp = 0L).blocks
        assertContentEquals(payload, blocks.single().encryptedPayload.get())
    }

    @Test
    fun gcSweep_dropsBodyWhenBothConditionsMet() {
        // Quorum AND retention satisfied — sweep should drop body, preserve envelope,
        // preserve tombstone.
        val (ctx, _, _) = makeContext()
        val owner = "both-met-${System.nanoTime()}"
        val targetHash = "victim-both"
        mailbox.setRetentionWindowMs(owner, 1_000L)

        val knownDevices = KnownDevicesStore().apply {
            register(owner, "device-A", byteArrayOf(0x0A))
            register(owner, "device-B", byteArrayOf(0x0B))
        }
        val tipStore = HlcTipStore().apply {
            record(owner, makeTip(ctx, owner, "device-A", 5_000L))
            record(owner, makeTip(ctx, owner, "device-B", 5_000L))
        }

        val originalPayload = "original-payload-bytes".encodeToByteArray()
        mailbox.pushBlock(
            owner,
            makeSyncBlock(ctx, owner, "block-1", 1_000L, targetHash, originalPayload)
        )
        val tomb = makeTombstone(ctx, owner, 2_000L, targetHash)
        mailbox.pushTombstone(owner, tomb)

        // tombstone HLC = 2_000; retention = 1_000 -> deadline = 3_000. nowMs = 4_000
        // is strictly past -> sweep drops.
        val dropped = mailbox.gcSweep(knownDevices, tipStore, nowMs = 4_000L)
        assertEquals(1, dropped)

        // Envelope preserved, payload empty.
        val blocks = mailbox.getChanges(owner, sinceTimestamp = 0L).blocks
        assertEquals(1, blocks.size)
        val survivor = blocks.single()
        assertEquals("block-1", survivor.blockId.get())
        assertEquals(targetHash, survivor.targetEntityIdHash?.get())
        assertEquals(0, survivor.encryptedPayload.get().size, "payload should be empty after GC")
        assertNotEquals(originalPayload.size, survivor.encryptedPayload.get().size)

        // Tombstone preserved intact.
        val tombs = mailbox.getTombstones(owner, sinceTimestamp = 0L).tombstones
        assertEquals(1, tombs.size)
        assertEquals(tomb.blockId.get(), tombs.single().blockId.get())

        // Bookkeeping: blockId recorded as dropped.
        assertTrue(mailbox.droppedBlockIds(owner).contains("block-1"))
    }

    @Test
    fun gcSweep_isIdempotent_secondSweepIsNoop() {
        // Re-running the sweep on already-GC'd bodies must not double-count and must
        // not crash. Important because the production cadence is "nightly" — the
        // sweep is going to keep firing on the same already-empty bodies.
        val (ctx, _, _) = makeContext()
        val owner = "idempotent-${System.nanoTime()}"
        val targetHash = "victim-idem"
        mailbox.setRetentionWindowMs(owner, 0L)

        val knownDevices = KnownDevicesStore().apply {
            register(owner, "device-A", byteArrayOf(0x0A))
        }
        val tipStore = HlcTipStore().apply {
            record(owner, makeTip(ctx, owner, "device-A", 5_000L))
        }

        mailbox.pushBlock(
            owner,
            makeSyncBlock(ctx, owner, "block-1", 1_000L, targetHash)
        )
        mailbox.pushTombstone(owner, makeTombstone(ctx, owner, 2_000L, targetHash))

        val first = mailbox.gcSweep(knownDevices, tipStore, nowMs = 10_000L)
        val second = mailbox.gcSweep(knownDevices, tipStore, nowMs = 10_000L)
        assertEquals(1, first)
        assertEquals(0, second, "second sweep should be a no-op")
    }

    @Test
    fun gcSweep_neverGcWhenRetentionIsMaxValue() {
        // `Long.MAX_VALUE` retention = "never GC" per §2.2. Even with full quorum and
        // far-future nowMs the sweep must not drop. Also guards against integer
        // overflow on `tombstoneHlc + retention`.
        val (ctx, _, _) = makeContext()
        val owner = "never-gc-${System.nanoTime()}"
        val targetHash = "victim-never"
        mailbox.setRetentionWindowMs(owner, Long.MAX_VALUE)

        val knownDevices = KnownDevicesStore().apply {
            register(owner, "device-A", byteArrayOf(0x0A))
        }
        val tipStore = HlcTipStore().apply {
            record(owner, makeTip(ctx, owner, "device-A", 5_000L))
        }

        mailbox.pushBlock(
            owner,
            makeSyncBlock(ctx, owner, "block-1", 1_000L, targetHash)
        )
        mailbox.pushTombstone(owner, makeTombstone(ctx, owner, 2_000L, targetHash))

        val dropped = mailbox.gcSweep(knownDevices, tipStore, nowMs = Long.MAX_VALUE)
        assertEquals(0, dropped, "Long.MAX_VALUE retention disables GC")
    }

    @Test
    fun gcSweep_doesNotDropBlocksAtOrAboveTombstoneHlc_recreateProtection() {
        // §3.4 recreate semantics: a block with HLC > tombstone is the entity being
        // re-created; the sweep must not touch it. The sweep target is strictly
        // pre-tombstone blocks.
        val (ctx, _, _) = makeContext()
        val owner = "recreate-${System.nanoTime()}"
        val targetHash = "victim-recreate"
        mailbox.setRetentionWindowMs(owner, 0L)

        val knownDevices = KnownDevicesStore().apply {
            register(owner, "device-A", byteArrayOf(0x0A))
        }
        val tipStore = HlcTipStore().apply {
            record(owner, makeTip(ctx, owner, "device-A", 10_000L))
        }

        // pre-tombstone block (will GC), tombstone, post-tombstone recreate (must survive).
        val preBlock = makeSyncBlock(ctx, owner, "pre", 1_000L, targetHash)
        val postBlock = makeSyncBlock(ctx, owner, "post-recreate", 5_000L, targetHash)
        mailbox.pushBlock(owner, preBlock)
        mailbox.pushTombstone(owner, makeTombstone(ctx, owner, 2_000L, targetHash))
        mailbox.pushBlock(owner, postBlock)

        mailbox.gcSweep(knownDevices, tipStore, nowMs = 100_000L)

        val blocks = mailbox.getChanges(owner, sinceTimestamp = 0L).blocks
        val byId = blocks.associateBy { it.blockId.get() }
        assertEquals(0, byId["pre"]?.encryptedPayload?.get()?.size, "pre should be GC'd")
        assertTrue(
            (byId["post-recreate"]?.encryptedPayload?.get()?.size ?: 0) > 0,
            "post-tombstone recreate must keep its payload"
        )
    }

    @Test
    fun gcSweep_skipsOwnersWithNoRegisteredDevices() {
        // No realm devices means quorum is unreachable — sweep skips this owner
        // gracefully (don't accidentally drop bodies).
        val (ctx, _, _) = makeContext()
        val owner = "no-devices-${System.nanoTime()}"
        val targetHash = "victim-no-devs"

        val knownDevices = KnownDevicesStore()  // intentionally empty for `owner`
        val tipStore = HlcTipStore()

        val payload = "still-here".encodeToByteArray()
        mailbox.pushBlock(
            owner,
            makeSyncBlock(ctx, owner, "block-1", 1_000L, targetHash, payload)
        )
        mailbox.pushTombstone(owner, makeTombstone(ctx, owner, 2_000L, targetHash))

        val dropped = mailbox.gcSweep(knownDevices, tipStore, nowMs = Long.MAX_VALUE / 2)
        assertEquals(0, dropped)
        assertContentEquals(
            payload,
            mailbox.getChanges(owner, sinceTimestamp = 0L).blocks.single().encryptedPayload.get()
        )
    }

    @Test
    fun tombstoneIndex_keepsLatestHlcPerTarget() {
        // Same target tombstoned twice (delete -> recreate -> delete). Index returns
        // the latest tombstone — the sweep uses that to compute "what to GC."
        val (ctx, _, _) = makeContext()
        val owner = "index-${System.nanoTime()}"
        val targetHash = "victim-index"
        mailbox.pushTombstone(owner, makeTombstone(ctx, owner, 1_000L, targetHash))
        mailbox.pushTombstone(owner, makeTombstone(ctx, owner, 5_000L, targetHash))
        mailbox.pushTombstone(owner, makeTombstone(ctx, owner, 3_000L, targetHash))

        val index = mailbox.tombstoneIndex(owner)
        assertEquals(1, index.size)
        assertEquals(5_000L, index[targetHash]?.hlc?.physicalTime?.get())
    }

    @Test
    fun endToEnd_pushTombstone_ackViaChanges_retentionElapses_relayDropsBody() = testApplication {
        // Phase 4 exit criteria: Device A pushes a `DeleteBlock`; Device B ack's via
        // `/sync/changes`; retention window passes (in test time); relay drops body. A
        // subsequent `/sync/changes` query returns the (envelope-only) block; a
        // subsequent `/sync/tombstones` query still returns the intact tombstone.
        //
        // Using devMode here so the tip-signature check is bypassed — the GC logic does
        // not depend on signature verification (that's enforced upstream by the routing
        // layer; this test pins the storage-side flow).
        val owner = "e2e-tomb-${System.nanoTime()}"
        val targetHash = "e2e-victim"

        // Pre-register both devices in `KnownDevicesStore` so the sweep can see the
        // realm-devices set. Even in dev mode, `gcSweep` reads `knownDevices.deviceIds`
        // — without these registrations it would conclude "no realm devices" and skip.
        val knownDevices = KnownDevicesStore().apply {
            register(owner, "device-A", byteArrayOf(0x0A))
            register(owner, "device-B", byteArrayOf(0x0B))
        }
        val tipStore = HlcTipStore()

        application {
            testModule(
                config = RelayConfig(devMode = true, bindHost = "127.0.0.1", port = 8080),
                knownDevices = knownDevices,
                hlcTipStore = tipStore,
                syncMailbox = mailbox
            )
        }

        val (ctx, serializer, parser) = makeContext()

        // 1. Device A pushes a SyncBlock (the soon-to-be-GC'd body) and then a tombstone.
        val originalPayload = "device-A-bytes".encodeToByteArray()
        val syncBlock = makeSyncBlock(ctx, owner, "victim-block", 1_000L, targetHash, originalPayload)
        client.post("/sync/push") {
            contentType(ContentType.Application.OctetStream)
            header("X-Dev-Owner", owner)
            setBody(serializer.serialize(edgedPush(ctx, syncBlock)))
        }.bodyAsBytes()

        val tomb = makeTombstone(ctx, owner, 2_000L, targetHash)
        client.post("/sync/push-tombstone") {
            contentType(ContentType.Application.OctetStream)
            header("X-Dev-Owner", owner)
            setBody(serializer.serialize(cascadedTombstone(ctx, tomb)))
        }.bodyAsBytes()

        // 2. Device A and Device B ack via `/sync/changes`. The route records each
        // requesting device's tip into `HlcTipStore`. Use a tip with HLC well past the
        // tombstone (5_000 > 2_000) so quorum will compute as full.
        for (deviceId in listOf("device-A", "device-B")) {
            val tip = makeTip(ctx, owner, deviceId, 5_000L)
            val resp = client.post("/sync/changes") {
                contentType(ContentType.Application.OctetStream)
                header("X-Dev-Owner", owner)
                header("X-Dev-Device", deviceId)
                url { parameters.append("since", "0") }
                setBody(serializer.serialize(tip))
            }
            assertEquals(HttpStatusCode.OK, resp.status)
            resp.bodyAsBytes()
        }
        // Quorum is now reached — both realm devices have tips above 2_000.

        // 3. Set a short retention window for the test owner and run the sweep at a
        // point strictly past `tombstoneHlc + retentionWindow`.
        mailbox.setRetentionWindowMs(owner, 1_000L)
        val dropped = mailbox.gcSweep(
            knownDevices = knownDevices,
            hlcTipStore = tipStore,
            nowMs = 100_000L
        )
        assertEquals(1, dropped, "exactly one body should be GC'd")

        // 4. Subsequent `/sync/changes` returns the envelope-only block. The tombstone
        // is preserved intact via `/sync/tombstones`.
        val changesResp = client.post("/sync/changes") {
            contentType(ContentType.Application.OctetStream)
            header("X-Dev-Owner", owner)
            header("X-Dev-Device", "device-A")
            url { parameters.append("since", "0") }
            setBody(serializer.serialize(makeTip(ctx, owner, "device-A", 6_000L)))
        }
        assertEquals(HttpStatusCode.OK, changesResp.status)
        val parsedChanges = parser.parse<ChangesResponse>(changesResp.bodyAsBytes())
        val blocks = parsedChanges.blocks.toList()
        assertEquals(1, blocks.size, "envelope still served (tombstone marker preservation)")
        val survivor = blocks.single()
        assertEquals("victim-block", survivor.blockId.get())
        assertEquals(0, survivor.encryptedPayload.get().size, "body bytes are gone")
        assertNotEquals(originalPayload.size, survivor.encryptedPayload.get().size)

        val tombsResp = client.get("/sync/tombstones") {
            header("X-Dev-Owner", owner)
            url { parameters.append("since", "0") }
        }
        assertEquals(HttpStatusCode.OK, tombsResp.status)
        val parsedTombsResponse =
            parser.parse<org.tenshu.sync.TombstonesResponse>(tombsResp.bodyAsBytes())
        val parsedTombs = parsedTombsResponse.tombstones.toList()
        assertEquals(1, parsedTombs.size, "tombstone itself is preserved indefinitely")
        assertEquals(tomb.blockId.get(), parsedTombs.single().blockId.get())
        assertEquals(targetHash, parsedTombs.single().targetEntityIdHash.get())
    }
}
