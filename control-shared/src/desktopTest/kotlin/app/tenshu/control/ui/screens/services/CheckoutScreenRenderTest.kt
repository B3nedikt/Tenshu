package app.tenshu.control.ui.screens.services

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import org.junit.Rule
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.assertTrue

/**
 * Renders the checkout steps — above all the acknowledgement (policy §5.4) — to PNGs under
 * `build/reports/checkout/`, on the [ServicesCardRenderTest] precedent.
 *
 * Why: the acknowledgement is the one screen standing between a customer and a charge, and it is
 * almost entirely *prose*. Assertions on displayed strings say nothing about whether that prose is
 * legible at the width it actually gets — and CE-6 found two real defects (a `2048 GB` label, a
 * 4 dp gauge) by looking at a render rather than by asserting on one. Same posture here: build
 * artifacts, no golden-image gate, weak assertions whose job is to fail if rendering breaks outright.
 */
class CheckoutScreenRenderTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun render(name: String, state: CheckoutUiState) {
        composeTestRule.setContent {
            CheckoutScreenContent(
                state = state,
                canReopen = true,
                onAcknowledgementChanged = {},
                onConfirmAcknowledgement = {},
                onReopen = {},
                onRetry = {},
                onDone = {},
                onCancel = {},
            )
        }
        val image = composeTestRule.onRoot().captureToImage().toAwtImage()
        val out = File("build/reports/checkout").apply { mkdirs() }.resolve("$name.png")
        ImageIO.write(image, "png", out)

        assertTrue(image.width > 100 && image.height > 100, "$name rendered at ${image.width}x${image.height}")
        val colours = buildSet {
            for (x in 0 until image.width step 16) {
                for (y in 0 until image.height step 16) add(image.getRGB(x, y))
            }
        }
        assertTrue(colours.size > 3, "$name looks blank — only ${colours.size} distinct colours")
    }

    @Test
    fun acknowledgeUnticked() = render("1-acknowledge-unticked", CheckoutUiState())

    @Test
    fun acknowledgeTicked() = render("2-acknowledge-ticked", CheckoutUiState(acknowledged = true))

    @Test
    fun awaitingConfirmation() = render(
        "3-awaiting",
        CheckoutUiState(step = CheckoutStep.AwaitingConfirmation, acknowledged = true),
    )

    @Test
    fun done() = render("4-done", CheckoutUiState(step = CheckoutStep.Done, acknowledged = true))
}
