package app.tenshu.mia.ui.screens.chat

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.runComposeUiTest
import app.tenshu.mia.model.MessageBody
import app.tenshu.mia.model.MiaMessage
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.input.key.Key
import app.tenshu.mia.data.MockConversationRepository
import app.tenshu.ui.theme.TenshuTheme
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import app.tenshu.mia.composeVmTest

/**
 * Compose-render coverage for the chat surface: the greeting empty state, a full
 * send→reply round-trip, and the rich-result PhotoGrid cell rendering its tiles.
 */
@OptIn(ExperimentalTestApi::class)
class ChatScreenTest {

    // ChatScreen resolves the optional MiaCapabilities via Koin (D4 mic gate, M5);
    // an empty container = the flag's absent/default-false posture.
    @BeforeTest
    fun setUpKoin() {
        startKoin { }
    }

    @AfterTest
    fun tearDownKoin() = stopKoin()

    @Test
    fun greetingShowsThenSendRoundTripsToAPhotoGrid() = composeVmTest { vms ->
        val vm = vms.own(ChatViewModel(MockConversationRepository(replyDelay = 1.milliseconds)))
        setContent {
            TenshuTheme {
                ChatScreen(viewModel = vm)
            }
        }

        waitUntil {
            onAllNodesWithText("Hi, I", substring = true, useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }

        onNode(hasSetTextAction()).performTextInput("Show me a photo from the lake")
        onNodeWithContentDescription("Send", useUnmergedTree = true).performClick()

        // The user bubble appears immediately; the mock's PhotoGrid reply follows.
        waitUntil(timeoutMillis = 5_000) {
            onAllNodesWithText("I found these in your Drive:", useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithText("Show me a photo from the lake", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithText("Beach sunset", useUnmergedTree = true).assertIsDisplayed()
    }

    /** ⭐ Enter sends, as the Send button does: the composer is a desktop text field too. */
    @Test
    fun enterInTheComposerSends() = composeVmTest { vms ->
        val vm = vms.own(ChatViewModel(MockConversationRepository(replyDelay = 1.milliseconds)))
        setContent {
            TenshuTheme {
                ChatScreen(viewModel = vm)
            }
        }

        val composer = onNode(hasSetTextAction())
        composer.performTextInput("Show me a photo from the lake")
        composer.performKeyInput { pressKey(Key.Enter) }

        waitUntil(timeoutMillis = 5_000) {
            onAllNodesWithText("I found these in your Drive:", useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithText("Show me a photo from the lake", useUnmergedTree = true).assertIsDisplayed()
    }

    /** ⚠ Enter on an empty composer sends nothing, as the disabled Send button does not. */
    @Test
    fun enterOnAnEmptyComposerSendsNothing() = composeVmTest { vms ->
        val vm = vms.own(ChatViewModel(MockConversationRepository(replyDelay = 1.milliseconds)))
        setContent {
            TenshuTheme {
                ChatScreen(viewModel = vm)
            }
        }

        onNode(hasSetTextAction()).performKeyInput { pressKey(Key.Enter) }
        waitForIdle()

        kotlin.test.assertTrue(vm.uiState.value.messages.isEmpty(), "an empty Enter sent a message")
    }

    /**
     * ⚠ The alternative sits above Confirm (§25.13): the card asks about the time first, and the
     * action that commits is the last thing on it.
     */
    @Test
    fun theAlternativeSitsAboveConfirm() = runComposeUiTest {
        setContent {
            TenshuTheme {
                MessageRow(
                    MiaMessage(
                        id = "1",
                        at = kotlin.time.Instant.fromEpochMilliseconds(0),
                        fromUser = false,
                        body = MessageBody.Confirmation(
                            title = "dinner with Anna",
                            detail = "2026-11-20 08:00 – 09:00 · Personal",
                            token = "t",
                            alternatives = listOf(MessageBody.Choice("20:00 instead?", "at 20:00")),
                        ),
                    ),
                )
            }
        }
        val alternative = onNodeWithText("20:00 instead?", useUnmergedTree = true).getBoundsInRoot()
        val confirm = onNodeWithText("Confirm", useUnmergedTree = true).getBoundsInRoot()
        kotlin.test.assertTrue(alternative.bottom <= confirm.top, "the alternative is not above Confirm")
    }

    /**
     * ⚠⚠ Three spent states, three labels (§25.13): "Done" when the action ran, "Not done" when the
     * proxy said nothing was written, and "Unknown" when no answer came back, because it may have
     * run. None of them can be tapped again, and none still offers its alternative.
     */
    @Test
    fun eachSpentCardSaysWhatHappened() = runComposeUiTest {
        val card = MessageBody.Confirmation(
            title = "dinner with Anna",
            detail = "2026-11-20 08:00 – 09:00 · Personal",
            token = "t",
            alternatives = listOf(MessageBody.Choice("20:00 instead?", "at 20:00")),
        )
        val shown = androidx.compose.runtime.mutableStateOf(card)
        setContent {
            TenshuTheme {
                MessageRow(MiaMessage(id = "1", at = kotlin.time.Instant.fromEpochMilliseconds(0), fromUser = false, body = shown.value))
            }
        }
        listOf(
            card.copy(done = true) to "Done",
            card.copy(refused = true) to "Not done",
            card.copy(outcomeUnknown = true) to "Unknown",
        ).forEach { (spent, label) ->
            shown.value = spent
            waitForIdle()
            onNodeWithText(label, useUnmergedTree = true).assertIsDisplayed()
            onAllNodesWithText("Confirm", useUnmergedTree = true).assertCountEquals(0)
            onAllNodesWithText("20:00 instead?", useUnmergedTree = true).assertCountEquals(0)
        }
    }
}
