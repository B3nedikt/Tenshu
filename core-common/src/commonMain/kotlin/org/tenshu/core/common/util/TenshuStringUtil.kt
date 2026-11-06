package org.tenshu.core.common.util

import org.tenshu.core.TString
import org.tenshu.core.core.TContext
import org.tenshu.core.toTString as coreToTString

fun String.toTString(context: TContext? = null): TString {
    return this.coreToTString(context)
}
