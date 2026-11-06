package org.tenshu.core

import org.tenshu.core.core.TContext
import org.tenshu.core.core.create
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.Type
import org.tenshu.core.schema.UnionSchema
import org.tenshu.core.schema.UnionType
import org.tenshu.core.schema.Variance
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.field
import org.tenshu.core.schema.of
import org.tenshu.core.schema.toType
import org.tenshu.core.schema.type
import org.tenshu.core.schema.typeOf
import org.tenshu.core.schema.typeVar
import org.tenshu.core.schema.unionSchema
import org.tenshu.core.schema.unionType

interface TResult<out T : Data?> : Composite {

    companion object {
        const val ID = "tenshu/core/result"

        val Schema: UnionSchema = unionSchema(
            id = ID,
            members = { listOf(Success.Schema, Failure.Schema) }
        )

        fun type(itemType: Type = Data.Schema.toType()): UnionType = unionType(
            typeOf(Success.Schema).of(itemType, variance = Variance.Covariant),
            Failure.Schema.toType()
        )
    }

    interface Success<out T : Data?> : TResult<T> {

        override val schema: CompositeSchema
            get() = Schema

        val value: T?
            get() = getOptionalField(Fields.VALUE)

        companion object {
            const val ID = "tenshu/core/result/success"

            val Schema: CompositeSchema = composite(
                id = ID,
                parent = TResult.Schema,
                typeParams = { type("E") },
                fields = {
                    field(Fields.VALUE, typeVar("E"), isOptional = true)
                }
            )

            fun <T : Data?> create(
                context: TContext,
                value: T? = null
            ): Success<T> = context.create(
                type = ID,
                fields = listOfNotNull(value)
            )
        }

        object Fields {
            const val VALUE = "$ID/value"
        }
    }

    interface Failure<out T : Data?> : TResult<T> {

        override val schema: CompositeSchema
            get() = Schema

        val error: TError
            get() = getField(Fields.ERROR)

        companion object {
            const val ID = "tenshu/core/result/failure"

            val Schema: CompositeSchema = composite(
                id = ID,
                parent = TResult.Schema,
                // Failure<T> carries T to satisfy the TResult<T> umbrella, even
                // though `error` doesn't reference it. typeParams kept for
                // schema-level consistency.
                typeParams = { type("E") },
                fields = {
                    composite(Fields.ERROR, type = TError.Schema)
                }
            )

            fun <T : Data?> create(
                context: TContext,
                error: TError
            ): Failure<T> = context.create(
                type = ID,
                fields = listOf(error)
            )
        }

        object Fields {
            const val ERROR = "${ID}/error"
        }
    }
}

fun <T : Data?, R> TResult<T>.fold(
    onSuccess: (T) -> R,
    onFailure: (TError) -> R
): R {
    return when (this) {
        is TResult.Success<*> -> {
            @Suppress("UNCHECKED_CAST")
            onSuccess(this.value as T)
        }

        is TResult.Failure<*> -> onFailure(this.error)
        else -> error("Unknown result type: $this")
    }
}

/**
 * Maps the success value of this result using the given [transform] function.
 */
fun <T : Data?, R : Data?> TResult<T>.map(
    context: TContext,
    transform: (T) -> R
): TResult<R> = fold(
    onSuccess = { TResult.Success.create(context, transform(it)) },
    onFailure = {
        @Suppress("UNCHECKED_CAST")
        this as TResult<R>
    }
)

/**
 * Maps the success value of this result using the given [transform] function that returns a [TResult].
 */
fun <T : Data?, R : Data?> TResult<T>.flatMap(
    transform: (T) -> TResult<R>
): TResult<R> = fold(
    onSuccess = { transform(it) },
    onFailure = {
        @Suppress("UNCHECKED_CAST")
        this as TResult<R>
    }
)

@Suppress("UNCHECKED_CAST")
fun <T : Data> TResult<T>.get(): T =
    (this as? TResult.Success<*>
        ?: error("Result is ${this as? TResult.Failure<*>}")).value as T

@Suppress("UNCHECKED_CAST")
fun <T : Data?> TResult<T>.getOrNull(): T? =
    (this as? TResult.Success<*>)?.value as T?

fun <T : Data?> TResult<T>.onSuccess(block: (T) -> Unit): TResult<T> {
    if (this is TResult.Success<*>) {
        @Suppress("UNCHECKED_CAST")
        block(this.value as T)
    }
    return this
}

suspend fun <T : Data?> TResult<T>.onSuccessSuspend(block: suspend (T) -> Unit): TResult<T> {
    if (this is TResult.Success<*>) {
        @Suppress("UNCHECKED_CAST")
        block(this.value as T)
    }
    return this
}

fun <T : Data?> TResult<T>.onFailure(block: (TError) -> Unit): TResult<T> {
    if (this is TResult.Failure<*>) {
        block(this.error)
    }
    return this
}
