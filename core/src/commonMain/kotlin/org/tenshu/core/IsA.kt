package org.tenshu.core

import org.tenshu.core.core.TContext
import org.tenshu.core.core.findOperation
import org.tenshu.core.core.schemaProvider
import org.tenshu.core.request.RequestObject
import org.tenshu.core.schema.DataSchema
import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.operation
import org.tenshu.core.schema.string
import org.tenshu.core.schema.toType
import kotlin.reflect.KClass

/**
 * Type test — `true` when the receiver's **own** type is [T] or a subtype of it, by schema
 * parentage (`Folder : File`, so a `Folder` row `isA<File>()` and `isA<Folder>()`, a plain `File`
 * row only the former). Kotlin's `is`, for a row.
 *
 * **Why it exists: it is the first predicate over a row's *type* rather than a field.** Nothing
 * else in the chain vocabulary can ask it. `filter<Folder> { … }` / `search<Folder> { … }` only
 * *narrow the predicate's element type* (`OperationReceiverHolder.narrowedElementType`) — they
 * filter nothing, and `File.Category` has no folder value. So "folders first" had no sort key and
 * "only folders" had no filter; both were done client-side over a fully materialised list
 * (`drive-sorted-folder-paging.md` §3 B1).
 *
 * The two shapes it serves:
 *  - a **sort key** — `sortByDescending { isA<Folder>() }` puts every folder ahead of every file,
 *    and the ordered-window fusion reads it off the row's stored type id at no extra cost
 *    (`core-ordered-window-fusion.md` §8, the `TypeIs` key);
 *  - a **predicate** — `filter { isA<Folder>() }` keeps only the folders.
 *
 * ⚠ The type is carried on the wire as the schema **id** (a string), never as a class: the proxy
 * resolves it against its own registry and walks the parent chain there. An id the proxy does not
 * know is simply no row's type — a `false`, not an error — which is the same answer Kotlin's `is`
 * gives for an unrelated class.
 *
 * Dual-dispatches like [oneOf] / [contains]: against a recording [RequestObject] it appends a
 * serialisable `IsA` op to the chain (resolved on the proxy via [isALogic]); against real
 * in-memory [Data] it evaluates directly. No IO/terminal/stream traits, so it composes mid-chain
 * and resolves synchronously in a scan.
 *
 * The reified form needs a context to turn [T] into a schema id, which a request object carries;
 * plain in-memory data takes the schema itself via [isA] `(DataSchema)`.
 */
@Suppress("UNCHECKED_CAST")
inline fun <reified T : Data> Data.isA(): TBoolean {
    val context = this as? TContext
        ?: error(
            "isA<${T::class.simpleName}>() needs a context-carrying receiver (a request object " +
                "in a chain); on plain in-memory data pass the schema: isA(${T::class.simpleName}.Schema)"
        )
    return isA(context.schemaProvider().getTypeId(T::class as KClass<Data>))
}

/** [isA] by schema — the in-memory spelling, usable with no context. */
fun Data.isA(type: DataSchema): TBoolean = isA(type.id.get())

/** [isA] by schema id — the wire spelling; the two overloads above resolve to this one. */
fun Data.isA(typeId: String): TBoolean {
    return if (this is RequestObject) {
        findOperation<IsA>(IsA.ID).isA(typeId)
    } else {
        isALogic(this, typeId, (this as? TContext)?.schemaProvider())
    }
}

interface IsA : Operation {

    override val schema: OperationSchema
        get() = Schema

    fun isA(typeId: String): TBoolean

    companion object {
        const val ID = "tenshu/core/protocol/isA"

        val Schema = operation(
            id = ID,
            receiver = Data.Schema.toType(),
            params = {
                string(id = Params.TYPE_ID)
            }
        )
    }

    object Params {
        const val TYPE_ID: String = "${ID}/typeId"
    }
}
