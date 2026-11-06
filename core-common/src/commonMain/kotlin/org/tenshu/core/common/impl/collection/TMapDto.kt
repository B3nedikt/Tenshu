package org.tenshu.core.common.impl.collection

import org.tenshu.core.Data
import org.tenshu.core.collection.TMap
import org.tenshu.core.collection.TPair
import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.core.common.impl.array.TListDto

class TMapDto<K : Data, V : Data>(
    fieldValues: List<Data?>,
) : TMap<K, V>, CompositeDto(fieldValues) {

    constructor(pairs: TList<TPair<K, V>>) : this(listOf(pairs))

    constructor(kotlinMap: Map<Data, Data>) : this(pairs = kotlinMap.mapToList())

    override val size: Int
        get() = pairs.toList().size

    override val keys: Set<K>
        get() = pairs.toList().map { it.first }.toSet()

    override val values: Collection<V>
        get() = pairs.toList().map { it.second }

    override val entries: Set<Map.Entry<K, V>>
        get() = pairs.toList().associate { it.first to it.second }.entries

    override fun isEmpty(): Boolean {
        return pairs.toList().isEmpty()
    }

    override fun containsKey(key: K): Boolean {
        return key in keys
    }

    override fun containsValue(value: V): Boolean {
        return value in values
    }

    override fun get(key: K): V? {
        return pairs.toList().firstOrNull { it.first == key }?.second
    }
}

private fun <K : Data, V : Data> Map<Data, Data>.mapToList(): TList<TPair<K, V>> {
    return TListDto(map {

        @Suppress("UNCHECKED_CAST")
        (TPairDto(it.key as K, it.value as V))
    })
}

