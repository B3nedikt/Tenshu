package org.tenshu.file.proxy

/**
 * How a raster stored the way the camera wrote it is turned into the way it is shown, for an EXIF
 * orientation 1–8: the one table every platform's thumbnail render uses.
 *
 * ⚠⚠ **A thumbnail rung is stored upright.** It is re-encoded with no EXIF, so nothing downstream
 * can rotate it: no display surface (Android, desktop, iOS, web) reads `File.Orientation` before
 * drawing one, and a rung is minted once per content and synced, so the device that mints it decides
 * what every device shows. iOS has always minted upright (`UIImage` applies the orientation when it
 * draws). Android's `BitmapFactory` and the JVM's `ImageIO` do not, and for a while they minted rungs
 * the way the sensor wrote them — iPhone selfies showed upside down on the phone that minted them
 * (2026-11-01). A render that decodes with one of those has to come through here.
 *
 * ⚠ Each platform applies a [Turn] with its own native transform (`Matrix`, `AffineTransform`), not a
 * pixel loop: that keeps the bitmap's colour space (Display P3 on every iPhone photo) and costs one
 * allocation. Orientations outside 2..8 (absent, 1, garbage) are left alone.
 */
internal object ExifUpright {

    /**
     * Mirror left-to-right first when [mirror], then turn [clockwiseDegrees] (0, 90, 180 or 270)
     * clockwise, in y-down image coordinates.
     */
    class Turn(val mirror: Boolean, val clockwiseDegrees: Int)

    /** Whether [orientation] changes nothing. */
    fun isIdentity(orientation: Int?): Boolean = turnFor(orientation) == null

    /** Whether the shown image's width is the stored height (5–8, the quarter turns). */
    fun swapsAxes(orientation: Int?): Boolean = turnFor(orientation)?.let { it.clockwiseDegrees % 180 != 0 } == true

    /** The turn [orientation] asks for, or `null` when it asks for none. */
    fun turnFor(orientation: Int?): Turn? = when (orientation) {
        2 -> Turn(mirror = true, clockwiseDegrees = 0)
        3 -> Turn(mirror = false, clockwiseDegrees = 180)
        4 -> Turn(mirror = true, clockwiseDegrees = 180) // vertical mirror
        5 -> Turn(mirror = true, clockwiseDegrees = 270) // transpose
        6 -> Turn(mirror = false, clockwiseDegrees = 90)
        7 -> Turn(mirror = true, clockwiseDegrees = 90) // transverse
        8 -> Turn(mirror = false, clockwiseDegrees = 270)
        else -> null
    }
}
