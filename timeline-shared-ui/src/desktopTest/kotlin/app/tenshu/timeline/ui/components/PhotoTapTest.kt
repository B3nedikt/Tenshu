package app.tenshu.timeline.ui.components

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import app.tenshu.timeline.model.Moment
import app.tenshu.timeline.model.MomentKind
import app.tenshu.timeline.model.Photo
import app.tenshu.timeline.ui.theme.Personalisation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Where a tap on a photo goes, and why the answer differs by surface
 * (`control-cross-app-open.md` §5m).
 *
 * ⚠⚠ **The defect these pin**: a feed card opened two different *apps* depending on which pixel was
 * pressed — the title landed on the moment's detail screen, the photo left Timeline for whichever
 * app owns the file. Nothing on the card said the two halves were different targets.
 *
 * ⚠ Both directions are asserted. A test that only pinned the new routing would still pass with the
 * cross-app open deleted outright, which would break the detail screens the feature was built for.
 */
@OptIn(ExperimentalTestApi::class)
class PhotoTapTest {

    private val photo = Photo(dominantColor = "#8B7BB8", uri = "/Backup/Camera/IMG_1.jpg")

    private val moment = Moment(
        id = "moment-7",
        time = "09:15",
        title = "Office",
        place = "Office",
        kind = MomentKind.Visit,
        photos = listOf(photo),
    )

    private val personalisation =
        Personalisation(accent = Color(0xFF8B7BB8), soft = Color(0x228B7BB8), tint = null)

    /** The frame inside a `NamedMoment`: the one clickable node nested inside the row's own. */
    private val photoFrame: SemanticsMatcher = hasClickAction() and hasAnyAncestor(hasClickAction())

    @Test
    fun aFeedRowsPhotoOpensTheRowRatherThanAnotherApp() = runComposeUiTest {
        var opened: Photo? = null
        var rowClicks = 0
        setContent {
            CompositionLocalProvider(LocalPhotoOpener provides PhotoOpener { opened = it }) {
                NamedMoment(
                    moment = moment,
                    personalisation = personalisation,
                    onClick = { rowClicks++ },
                )
            }
        }

        onNode(photoFrame).performClick()

        assertEquals(1, rowClicks, "tapping the photo must land where tapping the title lands")
        assertNull(opened, "a feed row's photo must not leave the app")
    }

    @Test
    fun theTitleAndThePhotoAgree() {
        // The other half of the same claim: whichever the user presses, one destination.
        var rowClicks = 0
        runComposeUiTest {
            setContent {
                NamedMoment(
                    moment = moment,
                    personalisation = personalisation,
                    onClick = { rowClicks++ },
                )
            }
            onNodeWithText("Office").performClick()
        }
        assertEquals(1, rowClicks)
    }

    @Test
    fun aDetailScreensPhotoStillOpensTheOwningApp() = runComposeUiTest {
        // `MomentDetailScreen` / `PlaceDetailScreen` shape: the photo IS the subject, so the
        // cross-app open (TT-2a) is the right answer and must survive this change.
        var opened: Photo? = null
        setContent {
            CompositionLocalProvider(LocalPhotoOpener provides PhotoOpener { opened = it }) {
                PhotoCluster(photos = listOf(photo), accent = Color.Magenta)
            }
        }

        onNode(hasClickAction()).performClick()

        assertEquals(photo, opened)
    }

    @Test
    fun aRowThatDoesNotNavigateStillOpensTheOwningApp() = runComposeUiTest {
        // ⚠ The fallback: with no destination of its own the row has nothing better to offer, and
        // an inert photo would be a loss rather than a fix.
        var opened: Photo? = null
        setContent {
            CompositionLocalProvider(LocalPhotoOpener provides PhotoOpener { opened = it }) {
                NamedMoment(moment = moment, personalisation = personalisation, onClick = null)
            }
        }

        onNode(hasClickAction()).performClick()

        assertEquals(photo, opened)
    }
}
