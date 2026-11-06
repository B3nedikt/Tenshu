package app.tenshu.control.integration

import app.tenshu.control.integration.utils.startAppIntegrationKoin
import app.tenshu.control.integration.utils.stopAppIntegrationKoin
import org.junit.After
import org.junit.Test
import org.koin.core.context.GlobalContext
import org.tenshu.core.common.request.AsyncTaskExecutor
import org.tenshu.core.common.request.CancellableLocalTaskExecutor
import kotlin.test.assertTrue

/**
 * Regression guard for the "Pairing timed out instantly" bug.
 *
 * Control dispatches `BackgroundOperation`s (e.g. `AwaitPairing`) through its
 * own in-process executor. `DefaultInteractorProvider.buildCancellableTaskInteractor`
 * only runs such an op *awaited* when the executor is a
 * [CancellableLocalTaskExecutor]; a bare [AsyncTaskExecutor] falls to
 * `executeAsync`, which for a background op takes `ExecutionContext`'s legacy
 * detached path (`sessionScope.launch` + immediate `Success(null)`). The
 * `TaskHandle.await()` then returns instantly and the pairing wizard shows
 * "Pairing timed out" while the handshake completes fire-and-forget.
 *
 * `DefaultInteractorProvider` now also fails loud on that mismatch at runtime;
 * this test pins Control's side of the contract so a future refactor of
 * `proxyModule`'s `single<AsyncTaskExecutor>` can't silently regress to a
 * bare executor. It resolves the *real* production wiring (via
 * [startAppIntegrationKoin], which loads `proxyModule`), not a test double.
 */
class InProcessExecutorWiringTest {

    @After
    fun tearDown() = stopAppIntegrationKoin()

    @Test
    fun controlInProcessExecutor_isCancellable_soBackgroundOpsRunAwaited() {
        startAppIntegrationKoin()

        val executor = GlobalContext.get().get<AsyncTaskExecutor>()

        assertTrue(
            executor is CancellableLocalTaskExecutor,
            "Control's in-process AsyncTaskExecutor must implement " +
                "CancellableLocalTaskExecutor so a BackgroundOperation (AwaitPairing) " +
                "runs awaited rather than detached — otherwise TaskHandle.await() " +
                "resolves instantly to Success(null) and pairing 'times out' the " +
                "moment it starts. Got ${executor::class.simpleName}.",
        )
    }
}
