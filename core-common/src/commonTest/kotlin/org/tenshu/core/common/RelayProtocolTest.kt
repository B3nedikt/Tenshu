package org.tenshu.core.common

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the invariants of the relay-protocol contract (`core-schema-evolution.md` §7.5,
 * as built §7.8). These are the properties the relay's 426 gate is built on, so they are
 * asserted here rather than left to the KDoc.
 */
class RelayProtocolTest {

    @Test
    fun supportedRangeIsDerivedFromTheTwoConstants() {
        assertEquals(RELAY_PROTOCOL_MIN_SUPPORTED_VERSION, RELAY_PROTOCOL_SUPPORTED_RANGE.first)
        assertEquals(RELAY_PROTOCOL_VERSION, RELAY_PROTOCOL_SUPPORTED_RANGE.last)
        assertTrue(
            RELAY_PROTOCOL_VERSION in RELAY_PROTOCOL_SUPPORTED_RANGE,
            "a relay must accept clients built at its own protocol version"
        )
    }

    /**
     * ⚠ The permanent floor rule. [RELAY_PROTOCOL_MIN_SUPPORTED_VERSION] must never drop
     * below [RELAY_PROTOCOL_FENCING_FLOOR_VERSION] — clients older than that have no
     * client-side epoch fence (E2d), so the relay gate is their only fence and admitting
     * them corrupts data. Lowering it is a bug, not a config choice; this test is the
     * tripwire.
     */
    @Test
    fun minSupportedNeverDropsBelowTheVersionClientSideFencingShippedAt() {
        assertTrue(
            RELAY_PROTOCOL_MIN_SUPPORTED_VERSION >= RELAY_PROTOCOL_FENCING_FLOOR_VERSION,
            "min supported ($RELAY_PROTOCOL_MIN_SUPPORTED_VERSION) is below the fencing floor " +
                "($RELAY_PROTOCOL_FENCING_FLOOR_VERSION) — see core-schema-evolution.md §7.5"
        )
        // The floor is the protocol version E2e shipped as, and it is frozen at that value.
        assertEquals(1, RELAY_PROTOCOL_FENCING_FLOOR_VERSION)
    }
}
