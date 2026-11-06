package org.tenshu.file.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.file.test.createFileProxy
import kotlin.test.Test

/**
 * Registering the offline keeper must not be able to take the proxy down with it.
 *
 * ⚠⚠ Every call site is a bare `sessionScope.launch { registerOfflineKeeperSchedule() }` in the
 * platform proxy init (android, desktop and iOS alike), and that scope carries **no**
 * `CoroutineExceptionHandler` — `ClientRegistry` builds it as `parent.coroutineContext +
 * SupervisorJob(...)`. So anything thrown here reaches the thread's uncaught handler, which on
 * Android kills Control. A 6-hourly maintenance tick failing to register is not worth a process.
 *
 * Reachable without exotic conditions: `Schedule` resolves through the platform implementation
 * chain, so any graph that does not wire the sync factory throws `No implementation found for
 * operation: …/schedule` — which is exactly what this proxy is, and exactly what a drive
 * integration test hit, where it surfaced as an uncaught exception charged to an unrelated test.
 */
class OfflineKeeperScheduleFailureTest {

    @Test
    fun aMissingScheduleOperationDoesNotEscapeAsAnUncaughtThrow() = runTest {
        // This proxy wires the file service only — no sync factory, hence no `Schedule`.
        val proxy = createFileProxy(backgroundScope)

        // The assertion IS the absence of a throw: `runTest` fails the test on one, and the
        // production caller has no catch of its own. Pre-fix this threw IllegalStateException.
        proxy.executionContext.registerOfflineKeeperSchedule()
    }
}
