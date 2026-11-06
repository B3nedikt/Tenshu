package app.tenshu.ui.legal

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import app.tenshu.ui.theme.TenshuTheme
import kotlin.test.Test
import kotlin.test.assertEquals

/** The shared notices screen (`docs/oss-licences.md`) over each [OssLicencesState]. */
@OptIn(ExperimentalTestApi::class)
class OpenSourceLicencesScreenTest {

    private val json = """
        {"libraries":[
          {"uniqueId":"io.ktor:ktor-client-core","artifactVersion":"3.6.0","name":"ktor-client-core",
           "developers":[],"licenses":["Apache-2.0"]},
          {"uniqueId":"org.jetbrains.kotlinx:kotlinx-coroutines-core","artifactVersion":"1.11.0",
           "name":"kotlinx-coroutines-core","developers":[],"licenses":["Apache-2.0"]}
        ],
        "licenses":{"Apache-2.0":{"name":"Apache License 2.0","url":"https://spdx.org/licenses/Apache-2.0.html",
          "content":"Apache License Version 2.0, January 2004","hash":"Apache-2.0","spdxId":"Apache-2.0"}}}
    """.trimIndent()

    @Test
    fun parsingKeepsNamesVersionsAndLicenceTexts() {
        val parsed = parseOssLicences(json)
        assertEquals(listOf("kotlinx-coroutines-core", "ktor-client-core"), parsed.map { it.name })
        assertEquals("1.11.0", parsed.first().version)
        assertEquals("Apache License Version 2.0, January 2004", parsed.first().licences.single().text)
    }

    @Test
    fun aLoadedListShowsEachLibraryAndItsLicenceText() = runComposeUiTest {
        setContent {
            TenshuTheme {
                OpenSourceLicencesContent(
                    OssLicencesState.Loaded(parseOssLicences(json)),
                    onBack = {},
                    dataAttributions = DataAttributions.Basemap,
                )
            }
        }
        onNodeWithText("Map data © OpenStreetMap contributors", substring = true).assertExists()
        onNodeWithText("ktor-client-core 3.6.0").assertExists()
        onNodeWithText("kotlinx-coroutines-core 1.11.0").performClick()
        onNodeWithText("Apache License Version 2.0, January 2004", substring = true).assertExists()
    }

    @Test
    fun aBuildWithoutTheListSaysSoRatherThanShowingNothing() = runComposeUiTest {
        setContent { TenshuTheme { OpenSourceLicencesContent(OssLicencesState.Missing, onBack = {}) } }
        onNodeWithText("This build does not include its licence list.").assertExists()
    }
}
