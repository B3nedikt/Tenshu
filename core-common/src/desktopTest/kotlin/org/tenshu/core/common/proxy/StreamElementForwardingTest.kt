package org.tenshu.core.common.proxy

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.tenshu.core.StreamElement
import org.tenshu.core.StreamElementDispatcher
import org.tenshu.core.common.createSyntheticTestRegistry
import org.tenshu.core.common.createTestContext
import org.tenshu.core.common.impl.StreamElementValueDto
import org.tenshu.core.toTString
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [forwardStreamElementsInOrder]'s two promises: elements reach the dispatcher in the order the
 * connection received them, and a destroyed connection **discards** what is still queued.
 *
 * The second matters because closing the channel on destroy lets the consumer drain its backlog,
 * and Control's quiesce / re-mint installs the next session's consumer against the same provider
 * while that drain runs — an old session's patch or terminal could land after the new session's
 * snapshot. Red with `elements.close()` alone (frames 2 and 3 still arrive after the destroy),
 * green with the consumer cancelled in the destroy hook.
 */
class StreamElementForwardingTest {

    private val context = createTestContext(createSyntheticTestRegistry())

    private fun frame(id: Long): StreamElement<*> =
        StreamElementValueDto(streamId = id, payload = "frame".toTString(context))

    /** Records what it is handed; the first element blocks until [release] so the rest queue up. */
    private class BlockingDispatcher : StreamElementDispatcher {
        val seen = CopyOnWriteArrayList<Long>()
        val firstEntered = CountDownLatch(1)
        val release = CountDownLatch(1)

        override suspend fun dispatchElement(element: StreamElement<*>) {
            if (seen.isEmpty()) {
                firstEntered.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
            seen += element.streamId.get()
        }
    }

    @Test
    fun elementsReachTheDispatcherInArrivalOrder() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val dispatcher = BlockingDispatcher().apply { release.countDown() }
        val connection = LocalClientConnection()
        connection.forwardStreamElementsInOrder(scope, dispatcher)

        (1L..5L).forEach { connection.send(frame(it)) }

        withTimeout(5_000) { while (dispatcher.seen.size < 5) delay(10) }
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), dispatcher.seen.toList())
        scope.cancel()
    }

    @Test
    fun aDestroyedConnection_discardsTheFramesStillQueued() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val dispatcher = BlockingDispatcher()
        val connection = LocalClientConnection()
        connection.forwardStreamElementsInOrder(scope, dispatcher)

        connection.send(frame(1))
        dispatcher.firstEntered.await(5, TimeUnit.SECONDS) // the consumer is busy with frame 1 …
        connection.send(frame(2))                         // … so these two queue behind it
        connection.send(frame(3))

        connection.destroy() // the session is gone: whatever is queued belongs to it
        dispatcher.release.countDown()

        delay(300)
        assertEquals(
            listOf(1L),
            dispatcher.seen.toList(),
            "frames queued at destroy must be discarded, never drained into whatever session comes next",
        )
        scope.cancel()
    }

    /**
     * Control adopts a session on one thread while quiescing it on another, so
     * this install can run *after* `destroy()`. The connection must then refuse the listener and
     * run the destroy hook at once — the consumer ends instead of living forever on a
     * process-lifetime scope. Red when the connection keeps no destroyed state (the hook is stored
     * on a dead connection and the consumer stays active), green with it.
     */
    @Test
    fun installingOnAnAlreadyDestroyedConnection_endsTheConsumerAtOnce() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val dispatcher = BlockingDispatcher().apply { release.countDown() }
        val connection = LocalClientConnection()
        connection.destroy()

        val consumer = connection.forwardStreamElementsInOrder(scope, dispatcher)
        connection.send(frame(1)) // nothing is listening on a destroyed connection

        withTimeout(5_000) { consumer.join() }
        assertEquals(emptyList(), dispatcher.seen.toList(), "a destroyed connection forwards nothing")
        scope.cancel()
    }
}
