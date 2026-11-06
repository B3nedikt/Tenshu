package app.tenshu.ui.layout

import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

/** The shared desktop window rule (`ui-agent.md` §8): a first launch sized to the screen, centred. */
class WindowStatePersistenceTest {

    @Test
    fun theMacBookItWasWrittenForOpensAt1382By884() {
        // ⚠ Measured: AWT's usable area on a 16" MacBook Pro is 1728 × 982 pt (menu bar 33, Dock 102).
        assertEquals(DpSize(1382.dp, 884.dp), firstLaunchWindowSize(DpSize(1728.dp, 982.dp)))
    }

    @Test
    fun neverBelowTheOldFixedSize_unlessTheScreenIsSmaller() {
        assertEquals(DpSize(1024.dp, 768.dp), firstLaunchWindowSize(DpSize(1280.dp, 853.dp)))
        assertEquals(DpSize(1024.dp, 705.dp), firstLaunchWindowSize(DpSize(1280.dp, 705.dp)), "a short screen caps the height")
        assertEquals(DpSize(900.dp, 600.dp), firstLaunchWindowSize(DpSize(900.dp, 600.dp)), "never larger than the screen")
    }

    @Test
    fun aLargeDisplayIsCapped() {
        assertEquals(MAX_FIRST_LAUNCH_SIZE, firstLaunchWindowSize(DpSize(2560.dp, 1415.dp)))
    }

    @Test
    fun noScreenIsTheOldFixedSize() {
        assertEquals(MIN_FIRST_LAUNCH_SIZE, firstLaunchWindowSize(null))
    }

    @Test
    fun aFirstLaunchIsCentred_andASizeTheUserSetWins() {
        val dir = Files.createTempDirectory("window-state").toFile()
        try {
            val first = loadWindowState(File(dir, "photos.window.properties"), 1382.dp, 884.dp)
            assertEquals(DpSize(1382.dp, 884.dp), first.size)
            assertEquals(WindowPosition(Alignment.Center), first.position)
            assertEquals(WindowPlacement.Floating, first.placement)

            val saved = File(dir, "drive.window.properties").apply {
                writeText("width=1187.0\nheight=853.0\nx=322.0\ny=75.0\nplacement=Floating\n")
            }
            val restored = loadWindowState(saved, 1382.dp, 884.dp)
            assertEquals(DpSize(1187.dp, 853.dp), restored.size, "the user's size, not the rule's")
            assertEquals(WindowPosition(322.dp, 75.dp), restored.position)
        } finally {
            dir.deleteRecursively()
        }
    }
}
