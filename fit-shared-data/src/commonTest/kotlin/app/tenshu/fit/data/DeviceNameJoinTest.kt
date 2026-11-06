package app.tenshu.fit.data

import app.tenshu.fit.model.HealthSourceInput
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * DS-7 — the roster join that upgrades a stamped device label to the name the user actually gave
 * that device in Control.
 *
 * ⚠⚠ The rule under test is that the join is an **upgrade, never a gate**. `HealthSource
 * .deviceLabel` is stamped from the platform name at register time and is always renderable; the
 * roster replaces it only where it answers. This matters because an empty roster is
 * *indistinguishable* from "that device is gone" — revocation **deletes** the `Device` row rather
 * than flagging it — so absence must never remove or annotate a row (§17, Q2).
 */
class DeviceNameJoinTest {

    private fun source(deviceId: String, label: String) = HealthSourceInput(
        kind = "HEALTH_CONNECT",
        deviceId = deviceId,
        deviceLabel = label,
        autoImport = true,
    )

    @Test
    fun aRosterNameReplacesTheStampedLabel() {
        val out = listOf(source("device-A", "Pixel 8")).withRosterNames(mapOf("device-A" to "Work phone"))
        assertEquals("Work phone", out.single().deviceLabel)
    }

    @Test
    fun anUnknownDeviceKeepsItsStamp() {
        val out = listOf(source("device-A", "Pixel 8")).withRosterNames(emptyMap())
        assertEquals(
            "Pixel 8",
            out.single().deviceLabel,
            "an unread roster must not blank a label that was always renderable",
        )
    }

    /** ⚠ Per-device, not all-or-nothing: one device synced, one not, both still render. */
    @Test
    fun aPartlySyncedRosterUpgradesOnlyWhatItKnows() {
        val out = listOf(source("device-A", "Pixel 8"), source("device-B", "iPhone 15"))
            .withRosterNames(mapOf("device-B" to "Benedikt's iPhone"))
        assertEquals(listOf("Pixel 8", "Benedikt's iPhone"), out.map { it.deviceLabel })
    }
}
