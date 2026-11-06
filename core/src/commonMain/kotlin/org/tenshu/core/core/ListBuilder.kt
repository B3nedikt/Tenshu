package org.tenshu.core.core

import org.tenshu.core.Data

class ListBuilder<T : Data>(
    context: TContext,
    private val list: MutableList<T> = mutableListOf()
) : TContext by context, MutableList<T> by list

fun <T : Data> TContext.buildList(
    builder: ListBuilder<T>.() -> Unit
): List<T> = ListBuilder<T>(this).apply(builder)
