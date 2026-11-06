package org.tenshu.sync.relay.mailbox

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
import kotlin.test.Test
import kotlin.test.assertEquals

class HlcTipStoreTest {

    private fun ctx(): TContext {
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
        return ctx
    }

    private fun tip(
        ctx: TContext,
        owner: String,
        deviceId: String,
        physicalTime: Long
    ) = SignedHlcTip.create(
        context = ctx,
        ownerId = owner,
        deviceId = deviceId,
        hlc = HybridLogicalClock.create(ctx, physicalTime, 0, deviceId),
        signature = byteArrayOf(0x01)
    )

    @Test
    fun recordThenGetPeerTips_returnsOtherDevicesOnly() {
        val store = HlcTipStore()
        val ctx = ctx()
        store.record("alice", tip(ctx, "alice", "deviceA", 100L))
        store.record("alice", tip(ctx, "alice", "deviceB", 200L))
        store.record("alice", tip(ctx, "alice", "deviceC", 300L))

        // From deviceA's perspective, deviceB and deviceC are peers.
        val fromA = store.getPeerTips("alice", excludingDeviceId = "deviceA")
        assertEquals(setOf("deviceB", "deviceC"), fromA.map { it.deviceId.get() }.toSet())
    }

    @Test
    fun recordReplacesEarlierTipForSameDevice() {
        val store = HlcTipStore()
        val ctx = ctx()
        store.record("alice", tip(ctx, "alice", "deviceA", 100L))
        store.record("alice", tip(ctx, "alice", "deviceA", 200L))

        // The store always serves the latest claim — receivers do regression detection.
        val all = store.allTips("alice")
        assertEquals(1, all.size)
        assertEquals(200L, all.first().hlc.physicalTime.get())
    }

    @Test
    fun ownersAreIsolated() {
        val store = HlcTipStore()
        val ctx = ctx()
        store.record("alice", tip(ctx, "alice", "deviceA", 100L))
        store.record("bob", tip(ctx, "bob", "deviceA", 999L))

        assertEquals(100L, store.allTips("alice").first().hlc.physicalTime.get())
        assertEquals(999L, store.allTips("bob").first().hlc.physicalTime.get())
    }

    @Test
    fun getPeerTips_emptyForUnknownOwner() {
        assertEquals(emptyList(), HlcTipStore().getPeerTips("nobody", excludingDeviceId = "x"))
    }
}
