package org.tenshu.inference.proxy

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** [SceneForeground] (`ios-on-device-inference.md` §14.17). */
class SceneForegroundTest {

    @Test
    fun aForegroundIsOneNumberHoweverOftenItIsAnnouncedAndNoneInTheBackground() {
        val scene = SceneForeground()
        assertNull(scene.number())
        scene.set(true)
        scene.set(true)
        assertEquals(1L, scene.number())
        scene.set(false)
        assertNull(scene.number())
        assertEquals(false, scene.active())
        scene.set(true)
        assertEquals(2L, scene.number())
    }

    /**
     * ⚠ On the JVM only, with two real threads: the scene goes to the background and comes
     * back, over and over, while another thread reads which foreground it is.
     */
    @Test
    fun aReaderNeverSeesTheNewForegroundUnderThePreviousOnesNumber() {
        val scene = SceneForeground()
        // The number of the foreground the writer is about to enter, set BEFORE it enters and
        // after the previous one has ended: from then on any foreground that is read is that one.
        val entering = AtomicLong(0)
        val running = AtomicBoolean(true)
        val wrong = AtomicLong(-1)
        val reader = thread {
            while (running.get()) {
                val before: Long = entering.get()
                val seen: Long = scene.number() ?: continue
                // ⚠⚠ Active was read from after the change and the number from before it: the
                // foreground that had just ended, read as the one that had just begun.
                if (seen < before) wrong.compareAndSet(-1, before - seen)
            }
        }
        repeat(400_000) { round ->
            scene.set(false)
            entering.set(round + 1L)
            scene.set(true)
        }
        running.set(false)
        reader.join()
        assertEquals(-1L, wrong.get(), "a reader saw the scene active under the previous foreground's number")
    }
}
