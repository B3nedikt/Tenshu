package org.tenshu.logging.proxy

import org.tenshu.core.common.proxy.ErrorEventRecorder
import org.tenshu.core.common.proxy.ExecutionContext

/**
 * Factory for the `:logging-service-proxy` wiring — Phase 5 of
 * `docs/proposals/core-error-channel.md`.
 *
 * Two responsibilities:
 *  1. Construct an [ErrorEventRecorder] bound to the session
 *     [ExecutionContext] (the recorder needs to `saveData` into the realm
 *     DB to land an [org.tenshu.logging.ErrorEvent]).
 *  2. Call [initLoggingService] on the session context so the
 *     `LoggingService.events` relation is maintained from observed saves.
 *
 * Used by Control's `PlatformProxyModule` (desktop + android) symmetric with
 * `:preference-service-proxy` / `:notification-service-proxy` wiring.
 *
 * `appVersion` / `osLabel` defaults are useful in unit tests; production
 * passes platform-resolved values (BuildConfig + os.name + os.version).
 */
fun loggingServiceProxyImplementationFactory(
    sessionExecutionContext: ExecutionContext,
    /**
     * Read at each recorded event. ⚠ A provider, because the session outlives a "Forget this
     * device", which gives the process a new id (`SyncManager.forgetThisDevice`).
     */
    deviceId: () -> String,
    appId: String,
    appVersion: String = "dev",
    osLabel: String = "unknown",
): ErrorEventRecorder {
    sessionExecutionContext.initLoggingService()
    return ErrorEventRecorderImpl(
        sessionExecutionContext = sessionExecutionContext,
        deviceId = deviceId,
        appId = appId,
        appVersion = appVersion,
        osLabel = osLabel,
    )
}
