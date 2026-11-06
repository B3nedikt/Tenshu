package org.tenshu.core.common.impl.array

import org.tenshu.core.Data
import org.tenshu.core.SimpleComposite
import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.impl.TStringDto
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.DataSchema

class TListDto<out T : Data>(
    private val valuesList: List<T>
) : SimpleComposite(
    schema = TList.Schema,
    fieldValues = mapOf(
        DataSchema.Fields.SCHEMA_ID to TStringDto(TList.ID)
    )
), TList<T>, List<T> by valuesList {

    override val schema: CompositeSchema get() = TList.Schema

    override fun getElement(index: Int): T {
        return valuesList[index]
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TList<*>) return false
        
        val otherList = other.toList()
        if (size != otherList.size) return false

        for (i in 0 until size) {
            if (getElement(i) != other.getElement(i)) return false
        }
        return true
    }

    override fun hashCode(): Int {
        return valuesList.hashCode()
    }
}
