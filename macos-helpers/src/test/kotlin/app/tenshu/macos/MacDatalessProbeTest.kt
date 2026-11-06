package app.tenshu.macos

import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Detection of macOS dataless (online-only) placeholders — see
 * `drive-folder-upload.md` §11 P2. A *dataless* file can only be produced by a
 * real cloud File Provider, so we can't fabricate one in a unit test; what we can
 * pin is the negative direction (a real on-disk file / a missing path are NOT
 * flagged), which is the behaviour-preserving guarantee that matters — a false
 * positive would wrongly divert a local file.
 *
 * ⚠⚠ **Off macOS this class used to be three false greens and one honest red.**
 * [MacDatalessProbe] resolves its `stat` handle to `null` when `os.name` is not Mac, so
 * `isDataless` returns `false` on Linux **without consulting the file at all** — and the three
 * "is NOT dataless" assertions passed by hitting that fallback, testing nothing. The fourth
 * shelled out to `chflags`, a macOS-only binary, and failed with `Cannot run program "chflags"`,
 * which is what made the whole gate red for every Linux developer.
 *
 * ⚠ Skipping the lot would have swapped three misleading greens for four silent skips. Instead,
 * each test now states which platform's contract it is pinning: on macOS the real probe, off it
 * the documented all-or-nothing fallback (§"All-or-nothing" on [MacDatalessProbe]), which is a
 * genuine and previously untested guarantee — a caller on Linux must read the file normally.
 * Same shape as `MacCameraTest`, which asserts `CameraAuthorization.Unavailable` off-platform
 * rather than returning early.
 */
class MacDatalessProbeTest {

    /**
     * ⚠ Mirrors [MacDatalessProbe]'s own `isMac` (private there, so it cannot be imported) and
     * `ObjC.isMac`. Spelled the same way in all three; if one moves they all must.
     */
    private val mac = System.getProperty("os.name").orEmpty().startsWith("Mac", ignoreCase = true)

    private val file = Files.createTempFile("dataless-probe", ".bin").toFile().apply {
        writeBytes(ByteArray(1024) { 1 })
    }

    @AfterTest
    fun cleanup() {
        file.delete()
    }

    @Test
    fun anOrdinaryLocalFileWithBytesOnDisk_isNotDataless() {
        assertFalse(MacDatalessProbe.isDataless(file.absolutePath))
    }

    @Test
    fun aMissingPath_isNotDataless_neverThrows() {
        assertFalse(MacDatalessProbe.isDataless("/no/such/path/${System.nanoTime()}"))
    }

    @Test
    fun anEmptyPath_isNotDataless_neverThrows() {
        assertFalse(MacDatalessProbe.isDataless(""))
    }

    /**
     * The off-macOS half of the contract, which nothing pinned before: the probe must degrade to
     * "ordinary local file" rather than throw or guess. This is the branch every Linux and Windows
     * caller actually takes, so it is the one assertion in this class that means something here.
     */
    @Test
    fun offMacOs_theProbeIsInertRatherThanBroken() {
        if (mac) return
        assertNull(
            MacDatalessProbe.stFlagsOrNull(file.absolutePath),
            "off macOS there is no `stat` handle, so st_flags must be null — not a fabricated 0",
        )
        assertFalse(
            MacDatalessProbe.isDataless(file.absolutePath),
            "the documented all-or-nothing default: treat every file as ordinary and local",
        )
    }

    @Test
    fun stFlags_readsTheCorrectStructStatOffset_viaAKnownChflag() {
        // ⚠⚠ macOS only, and it must STAY a real assertion there rather than become a
        // cross-platform no-op: `chflags` is a BSD binary that does not exist on Linux, and
        // exec-ing it threw `Cannot run program "chflags"` on every non-Mac gate run. The guard
        // is the fix; weakening the assertions below is not.
        if (!mac) return
        // We can't fabricate an SF_DATALESS file in a unit test (only a real cloud
        // File Provider makes one), so instead pin that `st_flags` is read from the
        // right `struct stat` offset by setting a flag we CAN set — UF_IMMUTABLE via
        // `chflags uchg` — and confirming the probe sees it. A wrong offset (the
        // getattrlist bug) reads garbage and would miss this bit too.
        val ufImmutable = 0x2
        Runtime.getRuntime().exec(arrayOf("chflags", "uchg", file.absolutePath)).waitFor()
        try {
            val flags = MacDatalessProbe.stFlagsOrNull(file.absolutePath)
            assertNotNull(flags, "stat should have succeeded")
            assertTrue(
                (flags and ufImmutable) != 0,
                "expected UF_IMMUTABLE set; read st_flags=0x${flags.toUInt().toString(16)}",
            )
        } finally {
            Runtime.getRuntime().exec(arrayOf("chflags", "nouchg", file.absolutePath)).waitFor()
        }
    }
}
