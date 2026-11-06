package app.tenshu.timeline

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import org.tenshu.viewmodel.test.OwnedViewModels
import org.tenshu.viewmodel.test.withOwnedViewModels

/**
 * `vmTest` for a `runComposeUiTest` body: the scene closes first, then every owned view model is
 * cancelled and joined.
 *
 * ⚠ Per-module rather than in `:viewmodel-test-utils`, because `runComposeUiTest` would put Compose
 * on `:maps-shared-logic`, which uses that module precisely because it is Compose-free
 * (`docs/testing.md` §11.3). Only the builder is per-module — the own-join-assert logic is
 * [withOwnedViewModels], so this cannot drift from its twins.
 *
 * @param body the test, receiving the [OwnedViewModels] every construction must be wrapped in.
 */
@OptIn(ExperimentalTestApi::class)
internal fun composeVmTest(body: ComposeUiTest.(OwnedViewModels) -> Unit) =
    withOwnedViewModels { owned -> runComposeUiTest { body(owned) } }

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
