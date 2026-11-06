package org.tenshu.calendar.proxy

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.tenshu.calendar.CalendarService
import org.tenshu.calendar.client.calendarServiceClientModule

/**
 * Every operation Calendar declares has a proxy implementation.
 *
 * ⚠⚠ **This guards the shape of a real, shipped defect.** CA-5c found that
 * Calendar's whole proxy layer had never been wired into Control — the ops
 * existed only inside `TestProxy` fixtures, so on a device every write failed
 * and every synced row stayed invisible, across four merged phases. That
 * particular hole is closed by wiring; the *class* of hole it belongs to —
 * "declared an operation, never gave it an implementation" — is what this test
 * makes impossible to reach again, and it is the realistic next failure now
 * that the module is registered.
 *
 * ⚠ Nothing else catches it. A missing implementation is not a compile error,
 * and it does not fail at startup: it surfaces at the first call as an opaque
 * `error/internal`, the same way a missing `roFactory` does
 * (`fit-workout-streams-content-plane.md`).
 */
class CalendarOperationCoverageTest {

    /**
     * The op ids the client module registers — i.e. everything a caller can
     * actually invoke.
     *
     * ⚠ Read from the module rather than hand-listed, so a new op is covered by
     * this test the moment it is registered rather than when someone remembers
     * to extend a list here.
     */
    private fun declaredOperationIds(): Set<String> =
        calendarServiceClientModule.operations[CalendarService.ID]?.keys.orEmpty()

    @Test
    fun the_client_module_declares_operations_at_all() {
        // ⚠ A guard on the guard: if the reflection above ever returns nothing,
        // every assertion below would pass vacuously and this test would become
        // decoration.
        assertTrue(declaredOperationIds().size >= 9, "got ${declaredOperationIds()}")
    }

    @Test
    fun every_declared_operation_has_a_proxy_implementation() {
        val missing = declaredOperationIds()
            .filter { calendarServiceProxyImplementationFactory(it) == null }

        assertTrue(
            missing.isEmpty(),
            "no proxy implementation for: $missing — these fail at the first call " +
                "with an opaque error/internal, not at startup",
        )
    }

    @Test
    fun the_factory_declines_ids_it_does_not_own() {
        // ⚠ Returning an implementation for a foreign id would break the
        // `?:`-chained factory in Control's platform module, which relies on
        // null meaning "not mine, ask the next one".
        assertTrue(calendarServiceProxyImplementationFactory("tenshu/sync/service/drainSync") == null)
        assertTrue(calendarServiceProxyImplementationFactory("") == null)
    }

    @Test
    fun the_reminder_operations_are_among_them() {
        val ids = declaredOperationIds()

        listOf(
            "tenshu/calendar/service/saveReminder",
            "tenshu/calendar/service/deleteReminder",
            "tenshu/calendar/service/setReminderState",
        ).forEach { id ->
            assertTrue(id in ids, "$id is not registered client-side")
            assertNotNull(calendarServiceProxyImplementationFactory(id), "$id has no implementation")
        }
    }
}
