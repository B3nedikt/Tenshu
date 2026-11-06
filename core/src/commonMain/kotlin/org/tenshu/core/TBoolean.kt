package org.tenshu.core

import org.tenshu.core.core.TContext
import org.tenshu.core.core.dataFactory
import org.tenshu.core.schema.ValueSchema
import org.tenshu.core.schema.value

interface TBoolean : TValue {

    override val schema: ValueSchema
        get() = Schema

    fun get(): Boolean

    companion object {
        const val ID = "tenshu/core/boolean"

        val Schema: ValueSchema = value(
            id = ID,
            parent = TValue.Schema,
        )
    }
}

fun Boolean.toTBoolean(context: TContext? = null): TBoolean {
    return if (context != null) {
        context.dataFactory()
            .createValue(TBoolean.ID, this) as TBoolean
    } else {
        SimpleTBoolean(this)
    }
}

internal class SimpleTBoolean(private val valueBool: Boolean) : TBoolean {
    override fun get(): Boolean = valueBool
    override val value: Data get() = this
    override val schema: ValueSchema get() = TBoolean.Schema

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TBoolean) return false
        return valueBool == other.get()
    }

    override fun hashCode(): Int = valueBool.hashCode()
    override fun toString(): String = valueBool.toString()
}
