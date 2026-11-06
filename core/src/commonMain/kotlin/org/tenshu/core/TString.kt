package org.tenshu.core

import org.tenshu.core.core.TContext
import org.tenshu.core.core.dataFactory
import org.tenshu.core.schema.ValueSchema
import org.tenshu.core.schema.value

interface TString : TValue {

    override val schema: ValueSchema
        get() = Schema

    fun get(): String

    companion object {
        const val ID = "tenshu/core/string"

        val Schema: ValueSchema = value(
            id = ID,
            parent = TValue.Schema,
        )
    }
}

fun String.toTString(context: TContext? = null): TString {
    return if (context != null) {
        context.dataFactory()
            .createValue(TString.ID, this) as TString
    } else {
        SimpleTString(this)
    }
}

internal class SimpleTString(private val valueStr: String) : TString {
    override fun get(): String = valueStr
    override val value: Data get() = this
    override val schema: ValueSchema get() = TString.Schema

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TString) return false
        return valueStr == other.get()
    }

    override fun hashCode(): Int = valueStr.hashCode()
    override fun toString(): String = valueStr
}
