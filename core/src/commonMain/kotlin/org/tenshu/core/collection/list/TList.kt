package org.tenshu.core.collection.list

import org.tenshu.core.Composite
import org.tenshu.core.Data
import org.tenshu.core.SimpleTString
import org.tenshu.core.core.TContext
import org.tenshu.core.core.dataFactory
import org.tenshu.core.number.toTInteger
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.DataSchema
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.type
import org.tenshu.core.toTBoolean
import org.tenshu.core.toTByteArray
import org.tenshu.core.toTString
import kotlin.jvm.JvmName

interface TList<out T : Data> : Composite {

    override val schema: CompositeSchema
        get() = Schema

    fun getElement(index: Int): T

    companion object {
        const val ID = "tenshu/core/list"

        val Schema: CompositeSchema = composite(
            id = ID,
            typeParams = { type("E") }
        )
    }
}

@Suppress("UNCHECKED_CAST")
fun <T : Data> TList<T>.toList(): List<T> =
    (this as? List<T>) ?: error("Can only be called on a returned value")

@JvmName("toTListAny")
fun List<Any>.toTList(context: TContext? = null): TList<Data> {
    val dataList = map {
        when (it) {
            is Data -> it
            is String -> it.toTString(context)
            is Int -> it.toTInteger(context)
            is Long -> it.toTInteger(context)
            is ByteArray -> it.toTByteArray(context)
            is Boolean -> it.toTBoolean(context)
            is List<*> -> {
                @Suppress("UNCHECKED_CAST")
                (it as List<Any>).toTList(context)
            }

            else -> error("Invalid type $it")
        }
    }
    return if (context != null) {
        context.dataFactory().createList(dataList)
    } else {
        SimpleTList(dataList)
    }
}

fun <T : Data> List<T>.toTList(context: TContext? = null): TList<T> {
    return if (context != null) {
        context.dataFactory().createList(this)
    } else {
        SimpleTList(this)
    }
}

class SimpleTList<out T : Data>(
    private val list: List<T>
) : TList<T>, List<T> by list {
    override fun getElement(index: Int): T = list[index]
    override fun <R : Data> getField(reference: String): R =
        getOptionalField(reference) ?: error("Field $reference not found in SimpleTList")

    override fun <R : Data> getOptionalField(reference: String): R? {
        @Suppress("UNCHECKED_CAST")
        return when (reference) {
            DataSchema.Fields.SCHEMA_ID -> SimpleTString(TList.ID) as R
            else -> null
        }
    }
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is List<*>) return false
        return list == other
    }

    override fun hashCode(): Int = list.hashCode()
    override fun toString(): String = list.toString()
}
