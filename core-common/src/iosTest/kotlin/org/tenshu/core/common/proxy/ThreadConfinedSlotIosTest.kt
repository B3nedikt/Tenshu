package org.tenshu.core.common.proxy

import kotlin.native.concurrent.ObsoleteWorkersApi
import kotlin.native.concurrent.TransferMode
import kotlin.native.concurrent.Worker
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The Kotlin/Native actual keeps its values in a `@ThreadLocal` top-level map. If that annotation
 * were lost the map would be one per process, and the slot would be the shared field the
 * reference collector must not be (`ReferenceCollectorConfinementTest`).
 */
@OptIn(ObsoleteWorkersApi::class)
class ThreadConfinedSlotIosTest {

    @Test
    fun aValueSetOnOneThreadIsNotSeenOnAnother() {
        val slot = ThreadConfinedSlot<String>()
        slot.set("main")

        val worker = Worker.start()
        val seenOnWorker: String? = try {
            worker.execute(TransferMode.SAFE, { slot }) { shared ->
                val before: String? = shared.get()
                shared.set("worker")
                before
            }.result
        } finally {
            worker.requestTermination().result
        }

        assertNull(seenOnWorker, "the worker thread saw the main thread's value")
        assertEquals("main", slot.get(), "the worker thread's write reached the main thread")
        slot.set(null)
    }
}
