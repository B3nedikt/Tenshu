package app.tenshu.fit.ui.screens.browse

import app.tenshu.fit.model.CategoryId
import app.tenshu.fit.model.CategorySummary
import app.tenshu.fit.model.CategoryTeaser
import app.tenshu.fit.model.LastUpdated
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * When Browse's "Connect a data source" prompt shows ([hasImportableGap]). Found by the Android
 * store-screenshot walk: with Health Connect connected and every activity, sleep and body card
 * full, the prompt stayed up because Medical — which no Android source can fill — was empty.
 */
class ImportableGapTest {

    private fun full(id: CategoryId) = CategorySummary(id, CategoryTeaser.SleepDuration(420), LastUpdated.Today)
    private fun empty(id: CategoryId) = CategorySummary(id, CategoryTeaser.NotSetUp, LastUpdated.Never)

    private val connected = listOf(CategoryId.Activity, CategoryId.Body, CategoryId.Vitals, CategoryId.Sleep).map(::full)

    @Test
    fun aConnectedSourceWithNoMedicalRecordsAsksForNothing() {
        assertFalse(hasImportableGap(connected + empty(CategoryId.Medical) + empty(CategoryId.Nutrition) + empty(CategoryId.Cycle)))
    }

    @Test
    fun anEmptyCategoryASourceCanFillStillPrompts() {
        for (id in listOf(CategoryId.Activity, CategoryId.Body, CategoryId.Vitals, CategoryId.Sleep)) {
            val categories = connected.filter { it.id != id } + empty(id)
            assertTrue(hasImportableGap(categories), "an empty $id must keep the prompt")
        }
    }
}
