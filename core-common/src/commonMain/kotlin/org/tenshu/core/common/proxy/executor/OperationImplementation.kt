package org.tenshu.core.common.proxy.executor

import org.tenshu.core.Data
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.core.TContext

interface OperationImplementation

fun interface OperationImplementationFactory {
    operator fun invoke(operationId: String): OperationImplementation?
}

/**
 * Typed accessor for a dispatcher-guaranteed-present operation parameter.
 *
 * Phase 2 of `docs/proposals/core-error-channel.md` (the §3.0 structural lift)
 * makes both presence + type a dispatcher concern:
 *
 *  - Presence is enforced just before dispatch by `evaluateCallstack`'s
 *    `checkRequiredParameters`, which throws [org.tenshu.core.TenshuException]
 *    wrapping a typed [org.tenshu.core.MissingParameter] if a required
 *    schema-declared param is absent.
 *  - Unknown keys + wrong types are enforced by `OperationDto.init` (parser
 *    boundary), which throws `TenshuException(UnknownParameter)` /
 *    `TenshuException(WrongParameter)`.
 *
 * So by the time an impl body reads a param, the map already has every required
 * key under a value of the right schema. [read] is a thin typed-cast shim that
 * trusts those invariants — no runtime check, no error wrapping. If it ever fails
 * (programmer error: a missing schema field, a value of the wrong runtime class),
 * the resulting `ClassCastException` / NPE falls through to the proxy catch-all
 * just like any other unexpected exception.
 *
 * Replaces the old `params.require(KEY)` helper, which conflated presence with
 * type and threw freetext into the catch-all.
 */
@Suppress("UNCHECKED_CAST")
fun <T : Data> Map<String, Data>?.read(key: String): T {
    return this!!.getValue(key) as T
}

/**
 * Phase 4 of `docs/proposals/core-error-channel.md` — Shape A (§3.1 / §3.3): impls
 * return `T` directly. Failure travels as a thrown
 * [org.tenshu.core.TenshuException] carrying a typed [org.tenshu.core.TError]
 * (typed-throw + single catch-all at the wire boundary). The single wire-side
 * catch in [org.tenshu.core.common.proxy.LocalTaskExecutor] unwraps that
 * exception into a `TResult.Failure(error)` on the reply path, so cross-process
 * callers continue to see the same Result-shaped wire payload; the change is in
 * the *contract* between impls and the executor, not on the wire.
 */

/**
 * Interface for logic that only depends on TContext and parameters.
 */
interface PureOperationImplementation : OperationImplementation {

    fun <T : Data?> execute(
        context: TContext,
        implementationFactory: OperationImplementationFactory,
        caller: Any? = null,
        params: Map<String, Data>? = null
    ): T
}

/**
 * Interface for logic that requires the full ExecutionContext (Proxy side).
 * This is the synchronous version, used by default.
 */
interface SyncOperationImplementation : OperationImplementation {

    fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext? = null,
        implementationFactory: OperationImplementationFactory,
        caller: Any? = null,
        params: Map<String, Data>? = null
    ): T
}

/**
 * Interface for logic that requires asynchronous execution (IO).
 */
interface AsyncOperationImplementation : OperationImplementation {

    suspend fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext? = null,
        implementationFactory: OperationImplementationFactory,
        caller: Any? = null,
        params: Map<String, Data>? = null
    ): T
}

