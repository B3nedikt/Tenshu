package app.tenshu.control.data

import org.tenshu.core.common.app.HandlerTable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The picker's derivation (`control-cross-app-open.md` TT-4b).
 *
 * ⚠⚠ These are the same four properties `OpenTargetTest` asserts of the **resolver**, restated for
 * the **picker** — because the two are separate code and a settings screen that disagrees with the
 * resolver is worse than no settings screen. If one ever changes, this file should go red.
 */
class OpenHandlerChoicesTest {

    private fun app(appId: String, name: String, here: Boolean = true) = InstalledApp(
        appId = appId,
        name = name,
        iconName = "",
        version = "1.0.0",
        shortDescription = "",
        installedAt = 1L,
        verified = true,
        deviceCount = 1,
        installedOnThisDevice = here,
    )

    private val timeline = app("app.tenshu.timeline", "Timeline")
    private val maps = app("app.tenshu.maps", "Maps")
    private val place = HandlerTable.PLACE_TYPE

    @Test
    fun withNoChoiceTheTablesOrderIsOffered() {
        val rows = openHandlerChoices(listOf(maps, timeline), chosen = emptyMap())

        val row = rows.single()
        assertEquals(place, row.type)
        // ⚠⚠ The TABLE's order, not the list order it was handed: `maps` was passed first, and the
        // picker must still show Timeline first because that is what would actually open.
        assertEquals(listOf("app.tenshu.timeline", "app.tenshu.maps"), row.candidates.map { it.appId })
        assertNull(row.chosenAppId)
    }

    @Test
    fun theChosenAppMovesToTheFront() {
        val row = openHandlerChoices(listOf(timeline, maps), mapOf(place to maps.appId)).single()

        assertEquals("app.tenshu.maps", row.chosenAppId)
        assertEquals(listOf("app.tenshu.maps", "app.tenshu.timeline"), row.candidates.map { it.appId })
    }

    @Test
    fun oneCandidateInstalledHereIsNotAChoice() {
        // ⚠⚠ Maps is in the realm but not on this device, so nothing here can be swapped. Offering
        // the row would invite a preference that changes nothing locally.
        val elsewhere = maps.copy(installedOnThisDevice = false)

        assertTrue(openHandlerChoices(listOf(timeline, elsewhere), emptyMap()).isEmpty())
    }

    @Test
    fun aChoiceForAnAppThatIsNotACandidateHereReadsAsNoChoice() {
        // ⚠⚠ The user picked Maps on another device that has it; this one does not. The resolver
        // falls through to Timeline (§5j.2), so the picker must not show Maps as selected — that
        // would be a screen claiming something the open would not do.
        val calendar = app("app.tenshu.calendar", "Calendar")
        val row = openHandlerChoices(
            apps = listOf(timeline, maps, calendar),
            chosen = mapOf(place to calendar.appId),
        ).single()

        assertNull(row.chosenAppId)
        assertEquals(listOf("app.tenshu.timeline", "app.tenshu.maps"), row.candidates.map { it.appId })
    }

    @Test
    fun anEmptyChoiceIsNoChoice() {
        // ⚠ Clearing writes the empty string (`setPrimaryHandler(type, null)`), and the proxy's
        // reader treats it as absent. The picker must agree, or a cleared setting would render as
        // a selection of nothing at all.
        val row = openHandlerChoices(listOf(timeline, maps), mapOf(place to "")).single()

        assertNull(row.chosenAppId)
    }

    @Test
    fun aChoiceForOneTypeDoesNotAffectAnother() {
        val row = openHandlerChoices(
            apps = listOf(timeline, maps),
            chosen = mapOf(HandlerTable.MOMENT_TYPE to maps.appId),
        ).single()

        assertNull(row.chosenAppId)
    }
}
