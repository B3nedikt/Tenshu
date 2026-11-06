package app.tenshu.control.ui.screens.services

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import app.tenshu.control.data.PublisherAccount
import org.junit.Rule
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.assertTrue

/**
 * Renders each Services-card state to a PNG under `build/reports/services-card/`, so the CE-4
 * change can be **looked at** rather than only asserted against
 * (`commerce-end-to-end-dry-run.md` CE-4/CE-6).
 *
 * Why this exists: the other tests in this package assert that particular strings are displayed,
 * which pins behaviour but says nothing about whether the card reads sensibly — and the four states
 * are exactly the thing a reviewer wants to compare side by side before the manual pass. The images
 * are build artifacts, not committed fixtures: there is no golden-image comparison here, because a
 * pixel-diff gate on a Compose surface is a maintenance cost this project has not chosen to take on.
 *
 * The assertions are deliberately weak (non-trivial, non-blank output) — their job is to fail if
 * rendering breaks outright, not to police layout.
 */
class ServicesCardRenderTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val oneGib = 1L * 1024 * 1024 * 1024

    private fun render(name: String, state: ServicesCardState, manage: Boolean = false) {
        composeTestRule.setContent {
            ServicesScreenContent(
                cardState = state,
                onSignUp = {},
                onSignIn = {},
                onGetMoreStorage = {},
                onChangeIdentityMethod = {},
                onDisconnect = {},
                onManageSubscription = if (manage) ({}) else null,
            )
        }
        val image = composeTestRule.onRoot().captureToImage().toAwtImage()
        val out = File("build/reports/services-card").apply { mkdirs() }.resolve("$name.png")
        ImageIO.write(image, "png", out)

        assertTrue(image.width > 100 && image.height > 100, "$name rendered at ${image.width}x${image.height}")
        // A uniformly-coloured surface means the card did not draw; sample a grid rather than the
        // whole bitmap so the check stays cheap.
        val colours = buildSet {
            for (x in 0 until image.width step 16) {
                for (y in 0 until image.height step 16) add(image.getRGB(x, y))
            }
        }
        assertTrue(colours.size > 3, "$name looks blank — only ${colours.size} distinct colours")
    }

    @Test
    fun emptyState() = render(
        "1-empty",
        ServicesCardState.EMPTY,
    )

    @Test
    fun syncedOnly_pairedDeviceThatNeverSignedIn() = render(
        "2-synced-only",
        ServicesCardState(
            storage = SyncedStorage(
                usedBytes = 34L * 1024 * 1024,
                limitBytes = 5 * oneGib,
                plan = SyncedPlanTier.Free,
            ),
            identity = null,
        ),
    )

    @Test
    fun signedInWithPaidStorage() = render(
        "3-signed-in-premium",
        ServicesCardState(
            storage = SyncedStorage(
                usedBytes = 412L * 1024 * 1024 * 1024,
                limitBytes = 2L * 1024 * oneGib,
                plan = SyncedPlanTier.Premium,
            ),
            identity = PublisherAccount.IdentityMethod.Email("benedikt@example.com"),
        ),
        manage = true,
    )

    @Test
    fun signedInBeforeTheEntitlementSynced() = render(
        "4-storage-pending",
        ServicesCardState(
            storage = null,
            identity = PublisherAccount.IdentityMethod.Email("benedikt@example.com"),
        ),
    )
}
