package app.tenshu.ui.layout

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import java.awt.GraphicsEnvironment
import kotlin.math.roundToInt
import java.io.File
import java.util.Properties

/**
 * Remembers a [WindowState] whose size, position, and placement are persisted
 * across app launches.
 *
 * Storage: `<storageDir>/<key>.window.properties`. Storage errors are swallowed —
 * a corrupted or unreadable file simply reads as a first launch.
 *
 * ⚠⚠ **A first launch opens at a size relative to the screen, centred** — every Tenshu desktop app,
 * one rule ([firstLaunchWindowSize]). It used to be a fixed 1024 × 768 dp in the platform's default
 * top-left spot: ~60 % of a 16" MacBook's width, smaller than the native apps beside it, and the
 * size a user saw until they dragged it bigger — after which each app remembered a different size,
 * which is what made the apps look inconsistent. A size the user has set always wins.
 *
 * The directory is injected rather than hardcoded so the desktop apps can route
 * window state through the same resolver as the rest of their app data
 * (`DesktopAppPaths.root()` in `:core-common`), which lands it in the
 * sandbox-reachable App Group container on a signed build. `:ui-core` stays a
 * dependency-free leaf module: the default keeps the historical `~/.tenshu`
 * location, so anything that doesn't pass [storageDir] is byte-for-byte
 * unchanged.
 *
 * @param key Unique identifier for this window (e.g., `"drive"`, `"control"`).
 *            Two windows with the same key will share persisted state.
 * @param defaultWidth Initial width on first launch; unspecified (the default) means the
 *            screen-relative size. Pass one only for a window that must not follow the rule.
 * @param defaultHeight Initial height on first launch; as [defaultWidth].
 * @param storageDir Directory the `<key>.window.properties` file lives in.
 *            Defaults to `~/.tenshu`; pass `DesktopAppPaths.root()` to keep
 *            window state with the app's other data (sandbox-safe).
 */
@OptIn(FlowPreview::class)
@Composable
fun rememberPersistedWindowState(
    key: String,
    defaultWidth: Dp = Dp.Unspecified,
    defaultHeight: Dp = Dp.Unspecified,
    storageDir: File = defaultWindowStateDir()
): WindowState {
    val file = remember(key, storageDir) { windowStateFile(storageDir, key) }
    val loaded = remember(key) {
        val screenRelative = firstLaunchWindowSize(usableScreenSize())
        loadWindowState(
            file = file,
            defaultWidth = if (defaultWidth.isSpecified) defaultWidth else screenRelative.width,
            defaultHeight = if (defaultHeight.isSpecified) defaultHeight else screenRelative.height,
        )
    }
    val state = rememberWindowState(
        placement = loaded.placement,
        position = loaded.position,
        size = loaded.size
    )

    LaunchedEffect(state, file) {
        snapshotFlow {
            PersistedWindow(
                size = state.size,
                position = state.position,
                placement = state.placement
            )
        }
            .distinctUntilChanged()
            .drop(1) // skip the initial value we just loaded
            .debounce(250) // coalesce rapid resize/move emissions
            .collect { saveWindowState(file, it) }
    }

    return state
}

internal data class PersistedWindow(
    val size: DpSize,
    val position: WindowPosition,
    val placement: WindowPlacement
)

/** Historical default location, kept so `:ui-core` needs no `:core-common` dep. */
private fun defaultWindowStateDir(): File =
    File(System.getProperty("user.home") ?: ".", ".tenshu")

private fun windowStateFile(storageDir: File, key: String): File {
    runCatching { storageDir.mkdirs() }
    return File(storageDir, "$key.window.properties")
}

/**
 * The size a window opens at the first time, given the [usable] screen area — the display minus the
 * menu bar and the Dock or taskbar — or `null` where there is no screen to ask (headless).
 *
 * **80 % of the usable width and 90 % of its height**, then clamped: never below
 * [MIN_FIRST_LAUNCH_SIZE] unless the screen itself is smaller, never above [MAX_FIRST_LAUNCH_SIZE].
 *
 * ⚠ The height fraction is larger on purpose. Measured on the 16" MacBook Pro this was written for,
 * the usable area is 1728 × 982 pt — the menu bar and Dock take 135 of the 1117 pt — so a uniform 80 %
 * would open at 1382 × 786, barely taller than the old fixed 768. It opens at 1382 × 884.
 *
 * ⚠ The cap exists for large displays: on a 5K panel 80 % is ~2000 dp wide, far past what any of
 * these apps lays out usefully, and larger than native apps open at.
 */
internal fun firstLaunchWindowSize(usable: DpSize?): DpSize {
    if (usable == null || !usable.isSpecified) return MIN_FIRST_LAUNCH_SIZE
    fun edge(available: Dp, fraction: Float, min: Dp, max: Dp): Dp =
        (available * fraction).coerceIn(minOf(min, available), max).value.roundToInt().dp
    return DpSize(
        width = edge(usable.width, 0.8f, MIN_FIRST_LAUNCH_SIZE.width, MAX_FIRST_LAUNCH_SIZE.width),
        height = edge(usable.height, 0.9f, MIN_FIRST_LAUNCH_SIZE.height, MAX_FIRST_LAUNCH_SIZE.height),
    )
}

/** The fixed size every window used to open at, now the floor of [firstLaunchWindowSize]. */
internal val MIN_FIRST_LAUNCH_SIZE = DpSize(1024.dp, 768.dp)

/** The most [firstLaunchWindowSize] opens at, whatever the display. */
internal val MAX_FIRST_LAUNCH_SIZE = DpSize(1680.dp, 1050.dp)

/**
 * The default screen's usable area, in the window-coordinate units Compose's [DpSize] maps to (points
 * on macOS). `null` when headless or when AWT cannot answer.
 */
private fun usableScreenSize(): DpSize? = runCatching {
    if (GraphicsEnvironment.isHeadless()) return@runCatching null
    val bounds = GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
    DpSize(bounds.width.dp, bounds.height.dp)
}.getOrNull()

internal fun loadWindowState(
    file: File,
    defaultWidth: Dp,
    defaultHeight: Dp
): PersistedWindow {
    // ⚠ A first launch is centred. The platform default is the top-left cascade, which on a window
    // sized to most of the screen leaves it hanging off-centre by the whole cascade offset.
    val fallback = PersistedWindow(
        size = DpSize(defaultWidth, defaultHeight),
        position = WindowPosition(Alignment.Center),
        placement = WindowPlacement.Floating
    )
    if (!file.exists()) return fallback

    return runCatching {
        val props = Properties().apply {
            file.inputStream().use { load(it) }
        }
        val width = props.getProperty("width")?.toFloatOrNull()?.dp ?: defaultWidth
        val height = props.getProperty("height")?.toFloatOrNull()?.dp ?: defaultHeight
        val x = props.getProperty("x")?.toFloatOrNull()?.dp
        val y = props.getProperty("y")?.toFloatOrNull()?.dp
        val position = if (x != null && y != null) {
            WindowPosition(x, y)
        } else {
            WindowPosition.PlatformDefault
        }
        val placement = runCatching {
            WindowPlacement.valueOf(
                props.getProperty("placement") ?: WindowPlacement.Floating.name
            )
        }.getOrDefault(WindowPlacement.Floating)

        PersistedWindow(DpSize(width, height), position, placement)
    }.getOrDefault(fallback)
}

private fun saveWindowState(file: File, state: PersistedWindow) {
    runCatching {
        val props = Properties().apply {
            if (state.size.isSpecified) {
                setProperty("width", state.size.width.value.toString())
                setProperty("height", state.size.height.value.toString())
            }
            val pos = state.position
            if (pos is WindowPosition.Absolute) {
                setProperty("x", pos.x.value.toString())
                setProperty("y", pos.y.value.toString())
            }
            setProperty("placement", state.placement.name)
        }
        file.outputStream().use { props.store(it, "Tenshu window state") }
    }
}
