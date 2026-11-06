package org.tenshu.core.common.util

import android.os.Build

actual fun getPlatformDeviceName(): String = getPlatformHardwareName()

actual fun getPlatformHardwareName(): String {
    val manufacturer = Build.MANUFACTURER
    val model = Build.MODEL
    return if (model.startsWith(manufacturer)) {
        model.replaceFirstChar { it.uppercase() }
    } else {
        "${manufacturer.replaceFirstChar { it.uppercase() }} $model"
    }
}
