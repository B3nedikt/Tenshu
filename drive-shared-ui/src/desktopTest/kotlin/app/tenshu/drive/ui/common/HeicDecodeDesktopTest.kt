package app.tenshu.drive.ui.common

import app.tenshu.macos.MacImageDecoder
import app.tenshu.ui.image.decodeToImageBitmap
import org.jetbrains.skia.Image
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the macOS ImageIO fallback in the desktop [decodeToImageBitmap] actual, and
 * the routing gate that stands in for it where it doesn't exist.
 *
 * The problem it guards: Compose Desktop decodes through Skia, and Skiko's Skia
 * build carries no HEIF/HEVC codec — so every photo taken on an iPhone (HEIC is
 * the iOS camera default) failed to open on the Mac with "Could not decode
 * image", despite macOS itself decoding HEIC natively. [skiaAloneCannotDecodeHeic]
 * is that premise written down as an assertion: if a future Skiko gains the codec
 * that test fails, and the bridge can be deleted.
 *
 * ImageIO-dependent tests self-skip off macOS (the helper is swiftc-built and
 * simply absent elsewhere), which is also exactly the production behaviour the
 * routing gate encodes.
 */
@OptIn(ExperimentalEncodingApi::class)
class HeicDecodeDesktopTest {

    /** A real 16x16 HEIC (`sips -s format heic`), the smallest honest fixture — no JVM library can encode one. */
    private val heicBytes: ByteArray = Base64.decode(
        "AAAAJGZ0eXBoZWljAAAAAG1pZjFNaVBybWlhZk1pSEJoZWljAAABw21ldGEAAAAAAAAAIWhkbHIAAAAAAAAAAHBp" +
        "Y3QAAAAAAAAAAAAAAAAAAAAAJGRpbmYAAAAcZHJlZgAAAAAAAAABAAAADHVybCAAAAABAAAADnBpdG0AAAAAAAEA" +
        "AAA4aWluZgAAAAAAAgAAABVpbmZlAgAAAAABAABodmMxAAAAABVpbmZlAgAAAQACAABFeGlmAAAAABppcmVmAAAA" +
        "AAAAAA5jZHNjAAIAAQABAAAA5mlwcnAAAADFaXBjbwAAABNjb2xybmNseAACAAIABoAAAAAMY2xsaQDLAEAAAAAU" +
        "aXNwZQAAAAAAAAAQAAAAEAAAAAlpcm90AAAAABBwaXhpAAAAAAMICAgAAABxaHZjQwEDcAAAALAAAAAAAB7wAPz9" +
        "+PgAAAsDoAABABdAAQwB//8DcAAAAwCwAAADAAADAB5wJKEAAQAjQgEBA3AAAAMAsAAAAwAAAwAeoBQgQcCTDOIe" +
        "5FlU3AgIGAKiAAEACUQBwGFyyERTZAAAABlpcG1hAAAAAAAAAAEAAQaBAgMFhoQAAAAsaWxvYwAAAABEAAACAAEA" +
        "AAABAAACdQAAAOcAAgAAAAEAAAH3AAAAfgAAAAFtZGF0AAAAAAAAAXUAAAAGRXhpZgAATU0AKgAAAAgABQEGAAMA" +
        "AAABAAIAAAESAAMAAAABAAEAAAFCAAQAAAABAAACAAFDAAQAAAABAAACAIdpAAQAAAABAAAASgAAAAAAA6ABAAMA" +
        "AAABAAEAAKACAAQAAAABAAAAEKADAAQAAAABAAAAEAAAAAAAAADjKAGvoRYwGmG1UxXrw1rVoP4nlBgCHfR7QtW0" +
        "hF7TaMEsi2gXHEqr5SxnpS2CMKmPwWfTusglkmZQC4/h96+LY055nnnsEcf+HbYofT87VRG+tXit//f2aA1H8d9J" +
        "P2wTasEiUTfGLPEzDA6lzhw5Pouszy/644Byht2TX9dF9MI9V9MjBoIPbBF92tLRi5Ux391Hiq1PKxTArAwia7iC" +
        "J8yeiA7bznY9f+kH//5A3OdiZr9k++X+gDAZHBcWlIOahK58mmSbr2j6CjKxLOu7VU2ZoZVSFR436LP4w+dFfenV" +
        "5/g="
    )

    @Test
    fun skiaAloneCannotDecodeHeic() {
        val skiaResult = runCatching { Image.makeFromEncoded(heicBytes) }.getOrNull()
        assertNull(
            skiaResult,
            "Skiko grew a HEIF codec — the ImageIO bridge in ImageDecode.desktop.kt is now redundant."
        )
    }

    @Test
    fun decodesHeicThroughTheImageIoBridge() {
        if (!MacImageDecoder.isAvailable()) return // non-macOS build host
        val bitmap = assertNotNull(
            decodeToImageBitmap(heicBytes),
            "HEIC should decode via the ImageIO helper on macOS"
        )
        assertEquals(16, bitmap.width)
        assertEquals(16, bitmap.height)
    }

    @Test
    fun heicIsPreviewableOnlyWhereTheBridgeExists() {
        assertEquals(MacImageDecoder.isAvailable(), isImagePreviewSupported("IMG_0421.HEIC"))
        assertEquals(MacImageDecoder.isAvailable(), isImagePreviewSupported("scan.heif"))
    }

    @Test
    fun skiaNativeFormatsStayPreviewableEverywhere() {
        assertTrue(isImagePreviewSupported("cat.jpg"))
        assertTrue(isImagePreviewSupported("cat.PNG"))
        assertTrue(isImagePreviewSupported("cat.webp"))
    }

    @Test
    fun garbageBytesDecodeToNullRatherThanThrowing() {
        assertNull(decodeToImageBitmap(ByteArray(0)))
        assertNull(decodeToImageBitmap(ByteArray(256) { it.toByte() }))
    }

    @Test
    fun helperRejectsNonImageBytesWithoutHanging() {
        if (!MacImageDecoder.isAvailable()) return
        assertNull(MacImageDecoder.decode("not an image".encodeToByteArray()))
    }
}
