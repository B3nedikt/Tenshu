package app.tenshu.control.ui

import app.tenshu.control.ui.navigation.Destination
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The deep-link start-destination rule (storage-capacity-gating.md Phase 5): a
 * deep link applies only when the app is Active (Home); Locked / FirstTime
 * landings take precedence so a deep link can't bypass unlock or onboarding.
 */
class StartDestinationTest {

    @Test
    fun deepLink_appliesWhenActive() {
        assertEquals(
            Destination.Services,
            resolveStartDestination(landing = Destination.Home, deepLink = Destination.Services),
        )
    }

    @Test
    fun deepLink_ignoredWhenLocked() {
        assertEquals(
            Destination.Locked,
            resolveStartDestination(landing = Destination.Locked, deepLink = Destination.Services),
        )
    }

    @Test
    fun deepLink_ignoredWhenFirstTime() {
        assertEquals(
            Destination.Welcome,
            resolveStartDestination(landing = Destination.Welcome, deepLink = Destination.Services),
        )
    }

    @Test
    fun noDeepLink_usesLanding() {
        assertEquals(
            Destination.Home,
            resolveStartDestination(landing = Destination.Home, deepLink = null),
        )
    }
}

/**
 * The soft app-lock gate engages only over the active app UI and never during
 * onboarding: destinations pushed from Welcome (the first-run PairingWizard) keep
 * Welcome on the back stack, which marks the whole first-run flow as exempt.
 */
class AppLockGateActiveTest {

    @Test
    fun engagesOverActiveAppUi() {
        assertEquals(
            true,
            appLockGateActive(showNav = true, backStack = listOf(Destination.Home)),
        )
    }

    @Test
    fun neverEngagesWhenNavHidden() {
        assertEquals(
            false,
            appLockGateActive(showNav = false, backStack = listOf(Destination.Welcome)),
        )
    }

    @Test
    fun exemptsPairingWizardReachedFromWelcome() {
        assertEquals(
            false,
            appLockGateActive(
                showNav = true,
                backStack = listOf(Destination.Welcome, Destination.PairingWizard),
            ),
        )
    }

    @Test
    fun engagesForPairingWizardFromSyncDashboard() {
        assertEquals(
            true,
            appLockGateActive(
                showNav = true,
                backStack = listOf(Destination.Settings, Destination.Sync, Destination.PairingWizard),
            ),
        )
    }
}
