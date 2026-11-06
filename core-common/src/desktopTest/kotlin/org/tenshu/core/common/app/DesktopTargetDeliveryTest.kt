package org.tenshu.core.common.app

import kotlinx.coroutines.test.runTest
import org.tenshu.core.Data
import org.tenshu.core.TValue
import org.tenshu.core.app.OpenTargetRequest
import org.tenshu.core.common.app.proxy.desktopTargetDelivery
import org.tenshu.core.common.createTestContext
import org.tenshu.core.common.parser.TokenString
import org.tenshu.core.common.proxy.ClientConnection
import org.tenshu.core.entity.DataId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Desktop's half of a cross-app open (`control-cross-app-open.md` §5h).
 *
 * ⚠⚠ **The assertion that matters is that a CONNECTED handler is still raised.** §5e.1 measured on
 * Android that delivering the row and bringing the app forward are different acts; the platforms
 * differ only in who may perform the second one. A desktop delivery that pushed without raising
 * would reproduce that defect on the platform where it is trivially avoidable.
 */
class DesktopTargetDeliveryTest {

    private class Recording : ClientConnection {
        val sent = mutableListOf<Data>()
        override fun init() {}
        override fun send(data: Data) { sent += data }
        override fun destroy() {}
        override fun tokenize(value: TValue): TokenString? = null
    }

    private fun request(): OpenTargetRequest {
        val context = createTestContext()
        return OpenTargetRequest.create(
            context = context,
            target = DataId.StringDataId.create(
                context = context,
                type = "tenshu/file",
                keyValue = "/a.jpg",
            ),
        )
    }

    @Test
    fun aConnectedHandlerIsPushedToAndRaised() = runTest {
        val handler = Recording()
        val launched = mutableListOf<String>()
        val delivery = desktopTargetDelivery(
            connectionFor = { if (it == "app.tenshu.drive") handler else null },
            launch = { launched += it; true },
        )

        val outcome = delivery.deliver("app.tenshu.drive", request(), Recording())

        assertEquals(1, handler.sent.size, "the running handler was not pushed to")
        assertEquals(listOf("app.tenshu.drive"), launched, "a connected handler was not raised")
        // ⭐ Desktop hands the caller nothing to do — Control may launch and raise here.
        assertNull(outcome?.launchTicketId)
    }

    @Test
    fun aColdHandlerIsLaunchedThenPushedOnceItConnects() = runTest {
        val handler = Recording()
        var connected = false
        val delivery = desktopTargetDelivery(
            connectionFor = { if (connected) handler else null },
            launch = { connected = true; true },
        )

        val outcome = delivery.deliver("app.tenshu.drive", request(), Recording())

        // ⚠⚠ The payload cannot ride the launch on desktop (`fun main()` takes no arguments), so
        // "launched" is not "delivered" — the push after connect is the whole contract.
        assertEquals(1, handler.sent.size)
        assertNull(outcome?.launchTicketId)
    }

    @Test
    fun aLaunchThatFailsIsReportedRatherThanWaitedOut() = runTest {
        val delivery = desktopTargetDelivery(connectionFor = { null }, launch = { false })

        // ⚠ A dev tree has no installed bundle for `open -b` to find. That must surface as a
        // refusal the caller can say something about, not a ten-second stall.
        assertNull(delivery.deliver("app.tenshu.drive", request(), Recording()))
    }

    @Test
    fun anAppThatNeverConnectsGivesUp() = runTest {
        val delivery = desktopTargetDelivery(connectionFor = { null }, launch = { true })

        // ⚠⚠ Bounded, and giving up is an outcome: `LAUNCH_FAILED` and a sentence beats a caller
        // waiting forever on a boot that failed. `runTest` skips the delay, so this is instant.
        assertNull(delivery.deliver("app.tenshu.drive", request(), Recording()))
    }
}
