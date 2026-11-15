package app.tenshu.contacts

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import org.tenshu.viewmodel.test.OwnedViewModels
import org.tenshu.viewmodel.test.withOwnedViewModels
import org.tenshu.ui.test.NoMotion
import kotlin.coroutines.CoroutineContext

/**
 * `vmTest` for a `runComposeUiTest` body: the scene closes first, then every owned view model is
 * cancelled and joined.
 *
 * ⚠ Per-module rather than in `:viewmodel-test-utils`, because `runComposeUiTest` would put Compose
 * on `:maps-shared-logic`, which uses that module precisely because it is Compose-free
 * (`docs/testing.md` §11.3). Only the builder is per-module — the own-join-assert logic is
 * [withOwnedViewModels], so this cannot drift from its twins.
 *
 * @param effectContext [NoMotion] unless animation timing is the test's subject (`docs/testing.md` §13).
 * @param body the test, receiving the [OwnedViewModels] every construction must be wrapped in.
 */
@OptIn(ExperimentalTestApi::class)
internal fun composeVmTest(
    effectContext: CoroutineContext = NoMotion,
    body: ComposeUiTest.(OwnedViewModels) -> Unit,
) =
    withOwnedViewModels { owned -> runComposeUiTest(effectContext = effectContext) { body(owned) } }
