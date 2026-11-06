package app.tenshu.photos.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.tenshu.photos.model.MotionTurn

/**
 * Whether this platform can play a `MOTION` clip inside a grid tile (`photos-video-support.md`
 * §4.4 "Playback of the class"): Android through `MediaPlayer`; desktop on macOS through the
 * `:macos-helpers` AVFoundation bridge; Windows and Linux not at all — a static poster, honestly.
 *
 * ⚠⚠ **The shell asks this and gates the rotation on it.** A platform with no player must never
 * be handed a turn: the tile could not finish it, and a rotation waiting on a turn that never ends
 * is a grid that never moves *and* never hands over. Off means exactly today's grid.
 */
expect fun isMotionPlaybackSupported(): Boolean

/**
 * The grid's player — **not the viewer's** (`photos-video-support.md` §6 / D8). Muted, no
 * controller, no spinner, no error surface, no chrome: a 110 dp cell has room for none of it. It
 * plays [turn]'s clip through once and calls [onFinished], and that is the whole contract.
 *
 * ⚠⚠ Keyed on the turn by the caller (`PhotoTile`), so a new turn on the same tile — the one-video
 * case, where "next" is itself — is a fresh player starting at frame one, and a hand-over disposes
 * this one. Nothing here survives a turn.
 *
 * ⚠⚠ [onFinished] is the rotation's clock, so it must be called **exactly once per turn, and
 * always** — after a clip that played, after one that failed to open, after one the platform
 * could not decode. A player that stays silent on failure stalls every other video on screen. A
 * failure waits out a nominal turn before reporting, so a platform where every clip fails degrades
 * to a static grid rather than spinning through the rotation as fast as it can recompose.
 */
@Composable
expect fun MotionTilePlayer(
    turn: MotionTurn,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
)

/** How long a failed turn holds the slot before handing over — the clip's own nominal length. */
const val MOTION_FAILED_TURN_MS: Long = 3_000L

/**
 * The backstop behind [MotionTilePlayer]'s *always* — the ceiling a turn may hold the slot for when
 * the platform player reports neither completion nor error.
 *
 * ⚠⚠ Not a turn timer: a nominal clip is [MOTION_FAILED_TURN_MS] long and reports itself, so this
 * fires only when a player has gone silent — and one that does stalls **every** video on screen,
 * not just its own tile (`photos-video-support.md` §15.9). Generous on purpose: cutting a clip that
 * is genuinely playing is the worse failure.
 */
const val MOTION_TURN_CEILING_MS: Long = 10_000L

/** Test handle for the player's box inside a tile — present only while that tile holds the turn. */
const val MOTION_TILE_PLAYER_TAG = "motionTilePlayer"
