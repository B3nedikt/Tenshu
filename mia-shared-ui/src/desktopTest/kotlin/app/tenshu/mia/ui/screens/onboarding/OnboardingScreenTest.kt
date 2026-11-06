package app.tenshu.mia.ui.screens.onboarding

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.tenshu.mia.data.InMemoryOnboardingStore
import app.tenshu.ui.theme.TenshuTheme
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import app.tenshu.mia.composeVmTest

/**
 * Compose-render coverage for the M5 intro flow: the three steps walk forward, and
 * "Get started" persists the flag. Koin is started empty so the Assistant step's
 * launcher lookup resolves to absent (the desktop posture).
 */
@OptIn(ExperimentalTestApi::class)
class OnboardingScreenTest {

    @Test
    fun walkThroughAndCompletePersistsTheFlag() = composeVmTest { vms ->
        stopKoin()
        startKoin { }
        val store = InMemoryOnboardingStore()
        val vm = vms.own(OnboardingViewModel(store))
        setContent {
            TenshuTheme {
                OnboardingScreen(viewModel = vm)
            }
        }

        waitUntil {
            onAllNodesWithText("Meet Mia", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithText("Next", useUnmergedTree = true).performClick()
        waitUntil {
            onAllNodesWithText("Make Mia your assistant", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithText("Next", useUnmergedTree = true).performClick()
        waitUntil {
            onAllNodesWithText("All set", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithText("Get started", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithText("Get started", useUnmergedTree = true).performClick()

        waitUntil { runBlocking { store.onboarded().first() } }
        assertTrue(runBlocking { store.onboarded().first() })
        stopKoin()
    }
}
