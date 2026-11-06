package org.tenshu.viewmodel.test

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain

/**
 * [vmTest] for an integration test that drives a real proxy under `runBlocking` — no virtual clock,
 * no `setMain`.
 *
 * ⚠ The join happens before [body] returns, and so before the test shuts its proxy down. The other
 * order lets the shutdown's last emission dispatch onto `Main` from a worker thread after the test
 * is gone.
 *
 * @param body the test, receiving the [OwnedViewModels] every construction must be wrapped in.
 */
fun vmBlocking(body: suspend CoroutineScope.(OwnedViewModels) -> Unit) = runBlocking {
    val owned = OwnedViewModels()
    try {
        body(owned)
    } finally {
        owned.closeAll()
    }
    owned.assertNothingActive()
}

/**
 * Runs [body] with a fresh [OwnedViewModels], joining everything it registered afterwards and then
 * asserting nothing survived.
 *
 * The own-join-assert logic for a test builder this module cannot call itself — above all
 * `runComposeUiTest`, which would put Compose on `:maps-shared-logic`. A Compose module's wrapper
 * is then one line:
 *
 * ```
 * internal fun composeVmTest(body: ComposeUiTest.(OwnedViewModels) -> Unit) =
 *     withOwnedViewModels { owned -> runComposeUiTest { body(owned) } }
 * ```
 *
 * ⚠ Not a `runTest`: a Compose test keeps its own frame clock and `waitUntil` budgets, and this
 * changes neither.
 *
 * @param body receives the [OwnedViewModels] every construction inside must be wrapped in.
 */
fun withOwnedViewModels(body: (OwnedViewModels) -> Unit) {
    val owned = OwnedViewModels()
    try {
        body(owned)
    } finally {
        runBlocking { owned.closeAll() }
    }
    owned.assertNothingActive()
}

/**
 * The teardown for a test class that does **not** install its own `Main`: joins every owned scope,
 * then asserts nothing survived.
 *
 * Call it from a JUnit `@After`, which cannot be `suspend` — that is the whole reason this exists
 * rather than calling [OwnedViewModels.closeAll] directly. A class that *does* install `Main` wants
 * [closeAndResetMain], which orders the reset against the join.
 *
 * @throws AssertionError if anything survived.
 */
fun OwnedViewModels.closeBlocking() {
    runBlocking { closeAll() }
    assertNothingActive()
}

/**
 * The teardown for a test class that installs its own `Main`: joins every owned scope, resets
 * `Main`, then asserts nothing survived.
 *
 * Call it from `@AfterTest`, typically as the whole body.
 *
 * ⚠⚠ The order is the reason this is a function rather than three lines per class. Cancelling a
 * `viewModelScope` dispatches onto `Main`, so a `resetMain()` that ran first would leave the
 * cancellation nowhere to go; a join that ran after it would be racing the very swap it exists to
 * order against.
 *
 * @throws AssertionError if anything survived — raised last, and outside any `try`, so a test that
 *   already failed reports its own failure rather than this one.
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun OwnedViewModels.closeAndResetMain() {
    runBlocking { closeAll() }
    Dispatchers.resetMain()
    assertNothingActive()
}
