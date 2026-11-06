package org.tenshu.core.common.proxy

import org.tenshu.core.TError

/**
 * Port for the proxy-side diagnostic recorder. Phase 5 of
 * `docs/proposals/core-error-channel.md`: [LocalTaskExecutor]'s single
 * catch-all fires [record] with the typed [TError] + best-effort
 * callstack metadata, and the `:logging-service-proxy` impl persists
 * it as a realm-scoped `ErrorEvent`.
 *
 * Lives in `:core-common` so the executor can call it without
 * depending on `:logging-service-*` directly — same shape as the
 * existing `ClientConnection` / `BackgroundScopeProvider` ports.
 * The proxy DI wires a real impl in production; tests omit it.
 *
 * **Implementations must not throw.** A misbehaving recorder must
 * never escape the catch-all and reach the wire as an Internal
 * error. Swallow + best-effort.
 */
fun interface ErrorEventRecorder {
    fun record(error: TError, operationId: String, callstackDepth: Int)
}
