package app.tenshu.drive.ui.common

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import app.tenshu.drive.data.FileIcon
import app.tenshu.drive.ui.model.FileUiModel
import app.tenshu.drive.ui.navigation.Destination
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import org.jetbrains.compose.resources.stringResource

/**
 * A German launch draws the Files screen's chrome in German: the rail labels and a folder row's
 * child count come from resources, not from English literals (store-screenshots.md §10 found them
 * English beside German resource text).
 */
@OptIn(ExperimentalTestApi::class)
class FileItemGermanCopyTest {

    private fun folder(count: Long?) = FileUiModel(
        path = "/Fotos", name = "Fotos", subLine = "$count files", previewIcon = FileIcon.Folder,
        isFolder = true, childCount = count,
    )

    private fun <T> inLocale(locale: Locale, block: () -> T): T {
        val previous = Locale.getDefault()
        Locale.setDefault(locale)
        try { return block() } finally { Locale.setDefault(previous) }
    }

    private fun render(locale: Locale): List<String> = inLocale(locale) {
        var out = emptyList<String>()
        runDesktopComposeUiTest {
            setContent {
                out = listOf(
                    folder(1).supportingText(),
                    folder(3).supportingText(),
                    stringResource(Destination.Home.label),
                    stringResource(Destination.Files.label),
                    stringResource(Destination.Shared.label),
                )
            }
            waitForIdle()
        }
        out
    }

    @Test
    fun german() {
        assertEquals(listOf("1 Datei", "3 Dateien", "Start", "Dateien", "Freigegeben"), render(Locale.GERMANY))
    }

    @Test
    fun english() {
        assertEquals(listOf("1 file", "3 files", "Home", "Files", "Shared"), render(Locale.US))
    }

    @Test
    fun aRowWithNoCountKeepsItsSubLine() {
        var out = ""
        runDesktopComposeUiTest {
            setContent { out = FileUiModel("/a.txt", "a.txt", "4 KB", FileIcon.GenericFile).supportingText() }
            waitForIdle()
        }
        assertEquals("4 KB", out)
    }
}
