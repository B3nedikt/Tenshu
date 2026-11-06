package org.tenshu.core.common.proxy

import org.tenshu.core.Data
import org.tenshu.core.common.createSyntheticTestRegistry
import org.tenshu.core.common.createTestContext
import org.tenshu.core.toTString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [LocalClientConnection]'s registration contract around [LocalClientConnection.destroy]: a
 * connection remembers that it was destroyed, refuses listeners from then on, and runs a destroy
 * hook registered too late at once. Without that, an install racing a teardown leaves a listener
 * and a hook on a dead connection — and the consumer the hook was meant to end lives forever.
 */
class LocalClientConnectionTest {

    private val context = createTestContext(createSyntheticTestRegistry())

    private fun payload(text: String): Data = text.toTString(context)

    @Test
    fun aListenerRegisteredBeforeDestroy_receivesSends_andIsDroppedByDestroy() {
        val connection = LocalClientConnection()
        val seen = mutableListOf<Data>()
        assertTrue(connection.addListener { seen += it })

        connection.send(payload("before"))
        connection.destroy()
        connection.send(payload("after"))

        assertEquals(1, seen.size, "only the send before destroy reaches the listener")
    }

    @Test
    fun aDestroyHook_runsExactlyOnce_whenTheConnectionIsDestroyed() {
        val connection = LocalClientConnection()
        var runs = 0
        connection.addOnDestroy { runs++ }

        connection.destroy()
        connection.destroy()

        assertEquals(1, runs)
    }

    @Test
    fun aListenerAddedAfterDestroy_isRefused_andNeverCalled() {
        val connection = LocalClientConnection()
        connection.destroy()

        var called = false
        assertFalse(connection.addListener { called = true }, "a destroyed connection takes no listener")
        connection.send(payload("late"))

        assertFalse(called)
    }

    @Test
    fun aDestroyHookAddedAfterDestroy_runsAtOnce() {
        val connection = LocalClientConnection()
        connection.destroy()

        var ran = false
        connection.addOnDestroy { ran = true }

        assertTrue(ran, "the connection is already gone, so the release it guards is due now")
    }
}
