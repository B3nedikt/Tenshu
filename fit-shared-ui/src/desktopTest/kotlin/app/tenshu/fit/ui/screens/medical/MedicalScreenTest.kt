package app.tenshu.fit.ui.screens.medical

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import app.tenshu.fit.data.MockFitRepository
import app.tenshu.fit.model.MedicalRecord
import app.tenshu.fit.model.MedicalRecordKind
import app.tenshu.ui.theme.TenshuTheme
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import app.tenshu.fit.composeVmTest

/**
 * ⚠ The assertion here is at the **record** level, not the section level, and that distinction is
 * the whole test.
 *
 * `MedicalSection` is one per [MedicalRecordKind] — at most four, fixed by the enum. A `LazyColumn`
 * over *sections* therefore proves nothing: four items all fit on screen, so "a distant section is
 * not composed" is vacuously false and the assertion would pass against an eager `Column` too. What
 * an import makes unbounded is the number of **records inside** a kind, which is why the screen
 * emits them through `groupedRows` as individual items.
 */
@OptIn(ExperimentalTestApi::class)
class MedicalScreenTest {

    @Test
    fun distantRecordsWithinASectionAreNotComposed() = composeVmTest { vms ->
        val vm = vms.own(MedicalViewModel(ManyRecordsRepository(records = 80)))
        setContent {
            TenshuTheme { MedicalScreen(viewModel = vm, onBack = {}) }
        }

        waitUntil {
            onAllNodesWithText("Record 0", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(
            0,
            onAllNodesWithText("Record 79", useUnmergedTree = true).fetchSemanticsNodes().size,
            "the last record composed on first paint — the section is rendering its rows eagerly",
        )
    }
}

/** [MockFitRepository] with one long medical section in place of the fixture's handful. */
private class ManyRecordsRepository(
    records: Int,
    private val delegate: MockFitRepository = MockFitRepository(),
) : app.tenshu.fit.data.FitRepository by delegate {

    private val rows = (0 until records).map { i ->
        MedicalRecord(
            kind = MedicalRecordKind.Medication,
            title = "Record $i",
            detail = "detail $i",
            effectiveDate = LocalDate(2026, 1, 1),
            issuer = "",
            source = app.tenshu.fit.model.Source.File,
        )
    }

    override fun medicalRecords() = kotlinx.coroutines.flow.flowOf(rows)
}
