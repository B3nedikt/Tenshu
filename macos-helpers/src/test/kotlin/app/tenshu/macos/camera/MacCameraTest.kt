package app.tenshu.macos.camera

import app.tenshu.macos.ffi.ObjC
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Covers the in-process camera engine up to — but deliberately **not including** —
 * `start()` (`desktop-qr-scanning.md` §13 P2).
 *
 * That line is where the test boundary belongs. Building the whole graph (device, input,
 * session, BGRA output, delegate, queue) exercises nearly all the FFM wiring and turns the
 * camera on for exactly nobody; `startRunning` is the one call that activates hardware,
 * lights the indicator, and would make an unattended suite both noisy and machine-dependent.
 * Live capture is the environmental gate §13.4 already reserves for a human.
 *
 * Tests that need real authorization self-skip when it isn't already granted, so this never
 * *provokes* a permission dialog — asking is a decision for the app, not for a test run.
 */
class MacCameraTest {

    private val mac = ObjC.isAvailable
    private val authorized = mac && MacCamera.authorizationStatus() == CameraAuthorization.Authorized

    @Test
    fun reportsAnAuthorizationStatusWithoutTouchingTheCamera() {
        if (!mac) {
            assertEquals(CameraAuthorization.Unavailable, MacCamera.authorizationStatus())
            return
        }
        // Any concrete answer is fine — the point is that a *pure status read* works and
        // never opens a session, which is what makes the §9 pre-grant explainer possible.
        val status = MacCamera.authorizationStatus()
        assertTrue(
            status in setOf(
                CameraAuthorization.Authorized,
                CameraAuthorization.NotDetermined,
                CameraAuthorization.Denied,
                CameraAuthorization.NoCamera,
            ),
            "unexpected status $status",
        )
    }

    @Test
    fun invokesAnObjectiveCBlockBuiltFromKotlin() {
        if (!mac) return
        // Blocks are the mechanism `requestAccessForMediaType:completionHandler:` needs, and
        // a hand-laid-out block literal either works or corrupts the stack — so prove it on
        // a Foundation API that takes one and needs no permission at all.
        val seen = AtomicInteger()
        val body = EnumerateBlock { _, _, _, _ -> seen.incrementAndGet() }
        val handle = MethodHandles.lookup()
            .findVirtual(
                EnumerateBlock::class.java, "invoke",
                MethodType.methodType(
                    Void.TYPE,
                    MemorySegment::class.java, MemorySegment::class.java,
                    Long::class.java, MemorySegment::class.java,
                ),
            )
            .bindTo(body)
        val block = ObjC.globalBlock(
            FunctionDescriptor.ofVoid(
                ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                ValueLayout.JAVA_LONG, ValueLayout.ADDRESS,
            ),
            handle,
        )
        assertFalse(ObjC.isNull(block), "the block literal must be constructible")

        ObjC.autoreleasePool {
            val array = ObjC.send(
                ObjC.cls("NSArray"), "arrayWithObject:", ObjC.nsString("tenshu"),
            )
            assertFalse(ObjC.isNull(array))
            ObjC.sendVoid(array, "enumerateObjectsUsingBlock:", block)
            // The ObjC runtime called into Kotlin through our own hand-laid-out block.
            assertEquals(1, seen.get(), "the block body must run once per element")
        }
    }

    @Test
    fun requestAccessReturnsImmediatelyWhenAlreadyAuthorized() {
        if (!authorized) return
        // Already-authorized means no dialog, so this exercises the real production block
        // path — the same call that SIGKILLed the host when a *spawned helper* made it — and
        // proves the completion fires and the latch is released. First-grant behaviour on a
        // fresh machine stays the human gate (§13.4).
        assertTrue(MacCamera.requestAccess(timeoutSeconds = 5))
    }

    @Test
    fun buildsAWiredButNotRunningSession() {
        if (!authorized) return
        val session = assertNotNull(
            MacCamera.createSession(onFrame = { }),
            "a session must be constructible when the camera is authorized",
        )
        try {
            // The whole graph exists and the camera is still off — the split that keeps this
            // test suite unattended.
            assertFalse(session.isRunning(), "createSession must not start capture")
        } finally {
            session.close()
        }
    }

    @Test
    fun closeIsIdempotentAndSurvivesDoubleCall() {
        if (!authorized) return
        val session = assertNotNull(MacCamera.createSession(onFrame = { }))
        session.close()
        session.close()
        assertFalse(session.isRunning(), "a closed session never reports running")
    }

    /** `void (^)(id obj, NSUInteger idx, BOOL *stop)`, prefixed by the block pointer. */
    private fun interface EnumerateBlock {
        fun invoke(block: MemorySegment, obj: MemorySegment, index: Long, stop: MemorySegment)
    }
}
