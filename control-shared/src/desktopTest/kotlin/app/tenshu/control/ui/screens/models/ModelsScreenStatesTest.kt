package app.tenshu.control.ui.screens.models

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.tenshu.control.inference.ModelSetup
import app.tenshu.control.inference.ModelsAvailability
import org.junit.Rule
import org.junit.Test
import org.tenshu.inference.InferenceCapability
import kotlin.test.assertEquals

/**
 * The Models screen **opened** in each state its repository can report: what it says, and which
 * buttons exist.
 *
 * ⚠⚠ The only automated look at this screen. The Android smoke gate launches Control and never
 * opens Settings → Models, so a state that draws a dead button, or no row, is caught here or by
 * hand.
 */
class ModelsScreenStatesTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val search = InferenceCapability.SEMANTIC_IMAGE_SEARCH
    private val absent = ManagedModel("siglip", "SigLIP", byteSize = 401_604_608)
    private val held = ManagedModel("faces", "Faces", byteSize = 104_857_600, installed = true)
    private fun download(written: Long?) = ModelSetup.Download(allowsCellular = true, writtenBytes = written, totalBytes = 401_604_608, held = null)

    private var state by mutableStateOf(ModelsUiState(ControlModelsCapabilities))
    private val pressed = mutableListOf<String>()

    private fun open(opening: ModelsUiState) {
        state = opening
        composeTestRule.setContent {
            ModelsScreenContent(
                state = state,
                onDownload = { pressed += "download:$it" },
                onDownloadOverCellular = { pressed += "cellular:$it" },
                onCancel = { pressed += "cancel:$it" },
                onConfirmDelete = { pressed += "remove:$it" },
                onRetry = { pressed += "retry" },
                onBack = {},
            )
        }
    }

    private fun texts(text: String) = composeTestRule.onAllNodesWithText(text).fetchSemanticsNodes().size
    private fun buttons(description: String) = composeTestRule.onAllNodesWithContentDescription(description).fetchSemanticsNodes().size
    private fun rowButtons() = listOf("Download SigLIP", "Remove SigLIP", "Download Faces", "Remove Faces").associateWith(::buttons).filterValues { it > 0 }.keys

    @Test
    fun loadingSaysSoAndNothingElse() {
        open(ModelsUiState(ControlModelsCapabilities))

        composeTestRule.onNodeWithText("Loading…").assertIsDisplayed()
        assertEquals(0, texts("Try again"))
        assertEquals(0, texts("Indexing photos"))
        assertEquals(emptySet(), rowButtons())
    }

    /**
     * ⚠⚠ A read that failed: its sentence, the retry, and **no row**. Nothing is shown as absent
     * with a download offered, and the indexing line does not say "no model installed".
     */
    @Test
    fun aFailedReadSaysSoOffersTryAgainAndNoRow() {
        open(ModelsUiState(ControlModelsCapabilities))
        val cases = listOf(
            ModelsAvailability.InstalledUnreadable to INSTALLED_UNREADABLE,
            ModelsAvailability.NotConnected to NOT_CONNECTED,
        )
        cases.forEach { (failed, sentence) ->
            pressed.clear()
            state = ModelsUiState(ControlModelsCapabilities, availability = failed)
            composeTestRule.waitForIdle()

            composeTestRule.onNodeWithText(sentence).assertIsDisplayed()
            assertEquals(emptySet(), rowButtons(), "$failed")
            assertEquals(0, texts("No models are available for this device yet."), "$failed")
            assertEquals(0, texts("No model installed, so nothing is indexed yet."), "$failed")
            composeTestRule.onNodeWithText(INSTALLED_NOT_KNOWN).assertIsDisplayed()
            composeTestRule.onNodeWithText("Try again").assertIsEnabled().performClick()
            assertEquals(listOf("retry"), pressed, "$failed")

            // ⚠ While the retry runs the button stays and cannot be pressed twice.
            state = state.copy(checkingAgain = true)
            composeTestRule.waitForIdle()
            composeTestRule.onNodeWithText("Try again").assertIsNotEnabled()
        }
    }

    @Test
    fun anAbsentModelOffersDownloadAndAnInstalledOneRemove() {
        open(ModelsUiState(loading = false, models = listOf(absent, held)))

        composeTestRule.onNodeWithText("383 MB").assertIsDisplayed()
        composeTestRule.onNodeWithText("Installed · 100 MB").assertIsDisplayed()
        assertEquals(setOf("Download SigLIP", "Remove Faces"), rowButtons())
        composeTestRule.onNodeWithContentDescription("Download SigLIP").performClick()
        composeTestRule.onNodeWithContentDescription("Remove Faces").performClick()
        assertEquals(listOf("download:siglip", "remove:faces"), pressed)
        assertEquals(0, texts("Try again"))
    }

    /**
     * ⚠⚠ A screen opened over a running download shows it, with no press of its own: the bar is
     * the row's. And that row offers neither a second download nor a removal, while the other
     * row's button still works.
     */
    @Test
    fun aRunningDownloadIsShownOnOpenAndHoldsOnlyItsOwnRow() {
        open(ModelsUiState(ControlModelsCapabilities, availability = listed(listOf(absent.row(download = download(104_857_600)), held.row()))))

        composeTestRule.onNodeWithText("100 MB of 383 MB").assertIsDisplayed()
        assertEquals(setOf("Remove Faces"), rowButtons())
        composeTestRule.onNodeWithContentDescription("Remove Faces").assertIsEnabled().performClick()
        assertEquals(listOf("remove:faces"), pressed)
        // ⚠ No second download beside it: this repository runs one at a time, and would refuse.
        state = ModelsUiState(ControlModelsCapabilities, availability = listed(listOf(absent.row(download = download(104_857_600)), absent.copy(modelId = "other", name = "Other").row())))
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithContentDescription("Download Other").assertIsNotEnabled()
        // ⚠ And the row says why: a button that cannot be pressed cannot explain itself.
        composeTestRule.onNodeWithText("383 MB · $WAITS_FOR_A_DOWNLOAD").assertIsDisplayed()
        val waiting = state
        // ⚠ Over a store that cannot be changed the line is not said: the other download ending
        // would free nothing here, and the notice above says why the button is dead.
        state = waiting.copy(storeReadOnly = StoreReadOnly.OwnedElsewhere)
        composeTestRule.waitForIdle()
        assertEquals(0, texts("383 MB · $WAITS_FOR_A_DOWNLOAD"))
        composeTestRule.onNodeWithContentDescription("Download Other").assertIsNotEnabled()
        state = waiting.copy(capabilities = state.capabilities.copy(oneDownloadAtATime = false))
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithContentDescription("Download Other").assertIsEnabled()
        assertEquals(0, texts("383 MB · $WAITS_FOR_A_DOWNLOAD"), "nothing waits where two downloads may run")
        // And it goes when the slot frees.
        state = waiting
        composeTestRule.waitForIdle()
        assertEquals(1, texts("383 MB · $WAITS_FOR_A_DOWNLOAD"))
        state = ModelsUiState(ControlModelsCapabilities, availability = listed(listOf(absent.row(), absent.copy(modelId = "other", name = "Other").row())))
        composeTestRule.waitForIdle()
        assertEquals(0, texts("383 MB · $WAITS_FOR_A_DOWNLOAD"))
        composeTestRule.onNodeWithContentDescription("Download Other").assertIsEnabled()
        state = ModelsUiState(ControlModelsCapabilities, availability = listed(listOf(absent.row(download = download(104_857_600)), held.row())))
        composeTestRule.waitForIdle()
        // ⚠ No way to stop it is drawn where the repository has none.
        assertEquals(0, buttons("Stop downloading SigLIP"))

        // Before the first byte: said, with the size.
        state = ModelsUiState(ControlModelsCapabilities, availability = listed(listOf(absent.row(download = download(null)))))
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Downloading… · 383 MB").assertIsDisplayed()
    }

    /** ⚠⚠ An installed model with an update has both buttons: it could not be removed before. */
    @Test
    fun anInstalledModelWithAnUpdateCanBeUpdatedAndRemoved() {
        open(ModelsUiState(loading = false, models = listOf(held.copy(updateAvailable = true))))

        composeTestRule.onNodeWithText("Update available · 100 MB").assertIsDisplayed()
        assertEquals(setOf("Download Faces", "Remove Faces"), rowButtons())
        composeTestRule.onNodeWithContentDescription("Remove Faces").performClick()
        composeTestRule.onNodeWithContentDescription("Download Faces").performClick()
        assertEquals(listOf("remove:faces", "download:faces"), pressed)

        // ⚠ While the update is fetched the model is still installed, and is not removed under it.
        state = ModelsUiState(ControlModelsCapabilities, availability = listed(listOf(held.copy(updateAvailable = true).row(download = download(104_857_600)))))
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("100 MB of 383 MB").assertIsDisplayed()
        assertEquals(emptySet(), rowButtons())
    }

    /** With no catalog an installed model is listed, removable, and offered no update on no evidence. */
    @Test
    fun withNoCatalogAnInstalledModelIsListedAndClaimsNoUpdate() {
        open(ModelsUiState(loading = false, models = listOf(held), catalogUnavailable = true))

        composeTestRule.onNodeWithText(CATALOG_UNAVAILABLE).assertIsDisplayed()
        composeTestRule.onNodeWithText("Installed · 100 MB").assertIsDisplayed()
        assertEquals(setOf("Remove Faces"), rowButtons())
        assertEquals(1, texts("Try again"))
    }

    /** ⚠⚠ A refresh that failed keeps the rows, and says the list is the last one read. */
    @Test
    fun aStaleListKeepsItsRowsAndSaysSo() {
        open(ModelsUiState(ControlModelsCapabilities, availability = listed(listOf(absent.row(), held.row()), ModelsAvailability.Catalog.STALE)))

        composeTestRule.onNodeWithText(CATALOG_STALE).assertIsDisplayed()
        assertEquals(setOf("Download SigLIP", "Remove Faces"), rowButtons())
        composeTestRule.onNodeWithText("Try again").performClick()
        assertEquals(listOf("retry"), pressed)
    }

    /** A Control that does not own the store says so up front, and its buttons cannot be pressed. */
    @Test
    fun aReadOnlyStoreSaysSoAndItsButtonsCannotBePressed() {
        open(ModelsUiState(loading = false, models = listOf(absent, held), storeReadOnly = StoreReadOnly.OwnedElsewhere))

        composeTestRule.onNodeWithText(storeReadOnlyNotice(StoreReadOnly.OwnedElsewhere)).assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Download SigLIP").assertIsNotEnabled()
        composeTestRule.onNodeWithContentDescription("Remove Faces").assertIsNotEnabled()
    }

    /** A row being removed, or whose download was pressed and not answered, has no button. */
    @Test
    fun aRowThatIsBeingRemovedOrStartedHasNoButtonAndTheOthersKeepTheirs() {
        open(ModelsUiState(loading = false, models = listOf(absent, held)).copy(removing = setOf("faces")))
        composeTestRule.onNodeWithText("Removing…").assertIsDisplayed()
        assertEquals(setOf("Download SigLIP"), rowButtons())
        composeTestRule.onNodeWithContentDescription("Download SigLIP").assertIsEnabled()

        state = ModelsUiState(loading = false, models = listOf(absent, held)).copy(starting = setOf("siglip"))
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Starting… · 383 MB").assertIsDisplayed()
        assertEquals(setOf("Remove Faces"), rowButtons())
    }

    /**
     * ⚠⚠ What a repository cannot do is not drawn, and what it can is: the same screen over
     * another platform's capabilities has a stop, a second download choice, no indexing section
     * and no switch for a feature that platform does not have.
     */
    @Test
    fun whatTheRepositoryCannotDoIsNotDrawn() {
        val rows = listed(listOf(absent.row(), absent.copy(modelId = "other", name = "Other").row(download = download(1))))
        open(ModelsUiState(ControlModelsCapabilities, availability = rows, consents = listOf(CapabilityConsent(search))))

        assertEquals(0, buttons("Stop downloading Other"))
        assertEquals(0, buttons("Download SigLIP using mobile data"))
        composeTestRule.onNodeWithText("Indexing photos").assertIsDisplayed()

        val elsewhere = ModelsCapabilities(
            cancel = true,
            cellularChoice = true,
            oneDownloadAtATime = false,
            spaceReturns = ModelsCapabilities.SpaceReturns.TOWERS_AT_ONCE,
            features = listOf(search),
            indexing = false,
            otherDevices = false,
        )
        val on = listOf(CapabilityConsent(search, realm = true, device = true))
        val others = mapOf(search to listOf(OtherDeviceConsent("dev-laptop", "Laptop", allowed = true)))
        state = ModelsUiState(ControlModelsCapabilities, availability = rows, consents = on, consentsRead = true, otherDevices = others)
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("On for this device and 1 other device").assertIsDisplayed()

        state = ModelsUiState(availability = rows, capabilities = elsewhere, consents = on, consentsRead = true, otherDevices = others)
        composeTestRule.waitForIdle()
        // ⚠ A device the feature screen would not list is not counted in the summary either.
        composeTestRule.onNodeWithText("On for this device").assertIsDisplayed()

        composeTestRule.onNodeWithContentDescription("Stop downloading Other").performClick()
        composeTestRule.onNodeWithContentDescription("Download SigLIP using mobile data").performClick()
        assertEquals(listOf("cancel:other", "cellular:siglip"), pressed)
        assertEquals(0, texts("Indexing photos"))
        assertEquals(0, texts("Index now"))
        composeTestRule.onNodeWithText(consentHeadline(search)).assertIsDisplayed()
        assertEquals(0, texts(consentHeadline(InferenceCapability.FACE_CLUSTERING)))
        // A store's refusal has a sentence where no state says which refusal it was.
        state = state.copy(message = ModelsMessage.StoreReadOnly)
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText(STORE_REFUSED).assertIsDisplayed()
    }

    /** A device that is offered no model says why, and offers nothing: no row, no section, no switch. */
    @Test
    fun aDeviceThatIsOfferedNoModelSaysWhyAndOffersNothing() {
        open(ModelsUiState(ControlModelsCapabilities, availability = ModelsAvailability.NotOffered(ModelsAvailability.NotOffered.Why.NOT_ENOUGH_MEMORY)))

        composeTestRule.onNodeWithText(notOfferedText(ModelsAvailability.NotOffered.Why.NOT_ENOUGH_MEMORY)).assertIsDisplayed()
        assertEquals(0, texts("Indexing photos"))
        assertEquals(0, texts(consentHeadline(search)))
        assertEquals(0, texts("Try again"))
    }

    /** The feature screen without a roster: this device's switch alone, and no *Turn off everywhere*. */
    @Test
    fun theFeatureScreenWithoutARosterListsThisDeviceAlone() {
        val laptop = OtherDeviceConsent("dev-laptop", "Laptop", allowed = true)
        var current by mutableStateOf(
            ModelsUiState(
                loading = false,
                consents = listOf(CapabilityConsent(search, realm = true, device = true)),
                otherDevices = mapOf(search to listOf(laptop)),
                otherDevicesKnown = true,
            ),
        )
        composeTestRule.setContent { ModelFeatureScreenContent(state = current, capability = search, onBack = {}) }
        composeTestRule.onNodeWithText("Laptop").assertIsDisplayed()
        composeTestRule.onNodeWithText(turnOffEverywhereLabel(search)).assertIsDisplayed()
        // ⚠⚠ A roster that is not known now: its rows stay, and none of them can be written.
        val laptopSwitch = androidx.compose.ui.test.isToggleable() and androidx.compose.ui.test.hasAnyAncestor(androidx.compose.ui.test.hasText("Laptop"))
        composeTestRule.onNode(laptopSwitch).assertIsEnabled()
        current = current.copy(otherDevicesKnown = false)
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Laptop").assertIsDisplayed()
        composeTestRule.onNode(laptopSwitch).assertIsNotEnabled()
        current = current.copy(otherDevicesKnown = true)

        current = current.copy(capabilities = current.capabilities.copy(otherDevices = false))
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("This device").assertIsDisplayed()
        assertEquals(0, texts("Laptop"))
        assertEquals(0, texts(turnOffEverywhereLabel(search)))
    }
}
