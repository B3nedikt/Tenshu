package app.tenshu.drive

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import org.tenshu.viewmodel.test.OwnedViewModels
import org.tenshu.viewmodel.test.withOwnedViewModels

/**
 * `vmTest` for a `runComposeUiTest` body: the scene closes first, then every owned view model is
 * cancelled and joined.
 *
 * ⚠ Here rather than in `:viewmodel-test-utils` with the rest of the fixture, because
 * `runComposeUiTest` would put Compose on `:maps-shared-logic`, which uses that module precisely
 * because it is Compose-free (`docs/testing.md` §11.3). Only the builder is per-module — the
 * own-join-assert logic is `withOwnedViewModels`.
 *
 * @param body the test, receiving the [OwnedViewModels] every construction must be wrapped in.
 */
@OptIn(ExperimentalTestApi::class)
internal fun composeVmTest(body: ComposeUiTest.(OwnedViewModels) -> Unit) =
    withOwnedViewModels { owned -> runComposeUiTest { body(owned) } }
