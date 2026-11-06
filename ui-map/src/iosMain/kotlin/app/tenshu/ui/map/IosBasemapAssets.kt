package app.tenshu.ui.map

import app.tenshu.basemap.fileGlyphsUrl
import app.tenshu.ui.map.resources.Res
import org.jetbrains.compose.resources.ExperimentalResourceApi
import platform.Foundation.NSFileManager

/**
 * Where the bundled basemap bytes live on iOS.
 *
 * Android reads its tiles and glyph atlas out of `assets/` through the APK asset manager, which has
 * no iOS counterpart. The same two payloads are therefore staged into the app bundle as **Compose
 * resource files** (`stageIosBasemapResources` in `build.gradle.kts` mirrors them from
 * `src/androidMain/assets`, so the Android tree stays the single source of truth), and reached here
 * by absolute path.
 *
 * Two consequences are worth stating, because they are what make the iOS renderer *simpler* than the
 * Android one rather than merely different:
 *
 * 1. **No staging copy.** `OfflineBasemap.android.kt` copies the archive out of `assets/` into
 *    `filesDir` because `pmtiles://asset://` SIGABRTs the native reader (ui-map-agent.md) and because
 *    an asset is not a real file. A Compose resource on iOS already *is* a real file on disk, so
 *    `pmtiles://file://` can name it in place. Nothing is copied and nothing is written.
 * 2. **No region staging either.** The Android actual copies a downloaded region into app-private
 *    storage when it lives outside it. On iOS every path the process can read is already inside its
 *    sandbox, so a region bundle is opened where it lies.
 */
private const val BASEMAP_FILE = "files/tenshu_basemap.pmtiles"

/**
 * The bundled demo archive's absolute path, or null when it is missing from the bundle.
 *
 * ⚠ Returns a bare filesystem path, **not** a URL — the caller prefixes `pmtiles://file://`, exactly
 * as the Android actual does with its staged path, so both platforms build the source URL the same
 * way from the same commonMain style.
 */
@OptIn(ExperimentalResourceApi::class)
internal suspend fun bundledBasemapPath(): String? =
    runCatching { Res.getUri(BASEMAP_FILE).toFilesystemPath() }
        .getOrNull()
        ?.takeIf { fileExists(it) }

/**
 * The glyph atlas directory as a `file://` URL with no trailing slash, ready for [fileGlyphsUrl], or
 * null when the atlas is missing (in which case the caller must fall back to a label-free style
 * rather than emit a template MapLibre cannot resolve).
 *
 * Derived from the *archive's* URI rather than from a glyph file's, deliberately: a glyph file's path
 * contains a fontstack directory name, and those contain spaces (`"Noto Sans Regular"` —
 * ui-map-agent.md), so it would arrive percent-encoded and need unpicking. The archive's path has no
 * such segment.
 */
@OptIn(ExperimentalResourceApi::class)
internal suspend fun bundledGlyphsRoot(): String? {
    val uri = runCatching { Res.getUri(BASEMAP_FILE) }.getOrNull() ?: return null
    // …/compose-resources/files/tenshu_basemap.pmtiles -> …/compose-resources/files/glyphs
    val glyphsPath = "${uri.toFilesystemPath().substringBeforeLast('/')}/glyphs"
    // Returned DECODED, so the whole template is percent-encoding-free. MapLibre substitutes
    // `{fontstack}` verbatim — and the fontstacks contain spaces (`"Noto Sans Regular"`) — so a
    // half-encoded base would produce a path that does not exist. Android's `asset://` template is
    // likewise unencoded and is proven to resolve those names on device (see ui-map-agent.md).
    return "file://$glyphsPath".takeIf { fileExists(glyphsPath) }
}

/**
 * Strips a `file://` scheme (and any percent-encoding) off a Compose-resource URI, yielding a path
 * `NSFileManager` and MapLibre's local file source both understand.
 */
private fun String.toFilesystemPath(): String =
    removePrefix("file://").replace("%20", " ")

internal fun fileExists(path: String): Boolean =
    NSFileManager.defaultManager.fileExistsAtPath(path)
