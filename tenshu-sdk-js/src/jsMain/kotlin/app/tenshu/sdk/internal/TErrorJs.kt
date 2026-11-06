package app.tenshu.sdk.internal

import org.tenshu.core.GenericError
import org.tenshu.core.TError

// `TResult.fold`'s onFailure parameter is a `TError` Composite, not a JS
// string. The onboarding singletons surface failures to the apex as
// `{ ok: false, error: string }` (per `web/sdk/src/onboarding.ts`), so each
// wrapper needs to reduce the TError to a string. `GenericError` already
// carries a message; everything else falls back to its schema id (the
// closest stable identifier the apex can branch on).
internal fun TError.errorMessage(): String =
    (this as? GenericError)?.message?.get() ?: schema.id.get()

// ktor-client-js surfaces `fetch failed`, connection refusals, and similar
// transport failures as `kotlin.Error` rather than `Exception`. The runtime's
// HttpClient now maps those to an `IOException` (`TransportFailureMapping`), so
// `RestSyncRelay` returns them as `TResult.Failure`; the boundary `Throwable`
// catches that call this remain for anything else that throws. Onboarding
// wrappers grep the surfaced message for network-y keywords so the
// contract's `network` kind can fire instead of falling through to `other`.
//
// The relay also surfaces some HTTP failures as messages like
// `"Challenge fetch failed: ${status}"` — which contain "fetch" too, but
// only fire on actual network drops (HTTP errors come back through the
// TResult.Failure path which already classifies them, never through this
// helper).
internal fun looksLikeNetworkError(message: String): Boolean {
    val lower = message.lowercase()
    return "fetch" in lower || "network" in lower ||
            "connection" in lower || "refused" in lower ||
            "failed to fetch" in lower
}
