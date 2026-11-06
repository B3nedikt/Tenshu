package org.tenshu.core.common.util

import platform.UIKit.UIDevice

actual fun getPlatformDeviceName(): String {
    return UIDevice.currentDevice.name
}

/** `UIDevice.model` — "iPhone" / "iPad". ⚠ Not `name`, which is the user's label for the device. */
actual fun getPlatformHardwareName(): String {
    return UIDevice.currentDevice.model
}
