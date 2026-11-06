package app.tenshu.contacts.ui.screens

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.tenshu.contacts.data.MockContactsRepository
import app.tenshu.contacts.ui.screens.importing.ContactImportOutcome
import app.tenshu.contacts.ui.screens.importing.ContactImportSource
import app.tenshu.contacts.ui.screens.importing.ContactImportViewModel
import app.tenshu.contacts.ui.screens.importing.ContactImporter
import app.tenshu.contacts.ui.screens.importing.ImportFailure
import app.tenshu.contacts.ui.screens.importing.ImportSheetContent
import app.tenshu.ui.theme.TenshuTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import app.tenshu.contacts.composeVmTest

/**
 * The Ph7 import sheet over a stand-in [ContactImporter]. The platform actuals cannot be
 * exercised off a device, but everything *around* them can: which rows a platform offers,
 * what each outcome renders, and that the seam is the only thing that had to be faked.
 */
@OptIn(ExperimentalTestApi::class)
class ImportSheetTest {

    /** Answers [supported] to `supports` and replays [outcome] on launch. */
    private class FakeImporter(
        private val supported: Set<ContactImportSource>,
        private val outcome: ContactImportOutcome = ContactImportOutcome.Cancelled,
    ) : ContactImporter {
        val launched = mutableListOf<ContactImportSource>()

        override fun supports(source: ContactImportSource) = source in supported

        override fun import(
            source: ContactImportSource,
            onOutcome: (ContactImportOutcome) -> Unit,
        ) {
            launched += source
            onOutcome(outcome)
        }
    }

    private fun card(name: String) =
        "BEGIN:VCARD\r\nVERSION:3.0\r\nFN:$name\r\nEND:VCARD\r\n"

    @Test
    fun aDesktopOnlyImporterOffersJustTheFileRow() = composeVmTest { vms ->
        // The rows are absent, not disabled: a greyed-out "Import your whole address book"
        // on desktop would read as something the user failed to switch on.
        val vm = vms.own(ContactImportViewModel(MockContactsRepository(initial = emptyList())))
        setContent {
            TenshuTheme {
                ImportSheetContent(vm, FakeImporter(setOf(ContactImportSource.File)))
            }
        }

        onNodeWithText("Import a .vcf file", useUnmergedTree = true).assertIsDisplayed()
        assertEquals(
            0,
            onAllNodesWithText("Import your whole address book", useUnmergedTree = true)
                .fetchSemanticsNodes().size,
        )
        assertEquals(
            0,
            onAllNodesWithText("Pick one person", useUnmergedTree = true)
                .fetchSemanticsNodes().size,
        )
    }

    @Test
    fun anAndroidImporterListsThePermissionlessSourcesFirst() = composeVmTest { vms ->
        val vm = vms.own(ContactImportViewModel(MockContactsRepository(initial = emptyList())))
        setContent {
            TenshuTheme {
                ImportSheetContent(vm, FakeImporter(ContactImportSource.entries.toSet()))
            }
        }

        onNodeWithText("Pick one person", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithText("Import a .vcf file", useUnmergedTree = true).assertIsDisplayed()
        // And the one that costs a permission says so before it is tapped.
        onNodeWithText("Asks for permission to read your contacts.", useUnmergedTree = true)
            .assertIsDisplayed()
    }

    @Test
    fun importedCardsAreReportedByCount() = composeVmTest { vms ->
        val vm = vms.own(ContactImportViewModel(MockContactsRepository(initial = emptyList())))
        val importer = FakeImporter(
            supported = setOf(ContactImportSource.File),
            outcome = ContactImportOutcome.Cards(card("Mira") + card("Dad")),
        )
        setContent { TenshuTheme { ImportSheetContent(vm, importer) } }

        onNodeWithText("Import a .vcf file", useUnmergedTree = true).performClick()

        waitUntil {
            onAllNodesWithText("Imported 2 contacts.", useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(listOf(ContactImportSource.File), importer.launched)
    }

    @Test
    fun aRefusedContactsPermissionPointsAtTheRowThatAsksProperly() = composeVmTest { vms ->
        // The permissionless picker's grant does not always extend to the vCard URI. When it
        // does not, the sheet has to offer a way forward rather than a dead end.
        val vm = vms.own(ContactImportViewModel(MockContactsRepository(initial = emptyList())))
        val importer = FakeImporter(
            supported = ContactImportSource.entries.toSet(),
            outcome = ContactImportOutcome.Failed(ImportFailure.PermissionDenied),
        )
        setContent { TenshuTheme { ImportSheetContent(vm, importer) } }

        onNodeWithText("Pick one person", useUnmergedTree = true).performClick()

        waitUntil {
            onAllNodesWithText(
                "Import your whole address book",
                substring = true,
                useUnmergedTree = true,
            ).fetchSemanticsNodes().isNotEmpty()
        }
        // The other sources are still there to try.
        onNodeWithText("Import a .vcf file", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun cancellingThePickerSaysNothingAtAll() = composeVmTest { vms ->
        val vm = vms.own(ContactImportViewModel(MockContactsRepository(initial = emptyList())))
        setContent {
            TenshuTheme {
                ImportSheetContent(vm, FakeImporter(setOf(ContactImportSource.File)))
            }
        }

        onNodeWithText("Import a .vcf file", useUnmergedTree = true).performClick()

        // Backing out is ordinary; an error line for it would train people to ignore the one
        // that matters.
        assertEquals(
            0,
            onAllNodesWithText("could not be read", substring = true, useUnmergedTree = true)
                .fetchSemanticsNodes().size,
        )
        onNodeWithText("Import a .vcf file", useUnmergedTree = true).assertIsDisplayed()
    }
}
