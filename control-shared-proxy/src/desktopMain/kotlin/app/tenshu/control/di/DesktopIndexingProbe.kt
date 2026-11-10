package app.tenshu.control.di

import app.tenshu.windows.WindowsPowerProbe
import org.tenshu.inference.proxy.DesktopIndexingConditions

/**
 * The machine facts [DesktopIndexingConditions] reads, per desktop OS. macOS is the conditions'
 * own default (`MacPowerProbe`), so only Windows is supplied from here; a Linux host keeps that
 * default too, which answers unknown and so never schedules an index.
 */
internal fun desktopIndexingConditions(osName: String = System.getProperty("os.name").orEmpty()): DesktopIndexingConditions =
    windowsIndexingProbeFor(osName)?.let { DesktopIndexingConditions(probe = it) } ?: DesktopIndexingConditions()

/** [WindowsIndexingProbe] on Windows, `null` (the conditions' default) everywhere else. */
internal fun windowsIndexingProbeFor(osName: String): DesktopIndexingConditions.Probe? =
    if (osName.startsWith("Windows", ignoreCase = true)) WindowsIndexingProbe else null

/** `GetSystemPowerStatus` + `GetLastInputInfo`, via `:windows-helpers`. Null still means unknown. */
internal object WindowsIndexingProbe : DesktopIndexingConditions.Probe {
    override fun onExternalPower() = WindowsPowerProbe.onExternalPower()
    override fun batteryLow() = WindowsPowerProbe.batteryLow()
    override fun secondsSinceLastInput() = WindowsPowerProbe.secondsSinceLastInput()
}
