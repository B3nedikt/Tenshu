package org.tenshu.viewmodel.test

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.assertFalse

/**
 * Owns the view models, scopes and stores a test builds, cancelling and joining each one when the
 * test ends.
 *
 * Register every construction — [own] for a [ViewModel], [ownScope] for a hand-built
 * [CoroutineScope], [ownStore] for the [ViewModelStore] a composition resolves into — then let
 * [vmTest], [vmBlocking] or `closeAndResetMain` do the teardown. Nothing cancels a `viewModelScope`
 * on its own, so an unregistered view model's coroutines outlive the test and fail a later one.
 *
 * ⚠ [closeAll] uses [cancelAndJoin], never a bare `cancel`. Cancellation is cooperative, so a plain
 * cancel returns while a collector is still unwinding and loses the race this fixture exists to
 * close. The **join** is the fix, and it is pinned by `aJoinWaitsForTheCollectorToFinishUnwinding`
 * rather than by [assertNothingActive], which cannot see it.
 *
 * ⚠⚠ [assertNothingActive] is narrower than it looks: it catches a close that did not happen, but
 * neither a missing join nor a construction that skipped [own]. Read
 * `docs/testing.md` §11.2 before relying on it — the three forced pairs are tabulated there.
 *
 * @see vmTest the default entry point.
 */
class OwnedViewModels {

    private sealed interface Owned {
        suspend fun close()
        val active: Boolean
        val inFlight: Int
    }

    private class OwnedViewModel(val vm: ViewModel) : Owned {
        override suspend fun close() = vm.viewModelScope.coroutineContext.job.cancelAndJoin()
        override val active get() = vm.viewModelScope.isActive
        override val inFlight get() = vm.viewModelScope.coroutineContext.job.children.count { it.isActive }
    }

    private class OwnedScope(val scope: CoroutineScope) : Owned {
        override suspend fun close() = scope.coroutineContext.job.cancelAndJoin()
        override val active get() = scope.isActive
        override val inFlight get() = scope.coroutineContext.job.children.count { it.isActive }
    }

    /**
     * A store a composition resolved its `koinViewModel()`s into. Closing enumerates it, joins
     * every view model's scope, then clears it — which runs `onCleared()` for each, as production
     * does.
     *
     * Enumerating is why this is the one case that covers a view model never passed to [own]:
     * nothing in a test otherwise calls `clear()` on a hand-made store, so its view models were
     * never even cancelled.
     */
    private class OwnedStore(val store: ViewModelStore) : Owned {
        private var seen: List<ViewModel> = emptyList()
        private fun current(): List<ViewModel> = store.keys().mapNotNull { store[it] }
        override suspend fun close() {
            seen = current()
            seen.asReversed().forEach { it.viewModelScope.coroutineContext.job.cancelAndJoin() }
            store.clear()
        }
        override val active get() = (current() + seen).any { it.viewModelScope.isActive }
        override val inFlight
            get() = (current() + seen).sumOf { it.viewModelScope.coroutineContext.job.children.count { c -> c.isActive } }
    }

    private val owned = mutableListOf<Owned>()

    /**
     * Registers [vm] as this test's to close.
     *
     * @return [vm] unchanged, so the call wraps the construction.
     */
    fun <T : ViewModel> own(vm: T): T = vm.also { owned += OwnedViewModel(it) }

    /**
     * Registers a hand-built [scope] — a controller's, a presenter's — as this test's to close.
     *
     * @return [scope] unchanged.
     */
    fun <T : CoroutineScope> ownScope(scope: T): T = scope.also { owned += OwnedScope(it) }

    /**
     * Registers the [store] a `LocalViewModelStoreOwner` in the composition resolves into.
     *
     * @return [store] unchanged.
     */
    fun ownStore(store: ViewModelStore): ViewModelStore = store.also { owned += OwnedStore(it) }

    /**
     * Cancels and joins everything registered, in reverse order of registration.
     *
     * Reverse, because a later view model may observe an earlier one's repository, and unwinding a
     * producer before its consumer is how a teardown grows races of its own.
     */
    suspend fun closeAll() {
        owned.asReversed().forEach { it.close() }
    }

    /** Whether anything registered is still live. */
    val anyActive: Boolean get() = owned.any { it.active }

    /**
     * How many registered scopes still have a live child coroutine.
     *
     * Read it *before* [closeAll] to learn whether work was in flight; afterwards it is always 0.
     */
    val inFlight: Int get() = owned.count { it.inFlight > 0 }

    /**
     * Fails if anything registered survived [closeAll].
     *
     * ⚠⚠ See the class KDoc: this catches a close that did not happen, and nothing else.
     *
     * @throws AssertionError naming what to wrap, since the failure is otherwise unattributable.
     */
    fun assertNothingActive() = assertFalse(
        anyActive,
        "a view model or scope outlived its test — build it with `own(...)` / `ownScope(...)` " +
            "(or hand the composition's store to `ownStore(...)`) so it is cancelled and JOINED before " +
            "resetMain(); see docs/testing.md §11 for what leaks otherwise",
    )
}

/**
 * Runs [body] as a `runTest`, closing every view model and scope it registered and then asserting
 * none survived.
 *
 * The default entry point; see `docs/testing.md` §11 for the others and when each applies.
 *
 * @param context passed straight to `runTest`, exactly as `runTest(context)`. Pass the same
 *   dispatcher the class hands to `Dispatchers.setMain`, so a `viewModelScope` runs on the test's
 *   scheduler and `advanceUntilIdle()` drains it — the `docs/testing.md` §10 shape. Omitting it
 *   where the class used `runTest(dispatcher)` silently moves the test to a different scheduler.
 * @param body the test, receiving the [OwnedViewModels] every construction must be wrapped in.
 */
fun vmTest(
    context: CoroutineContext = EmptyCoroutineContext,
    body: suspend TestScope.(OwnedViewModels) -> Unit,
) = runTest(context) {
    val owned = OwnedViewModels()
    try {
        body(owned)
    } finally {
        owned.closeAll()
    }
    owned.assertNothingActive()
}
