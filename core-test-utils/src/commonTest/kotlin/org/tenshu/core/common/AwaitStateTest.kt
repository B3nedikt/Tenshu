package org.tenshu.core.common

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.milliseconds

/** [awaitState] on a real dispatcher — the clock it is meant for. */
class AwaitStateTest {

    @Test
    fun returnsTheStateThatSatisfiedThePredicate_evenWhenItArrivesLater() = runTest {
        val state = MutableStateFlow(0)
        val seen = withContext(Dispatchers.Default) {
            launch {
                for (i in 1..5) {
                    delay(5)
                    state.value = i
                }
            }
            state.awaitState("the fifth step") { it >= 5 }
        }
        assertEquals(5, seen)
    }

    @Test
    fun aTimeoutNamesTheWaitTheLastStateAndElapsedOverBudget() = runTest {
        val state = MutableStateFlow(7)
        val failure = assertFailsWith<AssertionError> {
            withContext(Dispatchers.Default) {
                state.awaitState("a state that never comes", timeout = 50.milliseconds) { it == 8 }
            }
        }
        val message = failure.message.orEmpty()
        assertContains(message, "a state that never comes")
        assertContains(message, "last state = 7")
        assertContains(message, "/ 50ms", message = "elapsed / budget, the evidence a timeout needs")
    }
}
