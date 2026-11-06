package app.tenshu.photos.ui.system

import androidx.compose.runtime.Composable

/**
 * Hide or show the OS status and navigation bars for as long as this composable is in the tree
 * (`photos-viewer-about-screen.md` §3.1b).
 *
 * ⚠⚠ **A platform seam, because Compose Multiplatform has no common API for it.** Android drives
 * `WindowInsetsControllerCompat`; desktop has no system bars and its actual is an honest **no-op**,
 * not a stub that pretends to work. That distinction is the one `compose-global-modal-no-ios-twin`
 * names: an app-global Compose affordance that silently has no twin on a platform is worse than one
 * that is visibly absent, because nobody goes looking for it.
 *
 * ⚠ It **restores the bars when it leaves the composition**. A viewer that is backed out of while
 * immersive must not leave the rest of the app with no status bar — and on Android that state
 * outlives the screen, because the controller is the window's, not the composable's.
 *
 * ⚠ Photos has no iOS shell today (`photos-app.md` §10.1). When one exists it owes an actual here;
 * until then there is no `iosMain` source set in this module to put one in.
 */
@Composable
expect fun HideSystemBars(hidden: Boolean)
