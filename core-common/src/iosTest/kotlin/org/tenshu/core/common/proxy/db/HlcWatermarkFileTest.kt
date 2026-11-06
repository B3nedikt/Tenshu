@file:OptIn(ExperimentalForeignApi::class)

package org.tenshu.core.common.proxy.db

import kotlinx.cinterop.ExperimentalForeignApi

import platform.Foundation.NSFileManager
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSTemporaryDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [HlcWatermarkFile] on the simulator (`sync-hlc-tip-device-identity.md` TC-2b).
 *
 * ⚠⚠ The test that matters is [twoRuntimesOverOneDirectoryNeverGoBackwards]: **two instances over
 * one directory** is the headless stand-in for Control-iOS and Drive-iOS over one App-Group
 * container, which is the configuration that produced the live bug — an iPhone tip of 15:07
 * followed by one of 14:51, which the desktop read as a forked view and rendered as a red tray for
 * fifty minutes while sync was perfectly healthy.
 *
 * ⚠ What this cannot cover, and what the device pass is for: two genuine *processes*. `flock`
 * excludes per open file description, so two instances here do contend for real — but iOS
 * suspending a holder mid-write, the reason [HlcWatermarkFile.raiseTo] tries the lock rather than
 * waiting on it, only happens on a device.
 */
class HlcWatermarkFileTest {

    private fun tempDir(): String {
        val dir = NSTemporaryDirectory() + "hlc-watermark-" +
            NSProcessInfo.processInfo.globallyUniqueString
        NSFileManager.defaultManager.createDirectoryAtPath(
            dir,
            withIntermediateDirectories = true,
            attributes = null,
            error = null,
        )
        return dir
    }

    @Test
    fun unwrittenReadsAsNone() {
        assertEquals(HlcWatermarkFile.Stamp.NONE, HlcWatermarkFile(tempDir()).read())
    }

    @Test
    fun raiseToPersistsAcrossInstances() {
        // A new instance over the same directory is what a relaunched app is. Without this the
        // durability half of TC-2 is absent, and a restart republishes the (0,0) tip that silently
        // zeroes this device's tombstone-GC quorum on the relay.
        val dir = tempDir()
        HlcWatermarkFile(dir).raiseTo(HlcWatermarkFile.Stamp(1_787_922_439_939L, 3L))

        assertEquals(
            HlcWatermarkFile.Stamp(1_787_922_439_939L, 3L),
            HlcWatermarkFile(dir).read(),
        )
    }

    @Test
    fun raiseToNeverLowers() {
        val file = HlcWatermarkFile(tempDir())
        file.raiseTo(HlcWatermarkFile.Stamp(2_000L, 0L))

        assertEquals(
            HlcWatermarkFile.Stamp(2_000L, 0L),
            file.raiseTo(HlcWatermarkFile.Stamp(1_000L, 0L)),
        )
        assertEquals(HlcWatermarkFile.Stamp(2_000L, 0L), file.read())
    }

    @Test
    fun equalPhysicalTimeBreaksOnTheCounter() {
        // Two runtimes writing inside one millisecond is exactly when this matters, and it is the
        // case a physical-time-only comparison would wave through.
        val file = HlcWatermarkFile(tempDir())
        file.raiseTo(HlcWatermarkFile.Stamp(500L, 5L))

        assertEquals(
            HlcWatermarkFile.Stamp(500L, 5L),
            file.raiseTo(HlcWatermarkFile.Stamp(500L, 3L)),
        )
        assertEquals(
            HlcWatermarkFile.Stamp(500L, 9L),
            file.raiseTo(HlcWatermarkFile.Stamp(500L, 9L)),
        )
    }

    @Test
    fun twoRuntimesOverOneDirectoryNeverGoBackwards() {
        // The live bug, headless. `control` has ingested recently; `drive` has been idle since
        // earlier. Neither may be handed a position below what the device already published —
        // that backward step is precisely what a peer reads as divergence.
        val dir = tempDir()
        val control = HlcWatermarkFile(dir)
        val drive = HlcWatermarkFile(dir)

        val ahead = control.raiseTo(HlcWatermarkFile.Stamp(1_787_922_439_939L, 0L)) // 15:07
        val behind = drive.raiseTo(HlcWatermarkFile.Stamp(1_787_921_479_762L, 0L))  // 14:51

        assertEquals(HlcWatermarkFile.Stamp(1_787_922_439_939L, 0L), ahead)
        assertEquals(
            HlcWatermarkFile.Stamp(1_787_922_439_939L, 0L),
            behind,
            "the lagging runtime must adopt the device position, not publish its own",
        )
        assertEquals(HlcWatermarkFile.Stamp(1_787_922_439_939L, 0L), control.read())
    }

    @Test
    fun aHeldLockStillYieldsACorrectValueWithoutPersisting() {
        // The suspend-safe fallback. Hold the lock the way a suspended sibling app would, then ask
        // for a raise: the caller must still get a value at least its own — publishing a *lower*
        // tip is the whole failure this class prevents — while the file stays untouched, because
        // this runtime never got to write it.
        val dir = tempDir()
        val file = HlcWatermarkFile(dir)
        file.raiseTo(HlcWatermarkFile.Stamp(1_000L, 0L))

        val blocker = FileAdvisoryLock.open("$dir/watermark.lock")
        assertTrue(blocker.tryLock(), "precondition: the test holds the lock")
        try {
            val merged = file.raiseTo(HlcWatermarkFile.Stamp(5_000L, 0L))

            assertEquals(HlcWatermarkFile.Stamp(5_000L, 0L), merged, "never below the candidate")
            assertEquals(
                HlcWatermarkFile.Stamp(1_000L, 0L),
                file.read(),
                "and nothing was persisted while the lock was held elsewhere",
            )
        } finally {
            blocker.unlock()
            blocker.close()
        }
    }

    @Test
    fun aCorruptFileDegradesToNoneRatherThanThrowing() {
        // The watermark is an optimisation over a clock that works without it. A corrupt file must
        // cost the optimisation, never the launch.
        val dir = tempDir()
        val file = HlcWatermarkFile(dir)
        file.raiseTo(HlcWatermarkFile.Stamp(100L, 0L))
        NSFileManager.defaultManager.removeItemAtPath("$dir/${HlcWatermarkFile.STATE_FILE}", error = null)
        NSFileManager.defaultManager.createFileAtPath(
            "$dir/${HlcWatermarkFile.STATE_FILE}",
            contents = null,
            attributes = null,
        )

        assertEquals(HlcWatermarkFile.Stamp.NONE, file.read())
        assertEquals(
            HlcWatermarkFile.Stamp(50L, 0L),
            file.raiseTo(HlcWatermarkFile.Stamp(50L, 0L)),
        )
    }

    @Test
    fun theDirectoryIsCreatedOnDemand() {
        // `sharedReplicaDirectory(SUBDIRECTORY)` names a sibling of the store's own directory that
        // nothing else creates, so the first raise on a fresh install has to make it.
        val dir = tempDir() + "/not-yet-there"

        assertEquals(
            HlcWatermarkFile.Stamp(7L, 0L),
            HlcWatermarkFile(dir).raiseTo(HlcWatermarkFile.Stamp(7L, 0L)),
        )
        assertTrue(
            NSFileManager.defaultManager.fileExistsAtPath("$dir/${HlcWatermarkFile.STATE_FILE}"),
        )
    }

    @Test
    fun encodeDecodeRoundTrips() {
        val stamp = HlcWatermarkFile.Stamp(1_787_922_439_939L, 7L)
        assertEquals(stamp, HlcWatermarkFile.decode(HlcWatermarkFile.encode(stamp)))
    }
}
