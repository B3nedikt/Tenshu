package org.tenshu.core.common.util

import kotlinx.browser.window

actual fun getPlatformDeviceName(): String {
    val userAgent = window.navigator.userAgent
    return when {
        userAgent.contains("Firefox") -> "Firefox Browser"
        userAgent.contains("Chrome") -> "Chrome Browser"
        userAgent.contains("Safari") -> "Safari Browser"
        else -> "Web Browser"
    }
}

/** The browser is the closest thing a web device has to a hardware model. */
actual fun getPlatformHardwareName(): String = getPlatformDeviceName()
