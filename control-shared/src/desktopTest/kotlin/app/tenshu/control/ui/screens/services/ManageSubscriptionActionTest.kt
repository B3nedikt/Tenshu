package app.tenshu.control.ui.screens.services

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** The billing-portal button follows the store-build gate, not only whether a browser exists. */
class ManageSubscriptionActionTest {
    private val openPortal: () -> Unit = {}

    @Test
    fun hidden_inAStoreBuild_evenWithABrowser() {
        assertNull(manageSubscriptionAction(showManageSubscription = false, canOpenBrowser = true, openPortal))
    }

    @Test
    fun hidden_withoutABrowser() {
        assertNull(manageSubscriptionAction(showManageSubscription = true, canOpenBrowser = false, openPortal))
    }

    @Test
    fun offered_whenAllowedAndABrowserCanOpenIt() {
        assertNotNull(manageSubscriptionAction(showManageSubscription = true, canOpenBrowser = true, openPortal))
    }
}
