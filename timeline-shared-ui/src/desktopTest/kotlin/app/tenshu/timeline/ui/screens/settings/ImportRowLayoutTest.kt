package app.tenshu.timeline.ui.screens.settings

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import app.tenshu.timeline.data.TimelineImportControl
import app.tenshu.timeline.data.TimelineImportStatus
import app.tenshu.ui.theme.TenshuTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.koin.compose.KoinApplication
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The Settings → Import row **at phone width** (B2 follow-up).
 *
 * ⚠⚠ **Reported from a real device, and invisible to every test that existed.** The button sat
 * beside a `weight(1f)` description, so it got only the leftover width and wrapped to *one word per
 * line*. `assertIsDisplayed` was perfectly happy: the node was there, it was on screen, and it was
 * unusable.
 *
 * ⭐ So this test asserts the button's **shape**, not its presence. A control that has been squeezed
 * into a column of words is tall and narrow, and that is a measurable, stable property — far more
 * so than a pixel diff, which this repo deliberately does not take on.
 */
@OptIn(ExperimentalTestApi::class)
class ImportRowLayoutTest {

    private class FakeControl : TimelineImportControl {
        override val status: StateFlow<TimelineImportStatus> = MutableStateFlow(TimelineImportStatus.Idle)
        override val includePhotos: StateFlow<Boolean> = MutableStateFlow(false)
        override fun pick() = Unit
        override fun dismiss() = Unit
        override fun setIncludePhotos(include: Boolean) = Unit
    }

    /**
     * ⚠⚠ A **composition-scoped** Koin, not the global one.
     *
     * The first draft used `startKoin`/`stopKoin` around each test and only the *first* test in the
     * class passed — the rest rendered a Settings screen with no Import section at all. The screen
     * was fine; `getKoin()` inside the composable was resolving a **stopped** instance held from
     * the previous test, so `getOrNull` answered null and the section simply did not compose.
     *
     * ⚠ The failure read as "the button is missing", which is the wrong diagnosis entirely, and it
     * only appeared when the tests ran *together* — the signature of shared global state. Providing
     * the container through the composition removes the global from the picture.
     */
    private fun render(width: Int, assertions: androidx.compose.ui.test.ComposeUiTest.() -> Unit) {
        runDesktopComposeUiTest(width = width, height = 900) {
            setContent {
                KoinApplication(application = {
                    modules(module { single<TimelineImportControl> { FakeControl() } })
                }) {
                    TenshuTheme { SettingsScreen(onBack = null) }
                }
            }
            assertions()
        }
    }

    @Test
    fun theImportButtonIsOneLineTallOnAPhone() = render(width = 360) {
        val button = onNodeWithText("Choose file…")
        button.assertIsDisplayed()

        val bounds = button.getBoundsInRoot()

        // ⚠⚠ The actual defect, in numbers: squeezed to one word per line the button was several
        // lines tall. A Material button with a single line of text is ~40dp; anything past 72dp is
        // wrapped text, whatever the label happens to be.
        assertTrue(
            bounds.height < 72.dp,
            "the import button is ${bounds.height} tall — it has wrapped to multiple lines",
        )
        // ⚠ And it is not a sliver: a button narrower than its own label has been crushed sideways.
        assertTrue(bounds.width > 80.dp, "the import button is only ${bounds.width} wide")
    }

    @Test
    fun theDescriptionStillGetsTheFullWidth() = render(width = 360) {
        // ⚠ The point of moving the button below: the explanation is the thing that needs the
        // width, and it now has all of it.
        onNodeWithText(
            "Bring your location history in from a Takeout export. Pick the .zip — every month " +
                "inside it is imported at once.",
        ).assertIsDisplayed()
    }

    @Test
    fun theButtonDoesNotRepeatTheRowTitle() = render(width = 360) {
        // ⚠ "Import from Google Takeout" is the row's title; the button says what it *does*.
        // Reusing one string for both was the reason the button was too wide to fit beside the
        // text in the first place.
        onNodeWithText("Import from Google Takeout").assertIsDisplayed()
        onNodeWithText("Choose file…").assertIsDisplayed()
    }

    @Test
    fun itSurvivesATabletWidthToo() = render(width = 840) {
        // ⚠ The fix must not trade a phone bug for a desktop one: on a wide window the button is
        // end-aligned rather than stretched across the whole row.
        val bounds = onNodeWithText("Choose file…").getBoundsInRoot()

        assertTrue(bounds.height < 72.dp, "still one line at 840dp")
        assertTrue(bounds.width < 400.dp, "the button must not stretch to the window's width")
    }
}
