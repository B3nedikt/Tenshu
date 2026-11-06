package org.tenshu.file.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.file.sync.ContentBackfillMarker
import org.tenshu.file.test.createFileProxy
import org.tenshu.preference.StringListAdapter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The backfill marker's **format** half — the lever that re-runs the pass on a latched device.
 *
 * ⚠⚠ A change to the latch rule reaches new runs only. A device whose marker latched under an older
 * format — `v1-refs` over a store a wipe had just emptied — never runs the pass again, and reads
 * "complete" while nothing is declared. Bumping [ContentBackfillMarker.BACKFILL_FORMAT] is what
 * re-arms it, and these pin that it actually does.
 */
class BackfillMarkerFormatTest {

    private val deviceId = "device-1"

    @Test
    fun aMarkerFromTheOldFormatDoesNotReadAsComplete() = runTest {
        val proxy = createFileProxy(backgroundScope, deviceId = deviceId)
        val ctx = proxy.executionContext

        // Latched, under a superseded format.
        ctx.writeDeviceSettingValue(
            deviceId = deviceId,
            preferenceId = ContentBackfillMarker.BackfillComplete.id,
            value = StringListAdapter.toData(context = ctx.context, value = listOf("v1-refs", "v2-settled", "v3-relay-durable")),
        )

        assertFalse(
            ctx.readBackfillComplete(deviceId),
            "no superseded format may satisfy the current one, or the fleet stays wedged",
        )
    }

    @Test
    fun aMarkerWrittenNowReadsAsComplete() = runTest {
        val proxy = createFileProxy(backgroundScope, deviceId = deviceId)
        val ctx = proxy.executionContext

        ctx.writeBackfillComplete(deviceId, complete = true)

        assertTrue(ctx.readBackfillComplete(deviceId))
    }

    @Test
    fun clearingTheMarkerReopensThePass() = runTest {
        val proxy = createFileProxy(backgroundScope, deviceId = deviceId)
        val ctx = proxy.executionContext

        ctx.writeBackfillComplete(deviceId, complete = true)
        ctx.writeBackfillComplete(deviceId, complete = false)

        assertFalse(ctx.readBackfillComplete(deviceId))
    }

    /**
     * ⚠ The format is compared with `in`, not equality, so a device that has run several formats
     * keeps its history and still reads complete. Pinned because switching to equality would look
     * tidier and would silently re-run the expensive pass on every such device.
     */
    @Test
    fun aDeviceThatHasRunSeveralFormatsStillReadsComplete() = runTest {
        val proxy = createFileProxy(backgroundScope, deviceId = deviceId)
        val ctx = proxy.executionContext

        ctx.writeDeviceSettingValue(
            deviceId = deviceId,
            preferenceId = ContentBackfillMarker.BackfillComplete.id,
            value = StringListAdapter.toData(
                context = ctx.context,
                value = listOf("v1-refs", "v2-settled", "v3-relay-durable", "v4-full-walk", ContentBackfillMarker.BACKFILL_FORMAT),
            ),
        )

        assertTrue(ctx.readBackfillComplete(deviceId))
    }

    /**
     * The current format. A marker at `v6-realign-described` was latched on a walk that counted
     * nothing it skipped, so if this ever reads `v6-realign-described` or older again, no device
     * walks with counts.
     */
    @Test
    fun theCurrentFormatIsNotTheOneTheFleetLatchedOn() {
        assertEquals("v8-repair-manifestless", ContentBackfillMarker.BACKFILL_FORMAT)
    }

    /**
     * ⚠⚠ The devices that latched `v7` are the ones holding manifest-less uploads; a pass that
     * returns at the marker never reaches the repair that heals them.
     */
    @Test
    fun aDeviceLatchedAtV7RunsThePassAgain() = runTest {
        val proxy = createFileProxy(backgroundScope, deviceId = deviceId)
        val ctx = proxy.executionContext
        ctx.writeDeviceSettingValue(
            deviceId = deviceId,
            preferenceId = ContentBackfillMarker.BackfillComplete.id,
            value = StringListAdapter.toData(
                context = ctx.context,
                value = listOf("v5-wire-names", "v6-realign-described", "v7-counted-walk"),
            ),
        )

        assertFalse(ctx.readBackfillComplete(deviceId), "a v7-latched device walks again, and repairs")
    }

    /** ⚠⚠ A device latched at `v6` must read NOT complete, or nothing is counted. */
    @Test
    fun aDeviceLatchedAtV6RunsThePassAgain() = runTest {
        val proxy = createFileProxy(backgroundScope, deviceId = deviceId)
        val ctx = proxy.executionContext
        ctx.writeDeviceSettingValue(
            deviceId = deviceId,
            preferenceId = ContentBackfillMarker.BackfillComplete.id,
            value = StringListAdapter.toData(
                context = ctx.context,
                value = listOf("v4-full-walk", "v5-wire-names", "v6-realign-described"),
            ),
        )

        assertFalse(ctx.readBackfillComplete(deviceId), "a v6-latched device walks again, with counts")
    }

    /** A device still latched at `v5` must read NOT complete, or it never realigns. */
    @Test
    fun aDeviceLatchedAtV5RunsThePassAgain() = runTest {
        val proxy = createFileProxy(backgroundScope, deviceId = deviceId)
        val ctx = proxy.executionContext
        ctx.writeDeviceSettingValue(
            deviceId = deviceId,
            preferenceId = ContentBackfillMarker.BackfillComplete.id,
            value = StringListAdapter.toData(
                context = ctx.context,
                value = listOf("v1-refs", "v2-settled", "v3-relay-durable", "v4-full-walk", "v5-wire-names"),
            ),
        )

        assertFalse(ctx.readBackfillComplete(deviceId), "a v5-latched device re-describes from the relay's manifests")
    }

    /** ⚠⚠ A device latched at `v4` must read NOT complete, or it never re-declares under wire names. */
    @Test
    fun aDeviceLatchedAtV4RunsThePassAgain() = runTest {
        val proxy = createFileProxy(backgroundScope, deviceId = deviceId)
        val ctx = proxy.executionContext

        ctx.writeDeviceSettingValue(
            deviceId = deviceId,
            preferenceId = ContentBackfillMarker.BackfillComplete.id,
            value = StringListAdapter.toData(context = ctx.context, value = listOf("v3-relay-durable", "v4-full-walk")),
        )

        assertFalse(ctx.readBackfillComplete(deviceId))
    }
}
