package app.tenshu.ui.map

import androidx.compose.foundation.layout.Box
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import app.tenshu.ui.theme.TenshuTheme
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The ODbL credit on every map that draws OpenStreetMap data (`docs/oss-licences.md` §3).
 *
 * [BasemapChrome] is the only attribution a Tenshu map carries — both live renders hide MapLibre's
 * own attribution button — so the credit lives or dies here. The schematic sketch draws no OSM
 * data, so it must not claim any, and the desktop's Apple Maps snapshot is Apple's data.
 */
@OptIn(ExperimentalTestApi::class)
class BasemapAttributionTest {

    @Test
    fun liveTilesCarryTheOpenStreetMapCredit() = runComposeUiTest {
        setContent { TenshuTheme { Box { OsmDataContent { BasemapChrome() } } } }

        onNodeWithText("© OpenStreetMap contributors", substring = true).assertExists()
    }

    @Test
    fun theSchematicSketchClaimsNoOpenStreetMapData() = runComposeUiTest {
        setContent { TenshuTheme { Box { BasemapChrome() } } }

        onNodeWithText("Offline · MapLibre").assertExists()
        onNodeWithText("OpenStreetMap", substring = true).assertDoesNotExist()
    }

    /** The desktop's elected Apple Maps snapshot is Apple's data: credit Apple, link its legal page. */
    @Test
    fun theAppleMapsSnapshotCreditsAppleAndOpensItsLegalNotices() = runComposeUiTest {
        val opened = mutableListOf<String>()
        val handler = object : UriHandler { override fun openUri(uri: String) { opened += uri } }
        setContent {
            TenshuTheme {
                CompositionLocalProvider(LocalUriHandler provides handler) { Box { AppleMapsDataContent { BasemapChrome() } } }
            }
        }

        onNodeWithText("MapLibre", substring = true).assertDoesNotExist()
        onNodeWithText("OpenStreetMap", substring = true).assertDoesNotExist()
        onNodeWithText("© Apple Maps · Legal").performClick()
        assertEquals(listOf(APPLE_MAPS_LEGAL_URL), opened)
    }
}
