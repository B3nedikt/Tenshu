package org.tenshu.core.number

import org.tenshu.core.Data
import org.tenshu.core.TValue
import org.tenshu.core.core.TContext
import org.tenshu.core.core.dataFactory
import org.tenshu.core.schema.ValueSchema
import org.tenshu.core.schema.value

interface TInteger : TValue {

    override val schema: ValueSchema
        get() = Schema

    fun get(): Long

    companion object {
        const val ID = "tenshu/core/number/integer"

        val Schema: ValueSchema = value(
            id = ID,
            parent = TValue.Schema,
        )
    }
}

fun Int.toTInteger(context: TContext? = null): TInteger =
    this.toLong().toTInteger(context)

fun Long.toTInteger(context: TContext? = null): TInteger {
    return if (context != null) {
        context.dataFactory()
            .createValue(TInteger.ID, this) as TInteger
    } else {
        SimpleTInteger(this)
    }
}

internal class SimpleTInteger(private val valueLong: Long) : TInteger {
    override fun get(): Long = valueLong
    override val value: Data get() = this
    override val schema: ValueSchema get() = TInteger.Schema

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TInteger) return false
        return valueLong == other.get()
    }

    override fun hashCode(): Int = valueLong.hashCode()
    override fun toString(): String = valueLong.toString()
}
