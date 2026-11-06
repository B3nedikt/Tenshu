package app.tenshu.hub

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * HD-D1: the pure quantised change-gate + per-device rate limit that keeps a
 * chatty sensor from flooding the realm with sync blocks — sub-quantum drift
 * publishes nothing, quantum crossings publish, and even crossings are held
 * to the per-device minimum publish interval (then flushed, never lost —
 * the flush path is the host's job, pinned by the system test).
 */
class DeviceStatePublishGateTest {

    private val t0 = 1_000_000L

    private fun sensor(tempDeciC: Int, battery: Int = 90) = BridgeDeviceState(
        deviceId = "simulated:sensor-1",
        online = true,
        temperatureDeciC = tempDeciC,
        humidityPercent = 45,
        batteryPercent = battery,
    )

    private fun gate(minIntervalMs: Long = 10_000, heartbeatMs: Long = 3_600_000) =
        DeviceStatePublishGate(minIntervalMs = minIntervalMs, heartbeatMs = heartbeatMs)

    @Test
    fun firstStateAlwaysPublishes() {
        assertEquals(
            DeviceStatePublishGate.Decision.Publish,
            gate().decide(sensor(215), t0),
        )
    }

    @Test
    fun subQuantumDrift_emitsNoRow() {
        val gate = gate()
        gate.decide(sensor(215), t0)
        // 21.5 → 21.9 °C wanders inside one 0.5 °C quantum (215..219 ÷ 5 = 43)
        // — every re-report is a NoChange, even far outside the min interval.
        var t = t0
        for (temp in intArrayOf(216, 217, 218, 219, 218, 215)) {
            t += 60_000
            assertEquals(
                DeviceStatePublishGate.Decision.NoChange,
                gate.decide(sensor(temp), t),
                "temp $temp must stay inside the 0.5°C quantum",
            )
        }
    }

    @Test
    fun crossingTheQuantum_publishes() {
        val gate = gate()
        gate.decide(sensor(215), t0)
        assertEquals(
            DeviceStatePublishGate.Decision.Publish,
            gate.decide(sensor(220), t0 + 60_000), // 22.0 °C — next 0.5 °C step
        )
    }

    @Test
    fun crossingInsideTheMinInterval_isRateLimited_thenPublishesAfterIt() {
        val gate = gate(minIntervalMs = 10_000)
        gate.decide(sensor(215), t0)
        assertEquals(
            DeviceStatePublishGate.Decision.RateLimited,
            gate.decide(sensor(225), t0 + 5_000),
            "a real change inside the interval floor must be held",
        )
        assertEquals(t0 + 10_000, gate.dueAtMs("simulated:sensor-1"))
        assertEquals(
            DeviceStatePublishGate.Decision.Publish,
            gate.decide(sensor(225), t0 + 10_000),
            "the held change publishes once the interval elapsed",
        )
    }

    @Test
    fun unchangedState_publishesAgainOnceTheHeartbeatIsDue() {
        val gate = gate(heartbeatMs = 60_000)
        gate.decide(sensor(215), t0)
        assertEquals(
            DeviceStatePublishGate.Decision.NoChange,
            gate.decide(sensor(215), t0 + 59_999),
        )
        assertEquals(
            DeviceStatePublishGate.Decision.Publish,
            gate.decide(sensor(215), t0 + 60_000),
            "bounded staleness: an unchanged row still refreshes each heartbeat",
        )
    }

    @Test
    fun forceAlwaysPublishes_andRebasesTheInterval() {
        val gate = gate(minIntervalMs = 10_000)
        gate.decide(sensor(215), t0)
        // Command outcome right after a publish: force wins over the interval.
        assertEquals(
            DeviceStatePublishGate.Decision.Publish,
            gate.decide(sensor(215), t0 + 1, force = true),
        )
        assertEquals(t0 + 1 + 10_000, gate.dueAtMs("simulated:sensor-1"))
    }

    @Test
    fun onlineFlip_isNeverQuantisedAway() {
        val gate = gate()
        gate.decide(sensor(215), t0)
        assertEquals(
            DeviceStatePublishGate.Decision.Publish,
            gate.decide(sensor(215).copy(online = false), t0 + 60_000),
        )
    }

    @Test
    fun devicesAreRateLimitedIndependently() {
        val gate = gate(minIntervalMs = 10_000)
        gate.decide(sensor(215), t0)
        val light = BridgeDeviceState(deviceId = "simulated:light-1", powerOn = false)
        assertEquals(
            DeviceStatePublishGate.Decision.Publish,
            gate.decide(light, t0 + 1),
            "another device's fresh state is not held by the sensor's interval",
        )
    }
}
