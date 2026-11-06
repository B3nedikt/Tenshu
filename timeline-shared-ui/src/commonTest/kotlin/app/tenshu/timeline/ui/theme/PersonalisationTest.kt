package app.tenshu.timeline.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PersonalisationTest {

    private val primary = Color(0xFF5A4A96)          // Amethyst primary
    private val primaryContainer = Color(0xFFE6DEFF) // Amethyst primaryContainer
    private val dominant = "#7a9a6d"

    @Test
    fun strengthZero_isPureAmethystNoOp() {
        val p = personalise(dominant, strength = 0f, primary, primaryContainer)
        assertEquals(primary, p.accent)
        assertEquals(primaryContainer, p.soft)
        assertNull(p.tint)
    }

    @Test
    fun nullColor_isNoOp() {
        val p = personalise(null, strength = 0.9f, primary, primaryContainer)
        assertEquals(primary, p.accent)
        assertEquals(primaryContainer, p.soft)
        assertNull(p.tint)
    }

    @Test
    fun withColor_shiftsAccentAndSoft() {
        val p = personalise(dominant, strength = 0.7f, primary, primaryContainer)
        assertNotEquals(primary, p.accent)
        assertNotEquals(primaryContainer, p.soft)
    }

    @Test
    fun tint_onlyAbove0_4() {
        assertNull(personalise(dominant, strength = 0.3f, primary, primaryContainer).tint)
        assertTrue(personalise(dominant, strength = 0.5f, primary, primaryContainer).tint != null)
    }

    @Test
    fun parseHex_validAndInvalid() {
        assertNotEquals(null, parseHexColor("#7a9a6d"))
        assertNotEquals(null, parseHexColor("7a9a6d"))
        assertNull(parseHexColor("nope"))
        assertNull(parseHexColor("#12"))
    }
}
