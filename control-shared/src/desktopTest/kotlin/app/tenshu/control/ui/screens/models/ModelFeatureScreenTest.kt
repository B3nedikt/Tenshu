package app.tenshu.control.ui.screens.models

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import org.tenshu.inference.InferenceCapability
import kotlin.test.assertEquals

/**
 * The feature screen (`inference-service.md` §38): one list of devices, each switch its own device's
 * answer.
 */
class ModelFeatureScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val search = InferenceCapability.SEMANTIC_IMAGE_SEARCH
    private val faces = InferenceCapability.FACE_CLUSTERING

    private val laptop = OtherDeviceConsent("dev-laptop", "Laptop", allowed = true)
    private val tablet = OtherDeviceConsent("dev-tablet", "Tablet", allowed = false)

    /** Every call the screen made, in order. */
    private val calls = mutableListOf<String>()

    private fun setScreen(
        capability: InferenceCapability,
        consent: CapabilityConsent = CapabilityConsent(capability),
        others: List<OtherDeviceConsent> = emptyList(),
        othersKnown: Boolean = true,
        read: Boolean = true,
    ) {
        composeTestRule.setContent {
            ModelFeatureScreenContent(
                state = ModelsUiState(
                    loading = false,
                    consents = listOf(consent),
                    otherDevices = mapOf(capability to others),
                    otherDevicesKnown = othersKnown,
                    consentsRead = read,
                ),
                capability = capability,
                onSetRealmConsent = { c, on -> calls += "realm:${c.id}:$on" },
                onSetDeviceConsent = { c, on -> calls += "device:${c.id}:$on" },
                onTurnOffOtherDevice = { c, id -> calls += "other:${c.id}:$id" },
                onTurnOffEverywhere = { c -> calls += "everywhere:${c.id}" },
                onBack = {},
            )
        }
    }

    private fun switchOf(row: String) = composeTestRule.onNode(isToggleable() and hasAnyAncestor(hasText(row)))

    /** ⚠⚠ Before the answers were read nothing can be switched: the seed is no answer. */
    @Test
    fun beforeTheAnswersWereReadNoSwitchActs() {
        setScreen(search, others = listOf(laptop), read = false)

        switchOf("This device").assertIsNotEnabled()
        switchOf("Laptop").assertIsNotEnabled()
        composeTestRule.onNodeWithText("This device").performClick()

        assertEquals(emptyList(), calls)
    }

    /**
     * ⚠⚠ On from this device is the realm write, which carries this device's answer with it and
     * orders the pair (§32.4) — a bare device write under a realm no would be a switch that moves
     * and indexes nothing.
     */
    @Test
    fun switchingThisDeviceOnWritesTheRealmAnswerToo() {
        setScreen(search)
        composeTestRule.onNodeWithText(featureDescription(search)).assertIsDisplayed()

        composeTestRule.onNodeWithText("This device").performClick()

        assertEquals(listOf("realm:${search.id}:true"), calls)
    }

    /** Off for this device is this device's answer alone: the other devices keep theirs. */
    @Test
    fun switchingThisDeviceOffLeavesTheRealmAnswerAlone() {
        setScreen(search, CapabilityConsent(search, realm = true, device = true), listOf(laptop))

        composeTestRule.onNodeWithText("This device").performClick()

        assertEquals(listOf("device:${search.id}:false"), calls)
    }

    /**
     * ⚠⚠ §38.2 — another device can be switched off from here and never on: its off switch is
     * disabled, and its row says where to turn it on.
     */
    @Test
    fun anotherDeviceCanBeSwitchedOffButNeverOn() {
        setScreen(search, CapabilityConsent(search, realm = true, device = true), listOf(laptop, tablet))

        composeTestRule.onNodeWithText("Tablet").performClick()
        assertEquals(emptyList(), calls)
        composeTestRule.onNodeWithText("Off. Turn it on from that device.").assertIsDisplayed()

        composeTestRule.onNodeWithText("Laptop").performClick()
        assertEquals(listOf("other:${search.id}:dev-laptop"), calls)
    }

    /**
     * ⚠⚠ Faces delete on an explicit no, so every way of switching them off asks first — a single
     * tap on the wrong switch must not delete a device's face data.
     */
    @Test
    fun switchingFacesOffAsksFirstAndSaysWhatIsDeleted() {
        setScreen(faces, CapabilityConsent(faces, realm = true, device = true), listOf(laptop))

        composeTestRule.onNodeWithText("This device").performClick()
        composeTestRule.onNodeWithText(turnOffTitle(faces, "this device")).assertIsDisplayed()
        composeTestRule.onNodeWithText(turnOffText(faces, everywhere = false)).assertIsDisplayed()
        composeTestRule.onNodeWithText("Keep on").performClick()
        assertEquals(emptyList(), calls)

        composeTestRule.onNodeWithText("Laptop").performClick()
        composeTestRule.onNodeWithText(turnOffTitle(faces, "Laptop")).assertIsDisplayed()
        composeTestRule.onNodeWithText("Turn off").performClick()
        assertEquals(listOf("other:${faces.id}:dev-laptop"), calls)
    }

    /** *Turn off everywhere* always asks, whatever the feature — it reaches devices not in the hand. */
    @Test
    fun turnOffEverywhereAlwaysAsks() {
        setScreen(search, CapabilityConsent(search, realm = true, device = true), listOf(laptop))

        composeTestRule.onNodeWithText(turnOffEverywhereLabel(search)).performClick()
        assertEquals(emptyList(), calls)
        composeTestRule.onNodeWithText(turnOffText(search, everywhere = true)).assertIsDisplayed()
        composeTestRule.onNodeWithText("Turn off").performClick()

        assertEquals(listOf("everywhere:${search.id}"), calls)
    }

    /** ⚠⚠ Not before the device list has loaded — it would reach none of them. */
    @Test
    fun turnOffEverywhereWaitsForTheDeviceList() {
        setScreen(search, CapabilityConsent(search, realm = true, device = true), othersKnown = false)

        composeTestRule.onNodeWithText(turnOffEverywhereLabel(search)).assertIsNotEnabled().performClick()

        assertEquals(0, composeTestRule.onAllNodes(hasText("Turn off")).fetchSemanticsNodes().size)
        assertEquals(emptyList(), calls)
    }

    /** ⚠ Nothing on anywhere: no button for a withdrawal with nothing to withdraw. */
    @Test
    fun aFeatureThatIsOffEverywhereOffersNoTurnOffEverywhere() {
        setScreen(search, others = listOf(tablet))

        assertEquals(0, composeTestRule.onAllNodes(hasText(turnOffEverywhereLabel(search))).fetchSemanticsNodes().size)
    }

    /**
     * ⚠⚠ A yes still stored under a realm no shows as ON, on this device and on another, so it can
     * be seen and switched off — hidden, it resumes when the feature is turned back on.
     */
    @Test
    fun aLingeringYesShowsAsOn() {
        setScreen(search, CapabilityConsent(search, realm = false, device = true), listOf(laptop, tablet))

        switchOf("This device").assertIsOn()
        switchOf("Laptop").assertIsOn()
        switchOf("Tablet").assertIsOff().assertIsNotEnabled()
        composeTestRule.onNodeWithText(thisDeviceSupportingText(CapabilityConsent(search, device = true))).assertIsDisplayed()
    }

    /** The list is the stored answers: a row moves when its write lands, with no refresh. */
    @Test
    fun theListFollowsTheState() {
        var others by mutableStateOf(listOf(laptop))
        composeTestRule.setContent {
            ModelFeatureScreenContent(
                state = ModelsUiState(loading = false, otherDevices = mapOf(search to others)),
                capability = search,
                onBack = {},
            )
        }
        switchOf("Laptop").assertIsOn()

        others = listOf(laptop.copy(allowed = false))
        composeTestRule.waitForIdle()

        switchOf("Laptop").assertIsOff()
    }
}
