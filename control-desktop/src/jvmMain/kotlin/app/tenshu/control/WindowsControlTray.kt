package app.tenshu.control

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.Tray
import app.tenshu.control.data.SyncPauseState
import app.tenshu.control.lifecycle.WindowController

/**
 * Control's notification-area icon on Windows (`desktop-windows-port.md` §3.4, *The tray*).
 *
 * The in-process counterpart of the macOS Swift `tray-agent`: the same three actions, calling the
 * objects the agent reaches over the bridge directly — [WindowController] for `openControlWindow` /
 * `quitApp`, the Koin [SyncPauseState] single for `setSyncPaused` (whose `persist` writes
 * `DesktopControlSettings`, so a pause survives a restart exactly as one set from the macOS tray).
 *
 * ⚠ Emit only where `DesktopShellSupport.current.inProcessTray`: never on macOS (the agent already
 * owns the icon) and never where AWT has no system tray. The icon is removed when this leaves the
 * composition, i.e. when `exitApplication()` ends `application { }`.
 *
 * @param onOpen show, de-minimise and raise the window — the primary click and *Open Control*.
 */
@Composable
internal fun ApplicationScope.WindowsControlTray(
    windowController: WindowController,
    syncPauseState: SyncPauseState,
    onOpen: () -> Unit,
) {
    val paused by syncPauseState.paused.collectAsState()
    val icon = remember(paused) { ControlTrayIcon(paused) }
    Tray(
        icon = icon,
        tooltip = if (paused) "Tenshu Control — sync paused" else "Tenshu Control",
        onAction = onOpen,
        menu = {
            Item("Open Control", onClick = onOpen)
            Item(
                if (paused) "Resume sync" else "Pause sync",
                onClick = { syncPauseState.setPaused(!syncPauseState.isPaused()) },
            )
            Separator()
            Item("Quit Tenshu Control", onClick = windowController::requestQuit)
        },
    )
}

/**
 * The tray glyph, drawn in code: there is no raster Control icon off macOS (`icons/` holds only an
 * `.icns`). A rounded tile with a "T"; grey with pause bars while sync is paused, so the state reads
 * without opening the menu. Compose rasterises it at the tray's own icon size.
 */
internal class ControlTrayIcon(private val paused: Boolean) : Painter() {
    override val intrinsicSize: Size = Size(INTRINSIC, INTRINSIC)

    override fun DrawScope.onDraw() {
        val s = size.minDimension
        drawRoundRect(
            color = if (paused) PAUSED_TILE else ACTIVE_TILE,
            size = Size(s, s),
            cornerRadius = CornerRadius(s * 0.22f),
        )
        if (paused) {
            val barWidth = s * 0.16f
            val barHeight = s * 0.52f
            val top = (s - barHeight) / 2
            drawRect(GLYPH, Offset(s * 0.30f, top), Size(barWidth, barHeight))
            drawRect(GLYPH, Offset(s * 0.54f, top), Size(barWidth, barHeight))
        } else {
            drawRect(GLYPH, Offset(s * 0.22f, s * 0.22f), Size(s * 0.56f, s * 0.16f))
            drawRect(GLYPH, Offset(s * 0.42f, s * 0.22f), Size(s * 0.16f, s * 0.58f))
        }
    }

    private companion object {
        const val INTRINSIC = 64f
        val ACTIVE_TILE = Color(0xFF3B6FD4)
        val PAUSED_TILE = Color(0xFF7A7A7A)
        val GLYPH = Color.White
    }
}
