package app.tenshu.drive.integration

import app.tenshu.drive.integration.utils.startAppIntegrationKoin
import org.junit.Test
import org.koin.core.context.GlobalContext
import kotlin.test.assertSame

/**
 * Pins the property `startAppIntegrationKoin` exists to have: the app graph lives in a
 * container of the caller's own, **not** in `GlobalContext`.
 *
 * ⚠ A guard, not a scenario — nothing here exercises Drive. It is here because the alternative
 * is a comment, and a comment cannot fail. Re-globalising the appKoin (the one-line change of
 * swapping `koinApplication` back to `startKoin`) turns this red.
 *
 * ⚠⚠ Deliberately asserts that starting a appKoin does not **change** the global slot, rather
 * than that the slot is null. The latter would be order-dependent: the two Compose tests in
 * this source set legitimately install a global container, so a null-assertion would go red for
 * *their* teardown — the exact misattribution shape this suite has been fighting
 * (`docs/tech-debt.md`).
 *
 * ⚠⚠ There is deliberately **no** "two containers are separate worlds" test here, because they
 * are not — see [startAppIntegrationKoin]'s own warning. A private container is not the same
 * thing as an isolated graph.
 */
class AppIntegrationKoinIsolationTest {

    @Test
    fun startingAHarnessDoesNotTouchTheGlobalContext() {
        val before = GlobalContext.getOrNull()
        val appKoin = startAppIntegrationKoin(clientId = "app.tenshu.drive.isolation")
        try {
            assertSame(
                before,
                GlobalContext.getOrNull(),
                "startAppIntegrationKoin must not install into GlobalContext — use " +
                    "startGlobalAppIntegrationKoin if a test genuinely needs the global slot",
            )
        } finally {
            appKoin.stop()
        }
    }
}
