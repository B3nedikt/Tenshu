package org.tenshu.core.collection.list

import org.tenshu.core.Data
import org.tenshu.core.Maybe
import org.tenshu.core.Operation
import org.tenshu.core.TBoolean
import org.tenshu.core.core.OperationReceiverHolder
import org.tenshu.core.core.TContext
import org.tenshu.core.core.findOperation
import org.tenshu.core.core.schemaProvider
import org.tenshu.core.entity.Entity
import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.list
import org.tenshu.core.schema.listType
import org.tenshu.core.schema.maybeType
import org.tenshu.core.schema.operation
import org.tenshu.core.schema.type
import kotlin.jvm.JvmName
import kotlin.reflect.KClass

/**
 * **Default overload — type inferred from the receiver's element type.**
 *
 * For `TList<File>.search { … }`, Kotlin picks this overload (receiver is
 * `TList<File>`, more specific than `TList<*>`), `T = File`, lambda
 * receiver is `File`, return is `Maybe<Entity<File>>`. No explicit
 * `<File>` written at the call site.
 *
 * Returns `Maybe<Entity<T>>` rather than the previous `Maybe<T>` to match
 * what the engine actually hands back — `OnDemandMaterialiser` /
 * `dataFactory.createEntity` produces an `EntityDto<*>` that implements
 * `Entity<*>` but **not** the concrete data interface (`File`, `Folder`,
 * …). Typing the return as `Maybe<Entity<T>>` aligns the Kotlin static
 * type with the runtime class so consumers no longer need to cast at
 * extraction. See `docs/proposals/core-chain-entity-typing.md` §2.1 for
 * the full rationale.
 *
 * Use the [narrowing overload below] (`.search<Folder> { … }` on a
 * `TList<File>`) when the search is *narrowing* — searching a list of a
 * supertype for a subtype match.
 */
@Suppress("UNCHECKED_CAST")
@JvmName("searchInferred")
inline fun <reified T : Data> TList<T>.search(
    noinline predicate: (T).() -> TBoolean
): Maybe<Entity<T>> = searchInternal(predicate)

/**
 * **Narrowing overload — explicit `<T>` selects a subtype of the
 * receiver's element type.**
 *
 * For `TList<File>.search<Folder> { … }`, Kotlin picks this overload
 * (receiver `TList<*>` matches but the default's `TList<T>` doesn't
 * resolve T = Folder when the receiver element is File), `T = Folder`,
 * lambda receiver is `Folder`, return is `Maybe<Entity<Folder>>`.
 *
 * Co-exists with the default overload via Kotlin's "more specific
 * receiver wins" rule: `TList<E>` beats `TList<*>` when E is known and
 * matches T. The narrowing case requires `T` ≠ E so the default doesn't
 * resolve, and this overload picks up.
 */
@Suppress("UNCHECKED_CAST")
@JvmName("searchNarrowed")
inline fun <reified T : Data> TList<*>.search(
    noinline predicate: (T).() -> TBoolean
): Maybe<Entity<T>> = searchInternal(predicate)

/**
 * Shared body — both overloads dispatch the same underlying `Search`
 * operation; only the Kotlin static types at the call site differ. The
 * receiver is typed as [Data] (matching `findOperation`'s extension
 * declaration in `:core/.../OperationResolver.kt`) so this helper can
 * sit in `commonMain` and serve both `TList<E>.search` and
 * `TList<*>.search` callers — `TList` is [Data] at the engine level.
 */
@Suppress("UNCHECKED_CAST")
@PublishedApi
internal inline fun <reified T : Data> Data.searchInternal(
    noinline predicate: (T).() -> TBoolean
): Maybe<Entity<T>> {
    val context = this as TContext
    val narrowed = context.schemaProvider().getSchema(
        context.schemaProvider().getTypeId(T::class as KClass<Data>)
    )
    val operation = findOperation<Search<T>>(Search.ID)
    (operation as? OperationReceiverHolder)?.narrowedElementType = narrowed
    return operation.search(predicate) as Maybe<Entity<T>>
}

interface Search<T : Data> : Operation {

    override val schema: OperationSchema
        get() = Schema

    fun search(predicate: (T) -> TBoolean): Maybe<T>

    companion object {
        const val ID = "tenshu/core/collection/list/search"

        val Schema = operation(
            id = ID,
            receiver = listType("E"),
            typeParams = { type("E") },
            params = {
                list(id = Params.PREDICATE)
            },
            returnType = maybeType("E")
        )
    }

    object Params {
        const val PREDICATE = "$ID/predicate"
    }
}
