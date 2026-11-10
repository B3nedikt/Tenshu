package app.tenshu.ui.layout

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.window.FrameWindowScope
import java.awt.Image
import javax.imageio.ImageIO

/**
 * Gives the window the app's own icon on Windows — title bar, Alt+Tab, and the taskbar of an unpackaged run
 * (docs/adr/0026-app-icon-system.md §6b). Call once, first thing inside `Window { }`.
 *
 * ⚠ Without it Windows shows the JVM's default icon however well the package is branded: the taskbar of a packaged
 * app comes from its manifest logos, but the window's own icon is whatever AWT was told. macOS needs no equivalent:
 * the Dock draws the bundle's `.icns` and ignores window icons.
 *
 * The images are `/tenshu-app-icon/<N>.png` on the app's classpath, from `<module>/icons/windows/window/`
 * (scripts/windows-app-icons.py). Every size is handed over so Windows picks a real render for each place rather
 * than scaling one; a module without them keeps the default rather than failing.
 */
@Composable
fun FrameWindowScope.TenshuWindowIcon() {
    LaunchedEffect(window) {
        if (!isWindows) return@LaunchedEffect
        val images = appIconImages()
        if (images.isNotEmpty()) window.iconImages = images
    }
}

private val isWindows = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)

/** Mirrors `WINDOW_SIZES` in scripts/windows-app-icons.py. */
private val SIZES = listOf(16, 20, 24, 32, 40, 48, 64, 128, 256)

internal fun appIconImages(loader: ClassLoader = TenshuWindowIconMarker::class.java.classLoader): List<Image> =
    SIZES.mapNotNull { size -> appIconImage(size, loader) }

/**
 * The app's own icon at [size] px (one of 16 20 24 32 40 48 64 128 256), or null when the app ships none — for
 * other places that draw it, such as an in-process tray icon.
 */
fun appIconImage(
    size: Int,
    loader: ClassLoader = TenshuWindowIconMarker::class.java.classLoader,
): java.awt.image.BufferedImage? =
    loader.getResourceAsStream("tenshu-app-icon/$size.png")?.use { runCatching { ImageIO.read(it) }.getOrNull() }

private object TenshuWindowIconMarker
