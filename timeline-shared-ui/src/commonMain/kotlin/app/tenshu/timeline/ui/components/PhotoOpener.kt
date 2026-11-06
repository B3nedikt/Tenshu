package app.tenshu.timeline.ui.components

import androidx.compose.runtime.staticCompositionLocalOf
import app.tenshu.timeline.model.Photo

/**
 * What happens when a photo is tapped — `control-cross-app-open.md` TT-2a, closing
 * `timeline-photos.md` §10.5.
 *
 * ⭐ **§10.5 left two options and called neither obviously right**: a real deep link into Drive
 * ("correct app owns the viewer, but a new cross-app surface"), or a second full-screen viewer
 * inside Timeline ("no new contract, but a second viewer to maintain"). The first one wins now
 * that the cross-app surface exists and is **generic** — Timeline names no app, declares no
 * `<queries>`, and gains no viewer. What §10.5 priced as a new contract is one `openTarget` call.
 *
 * ⚠ A `CompositionLocal` for the same reason [LocalPhotoBytes] is one: it is a *service* every
 * surface that draws a photo needs, and threading it through `NamedMoment`, `MomentDetailScreen`
 * and `PlaceDetailScreen` would be three parameters for one behaviour.
 *
 * ⚠⚠ **Absent means not tappable, and that is the honest default.** Previews, the mock feed and the
 * not-connected surface have no proxy to open anything with, and a tappable row that cannot open is
 * worse than an inert one — CO-D7's rule applied before the tap rather than after it.
 */
val LocalPhotoOpener = staticCompositionLocalOf<PhotoOpener?> { null }

/** Given the photo the user tapped, ask Control to open it in whichever app owns the file. */
fun interface PhotoOpener {
    fun open(photo: Photo)
}
