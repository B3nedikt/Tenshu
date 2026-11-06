package org.tenshu.core.common.util

actual fun getPlatformDeviceName(): String {
    val os = System.getProperty("os.name")
    val user = System.getProperty("user.name")
    return "Desktop ($user on $os)"
}

actual fun getPlatformHardwareName(): String {
    val os = System.getProperty("os.name").orEmpty().ifBlank { "Desktop" }
    val arch = System.getProperty("os.arch").orEmpty()
    return if (arch.isBlank()) os else "$os ($arch)"
}
