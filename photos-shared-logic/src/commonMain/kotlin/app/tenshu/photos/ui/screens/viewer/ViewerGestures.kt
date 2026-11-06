package app.tenshu.photos.ui.screens.viewer

/**
 * The viewer's zoom and drag-to-dismiss arithmetic (PH-15 / D4), for both shells (PIO-3 slice 2).
 *
 * ⚠⚠ One copy. Compose's viewer held these as private constants; the iOS viewer draws the same
 * gestures, and two copies of "how far is far enough to close" drift into two apps that close at
 * different distances.
 *
 * The gesture priority itself — two fingers always zoom; one finger pans while magnified; at
 * fit-scale one finger down dismisses and one finger sideways pages — is written on Compose's
 * `PhotoViewerScreen` and mirrored by the iOS page; these are the numbers both of them use.
 */
object ViewerGestures {
    /**
     * How far a photograph may be magnified. ⚠ A product choice: past this a synced thumbnail is all
     * upscale and the user is inspecting compression rather than their picture (PH-G9).
     */
    const val MAX_ZOOM: Float = 5f

    /** How far down the page a drag must travel to dismiss, as a fraction of its height. */
    const val DISMISS_FRACTION: Float = 0.18f

    /** The travel over which the photograph shrinks to its smallest, as a fraction of page height. */
    const val DISMISS_TRAVEL: Float = 0.5f

    /** How much smaller the photograph gets at full travel — the feedback that says "let go". */
    const val DISMISS_SHRINK: Float = 0.25f

    /** A magnification kept between fit-scale and [MAX_ZOOM]. */
    fun clampZoom(scale: Float): Float = scale.coerceIn(1f, MAX_ZOOM)

    /**
     * The photograph's scale while it is dragged [dragDown] points/pixels down a page [height] tall.
     *
     * ⚠ The shrink IS the dismiss's feedback: the viewer's ground is opaque, so fading would reveal
     * the window rather than the grid — the picture pulling away from the frame says "let go".
     */
    fun dismissScale(dragDown: Float, height: Float): Float {
        if (height <= 0f) return 1f
        val progress = (dragDown / (height * DISMISS_TRAVEL)).coerceIn(0f, 1f)
        return 1f - DISMISS_SHRINK * progress
    }

    /**
     * Whether letting go after dragging [dragDown] down a page [height] tall closes the viewer.
     * ⚠ Strictly past the threshold; a page not laid out yet (height 0) never dismisses.
     */
    fun dismisses(dragDown: Float, height: Float): Boolean =
        height > 0f && dragDown > height * DISMISS_FRACTION
}
