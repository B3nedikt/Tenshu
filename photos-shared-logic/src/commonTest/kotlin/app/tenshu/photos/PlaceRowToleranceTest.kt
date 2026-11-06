package app.tenshu.photos

import app.tenshu.photos.model.photoPlaceOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * A `Place` row that is missing a field must be **dropped, not fatal**
 * (`photos-viewer-about-screen.md`; `OptionalFieldValue`'s KDoc).
 *
 * ⚠⚠ These rows are written by **Timeline**, a different app. Photos only reads them, so a row is
 * whatever the Timeline that wrote it knew how to write — and the bare
 * `entity.field.get()` idiom turns an absent field into a `NullPointerException` on the JVM and, on
 * Kotlin/Native, an **`EXC_BAD_ACCESS` / `SIGSEGV` that no `catch` can intercept**. It was measured
 * on 2026-08-26 segfaulting `fit-shared-data`'s iOS test binary. `photos-shared-logic` has iOS
 * targets, so this is not a JVM-only concern here.
 *
 * ⚠ The rule under test is a **per-field judgement**, which is the whole reason this is a function
 * and not a `catch`: some absences make the row unusable, one of them does not.
 */
class PlaceRowToleranceTest {

    private val eiffelLat = 488_584_000L
    private val eiffelLng = 22_945_000L

    private fun place(
        placeId: String? = "p-1",
        label: String? = "Eiffel Tower",
        city: String? = "Paris",
        latE7: Long? = eiffelLat,
        lngE7: Long? = eiffelLng,
    ) = photoPlaceOrNull(placeId, label, city, latE7, lngE7)

    @Test
    fun acompleteRowBecomesAPlace() {
        val result = place()

        assertEquals("p-1", result?.placeId)
        assertEquals("Eiffel Tower", result?.label)
        assertEquals("Paris", result?.city)
        assertEquals(eiffelLat, result?.latE7)
    }

    @Test
    fun aMissingIdentityOrCoordinateDropsTheRow() {
        // ⚠ Without these the row cannot be identified or placed on a map, so there is nothing
        // truthful to render. Dropping is the same answer the Places section already gives for a
        // photograph with no coordinate.
        assertNull(place(placeId = null), "no id")
        assertNull(place(placeId = ""), "blank id")
        assertNull(place(latE7 = null), "no latitude")
        assertNull(place(lngE7 = null), "no longitude")
    }

    @Test
    fun anUnnamedRowIsDroppedWhetherItIsAbsentOrBlank() {
        // ⚠⚠ The rule that predates this change and had to survive it: `Place.label` is empty until
        // something geocodes it (PH-G7 is unshipped), so "unnamed" is the *usual* state of a place
        // and "Unnamed place" is not a section a user can act on. Absent and blank must behave
        // identically — otherwise the tolerance would quietly resurrect the rows the old code
        // deliberately hid.
        assertNull(place(label = null), "absent label")
        assertNull(place(label = ""), "blank label")
        assertNull(place(label = "   "), "whitespace label")
    }

    @Test
    fun aMissingCityIsToleratedBecauseAPlaceNeedNotHaveOne() {
        // ⚠ The one absence that is not fatal, and the reason this is a judgement rather than a
        // blanket "drop anything incomplete": a named place with no city is still a place.
        val result = place(city = null)

        assertEquals("Eiffel Tower", result?.label)
        assertEquals("", result?.city, "absent city reads as empty, not as a dropped row")
    }
}
