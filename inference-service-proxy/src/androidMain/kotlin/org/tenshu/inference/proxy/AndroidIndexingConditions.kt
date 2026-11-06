package org.tenshu.inference.proxy

import android.content.Context
import android.os.BatteryManager
import android.os.PowerManager

/**
 * Android [IndexingConditions]: charging, battery not low, screen off.
 *
 * ⚠ **"Not in use" is the screen being off**, which is weaker than WorkManager's device-idle (a doze
 * maintenance window). That is deliberate for the in-process path: the property IN-D6 asks for is
 * that nobody is holding the device, and a screen-off phone on a charger is that — while waiting for
 * doze would leave a proxy that is already awake idling through the night.
 *
 * ⚠ Low battery is read as the OS threshold WorkManager's `BATTERY_NOT_LOW` uses (15 %), from the
 * capacity property rather than a sticky broadcast: one binder call, no receiver.
 */
class AndroidIndexingConditions(private val context: Context) : IndexingConditions {

    override fun allowIndexing(): Boolean {
        val battery = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        return indexingConditionsAllow(
            onExternalPower = battery?.isCharging,
            batteryLow = batteryLow(battery),
            inUse = power?.isInteractive,
        )
    }

    /**
     * ⚠ Charging and screen-off are dropped; a low battery is not. ⚠⚠ Unknown battery reads as
     * **allowed** here, unlike the scheduled bar: the user asked, and refusing on a signal this
     * device cannot produce would make the button do nothing with nothing to say.
     */
    override fun allowManualIndexing(): Boolean =
        batteryLow(context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager) != true

    /** ⚠ `Int.MIN_VALUE` / 0 is "not supported" on some devices — unknown, so not "not low". */
    private fun batteryLow(battery: BatteryManager?): Boolean? =
        battery?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            ?.takeIf { it > 0 }
            ?.let { it <= LOW_BATTERY_PERCENT }

    private companion object {
        const val LOW_BATTERY_PERCENT = 15
    }
}
