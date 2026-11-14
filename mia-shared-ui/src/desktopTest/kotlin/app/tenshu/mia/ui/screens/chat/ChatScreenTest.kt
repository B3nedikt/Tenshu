package app.tenshu.mia.ui.screens.chat

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
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
}
