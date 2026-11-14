package app.tenshu.photos.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import app.tenshu.reachability.UnreachableReason
import app.tenshu.reachability.evaluateSuiteSyncProblem
import app.tenshu.ui.theme.TenshuTheme
import org.tenshu.storage.common.RealmStorageState
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The tab strip follows the Control verdict **inside a cached nav entry**.
 *
 * ⚠⚠ Android's `NavDisplay` builds each entry's content lambda once per back-stack key and keeps it
 * (`KmpNavHost.kt`). The desktop actual rebuilds it on every recomposition, so a desktop test that
 * goes through `KmpNavHost` cannot see a by-value capture. This one freezes the lambda with
 * `remember`, which is what `NavDisplay` does to it.
 *
 * The device symptom it pins: a cold start composed the shell in the ~0.3 s before the Binder
 * connected — verdict `Transport` — and the feed then kept *"Can't reach Tenshu Control"* on screen
 * for good while its photos loaded underneath, because the entry had captured that first value.
 */
@OptIn(ExperimentalTestApi::class)
class TabStripFollowsTheVerdictTest {

    @Test
    fun aRecoveredVerdictClearsTheStripOfAnEntryBuiltDuringTheOutage() = runDesktopComposeUiTest(width = 420, height = 600) {
        val verdict = mutableStateOf<UnreachableReason?>(UnreachableReason.Transport)
        setContent {
            TenshuTheme {
                // App's own derivation, in the shell's scope — held as a State, as App holds it.
                val problem = rememberUpdatedState(
                    evaluateSuiteSyncProblem(verdict.value, connectivity = null, RealmStorageState.Ok),
                )
                // NavDisplay's cache: the entry's content is built once, while the verdict is Transport.
                val entry: @Composable () -> Unit = remember {
                    { TabScaffold(syncProblem = problem) { Text(BODY) } }
                }
                entry()
            }
        }
        waitForIdle()
        assertEquals(1, strips(), "the outage is shown")

        verdict.value = null // the probe answered: Control is reachable
        waitForIdle()
        assertEquals(0, strips(), "the strip leaves with the outage — it is not latched by the entry")
    }

    private fun androidx.compose.ui.test.ComposeUiTest.strips(): Int =
        onAllNodes(androidx.compose.ui.test.hasText(STRIP, substring = true)).fetchSemanticsNodes().size

    private companion object {
        const val BODY = "feed body"
        const val STRIP = "Can't reach Tenshu Control"
    }
}
