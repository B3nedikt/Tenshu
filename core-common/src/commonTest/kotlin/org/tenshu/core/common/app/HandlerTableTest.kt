package org.tenshu.core.common.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What Control routes, and — just as importantly — what it does not
 * (`control-cross-app-open.md` CO-D2, TT-2a).
 *
 * ⚠⚠ **A type in this table with no route in the receiving app is worse than an absent one.** An
 * absent type refuses with `NO_HANDLER`, which a caller turns into a sentence; a present one
 * launches an app that then shows the user nothing at all. So the table's *contents* are a claim
 * about two codebases, and this test is where that claim is written down.
 */
class HandlerTableTest {

    @Test
    fun everyRoutedTypeNamesAppsThatCanActuallyShowIt() {
        // ⚠⚠ Each line is a promise that the named app has a receiving route. Adding one here
        // without building that route is the failure §5f.2 is about — a launch that shows nothing.
        assertEquals(
            listOf("app.tenshu.calendar"),
            HandlerTable.handlersFor(HandlerTable.OCCURRENCE_TYPE),
        )
        assertEquals(listOf("app.tenshu.drive"), HandlerTable.handlersFor(HandlerTable.FILE_TYPE))
        assertEquals(
            listOf("app.tenshu.timeline"),
            HandlerTable.handlersFor(HandlerTable.MOMENT_TYPE),
        )
        assertEquals(listOf("app.tenshu.fit"), HandlerTable.handlersFor(HandlerTable.WORKOUT_TYPE))
    }

    @Test
    fun aDeviceIsNotRoutedBecauseHomeCannotShowOne() {
        // ⚠⚠ Home's routes are Home, Automations, Activity, Settings and the automation editor —
        // there is no screen for ONE device. Routing it would launch an app that shows nothing,
        // which is worse than the honest `NO_HANDLER` a device citation gets today (§5f.2).
        // ⚠ Delete this test when Home grows a device detail route — not before.
        assertEquals(emptyList(), HandlerTable.handlersFor(HandlerTable.DEVICE_TYPE))
    }

    @Test
    fun aPlacePrefersTimelineAndFallsBackToMaps() {
        // ⚠⚠ **Order is the contract, not the membership.** Both apps can show a place; which one
        // the user gets when both are installed is what this list decides, and a silent reordering
        // would change behaviour on every device that has both.
        assertEquals(
            listOf("app.tenshu.timeline", "app.tenshu.maps"),
            HandlerTable.handlersFor(HandlerTable.PLACE_TYPE),
        )
    }

    @Test
    fun anUnknownTypeIsNotRouted() {
        // ⚠ Empty, not null — a caller that treats "no candidates" as a refusal reason must not
        // also have to handle an absent key.
        assertEquals(emptyList(), HandlerTable.handlersFor("tenshu/imaginary/thing"))
    }

    @Test
    fun onlyTypesWithMoreThanOneCandidateAreChoosable() {
        // ⚠⚠ TT-4b renders one picker per choosable type, and a "choice" between one option is not
        // a choice — offering it would invite the user to express a preference that can never
        // change anything. `place` is the only type with two candidates today, so it is the only
        // one the Apps screen may show.
        assertEquals(listOf(HandlerTable.PLACE_TYPE), HandlerTable.choosableTypes())
    }

    @Test
    fun everyChoosableTypeIsInTheTableItWasDerivedFrom() {
        // ⚠ Cheap, but it is the property a hand-maintained second list would break: the choosable
        // set is DERIVED from the handler map, so the two cannot drift.
        HandlerTable.choosableTypes().forEach { type ->
            assertTrue(HandlerTable.handlersFor(type).size > 1, type)
        }
    }
}
