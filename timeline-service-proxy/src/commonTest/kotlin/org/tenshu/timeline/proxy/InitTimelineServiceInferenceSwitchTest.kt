package org.tenshu.timeline.proxy

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createCoreTestRegistry
import org.tenshu.place.common.placeServiceCommonModule
import org.tenshu.sync.common.SemanticMergerRegistry
import org.tenshu.timeline.common.timelineServiceCommonModule
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `initTimelineService(inferPlaceRoles = …)`: native devices keep inferring Home/Work; the browser
 * runtime passes `false` and only syncs roles (maintainer, 2026-10-23, web-app-suite.md F7).
 */
class InitTimelineServiceInferenceSwitchTest {

    private fun proxy(scope: CoroutineScope): TestProxy {
        val registry = createCoreTestRegistry().apply {
            registerConfig(timelineServiceCommonModule)
            registerConfig(placeServiceCommonModule)
        }
        return TestProxy(
            schemaRegistry = registry,
            coroutineScope = scope,
            implementationFactoryOverride = { id, _ -> timelineServiceProxyImplementationFactory(id) },
        )
    }

    @Test
    fun theDefaultInstallsPlaceRoleInference() = runTest {
        val context = proxy(this).executionContext
        context.initTimelineService(SemanticMergerRegistry())
        assertTrue(context.observerRegistry.any { it is PlaceRoleInferencePass })
    }

    @Test
    fun switchedOffItInstallsNoInferenceButKeepsMomentReconciliation() = runTest {
        val context = proxy(this).executionContext
        context.initTimelineService(SemanticMergerRegistry(), inferPlaceRoles = false)
        assertFalse(context.observerRegistry.any { it is PlaceRoleInferencePass })
        assertTrue(context.observerRegistry.any { it is MomentReconciliationPass })
    }
}
