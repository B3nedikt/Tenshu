package app.tenshu.control

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.v2.runComposeUiTest
import app.tenshu.ui.legal.OpenSourceLicencesContent
import app.tenshu.ui.legal.OssLicencesState
import app.tenshu.ui.legal.parseOssLicences
import app.tenshu.ui.theme.TenshuTheme
import kotlin.test.Test
import kotlin.test.fail

/**
 * Control desktop's REAL notices on the shared screen: the PDFBox row opens a dialog that shows its
 * Apache-2.0 §4(d) NOTICE after the licence text (`docs/oss-licences.md` §1.1).
 */
@OptIn(ExperimentalTestApi::class)
class OssLicencesNoticeScreenTest {

    @Test
    fun pdfboxShowsItsNoticeOnTheLicencesScreen() = runComposeUiTest {
        val json = javaClass.getResourceAsStream("/aboutlibraries.json")?.bufferedReader()?.use { it.readText() }
            ?: fail("/aboutlibraries.json is not on the classpath")
        setContent {
            TenshuTheme { OpenSourceLicencesContent(OssLicencesState.Loaded(parseOssLicences(json)), onBack = {}) }
        }
        onNode(hasScrollAction()).performScrollToNode(hasText("Apache PDFBox 3.0.8"))
        onNodeWithText("Apache PDFBox 3.0.8").performClick()
        onNodeWithText("Copyright 2002-2026 The Apache Software Foundation", substring = true).assertExists()
        onNodeWithText("Based on source code contributed to the original PDFBox project", substring = true).assertExists()
    }
}
