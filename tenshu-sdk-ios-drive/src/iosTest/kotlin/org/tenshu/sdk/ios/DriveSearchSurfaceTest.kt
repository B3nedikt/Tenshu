package org.tenshu.sdk.ios

import app.tenshu.drive.data.FileIcon
import app.tenshu.drive.data.FileModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.tenshu.viewmodel.test.vmTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Coverage of [DriveSearchSurface] (`ios-drive-parity.md` §24) over [FakeFilesRepository].
 *
 * Like the sibling surface tests, `Dispatchers.setMain` points Main at the test scheduler before any
 * VM is built, so `viewModelScope` — where the debounced search pipeline runs — is actually pumped.
 * The 250 ms debounce means every assertion has to advance time; that is the behaviour under test as
 * much as the results are, since it is what keeps a keystroke burst from firing one drive-wide
 * traverse per key.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DriveSearchSurfaceTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun file(path: String) =
        FileModel.File(path, path.substringAfterLast('/'), "", FileIcon.Document)

    private fun repo() = FakeFilesRepository(
        initialListing = listOf(
            file("/Report.pdf"),
            file("/notes.txt"),
            file("/archive/report-2024.pdf"),
        )
    )

    @Test
    fun query_matches_names_case_insensitively() = vmTest(dispatcher) { vms ->
        val vm = vms.own(DriveSearchSurface.search(repo()))

        vm.onQueryChanged("report")
        advanceUntilIdle()

        val names = vm.uiState.value.files.map { it.name }.toSet()
        assertEquals(
            setOf("Report.pdf", "report-2024.pdf"),
            names,
            "search matches the file NAME case-insensitively, across folders"
        )
    }

    @Test
    fun blank_query_yields_no_results() = vmTest(dispatcher) { vms ->
        val vm = vms.own(DriveSearchSurface.search(repo()))

        vm.onQueryChanged("   ")
        advanceUntilIdle()

        assertTrue(
            vm.uiState.value.files.isEmpty(),
            "a blank query must not fall back to listing the whole drive"
        )
    }

    @Test
    fun query_is_debounced_so_a_keystroke_burst_runs_one_scan() = vmTest(dispatcher) { vms ->
        val vm = vms.own(DriveSearchSurface.search(repo()))

        // Type "report" a character at a time, faster than the debounce window.
        "report".forEachIndexed { index, _ ->
            vm.onQueryChanged("report".take(index + 1))
            advanceTimeBy(50)
        }

        // The query is reflected immediately (the text field is not debounced) …
        assertEquals("report", vm.uiState.value.query)
        // … but no scan has landed yet: 6 × 50 ms < the 250 ms window.
        assertTrue(
            vm.uiState.value.files.isEmpty(),
            "results must not land mid-burst — that would be one drive-wide traverse per keystroke"
        )

        advanceUntilIdle()
        assertEquals(2, vm.uiState.value.files.size, "the settled query runs exactly one scan")
    }

    @Test
    fun collapsing_the_bar_clears_query_and_results() = vmTest(dispatcher) { vms ->
        val vm = vms.own(DriveSearchSurface.search(repo()))

        vm.onQueryChanged("report")
        advanceUntilIdle()
        assertTrue(vm.uiState.value.files.isNotEmpty(), "precondition: results present")

        vm.onActiveChanged(false)
        advanceUntilIdle()

        assertEquals("", vm.uiState.value.query)
        assertTrue(
            vm.uiState.value.files.isEmpty(),
            "dismissing search must reset it, so the next open starts clean"
        )
    }
}
