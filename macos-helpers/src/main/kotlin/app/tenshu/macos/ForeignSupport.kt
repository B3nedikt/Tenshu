package app.tenshu.macos

/**
 * Whether the running JVM can make foreign (native) calls through
 * `java.lang.foreign` — Project Panama, final since JDK 22.
 *
 * This module *compiles* against a 22+ JDK (see its `build.gradle.kts`), but what
 * matters at runtime is the JRE the app actually ships with: jpackage bundles the
 * desktop apps' own runtime, and a stale bundle would fail at the first FFM call
 * rather than at startup. Callers that have a non-FFM fallback should branch on
 * this instead of assuming.
 *
 * The macOS camera scanner planned in `docs/proposals/desktop-qr-scanning.md` §13
 * is the first real consumer: option 3 opens `AVCaptureSession` *in-process*
 * through FFM so the JVM is its own responsible TCC subject, which is what makes
 * the §9 first-grant SIGKILL disappear. Until that lands this is only the
 * capability probe the toolchain bump (P0) exists to make possible.
 */
object ForeignSupport {

    /**
     * `true` when a native linker is obtainable — i.e. FFM is present *and*
     * permitted on this runtime. Both failure modes matter: an older JRE throws
     * on the class reference, and a restricted runtime can refuse the linker, so
     * every `Throwable` is treated as "unavailable" rather than propagated.
     */
    fun isAvailable(): Boolean = try {
        java.lang.foreign.Linker.nativeLinker()
        true
    } catch (e: Throwable) {
        false
    }
}
