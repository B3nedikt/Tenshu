package app.tenshu.photos

import app.tenshu.photos.ui.screens.viewer.ViewerGestures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The numbers both viewers close and zoom by (PIO-3 slice 2). */
class ViewerGesturesTest {

    @Test
    fun aDragPastEighteenPercentOfThePageDismisses_andOneShortOfItDoesNot() {
        // 1000 tall → the line is at 180.
        assertFalse(ViewerGestures.dismisses(180f, 1000f), "strictly PAST the line")
        assertTrue(ViewerGestures.dismisses(181f, 1000f))
        assertFalse(ViewerGestures.dismisses(-400f, 1000f), "an upward drag never dismisses")
    }

    @Test
    fun aPageNotLaidOutYetNeverDismisses() {
        assertFalse(ViewerGestures.dismisses(50f, 0f))
        assertEquals(1f, ViewerGestures.dismissScale(50f, 0f))
    }

    @Test
    fun thePhotographShrinksToThreeQuartersOverHalfThePage_andNoFurther() {
        assertEquals(1f, ViewerGestures.dismissScale(0f, 1000f))
        assertEquals(0.875f, ViewerGestures.dismissScale(250f, 1000f))
        assertEquals(0.75f, ViewerGestures.dismissScale(500f, 1000f))
        assertEquals(0.75f, ViewerGestures.dismissScale(900f, 1000f), "clamped past the travel")
        assertEquals(1f, ViewerGestures.dismissScale(-100f, 1000f), "an upward drag never grows it")
    }

    @Test
    fun zoomStaysBetweenFitAndFiveTimes() {
        assertEquals(1f, ViewerGestures.clampZoom(0.4f))
        assertEquals(2.5f, ViewerGestures.clampZoom(2.5f))
        assertEquals(5f, ViewerGestures.clampZoom(9f))
    }
}
