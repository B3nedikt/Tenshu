package app.tenshu.drive.ui.share

import platform.Foundation.NSProcessInfo

/**
 * iOS actual: read the `TENSHU_SHARE_HOST` environment variable (set in a scheme /
 * launch environment for dev builds); unset in normal use, so share links fall back
 * to the apex. There is no system-property analogue on iOS.
 */
internal actual fun platformShareHostOverride(): String? =
    NSProcessInfo.processInfo.environment["TENSHU_SHARE_HOST"]?.toString()?.takeIf { it.isNotBlank() }
