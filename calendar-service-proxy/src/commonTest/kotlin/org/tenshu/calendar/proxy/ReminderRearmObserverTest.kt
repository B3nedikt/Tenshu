package org.tenshu.calendar.proxy

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.tenshu.calendar.proxy.impl.ReminderRearmObserver
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * When the reminder plan is made. A host whose presenter only hands the plan to the OS (desktop) asks for a plan at
 * session start; otherwise its plan would stay empty after a restart until the first calendar change.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReminderRearmObserverTest {

    @Test
    fun aHostThatAsksPlansOnceAtStart_withoutAnyCalendarChange() = runTest {
        var plans = 0
        val scope = kotlinx.coroutines.CoroutineScope(coroutineContext + kotlinx.coroutines.Job())
        ReminderRearmObserver(scope = scope, planOnStart = true) { plans++ }
        advanceUntilIdle()
        assertEquals(1, plans)
        scope.cancel()
    }

    /** The default: a host whose presenter posts (Android) must not plan, and so deliver, on every start. */
    @Test
    fun byDefaultNothingIsPlannedUntilTheCalendarChanges() = runTest {
        var plans = 0
        val scope = kotlinx.coroutines.CoroutineScope(coroutineContext + kotlinx.coroutines.Job())
        ReminderRearmObserver(scope = scope) { plans++ }
        advanceUntilIdle()
        assertEquals(0, plans)
        scope.cancel()
    }
}
