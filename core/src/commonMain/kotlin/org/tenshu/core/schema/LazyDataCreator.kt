package org.tenshu.core.schema

import org.tenshu.core.Data
import org.tenshu.core.core.TContext

interface LazyDataCreator<out T : Data> {
    fun materialize(context: TContext): T
}
