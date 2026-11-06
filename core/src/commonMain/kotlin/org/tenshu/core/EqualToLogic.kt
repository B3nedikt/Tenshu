package org.tenshu.core

import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.toList
import org.tenshu.core.entity.Entity

fun equalToLogic(receiver: Data, other: Any): TBoolean {
    if (other !is Data) return false.toTBoolean()

    if (receiver is TValue && other is TValue) {
        if (receiver is TByteArray && other is TByteArray) {
            return (receiver == other).toTBoolean()
        }

        if (receiver is TString && other is TString) {
            return (receiver.get() == other.get()).toTBoolean()
        }

        return (receiver.value == other.value).toTBoolean()
    }

    if (receiver.id != other.id) return false.toTBoolean()

    if (receiver is TConstant && other is TConstant) {
        return (receiver.id == other.id).toTBoolean()
    }

    if (receiver is TList<*> && other is TList<*>) {
        val receiverList = receiver.toList()
        val otherList = other.toList()

        if (receiverList.size != otherList.size) return false.toTBoolean()

        return receiverList.zip(otherList)
            .all { (r, o) -> equalToLogic(r, o).get() }
            .toTBoolean()
    }

    if (receiver is Entity<*> && other is Entity<*>) {
        val receiverProperties = receiver.properties
        val otherProperties = other.properties

        if (receiverProperties.size != otherProperties.size) return false.toTBoolean()
        if (!receiverProperties.keys.containsAll(otherProperties.keys)) return false.toTBoolean()

        val propertiesEqual = receiverProperties.all { (key, value) ->
            val otherValue = otherProperties[key] ?: return@all false
            equalToLogic(value, otherValue).get()
        }
        if (!propertiesEqual) return false.toTBoolean()
    } else if (receiver is Entity<*> || other is Entity<*>) {
        return false.toTBoolean()
    }

    if (receiver is Composite && other is Composite) {
        val receiverFields = receiver.fields
        val otherFields = other.fields

        if (receiverFields.size != otherFields.size) return false.toTBoolean()
        if (!receiverFields.keys.containsAll(otherFields.keys)) return false.toTBoolean()

        return receiverFields.all { (key, value) ->
            val otherValue = otherFields[key] ?: return@all false
            equalToLogic(value, otherValue).get()
        }.toTBoolean()
    }

    return false.toTBoolean()
}
