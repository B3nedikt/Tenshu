package app.tenshu.control.android.server

import app.tenshu.control.data.BundledAppCatalogue
import app.tenshu.control.data.ClientBinding
import org.junit.Assert.assertEquals
import org.junit.Test
import org.tenshu.core.common.transport.FirstPartySigning

/**
 * ⚠⚠ `ProxyService` admits only the packages in [FirstPartySigning] (threat model T-C-20). An app
 * added to Control's catalogue without a pinned signing key would be refused by Control on every
 * call, and nothing before a device would say so. So the two lists must agree.
 */
class FirstPartyCallerCoverageTest {

    @Test
    fun everyAndroidAppInTheCatalogueHasAPinnedSigningKey() {
        val catalogue = BundledAppCatalogue.apps
            .flatMap { it.clients }
            .filterIsInstance<ClientBinding.AndroidPackage>()
            .map { it.packageName }
            .toSet()
        // Control is the one first-party package that is not a client of itself.
        assertEquals(catalogue + FirstPartySigning.CONTROL_PACKAGE, FirstPartySigning.PACKAGES)
    }
}
