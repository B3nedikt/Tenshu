package app.tenshu.macos.ffi

import java.lang.foreign.MemorySegment
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Camera-free proof that the Objective-C bridge actually dispatches
 * (`desktop-qr-scanning.md` §13 P1). Every test here talks to Foundation only — no
 * AVFoundation session, no TCC prompt, nothing that could crash the host — so it runs
 * unattended in the ordinary suite, which is the whole point of landing the primitive
 * before the camera work.
 *
 * What it deliberately covers, because each is a place the bridge can be silently wrong:
 * `objc_msgSend` binding (a variadic binding would return garbage on arm64, not fail),
 * reading through returned pointers, `NSString` round-tripping, framework-constant reads
 * by *identity*, and runtime class registration with a Kotlin upcall as the IMP.
 *
 * Self-skips off macOS, where [ObjC.isAvailable] is false by construction.
 */
class ObjCTest {

    private val mac = ObjC.isAvailable

    @Test
    fun resolvesKnownClassesAndRejectsUnknownOnes() {
        if (!mac) return
        assertFalse(ObjC.isNull(ObjC.cls("NSObject")), "NSObject must resolve")
        assertFalse(ObjC.isNull(ObjC.cls("NSString")), "NSString must resolve")
        assertTrue(
            ObjC.isNull(ObjC.cls("TenshuNoSuchClass")),
            "an unknown class resolves to nil, it does not throw",
        )
    }

    @Test
    fun internsSelectors() {
        if (!mac) return
        val a = ObjC.sel("description")
        val b = ObjC.sel("description")
        assertFalse(ObjC.isNull(a))
        assertEquals(a, b, "sel_registerName interns — the same name is the same pointer")
    }

    @Test
    fun roundTripsStringsThroughNSString() {
        if (!mac) return
        ObjC.autoreleasePool {
            val text = "tenshu ✓ 日本語"
            val ns = ObjC.nsString(text)
            assertFalse(ObjC.isNull(ns), "stringWithUTF8String: returned nil")
            assertEquals(text, ObjC.kotlinString(ns))
        }
    }

    @Test
    fun readsIntegerReturnsFromDispatch() {
        if (!mac) return
        ObjC.autoreleasePool {
            // `length` on an NSString is the classic integer-returning dispatch. A variadic
            // objc_msgSend binding tends to produce a plausible-but-wrong number here on
            // arm64 rather than failing outright, which is exactly why this is asserted.
            assertEquals(6L, ObjC.sendLong(ObjC.nsString("tenshu"), "length"))
            assertEquals(0L, ObjC.sendLong(ObjC.nsString(""), "length"))
        }
    }

    @Test
    fun readsBooleanReturnsFromDispatch() {
        if (!mac) return
        ObjC.autoreleasePool {
            val a = ObjC.nsString("tenshu")
            val b = ObjC.nsString("tenshu")
            val c = ObjC.nsString("other")
            assertTrue(ObjC.sendBool(a, "isEqualToString:", b))
            assertFalse(ObjC.sendBool(a, "isEqualToString:", c))
        }
    }

    @Test
    fun allocatesAndReleasesObjects() {
        if (!mac) return
        val obj = ObjC.allocInit("NSObject")
        assertFalse(ObjC.isNull(obj), "alloc/init must produce an object")
        ObjC.release(obj)
        ObjC.release(MemorySegment.NULL) // nil-safe
    }

    @Test
    fun readsFrameworkConstantsByIdentity() {
        if (!mac) return
        // AVMediaTypeVideo is an NSString* the API compares by identity, so it has to be
        // *read* from the framework rather than rebuilt from its text. Loading the
        // framework prompts for nothing — only using the camera would.
        val mediaTypeVideo = ObjC.frameworkConstant("AVFoundation", "AVMediaTypeVideo")
        assertFalse(ObjC.isNull(mediaTypeVideo), "AVMediaTypeVideo must resolve")
        assertEquals("vide", ObjC.kotlinString(mediaTypeVideo))
    }

    @Test
    fun missingFrameworkConstantResolvesToNull() {
        if (!mac) return
        assertTrue(ObjC.isNull(ObjC.frameworkConstant("AVFoundation", "TenshuNoSuchSymbol")))
    }

    @Test
    fun registersARuntimeClassWhoseMethodCallsBackIntoKotlin() {
        if (!mac) return
        val calls = AtomicInteger()
        val cls = ObjC.registerClass(
            name = "TenshuP1ProbeDelegate",
            superclassName = "NSObject",
            methods0 = mapOf("tenshuPing" to ("v@:" to ObjC.Method0 { _, _ -> calls.incrementAndGet() })),
        )
        assertFalse(ObjC.isNull(cls), "the class pair must register")

        val instance = ObjC.send(ObjC.send(cls, "alloc"), "init")
        assertFalse(ObjC.isNull(instance))
        try {
            ObjC.sendVoid(instance, "tenshuPing")
            ObjC.sendVoid(instance, "tenshuPing")
            // The real proof: the ObjC runtime dispatched into a Kotlin lambda through an
            // FFM upcall stub. This is the mechanism the AVFoundation frame delegate needs.
            assertEquals(2, calls.get(), "the upcall body must run once per message")
        } finally {
            ObjC.release(instance)
        }
    }

    @Test
    fun classRegistrationIsIdempotent() {
        if (!mac) return
        // The runtime refuses a second allocateClassPair for a live name, so a repeat call
        // must return the cached class rather than nil (and must not crash).
        val first = ObjC.registerClass("TenshuP1IdempotentProbe", "NSObject")
        val second = ObjC.registerClass("TenshuP1IdempotentProbe", "NSObject")
        assertFalse(ObjC.isNull(first))
        assertEquals(first, second)
    }
}
