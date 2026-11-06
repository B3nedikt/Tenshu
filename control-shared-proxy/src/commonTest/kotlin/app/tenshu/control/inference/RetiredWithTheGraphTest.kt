package app.tenshu.control.inference

import app.tenshu.control.ui.screens.models.OnDeviceModelsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.isActive
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The teardown's hold on a graph's Models repository is latched (`ios-on-device-inference.md` §14.18.15). */
class RetiredWithTheGraphTest {

    private fun repository(scope: CoroutineScope) = OnDeviceModelsRepository.notConnected(scope)

    @Test
    fun aRepositoryBuiltBeforeTheTeardownIsRetiredByIt() = runTest {
        val scope = CoroutineScope(SupervisorJob())
        val teardown = RetiredWithTheGraph()
        teardown.built(repository(scope))
        assertTrue(scope.isActive)
        teardown.retire()
        assertFalse(scope.isActive, "the repository's scope goes with the graph")
    }

    /** ⚠ The case the latch is for: the screen first opens during, or after, the teardown. */
    @Test
    fun aRepositoryFirstBuiltAfterTheTeardownIsRetiredAsItIsHandedOut() = runTest {
        val scope = CoroutineScope(SupervisorJob())
        val teardown = RetiredWithTheGraph()
        teardown.retire()
        val late = teardown.built(repository(scope))
        assertFalse(scope.isActive, "a repository of a retired graph was left running")
        assertEquals(listOf(ModelsAvailability.NotConnected), late.models().toList().map { it.availability })
    }

    @Test
    fun aGraphThatIsNeverTornDownRetiresNothing() = runTest {
        val scope = CoroutineScope(SupervisorJob())
        RetiredWithTheGraph().built(repository(scope))
        assertTrue(scope.isActive)
    }
}
