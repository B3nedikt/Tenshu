package org.tenshu.viewmodel.test

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The contract of [OwnedViewModels] itself.
 *
 * Every consumer inherits whatever this fixture does, and its KDoc spent three hand-copied
 * lifetimes claiming things that were not true (`docs/testing.md` §11.2). The claims are pinned
 * here rather than re-measured by hand the next time someone doubts them.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OwnedViewModelsGuardTest {

    private class Vm : ViewModel()

    @BeforeTest
    fun setUpMain() = Dispatchers.setMain(Dispatchers.Unconfined)

    @AfterTest
    fun tearDownMain() = Dispatchers.resetMain()

    @Test
    fun closeAllCancelsAnOwnedViewModel() = runBlocking {
        val owned = OwnedViewModels()
        val vm = owned.own(Vm())
        vm.viewModelScope.launch { awaitCancellation() }

        assertTrue(vm.viewModelScope.isActive, "precondition: the scope starts live")
        owned.closeAll()

        assertFalse(vm.viewModelScope.isActive)
        owned.assertNothingActive()
    }

    @Test
    fun aJoinWaitsForTheCollectorToFinishUnwinding() = runBlocking {
        // ⚠⚠ The forced pair for `cancelAndJoin`, and the only one it has. The body parks and its
        // `finally` takes real time to unwind — what a collector doing cleanup on the way out looks
        // like. `cancelAndJoin` cannot return until that finishes; a bare `cancel` returns while
        // the `finally` is still suspended at the delay, which is the window a leaked coroutine
        // uses to touch a `Main` that `resetMain()` has already swapped out.
        var unwound = false
        val owned = OwnedViewModels()
        val vm = owned.own(Vm())
        vm.viewModelScope.launch {
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) {
                    delay(50)
                    unwound = true
                }
            }
        }

        owned.closeAll()

        assertTrue(unwound, "closeAll() returned while the coroutine was still unwinding — that is a bare cancel, not a cancelAndJoin")
    }

    @Test
    fun assertNothingActiveFailsWhenACloseDidNotHappen() = runBlocking {
        // The one thing the guard genuinely catches: a teardown that stopped being called, or a
        // closeAll() that threw partway and left the rest of the list alive.
        val owned = OwnedViewModels()
        owned.own(Vm()).viewModelScope.launch { awaitCancellation() }

        val failure = assertFailsWith<AssertionError> { owned.assertNothingActive() }
        assertTrue(
            failure.message.orEmpty().contains("outlived its test"),
            "the guard must name what to do about it, not just fail: ${failure.message}",
        )

        owned.closeAll()
    }

    @Test
    fun ownScopeCancelsAndJoinsAHandBuiltScope() = runBlocking {
        val owned = OwnedViewModels()
        val scope = owned.ownScope(CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        scope.launch { awaitCancellation() }

        assertEquals(1, owned.inFlight, "precondition: the scope has live work")
        owned.closeAll()

        assertFalse(scope.isActive)
        owned.assertNothingActive()
    }

    @Test
    fun ownStoreJoinsEveryViewModelTheCompositionResolvedIntoIt() = runBlocking {
        // ⚠ The one case where a view model never passed to `own(...)` is still covered — the store
        // is enumerated at close time. That is why a composition hands over its store.
        val owned = OwnedViewModels()
        val store = owned.ownStore(ViewModelStore())
        val vm = Vm()
        store.put("resolved-by-the-composition", vm)
        vm.viewModelScope.launch { awaitCancellation() }

        owned.closeAll()

        assertFalse(vm.viewModelScope.isActive)
        owned.assertNothingActive()
    }

    @Test
    fun scopesAreClosedInReverseOrderOfOwnership() = runBlocking {
        // Consumers unwind before the producers they read, so the teardown grows no races of its own.
        val closed = mutableListOf<String>()
        val owned = OwnedViewModels()
        listOf("producer", "consumer").forEach { name ->
            val job = Job()
            job.invokeOnCompletion { closed += name }
            owned.ownScope(CoroutineScope(job + Dispatchers.Unconfined))
        }

        owned.closeAll()

        assertEquals(listOf("consumer", "producer"), closed)
    }

    @Test
    fun vmTestClosesWhatTheBodyBuilt() {
        var built: Vm? = null
        vmTest { owned ->
            val vm = owned.own(Vm())
            built = vm
            vm.viewModelScope.launch { awaitCancellation() }
        }
        assertFalse(built!!.viewModelScope.isActive, "vmTest must close the body's view models")
    }

    @Test
    fun vmTestReportsALeakRatherThanPassing() {
        // The wrapper's assertion has to run after its `finally`, or a scope that survived
        // `closeAll()` would be reported as a pass.
        val failure = assertFailsWith<AssertionError> {
            vmTest { owned -> owned.ownScope(UnclosedScope()) }
        }
        assertTrue(failure.message.orEmpty().contains("outlived its test"), "got: ${failure.message}")
    }

    /**
     * A scope that reports itself live no matter what was cancelled: every read of
     * [coroutineContext] hands back a fresh job, so `closeAll()`'s `cancelAndJoin` cancels one
     * object and `anyActive` then asks a different, un-cancelled one. Models "close returned and
     * the scope is still running" without needing to reach into the fixture.
     */
    private class UnclosedScope : CoroutineScope {
        override val coroutineContext: CoroutineContext get() = SupervisorJob() + Dispatchers.Unconfined
    }
}
