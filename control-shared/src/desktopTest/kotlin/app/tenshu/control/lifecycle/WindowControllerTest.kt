package app.tenshu.control.lifecycle

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WindowControllerTest {

    @Test
    fun startsVisibleByDefaultAndTogglesWithShowHide() {
        val controller = WindowController()
        assertTrue(controller.visible.value)

        controller.hide()
        assertFalse(controller.visible.value)

        controller.show()
        assertTrue(controller.visible.value)
    }

    @Test
    fun honoursInitiallyVisibleFalse() {
        assertFalse(WindowController(initiallyVisible = false).visible.value)
    }

    @Test
    fun requestQuitEmitsOnQuitRequested() = runTest {
        val controller = WindowController()
        val received = async { controller.quitRequested.first() }
        yield() // let the collector subscribe before we emit

        controller.requestQuit()

        // Completes (Unit) iff the signal was delivered; the test times out otherwise.
        assertEquals(Unit, received.await())
    }
}
