package org.tenshu.core.common.util

/**
 * The **suggested** user-facing label for this device — the default the pairing and realm-creation
 * screens prefill, which the user may replace. Stored as `Device.name`.
 *
 * ⚠ Not an identity: two phones of one model produce the same string, and on iOS it is whatever
 * the system reports as the device's name. For what the device *is*, use [getPlatformHardwareName].
 */
expect fun getPlatformDeviceName(): String

/**
 * The platform-derived hardware model of this device (e.g. "Google Pixel 8", "iPhone",
 * "Mac OS X (aarch64)"), published as `DeviceHardware.hardwareName` and never edited by the user.
 *
 * ⚠ Must stay stable across launches and OS updates of one device: the publisher writes it only
 * when it changes, so a value that drifts (an OS version, a hostname) re-emits a synced row every
 * time it does.
 */
expect fun getPlatformHardwareName(): String
