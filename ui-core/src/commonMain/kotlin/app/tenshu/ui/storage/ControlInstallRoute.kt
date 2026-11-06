package app.tenshu.ui.storage

import androidx.compose.runtime.Composable

/**
 * Where a user with no Tenshu Control is sent to get one — the *install* route, as distinct from
 * `rememberControlServicesLauncher`'s *launch* route (`ADR 0025` §Decision 6).
 *
 * ## ⚠⚠ Three answers, because "no link" means two different things
 *
 * A single nullable URL cannot say which sentence to show, and the two are not interchangeable:
 *
 * - *"Control isn't on the App Store yet"* is **temporary** and true of a pre-launch build. It is
 *   ADR 0022's wording and promises the user that a link is coming.
 * - *"Install Control the way you installed this app"* is **permanent for this copy**. It is what a
 *   Homebrew, F-Droid or sideloaded build must say — and it stays true *after* Control ships to a
 *   store, because sending someone who installed via a package manager to the Mac App Store leaves
 *   them with two copies from two channels and no idea which one updates.
 *
 * Telling the second user the first sentence is not merely unhelpful; it is a promise this build
 * will never keep.
 */
sealed interface ControlInstallRoute {

    /**
     * A page to get Control from, in this copy's own channel: a store page, reached only when a link
     * is configured **and** this build was actually installed from that store — or, for a
     * downloaded Android copy, the website's download page ([androidControlInstallRoute]). The
     * words ("Control isn't installed" + **Get Control**) name no store, so both read right.
     */
    data class Store(val url: String) : ControlInstallRoute

    /**
     * This copy did not come from a store, so neither should Control. The app names its own install
     * method in the copy ("the way you installed Maps"), because only the app knows what it is.
     */
    data object SameChannelAsThisApp : ControlInstallRoute

    /** A store build whose link is not configured yet — the honest pre-launch state (ADR 0022 §3). */
    data object NotPublishedYet : ControlInstallRoute
}

/**
 * This build's install route.
 *
 * ⚠⚠ **A configured link is necessary, not sufficient.** Both Android and macOS can see how *this*
 * copy was installed — Play's installer attribution, the Mac App Store's receipt — and a build that
 * did not come from the store never offers it, however well configured the link is. This is
 * stronger than the build flag ADR 0025 §6 first proposed: an artifact can reach a user by more than
 * one route (a Play-signed APK copied off a phone, a DMG installed through Homebrew), so the channel
 * is a fact about the installed copy, not the build.
 *
 * ⚠ It follows that the honest default everywhere is [ControlInstallRoute.SameChannelAsThisApp] or
 * [ControlInstallRoute.NotPublishedYet] — never a *store* link. A wrong store link cannot be shipped
 * by omission, only by configuring one *and* being installed from that store. The one link that is
 * not configured is our own website's, offered only to a downloaded Android copy.
 */
@Composable
expect fun rememberControlInstallRoute(): ControlInstallRoute
