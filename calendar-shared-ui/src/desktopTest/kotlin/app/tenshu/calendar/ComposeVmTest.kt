package app.tenshu.calendar

import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import org.tenshu.viewmodel.test.OwnedViewModels
import org.tenshu.viewmodel.test.withOwnedViewModels

/**
 * [composeVmTest] for a body that needs a real window size — the measurement tests, which fail by
 * drawing the wrong thing rather than by throwing.
 *
 * @param body the test, receiving the [OwnedViewModels] every construction must be wrapped in.
 */
@OptIn(ExperimentalTestApi::class)
internal fun desktopComposeVmTest(
    width: Int,
    height: Int,
    body: DesktopComposeUiTest.(OwnedViewModels) -> Unit,
) = withOwnedViewModels { owned ->
    runDesktopComposeUiTest(width = width, height = height) { body(owned) }
}
