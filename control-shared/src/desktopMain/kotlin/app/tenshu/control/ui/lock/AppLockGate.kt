package app.tenshu.control.ui.lock

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.LocalAwtWindow
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.tenshu.control.data.AppLockController
import app.tenshu.control.data.DesktopAppLock
import app.tenshu.control.data.EmbeddedAuthSlot
import app.tenshu.control.shared.resources.Res
import app.tenshu.control.shared.resources.app_lock_system_reason
import app.tenshu.control.shared.resources.app_lock_title
import app.tenshu.control.shared.resources.app_lock_touch_id_hint
import app.tenshu.control.shared.resources.app_lock_unlock
import app.tenshu.control.shared.resources.app_lock_use_password
import app.tenshu.control.ui.LocalWindowVisible
import app.tenshu.ui.components.marks.MarkOutline
import java.awt.Frame
import java.awt.Window
import javax.swing.RootPaneContainer
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject

/**
 * Control's **soft app-lock** UI gate on desktop — the macOS mirror of the Android
 * [AppLockGate] (`docs/proposals/control-keep-device-unlocked.md` §4.2, "Require
 * unlock"). Wraps the whole app content. When [AppLockController.locked] is true and
 * the window is on screen it draws an **opaque overlay** with Touch ID drawn inside it
 * (`LAAuthenticationView`) and a *Use password* route to the keyless system sheet, both
 * via [DesktopAppLock]; without Touch ID it goes straight to the sheet. Success clears the
 * gate until the window is next hidden. Touch ID re-asks by itself whenever the window
 * regains focus ([LockPromptFlow]); only a Mac without usable Touch ID gets a manual
 * *Unlock* retry after closing the sheet.
 *
 * The lifecycle signal is the window's visibility ([LocalWindowVisible] — driven by
 * the tray's hide/show over `WindowController`): hiding the window re-arms the gate
 * (the ON_STOP equivalent). **UI only** — it never touches keys, so the realm stays
 * headless and background sync is unaffected.
 */
@Composable
actual fun AppLockGate(active: Boolean, content: @Composable () -> Unit) {
    val controller: AppLockController = koinInject()
    val appLock: DesktopAppLock = koinInject()
    val locked by controller.locked.collectAsState()
    val visible = LocalWindowVisible.current

    // Re-arm when the window is hidden (close-to-tray / hidden daemon) — the desktop
    // equivalent of Android's ProcessLifecycle ON_STOP.
    LaunchedEffect(visible) {
        if (!visible) controller.onEnterBackground()
    }

    // Dormant-backend pass-through: with no configured helper (a helper-less bundle or
    // a bare `:run`) authenticate() can only ever return Unavailable, so an engaged
    // gate would be a permanent lockout — "Require unlock" defaults ON, but the
    // Settings toggle is disabled in this state and the gate must match it (the
    // documented "dormant" posture in DesktopAppLock's KDoc). Checked per engagement
    // rather than latched so a helper configured later is honoured at the next
    // re-arm. helper==null short-circuits isAvailable() without spawning a process,
    // so on the common path the overlay clears within a frame.
    LaunchedEffect(active, locked, visible) {
        if (active && locked && visible) controller.passIfUnenforceable(appLock)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        content()
        // Only engage over the active app UI (never the hard LockedScreen / onboarding —
        // avoids a duplicated unlock screen), and only while the window is on screen; a
        // hidden (close-to-tray) window shows nothing and re-prompts when re-opened.
        if (active && locked && visible) {
            LockOverlay(
                appLock = appLock,
                onUnlocked = controller::onAuthenticated,
            )
        }
    }
}

// LocalAwtWindow: the only public route from the composition to its AWT window.
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun LockOverlay(
    appLock: DesktopAppLock,
    onUnlocked: () -> Unit,
) {
    val reason = stringResource(Res.string.app_lock_system_reason)
    val window = LocalAwtWindow.current
    val density = LocalDensity.current
    val focused = LocalWindowInfo.current.isWindowFocused
    val flow = remember { LockPromptFlow(appLock, onUnlocked) }
    // Bumped to choose again, with [request] saying why: "Use password", a Touch ID failure, or the
    // Unlock button (null ⇒ Auto).
    var attempt by remember { mutableIntStateOf(0) }
    var request by remember { mutableStateOf<LockPromptFlow.Request?>(null) }
    var slot by remember { mutableStateOf<EmbeddedAuthSlot?>(null) }

    LaunchedEffect(attempt) {
        val asked = request ?: LockPromptFlow.Request.Auto
        request = null
        flow.choose(reason, asked)
    }

    // Touch ID only while the window has focus — macOS cancels a check for an inactive app, and
    // restarting on refocus is what makes an *Unlock* button unnecessary (see LockPromptFlow).
    // Losing focus or the route cancels this, which takes the native view down.
    LaunchedEffect(flow.route, focused) {
        if (flow.route != LockPromptFlow.Route.TouchId || !focused) return@LaunchedEffect
        val outcome = flow.touchId(reason) { snapshotFlow { slot }.filterNotNull().first() }
        // ⚠ Not if a request is already pending: a "Use password" press that raced this failure has
        // bumped `attempt` already, and a second bump would queue a second sheet behind the first.
        if (outcome == LockPromptFlow.TouchIdOutcome.NeedsPassword && request == null) {
            request = LockPromptFlow.Request.TouchIdFailed
            attempt++
        }
    }

    // Keep the native view on its slot as the window is resized.
    LaunchedEffect(slot) {
        slot?.let { appLock.moveEmbedded(it) }
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                MarkOutline(size = 120.dp)
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    text = stringResource(Res.string.app_lock_title),
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Center,
                )
                Spacer(modifier = Modifier.height(24.dp))
                when (flow.route) {
                    LockPromptFlow.Route.TouchId -> {
                        // Empty in Compose: macOS draws `LAAuthenticationView` over exactly this box
                        // while a check runs. Laid out for the whole route, so the slot is ready.
                        Box(
                            modifier = Modifier.size(TOUCH_ID_SIZE).onGloballyPositioned { coordinates ->
                                slot = window?.let { embeddedSlot(it, coordinates, density.density) }
                            },
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = stringResource(Res.string.app_lock_touch_id_hint),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        TextButton(onClick = {
                            request = LockPromptFlow.Request.UserPassword
                            attempt++
                        }) {
                            Text(stringResource(Res.string.app_lock_use_password))
                        }
                    }
                    LockPromptFlow.Route.Retry -> Button(onClick = { attempt++ }) {
                        Text(stringResource(Res.string.app_lock_unlock))
                    }
                    LockPromptFlow.Route.Checking, LockPromptFlow.Route.Password -> Unit
                }
            }
        }
    }
}

private val TOUCH_ID_SIZE = 64.dp

/**
 * [coordinates] as an [EmbeddedAuthSlot]: Compose lays out in pixels from the top-left of the
 * window's content pane, AppKit wants points — AWT's own unit on macOS, so the pane's size needs no
 * conversion. `null` for a window with no title to find it by.
 */
private fun embeddedSlot(window: Window, coordinates: LayoutCoordinates, density: Float): EmbeddedAuthSlot? {
    val title = (window as? Frame)?.title ?: return null
    val pane = (window as? RootPaneContainer)?.contentPane ?: return null
    val position = coordinates.positionInWindow()
    val scale = density.toDouble()
    return EmbeddedAuthSlot(
        windowTitle = title,
        contentWidth = pane.width.toDouble(),
        contentHeight = pane.height.toDouble(),
        x = position.x / scale,
        y = position.y / scale,
        width = coordinates.size.width / scale,
        height = coordinates.size.height / scale,
    )
}
