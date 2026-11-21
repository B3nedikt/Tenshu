package app.tenshu.fit.ui.components

import androidx.compose.material3.Text
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import app.tenshu.ui.format.LocaleNumbers
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Fit's figures read in the user's language: German groups with `.` and decimals with `,`
 * (`4.315` Schritte, `3,2 km`), English the other way round, French with a no-break space.
 * Rendered through Fit's real formatters, which go through `LocaleNumbers` (`:ui-core`).
 */
@OptIn(ExperimentalTestApi::class)
class FitNumberLocaleTest {

    private fun rendered(locale: Locale): List<String> {
        val previous = Locale.getDefault()
        Locale.setDefault(locale)
        try {
            var out: List<String> = emptyList()
            runDesktopComposeUiTest {
                setContent {
                    val energy = energyValueUnit(18_050)
                    val text = listOf(
                        groupThousands(4315),
                        distanceText(3200),
                        distanceText(540),
                        "${energy.value} ${energy.unit}",
                        oneDecimal(72.46),
                        groupThousands(1_234_567),
                        LocaleNumbers.bytes(1_610_612_736L), // 1.5 GiB — the one byte formatter, binary steps,
                    ).joinToString("|")
                    Text(text, Modifier.testTag("out"))
                }
                waitForIdle()
                out = onNodeWithTag("out").fetchSemanticsNode()
                    .config[SemanticsProperties.Text].single().text.split("|")
            }
            return out
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun englishUsesCommaGroupingAndPointDecimals() {
        assertEquals(
            listOf("4,315", "3.2 km", "540 m", "4,314 kcal", "72.5", "1,234,567", "1.5 GB"),
            rendered(Locale.ENGLISH),
        )
    }

    @Test
    fun germanUsesPointGroupingAndCommaDecimals() {
        assertEquals(
            listOf("4.315", "3,2 km", "540 m", "4.314 kcal", "72,5", "1.234.567", "1,5 GB"),
            rendered(Locale.GERMAN),
        )
    }

    @Test
    fun frenchGroupsWithANoBreakSpaceAndDecimalsWithAComma() {
        // CLDR French: narrow no-break space between thousands. No `values-fr` strings exist,
        // and the figures follow the locale anyway — nothing per language to translate.
        val nnbsp = '\u202F'
        assertEquals(
            listOf("4${nnbsp}315", "3,2 km", "540 m", "4${nnbsp}314 kcal", "72,5", "1${nnbsp}234${nnbsp}567", "1,5 GB"),
            rendered(Locale.FRANCE),
        )
    }
}
