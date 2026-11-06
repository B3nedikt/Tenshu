package app.tenshu.ui.feedback

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.GetApp
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SystemUpdateAlt
import app.tenshu.reachability.UnreachableReason
import app.tenshu.reachability.UpdateTarget
import app.tenshu.ui.storage.AppUpdatePages
import app.tenshu.ui.storage.ControlInstallRoute
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The banner posture every app shares (`ADR 0025` §Decision 2).
 *
 * ⚠⚠ **The point is that this is decided once.** Four shells and Fit's banner each render their own
 * words — they must, since only the app knows what it loses — but which reasons carry an action and
 * which carry an apology is not theirs to re-decide. Before ADR 0025 they were each about to, which
 * is how three of the pre-0022 iOS banners came to be byte-identical without anyone intending it.
 */
class ControlBannerPostureTest {

    // MARK: - what each reason offers

    private val store = ControlInstallRoute.Store("https://example.invalid/control")
    private val none = AppUpdatePages.NONE

    /**
     * ⚠ The one case recovered by *opening* Control, and the one whose copy has said *"open Control
     * and unlock it, then come back"* since fit-app.md §9.14 with nothing to tap.
     */
    @Test
    fun aTransportOutageOffersToOpenControl() {
        assertEquals(
            ControlBannerAction.Kind.OPEN_CONTROL,
            bannerActionKind(UnreachableReason.Transport, store, none),
        )
    }

    /** ⚠ And it does not become a *get* offer just because a store link happens to be configured. */
    @Test
    fun aTransportOutageNeverOffersTheStore() {
        assertEquals(
            ControlBannerAction.Kind.OPEN_CONTROL,
            bannerActionKind(UnreachableReason.Transport, ControlInstallRoute.SameChannelAsThisApp, none),
        )
    }

    @Test
    fun anAbsentControlWithAStoreOffersToGetIt() {
        assertEquals(
            ControlBannerAction.Kind.GET_CONTROL,
            bannerActionKind(UnreachableReason.NotInstalled, store, none),
        )
    }

    /**
     * ⚠⚠ The rule ADR 0022 was written about, generalised: **no link, no button.** A "Get Control"
     * that opens a broken store page is a failure only a user ever sees.
     */
    @Test
    fun anAbsentControlWithNoPublishedStoreOffersNothing() {
        assertNull(bannerActionKind(UnreachableReason.NotInstalled, ControlInstallRoute.NotPublishedYet, none))
    }

    /**
     * ⚠⚠ And **no button for a copy that did not come from a store**, even once one exists. Sending
     * a Homebrew or F-Droid user to a store leaves them with two copies from two channels and no
     * idea which one updates.
     */
    @Test
    fun anAbsentControlOnAnotherChannelOffersNothing() {
        assertNull(
            bannerActionKind(UnreachableReason.NotInstalled, ControlInstallRoute.SameChannelAsThisApp, none),
        )
    }

    /**
     * ⚠⚠ Installed, answering, no realm (ADR 0025 §Decision 8): opening Control *is* the recovery,
     * and a configured store link must not turn it into a *get* offer for an app already installed.
     */
    @Test
    fun aControlThatIsNotSetUpOffersToOpenItNeverToGetIt() {
        for (route in listOf(store, ControlInstallRoute.NotPublishedYet, ControlInstallRoute.SameChannelAsThisApp)) {
            assertEquals(
                ControlBannerAction.Kind.OPEN_CONTROL,
                bannerActionKind(UnreachableReason.NotSetUp, route, none),
            )
        }
    }

    /** ⚠ Neither the install glyph nor the cloud-off one: there is nothing to install or wait out. */
    @Test
    fun aControlThatIsNotSetUpGetsTheSettingsGlyph() {
        assertEquals(Icons.Default.Settings, controlBannerIcon(UnreachableReason.NotSetUp))
    }

    /**
     * ⚠ With no update page — a store client, a debug build, an unknown installer, desktop, an
     * unconfigured Play listing or App Store id — a skew offers nothing: opening Control does not *update* Control, and a store link to
     * *get* Control is not an update route either.
     */
    @Test
    fun aSkewWithNoUpdatePageOffersNothing() {
        assertNull(bannerActionKind(UnreachableReason.Skew(UpdateTarget.CONTROL), store, none))
    }

    /** ⚠ A fenced device is answering every read already; with no update page there is nothing to offer. */
    @Test
    fun aFencedDeviceWithNoUpdatePageOffersNothing() {
        assertNull(bannerActionKind(UnreachableReason.Fenced(UpdateTarget.CONTROL), store, none))
    }

    /** ⚠ Nothing known yet: no banner is shown, so no action can be owed. */
    @Test
    fun anUnknownReasonOffersNothing() {
        assertNull(bannerActionKind(null, store, none))
    }

    // MARK: - the update offer (`core-schema-skew-surface.md` §8.5)

    private val bothPages = AppUpdatePages(thisApp = THIS_APP_PAGE, control = CONTROL_PAGE)

    /**
     * ⚠⚠ The trap this offer exists around: the app to name is not the app the banner is in. A
     * Control-owned verdict opens *Control's* page from Maps' banner, and a this-app verdict opens
     * Maps' own.
     */
    @Test
    fun aSkewOnADownloadedCopyOffersTheStaleAppsOwnPage() {
        val control = UnreachableReason.Skew(UpdateTarget.CONTROL)
        val thisApp = UnreachableReason.Skew(UpdateTarget.THIS_APP)

        assertEquals(ControlBannerAction.Kind.UPDATE, bannerActionKind(control, store, bothPages))
        assertEquals(CONTROL_PAGE, updatePageFor(control, bothPages))
        assertEquals(ControlBannerAction.Kind.UPDATE, bannerActionKind(thisApp, store, bothPages))
        assertEquals(THIS_APP_PAGE, updatePageFor(thisApp, bothPages))
    }

    /** ⚠⚠ Gate on the reason, not the state: a fenced device is reachable and still owes it. */
    @Test
    fun aFenceOnADownloadedCopyOffersTheOwningAppsPage() {
        val fenced = UnreachableReason.Fenced(UpdateTarget.CONTROL)
        assertEquals(ControlBannerAction.Kind.UPDATE, bannerActionKind(fenced, store, bothPages))
        assertEquals(CONTROL_PAGE, updatePageFor(fenced, bothPages))
    }

    /** ⚠ A fence no host claimed names nothing, so it cannot open anything either. */
    @Test
    fun aFenceWithNoKnownOwnerOffersNothing() {
        assertNull(bannerActionKind(UnreachableReason.Fenced(null), store, bothPages))
        assertNull(updatePageFor(UnreachableReason.Fenced(null), bothPages))
    }

    /** ⚠ One button cannot carry two updates; the words already say both. */
    @Test
    fun aSkewThatNeedsBothOffersNothing() {
        assertNull(bannerActionKind(UnreachableReason.Skew(UpdateTarget.BOTH), store, bothPages))
        assertNull(bannerActionKind(UnreachableReason.Fenced(UpdateTarget.BOTH), store, bothPages))
    }

    /**
     * ⚠⚠ The page is decided per stale copy: a downloaded Maps beside a Play Control has its own page
     * and no Control page, so a Control verdict offers nothing rather than the website's Control.
     */
    @Test
    fun aStaleBinaryWithNoPageOffersNothingEvenWhenTheOtherHasOne() {
        val onlyThisApp = AppUpdatePages(thisApp = THIS_APP_PAGE, control = null)
        assertNull(bannerActionKind(UnreachableReason.Skew(UpdateTarget.CONTROL), store, onlyThisApp))
        assertEquals(
            ControlBannerAction.Kind.UPDATE,
            bannerActionKind(UnreachableReason.Skew(UpdateTarget.THIS_APP), store, onlyThisApp),
        )

        val onlyControl = AppUpdatePages(thisApp = null, control = CONTROL_PAGE)
        assertNull(bannerActionKind(UnreachableReason.Skew(UpdateTarget.THIS_APP), store, onlyControl))
    }

    /** ⚠ A download page never turns another reason into an update offer. */
    @Test
    fun updatePagesChangeNoOtherReasonsOffer() {
        assertEquals(ControlBannerAction.Kind.OPEN_CONTROL, bannerActionKind(UnreachableReason.Transport, store, bothPages))
        assertEquals(ControlBannerAction.Kind.OPEN_CONTROL, bannerActionKind(UnreachableReason.NotSetUp, store, bothPages))
        assertEquals(ControlBannerAction.Kind.GET_CONTROL, bannerActionKind(UnreachableReason.NotInstalled, store, bothPages))
        assertNull(bannerActionKind(UnreachableReason.NotInstalled, ControlInstallRoute.SameChannelAsThisApp, bothPages))
        assertNull(bannerActionKind(UnreachableReason.Checking, store, bothPages))
        assertNull(bannerActionKind(null, store, bothPages))
    }

    // MARK: - the glyph

    /**
     * ⚠⚠ Three problems, three glyphs, and the mapping is the message. A cloud-off beside "Control
     * isn't installed" reads as a network hiccup to wait out; an update badge beside it reads as a
     * version problem. Both send the reader somewhere there is nothing to do.
     */
    @Test
    fun eachKindOfProblemGetsItsOwnGlyph() {
        val absent = controlBannerIcon(UnreachableReason.NotInstalled)
        val transport = controlBannerIcon(UnreachableReason.Transport)
        val skew = controlBannerIcon(UnreachableReason.Skew(UpdateTarget.THIS_APP))

        assertEquals(Icons.Default.GetApp, absent)
        assertEquals(Icons.Default.CloudOff, transport)
        assertEquals(Icons.Default.SystemUpdateAlt, skew)
    }

    /** ⚠ A fence is an update problem too, however differently it is reached. */
    @Test
    fun aFenceSharesTheUpdateGlyph() {
        assertEquals(
            Icons.Default.SystemUpdateAlt,
            controlBannerIcon(UnreachableReason.Fenced(UpdateTarget.CONTROL)),
        )
    }

    /** ⚠ Unknown keeps transport's glyph, matching the words it keeps. */
    @Test
    fun anUnknownReasonKeepsTheTransportGlyph() {
        assertEquals(Icons.Default.CloudOff, controlBannerIcon(null))
    }

    private companion object {
        const val THIS_APP_PAGE = "https://tenshu.app/apps/maps/download/android"
        const val CONTROL_PAGE = "https://tenshu.app/apps/control/download/android"
    }
}
