package app.tenshu.control.data

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins Control's checkout acknowledgement (`checkout_ack_checkbox`, `:control-shared`) to the reading
 * the published terms take: the tick is the customer's **express request to start early**, and a
 * withdrawal owes pro-rata value (§ 357a Abs. 2 BGB). A storage plan is a digital service, so the
 * right is NOT lost (§ 356 Abs. 4). The copy used to say it was; this keeps it from drifting back,
 * and keeps the link to the withdrawal notice (§ 356 Abs. 3) on the screen. The web's twin is
 * pinned by `CheckoutPage.test.tsx`.
 *
 * ⚠ Reads the resources by path relative to the module directory (Gradle's test working directory).
 */
class CheckoutWithdrawalCopyTest {

    private val resources = File(File("..").canonicalFile, "control-shared/src/commonMain/composeResources")

    @Test
    fun englishAsksForAnEarlyStartWithProRataPayment() {
        val strings = strings("values")
        assertEquals(
            "I expressly ask Tenshu to start before the withdrawal period ends. I understand that if I withdraw, I pay for the time up to my withdrawal.",
            strings["checkout_ack_checkbox"],
        )
        assertTrue(strings.containsKey("checkout_ack_withdrawal_link"))
    }

    @Test
    fun germanAsksForAnEarlyStartWithProRataPayment() {
        val strings = strings("values-de")
        assertEquals(
            "Ich verlange ausdrücklich, dass Tenshu vor Ablauf der Widerrufsfrist mit der Leistung beginnt. Mir ist bekannt, dass ich bei einem Widerruf den Anteil des Preises für die Zeit bis zum Widerruf zahle.",
            strings["checkout_ack_checkbox"],
        )
        assertTrue(strings.containsKey("checkout_ack_withdrawal_link"))
    }

    @Test
    fun noLanguageSaysTheRightIsLost() {
        for (dir in listOf("values", "values-de")) {
            val text = strings(dir)["checkout_ack_checkbox"].orEmpty()
            assertFalse(Regex("lose|give up|verliere|aufgebe", RegexOption.IGNORE_CASE).containsMatchIn(text), "$dir: $text")
        }
    }

    @Test
    fun theCheckoutScreenLinksTheWithdrawalNotice() {
        val screen = File(File("..").canonicalFile, "control-shared/src/commonMain/kotlin/app/tenshu/control/ui/screens/services/CheckoutScreen.kt")
        assertTrue(screen.readText().contains("rememberTermsWithdrawalOpener()"), "CheckoutScreen must open TermsOfUse.withdrawalUrl")
    }

    private fun strings(dir: String): Map<String, String> {
        val file = File(resources, "$dir/strings.xml")
        assertTrue(file.isFile, "Not found: $file — update this test if it moved")
        return Regex("""<string name="([^"]+)">([^<]*)</string>""").findAll(file.readText())
            .associate { it.groupValues[1] to it.groupValues[2] }
    }
}
