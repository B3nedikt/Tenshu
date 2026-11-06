package org.tenshu.core.common.impl.collection

import org.tenshu.core.Data
import org.tenshu.core.collection.TPair

data class TPairDto<K : Data, V : Data>(
    val fieldValues: List<Data?>,
) : TPair<K, V>, org.tenshu.core.common.impl.CompositeDto(fieldValues) {

    constructor(
        first: K,
        second: V
    ) : this(
        listOf(
            first,
            second
        )
    )
}