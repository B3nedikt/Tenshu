package app.tenshu.macos.ffi

import java.lang.foreign.Arena
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Media-free proof that `CMTime` crosses the bridge intact — the one thing
 * [ObjC.msgSendReturningStruct] exists for, and the one thing that cannot be proved by
 * compiling.
 *
 * ⚠⚠ **Why this is worth a test file of its own.** A wrong struct-return binding does not
 * throw. On x86_64, calling plain `objc_msgSend` for a struct return makes the callee read
 * the *receiver* as the hidden result pointer and write a frame's worth of bytes through it;
 * on arm64 a mis-declared layout silently reads the wrong 8 bytes. Both surface far away, as
 * a player that seeks to the wrong place or a frame pump that never advances. So the round
 * trip is pinned here, in the ordinary suite, against Foundation and CoreMedia only — no
 * media file, no window, no permission prompt.
 *
 * `NSValue`'s CMTime category is the ideal probe: `+valueWithCMTime:` takes the struct **by
 * value** and `-CMTimeValue` returns one **by value**, so a single round trip exercises both
 * directions plus the field layout, using nothing but an object that boxes what it is given.
 *
 * Self-skips off macOS, where [ObjC.isAvailable] is false by construction.
 */
class CoreMediaTest {

    private val mac = ObjC.isAvailable

    @Test
    fun buildsATimeFromSecondsAndReadsItBack() {
        if (!mac) return
        Arena.ofConfined().use { arena ->
            val time = assertNotNull(
                CoreMedia.ofSeconds(arena, 2.5),
                "CMTimeMakeWithSeconds must bind — a C struct return, no ObjC symbol split",
            )
            val seconds = assertNotNull(CoreMedia.seconds(time))
            assertTrue(
                abs(seconds - 2.5) < 1e-9,
                "2.5s must survive the rational round trip, got $seconds",
            )
        }
    }

    @Test
    fun aTimeSurvivesAnObjectiveCRoundTripByValue() {
        if (!mac) return
        // The category lives in AVFoundation; loading it is what publishes the selectors.
        ObjC.framework("AVFoundation")

        Arena.ofConfined().use { arena ->
            ObjC.autoreleasePool {
                val original = assertNotNull(CoreMedia.ofSeconds(arena, 12.75))

                // Struct IN: +[NSValue valueWithCMTime:] takes the 24 bytes by value.
                val boxed = assertNotNull(
                    CoreMedia.sendReturningBoxedTime(ObjC.cls("NSValue"), original),
                )
                assertFalse(ObjC.isNull(boxed), "NSValue must box the time")

                // Struct OUT: -[NSValue CMTimeValue] returns them by value, through whichever
                // objc_msgSend variant this architecture requires.
                val unboxed = assertNotNull(
                    CoreMedia.sendReturningTime(arena, boxed, "CMTimeValue"),
                    "CMTimeValue must bind — this is the arch split doing its job",
                )
                val seconds = assertNotNull(
                    CoreMedia.seconds(unboxed),
                    "a round-tripped time must still be valid; an invalid one means the " +
                        "flags field landed at the wrong offset",
                )
                assertTrue(
                    abs(seconds - 12.75) < 1e-9,
                    "12.75s must survive Objective-C by value, got $seconds",
                )
            }
        }
    }

    @Test
    fun anInvalidTimeReadsAsAbsentRatherThanNaN() {
        if (!mac) return
        Arena.ofConfined().use { arena ->
            // All-zero is `kCMTimeInvalid`'s shape: no valid flag, zero timescale. Dividing
            // would hand the caller NaN to propagate instead of an absence to handle — which
            // is what a duration read before the item is ready actually looks like.
            val zeroed = arena.allocate(CoreMedia.CM_TIME)
            assertNull(CoreMedia.seconds(zeroed))
        }
    }

    @Test
    fun theLayoutIsTheOneCoreMediaDeclares() {
        // No macOS needed: this is arithmetic on our own declaration, and it is the assertion
        // that catches a field being added, reordered or padded into the wrong place.
        assertEquals(24L, CoreMedia.CM_TIME.byteSize(), "CMTime is 24 bytes")
        assertTrue(
            CoreMedia.CM_TIME.byteSize() > 16L,
            "> 16 bytes is precisely why the struct is returned indirectly, and why " +
                "ObjC.msgSendReturningStruct has to pick the symbol by architecture",
        )
    }

    @Test
    fun theHostClockAdvances() {
        if (!mac) return
        val first = CoreMedia.currentMediaTime()
        assertTrue(first > 0.0, "CACurrentMediaTime must bind and return a real reading")
        @Suppress("detekt.LoopWithTooManyJumpStatements")
        var second = CoreMedia.currentMediaTime()
        while (second == first) second = CoreMedia.currentMediaTime()
        assertTrue(second > first, "the host clock must move forward")
    }
}
