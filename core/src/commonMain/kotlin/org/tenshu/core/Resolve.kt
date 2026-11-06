package org.tenshu.core

import org.tenshu.core.core.findOperation
import org.tenshu.core.request.RequestObject
import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.operation
import org.tenshu.core.schema.toType
import org.tenshu.core.trait.IoOperation
import org.tenshu.core.trait.TerminalOperation

/**
 * Drive a chain that ends in a **value** to completion and return it.
 *
 * The missing half of the terminal family. [GetAll] terminates a list, [Get] terminates a
 * `Maybe<T>`, and until this there was nothing that terminated a scalar — so a chain like
 *
 * ```
 * files.traverse().filter<File> { category equalTo Image }.count()
 * ```
 *
 * could be *built* and *evaluated proxy-side*, but a client could not ask for the answer.
 *
 * ⚠⚠ **The failure that hid this was not obviously a missing terminal.** Dispatch is chosen by the
 * terminal operation's traits (`DefaultInteractorProvider.getInteractor`): stream, then IO, then
 * background, and **everything else falls to a synchronous interactor**. `Count` carries no traits
 * — deliberately, because its one caller is `Folder.FileCount`, a proxy-side dynamic property whose
 * non-suspend resolver could not await a `TaskInteractor` — so a chain ending in it was routed to
 * `TaskExecutor.execute`, which on a client is `RemoteBinaryTaskExecutor.execute`: it walks the
 * callstack running each op through `PureOperationRunner` and throws
 * `SynchronousViolationException` at the first non-pure one, which is `traverse`. The op was fine.
 * The chain had nowhere to end.
 *
 * ⚠⚠ **It is `resolve`, not `get`, and that is not a style choice.** `TInteger` declares a member
 * `fun get(): Long` — the local accessor — and in Kotlin a member always wins over an extension. An
 * extension named `get()` would silently bind to the accessor on exactly the type this exists for,
 * and fail at runtime with `IllegalStateException: Not a data object`. `Maybe.get()` escapes that
 * only because `Maybe` has no local `get()`; ⚠ the two names for one idea are worth reconciling,
 * and that is filed rather than done here.
 *
 * ⚠ The receiver is [TValue], not [Data]. The *schema* receiver is `Data` — `EqualTo` and `InRange`
 * are declared the same way — but a Kotlin bound keeps the call site honest: terminating a
 * composite is [Get]'s or [GetAll]'s job, and a second way to do it would be a way to get it wrong.
 *
 * ⚠ This makes a value chain **callable, not cheap**. `count()` still only reaches the raw-array
 * short-circuit (`evaluateCallstack`) when the receiver is an unfiltered array; past a `filter` it
 * materialises and counts proxy-side. What changes is the wire: one value comes back instead of
 * every row the caller would otherwise have had to count itself.
 */
suspend fun <T : TValue> T.resolve(): TResult<T?> {
    val operation = findOperation<Resolve<T>>(Resolve.ID)
    return operation.resolve()
        .asTaskInteractor<T?>()
        .execute()
}

interface Resolve<out T : Data> : Operation {

    override val schema: OperationSchema
        get() = Schema

    fun resolve(): RequestObject

    companion object {
        const val ID = "tenshu/core/value/resolve"

        val Schema: OperationSchema = operation(
            id = ID,
            // ⚠ `Data`, matching `EqualTo`/`InRange`: the schema layer has no "any value" type, and
            // the Kotlin `TValue` bound above is what actually constrains callers.
            receiver = Data.Schema.toType(),
            params = {},
            traits = listOf(IoOperation.Schema, TerminalOperation.Schema)
        )
    }
}

/**
 * Drive the `Maybe<T>` terminal `Get` operation to completion and surface
 * the **full** [TResult] so callers can distinguish a `Success(null)` (the
 * Maybe resolved cleanly but matched nothing) from a genuine `Failure`
 * mid-chain. Previously this collapsed both into a single `T?`, swallowing
 * any error the underlying `execute()` produced — a `try/catch` at every
 * call site was the only way to recover it, which is exactly the smell
 * this signature change cures.
 *
 * Callers that don't care about the failure path can collapse with
 * `Maybe.resolve().getOrNull()`; callers that do use
 * `.fold(onSuccess = …, onFailure = …)`.
 *
 * ⚠⚠ **Named `resolve`, not `get`, and it was renamed from `get` on 2026-09-01** to match
 * [org.tenshu.core.resolve] — one verb for "drive this chain to its value", whether the chain ends
 * in a `Maybe` or a scalar. The value terminal could not be called `get()` at all: `TInteger`
 * declares a *member* `fun get(): Long`, a member always beats an extension, so the call would have
 * bound to the local accessor and failed at runtime with "Not a data object". This one had no such
 * clash — `Maybe` has no local `get()` — so the rename buys consistency rather than correctness.
 *
 * ⚠ The operation id and the [Get] interface keep their names: they are **wire-visible**, and
 * renaming them would be a schema change for a readability win.
 */
suspend fun <T : Data> Maybe<T>.resolve(): TResult<T?> {
    val operation = findOperation<Get<T>>(Get.ID)
    return operation.get()
        .asTaskInteractor<T?>()
        .execute()
}
