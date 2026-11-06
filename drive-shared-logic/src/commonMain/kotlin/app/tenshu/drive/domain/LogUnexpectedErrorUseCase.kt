package app.tenshu.drive.domain

import org.tenshu.core.TError

/**
 * Marks the spots where Drive swallows an error it cannot surface to the
 * user. Currently a no-op: the proxy-side error-channel recorder
 * (`docs/proposals/core-error-channel.md` Phase 5) already captures every
 * typed error that escapes an operation, so client-side re-logging would
 * only duplicate events. The previous implementation forwarded to the now
 * retired `tenshu/core/logging` service, whose proxy handler discarded the
 * error anyway.
 *
 * The call sites are the migration targets for the `Log` facade
 * (`docs/proposals/core-logging-facade.md` §8) — they become `Log.error(…)`
 * once the facade lands.
 */
class LogUnexpectedErrorUseCase {
    operator fun invoke(error: TError) = Unit
}
