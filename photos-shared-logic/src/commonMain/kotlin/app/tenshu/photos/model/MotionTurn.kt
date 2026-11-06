package app.tenshu.photos.model

/**
 * One turn of the feed's single rotating player (`photos-video-support.md` §5 / M5): the tile at
 * [path] plays [clip] — its `MOTION` class, a ~3 s silent H.264 loop already in hand — through
 * once, then reports back and the rotation hands over.
 *
 * ⚠⚠ [serial] is part of the identity on purpose. The tile keys its player on the turn, and with
 * one video on screen "next" is the same tile again (§1.2's degenerate case that looked like a
 * loop): two consecutive turns on one path must still be two different keys, or the player would
 * never restart. A `data class` over `path` alone would collapse them.
 *
 * ⚠ Carries the bytes rather than a path to look up: the rotation's rule is that only a tile whose
 * clip is *already in hand* gets the player (§5.1), so by the time a turn exists the bytes do too,
 * and handing them over is what makes that rule impossible to break from the tile's side.
 */
class MotionTurn(
    val path: String,
    val clip: ByteArray,
    val serial: Long,
) {
    override fun equals(other: Any?): Boolean =
        other is MotionTurn && other.path == path && other.serial == serial

    override fun hashCode(): Int = 31 * path.hashCode() + serial.hashCode()

    override fun toString(): String = "MotionTurn($path #$serial, ${clip.size} B)"
}
