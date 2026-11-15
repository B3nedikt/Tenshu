package app.tenshu.calendar

import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import org.tenshu.viewmodel.test.OwnedViewModels
import org.tenshu.viewmodel.test.withOwnedViewModels
import org.tenshu.ui.test.NoMotion
import kotlin.coroutines.CoroutineContext

/**
 * [composeVmTest] for a body that needs a real window size — the measurement tests, which fail by
 * drawing the wrong thing rather than by throwing.
 *
 * @param effectContext [NoMotion] unless animation timing is the test's subject (`docs/testing.md` §13).
 * @param body the test, receiving the [OwnedViewModels] every construction must be wrapped in.
 */
@OptIn(ExperimentalTestApi::class)
internal fun desktopComposeVmTest(
    width: Int,
    height: Int,
    effectContext: CoroutineContext = NoMotion,
    body: DesktopComposeUiTest.(OwnedViewModels) -> Unit,
) = withOwnedViewModels { owned ->
    runDesktopComposeUiTest(width = width, height = height, effectContext = effectContext) { body(owned) }
}
