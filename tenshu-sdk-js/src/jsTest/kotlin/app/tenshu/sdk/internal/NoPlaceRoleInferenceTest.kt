@file:OptIn(DelicateCoroutinesApi::class)

package app.tenshu.sdk.internal

import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.promise
import org.tenshu.timeline.proxy.MomentReconciliationPass
import org.tenshu.timeline.proxy.PlaceRoleInferencePass
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ⚠ A browser tab syncs place roles but never computes them (maintainer, 2026-10-23; see
 * `initTimelineService`'s `inferPlaceRoles`). Since F7 registered the place schema, an installed
 * `PlaceRoleInferencePass` would write `roleSource = inferred` rows from the browser.
 */
class NoPlaceRoleInferenceTest {

    @Test
    fun theBrowserRuntimeInstallsNoPlaceRoleInference() = GlobalScope.promise {
        JsRuntime.awaitReady()
        val observers = JsRuntime.executionContext.observerRegistry
        assertFalse(observers.any { it is PlaceRoleInferencePass }, "a tab must not infer place roles")
        // Control: the timeline plugin's other observer IS installed, so the registry this reads is
        // the live one and the absence above is a decision, not an empty registry.
        assertTrue(observers.any { it is MomentReconciliationPass }, "timeline's moment reconciliation is installed")
    }
}
