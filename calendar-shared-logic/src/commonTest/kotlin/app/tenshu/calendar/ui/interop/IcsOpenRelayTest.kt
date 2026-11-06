package app.tenshu.calendar.ui.interop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * The buffer between "the OS handed us a file" and "the composition is up to collect
 * it" (CA-6c/CA-6d).
 *
 * ⚠⚠ Every test here is about *timing*, because that is the only thing this type
 * does. The platform halves either side of it — an `odoc` Apple Event, an
 * `ACTION_VIEW` intent, a `ContentResolver` stream — are framework behaviour on a
 * device, which is why they hold no logic beyond moving bytes.
 */
class IcsOpenRelayTest {

    private val file = OpenedIcsFile.Content("team.ics", "BEGIN:VCALENDAR\r\nEND:VCALENDAR")

    /**
     * ⚠⚠ **The reason this type exists.** Every platform delivers the first file
     * before there is a collector: on macOS the Apple Event for the double-click that
     * launched the app, on Android the intent `onCreate` reads before `setContent`
     * runs. A plain `SharedFlow` with no replay drops it, and the file association
     * then appears to work only on the *second* file.
     */
    @Test
    fun a_file_delivered_before_anything_collects_is_still_received() = runTest {
        val relay = IcsOpenRelay()

        relay.deliver(file)

        assertEquals(file, relay.files.first())
    }

    /**
     * ⚠ And it never blocks or fails on the way in — it is called from a lifecycle
     * callback, so the buffer drops the oldest rather than applying back-pressure to
     * `onCreate`.
     */
    @Test
    fun delivering_far_more_than_the_buffer_holds_neither_suspends_nor_fails() = runTest {
        val relay = IcsOpenRelay()

        repeat(20) { relay.deliver(OpenedIcsFile.Content("$it.ics", "x")) }

        assertEquals(OpenedIcsFile.Content("19.ics", "x"), relay.files.first())
    }

    /**
     * ⚠⚠ The same file opened twice must arrive twice — it is a distinct user gesture.
     * A `StateFlow`, the obvious alternative, would swallow the second as "no change"
     * and the second double-click would do nothing.
     */
    @Test
    fun the_same_file_opened_twice_arrives_twice() = runTest {
        val relay = IcsOpenRelay()
        val collected = async { relay.files.take(2).toList() }
        // ⚠ Lets the collector actually subscribe before anything is delivered; without
        // it this asserts the replay buffer again rather than live delivery.
        runCurrent()

        relay.deliver(file)
        relay.deliver(file)

        assertEquals(listOf(file, file), collected.await())
    }

    @Test
    fun an_unreadable_file_travels_as_itself_rather_than_as_empty_content() = runTest {
        val relay = IcsOpenRelay()
        val unreadable = OpenedIcsFile.Unreadable("gone.ics")

        relay.deliver(unreadable)

        assertEquals(unreadable, relay.files.first())
    }
}
