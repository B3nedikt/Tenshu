package org.tenshu.logging.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.NotFoundError
import org.tenshu.core.common.TestProxy
import org.tenshu.logging.test.createLoggingServiceCommonTestRegistry
import kotlin.test.Test

/**
 * Recording an error must never throw, even when this device's id cannot be read — after a
 * "Forget this device" whose new key could not be saved, every id read throws until Control
 * restarts (threat model A4, *lifecycle*). The recorder runs inside the executor's catch-all, so a
 * throw here would turn one failure into a second, unrelated one.
 */
class ErrorEventRecorderDeviceIdTest {

    @Test
    fun anUnreadableDeviceId_isRecordedAsUnknown_neverThrown() = runTest {
        val proxy = TestProxy(schemaRegistry = createLoggingServiceCommonTestRegistry())
        val recorder = loggingServiceProxyImplementationFactory(
            sessionExecutionContext = proxy.executionContext,
            deviceId = { error("made up: the new device key could not be saved") },
            appId = "control",
        )

        recorder.record(NotFoundError.create(proxy.tContext, "x"), operationId = "op", callstackDepth = 0)
    }
}
