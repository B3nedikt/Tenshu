package org.tenshu.core

data class ChainTemplate<T : Data, R : Data>(
    val block: T.(params: List<Any>) -> R,
) {
    companion object {
        val ItemSchema = Data.Schema
    }
}
