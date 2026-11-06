package org.tenshu.routine.common

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.tenshu.routine.common.engine.DevicePresence
import org.tenshu.routine.common.engine.PresenceResolver
import org.tenshu.routine.common.program.Occupancy

class PresenceResolverTest {

    private val staleWindowMs = 2L * 60 * 60 * 1000
    private val resolver = PresenceResolver(staleWindowMs)
    private val now = 1_782_000_000_000L

    private fun presence(
        deviceId: String,
        state: String,
        ageMs: Long = 0,
    ) = DevicePresence(deviceId, state, freshAsOfEpochMs = now - ageMs)

    private fun resolve(
        vararg contributors: DevicePresence,
        excluded: Set<String> = emptySet(),
    ) = resolver.resolve(contributors.toList(), excluded, now)

    // ------------------------------------------------------------ the happy paths

    @Test
    fun everyone_out_means_nobody_home() {
        assertEquals(
            Occupancy.NOBODY_HOME,
            resolve(presence("phone-1", "outside"), presence("phone-2", "outside")),
        )
    }

    @Test
    fun one_device_inside_means_somebody_home() {
        assertEquals(
            Occupancy.SOMEBODY_HOME,
            resolve(presence("phone-1", "outside"), presence("phone-2", "inside")),
        )
    }

    // ------------------------------------------------------------ fail closed

    /**
     * ⚠⚠ The assertion this whole class exists for. A phone that died must never
     * be read as "not home" — that is the alarm-arms-while-you-are-asleep bug.
     */
    @Test
    fun a_stale_contributor_makes_the_verdict_unknown() {
        assertNull(
            resolve(
                presence("phone-1", "outside"),
                presence("phone-2", "outside", ageMs = staleWindowMs + 1),
            ),
        )
    }

    @Test
    fun an_explicitly_unknown_contributor_makes_the_verdict_unknown() {
        assertNull(resolve(presence("phone-1", "outside"), presence("phone-2", "unknown")))
    }

    /** ⚠ A household that never enabled location has NOT told us the house is empty. */
    @Test
    fun no_contributors_at_all_is_unknown_not_nobody_home() {
        assertNull(resolve())
    }

    @Test
    fun an_unrecognised_state_string_is_unknown() {
        assertNull(resolve(presence("phone-1", "probably-out")))
    }

    /** Staleness is judged per device, so one fresh reporter cannot cover for a stale one. */
    @Test
    fun a_fresh_inside_does_not_rescue_a_stale_peer() {
        assertNull(
            resolve(
                presence("phone-1", "inside"),
                presence("phone-2", "outside", ageMs = staleWindowMs + 1),
            ),
        )
    }

    @Test
    fun a_row_exactly_at_the_window_is_still_trusted() {
        assertEquals(
            Occupancy.NOBODY_HOME,
            resolve(presence("phone-1", "outside", ageMs = staleWindowMs)),
        )
    }

    // ------------------------------------------------------------ exclusions

    /**
     * ⚠ A hub sits in the hall and is definitionally always home — including it
     * would make a nobody's-home condition permanently false.
     */
    @Test
    fun an_excluded_hub_does_not_keep_the_house_occupied() {
        assertEquals(
            Occupancy.NOBODY_HOME,
            resolve(
                presence("hub-1", "inside"),
                presence("phone-1", "outside"),
                excluded = setOf("hub-1"),
            ),
        )
    }

    /** ⚠ A revoked phone's last known position must not keep deciding the house. */
    @Test
    fun an_excluded_revoked_device_is_ignored_even_when_stale() {
        assertEquals(
            Occupancy.NOBODY_HOME,
            resolve(
                presence("old-phone", "inside", ageMs = staleWindowMs * 10),
                presence("phone-1", "outside"),
                excluded = setOf("old-phone"),
            ),
        )
    }

    @Test
    fun excluding_every_contributor_is_unknown() {
        assertNull(
            resolve(presence("hub-1", "inside"), excluded = setOf("hub-1")),
        )
    }
}
