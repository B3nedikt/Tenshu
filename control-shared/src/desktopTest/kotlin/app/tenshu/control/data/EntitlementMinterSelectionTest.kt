package app.tenshu.control.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The dev-key release guard (`commerce-accounts-and-sessions.md` §9.3): the in-process dev-seed
 * minter (signs as `tenshu.dev`) may be selected only in a non-release build, even with the billing
 * mock requested.
 */
class EntitlementMinterSelectionTest {

    @Test
    fun devSeed_onlyInNonReleaseMock() {
        assertTrue(EntitlementMinterSelection.useDevSeed(billingMock = true, isRelease = false))
    }

    @Test
    fun release_neverUsesDevSeed_evenWithMock() {
        assertFalse(
            EntitlementMinterSelection.useDevSeed(billingMock = true, isRelease = true),
            "a release build must never self-sign with the dev key",
        )
    }

    @Test
    fun noMock_neverUsesDevSeed() {
        assertEquals(false, EntitlementMinterSelection.useDevSeed(billingMock = false, isRelease = false))
        assertEquals(false, EntitlementMinterSelection.useDevSeed(billingMock = false, isRelease = true))
    }
}
