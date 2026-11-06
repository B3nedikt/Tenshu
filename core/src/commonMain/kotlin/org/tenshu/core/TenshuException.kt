package org.tenshu.core

/**
 * Carries a typed [TError] across throw/catch boundaries — Phase 2 of
 * `docs/proposals/core-error-channel.md` (the structural validation lift).
 *
 * Today's throw sites flatten `e.message` to a `GenericError(message=...)` at the
 * `LocalTaskExecutor` / `ProxyGateway` catch-all. With [TenshuException] the catch
 * can recover the typed code instead of the freetext message. Phase 3 will rewire
 * those catches to unwrap; Phase 2 only changes the throw side. The platform-level
 * `e.message` channel still receives `error.id` so that existing failure logs and
 * the catch-all's freetext-to-`GenericError` flatten remain readable in the
 * transitional period.
 */
class TenshuException(val error: TError) : RuntimeException(error.id)
