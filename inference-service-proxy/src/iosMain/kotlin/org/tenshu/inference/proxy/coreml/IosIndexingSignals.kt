package org.tenshu.inference.proxy.coreml

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.tenshu.inference.proxy.DeviceIndexingState
import org.tenshu.inference.proxy.ThermalState
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSProcessInfoPowerStateDidChangeNotification
import platform.Foundation.NSProcessInfoThermalStateDidChangeNotification
import platform.Foundation.isLowPowerModeEnabled
import platform.Foundation.thermalState
import platform.UIKit.UIApplicationDidReceiveMemoryWarningNotification
import platform.UIKit.UIDevice
import platform.UIKit.UIDeviceBatteryLevelDidChangeNotification
import platform.UIKit.UIDeviceBatteryState
import platform.UIKit.UIDeviceBatteryStateDidChangeNotification
import kotlin.concurrent.AtomicInt

/**
 * What the phone says for a foreground pass, and when it changes (`ios-on-device-inference.md`
 * §14.19's table): the shared [DeviceIndexingState] read the iOS way.
 *
 * | Half | Read from | Changes from |
 * |---|---|---|
 * | thermal | `ProcessInfo.thermalState` | `thermalStateDidChangeNotification` |
 * | Low Power Mode | `ProcessInfo.isLowPowerModeEnabled` | `NSProcessInfoPowerStateDidChange` |
 * | battery | `UIDevice.batteryLevel` / `batteryState`, monitoring switched on once | `UIDeviceBatteryLevelDidChange`, `…StateDidChange` |
 * | memory | — | `UIApplicationDidReceiveMemoryWarning` ([memoryWarnings]) |
 *
 * ⚠ The foreground is not read here: `UIApplication.applicationState` is main-thread-only, and
 * the scene's own record is the app's ([read]'s argument). ⚠ `ProcessInfo` is safe from any
 * thread; `UIDevice`'s battery is read off the main thread as `IosBackupEnvironment` already does.
 * ⚠ Nothing of this means anything on a simulator: the battery is unknown and the heat is the Mac's.
 */
object IosIndexingSignals {

    private val moved = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val warned = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val started = AtomicInt(0)

    /** The heat, the power or the battery moved. ⚠ Never completes; [observe] first. */
    val changes: Flow<Unit> get() = moved.asSharedFlow()

    /** A memory warning. ⚠ Never completes; [observe] first. */
    val memoryWarnings: Flow<Unit> get() = warned.asSharedFlow()

    /** Registers the observers, once a process. ⚠ The observers live as long as the process. */
    fun observe() {
        if (!started.compareAndSet(0, 1)) return
        UIDevice.currentDevice.batteryMonitoringEnabled = true
        val center: NSNotificationCenter = NSNotificationCenter.defaultCenter
        for (name in listOf(
            NSProcessInfoThermalStateDidChangeNotification,
            NSProcessInfoPowerStateDidChangeNotification,
            UIDeviceBatteryLevelDidChangeNotification,
            UIDeviceBatteryStateDidChangeNotification,
        )) {
            center.addObserverForName(name, `object` = null, queue = null) { _ -> moved.tryEmit(Unit) }
        }
        center.addObserverForName(UIApplicationDidReceiveMemoryWarningNotification, `object` = null, queue = null) { _ -> warned.tryEmit(Unit) }
    }

    /** The phone now, with [foreground] as the scene last said. */
    fun read(foreground: Boolean): DeviceIndexingState {
        val device: UIDevice = UIDevice.currentDevice
        val level: Float = device.batteryLevel
        return DeviceIndexingState(
            foreground = foreground,
            thermal = thermalOf(NSProcessInfo.processInfo.thermalState.value.toInt()),
            lowPowerMode = NSProcessInfo.processInfo.isLowPowerModeEnabled(),
            // ⚠ −1 is "not known" (monitoring off, a simulator), never empty.
            batteryLevel = level.takeIf { it >= 0f },
            charging = when (device.batteryState) {
                UIDeviceBatteryState.UIDeviceBatteryStateCharging, UIDeviceBatteryState.UIDeviceBatteryStateFull -> true
                UIDeviceBatteryState.UIDeviceBatteryStateUnplugged -> false
                UIDeviceBatteryState.UIDeviceBatteryStateUnknown -> null
            },
        )
    }

    /** ⚠ A value iOS adds later is read as the hottest: an unknown is a no. */
    internal fun thermalOf(raw: Int): ThermalState = when (raw) {
        0 -> ThermalState.NOMINAL
        1 -> ThermalState.FAIR
        2 -> ThermalState.SERIOUS
        else -> ThermalState.CRITICAL
    }
}
