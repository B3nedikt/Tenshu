package org.tenshu.core.common

import org.tenshu.core.Data
import org.tenshu.core.StreamElement
import org.tenshu.core.TResult

/**
 * Implementor-side contract that lets [DefaultInteractorProvider] inject elements into a
 * live [org.tenshu.core.StreamInteractor] without exposing those mutation entry points to
 * `:core` consumers (DSL extensions, ROs, operation impls). Implemented exclusively by
 * [StreamInteractorImpl]; held in [DefaultInteractorProvider]'s `interactors` map.
 *
 * Lives in `:core-common` rather than `:core` because it's an implementation contract,
 * not part of the public consumer surface (which is [org.tenshu.core.StreamInteractor]).
 * Should be `internal` to `:core-common`, but is currently public because
 * `FileServiceUnitTest.flowOperation_CreatesCorrectFlow` in `:file-service-client`
 * constructs a [StreamInteractorImpl] by hand and drives it via these methods. Tighten
 * to `internal` once that test is refactored to drive the dispatcher via the public
 * `DefaultInteractorProvider` API instead.
 */
interface StreamDispatcher<T : Data> {
    suspend fun dispatchElement(element: StreamElement<*>)
    suspend fun setInitialResult(result: TResult<Data?>)
}
