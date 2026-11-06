package app.tenshu.macos

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.ValueLayout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Proves the P0 toolchain split actually works, end to end.
 *
 * The claim P0 rests on is that this module can compile *and run* real
 * `java.lang.foreign` code while still emitting JVM-17 bytecode its 17-target
 * consumers can read (`desktop-qr-scanning.md` §13 D1, as-built). A test that
 * merely referenced the FFM classes would compile on a mis-set toolchain and
 * prove nothing, so [downcallsIntoLibc] performs an actual native call: resolve
 * `strlen` through the default lookup, bind it, and invoke it on off-heap memory.
 * If the toolchain, the linker, or the runtime were wrong, this fails.
 *
 * The bytecode half of the claim isn't checkable from inside the JVM that loaded
 * these classes — it's verified in CI-by-hand terms by the consumers compiling at
 * all (`:ui-core`, `:file-service-proxy` et al. are on `jvmToolchain(17)`; a v66+
 * class file in this jar would break their compile immediately).
 */
class ForeignSupportTest {

    @Test
    fun reportsForeignAccessAvailable() {
        assertTrue(
            ForeignSupport.isAvailable(),
            "Tests run on the module's own toolchain JDK, which must be FFM-final (22+)",
        )
    }

    @Test
    fun downcallsIntoLibc() {
        val linker = Linker.nativeLinker()
        val strlen = linker.defaultLookup().find("strlen").orElseThrow()
        val handle = linker.downcallHandle(
            strlen,
            FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS),
        )

        Arena.ofConfined().use { arena ->
            val text = arena.allocateFrom("tenshu")
            assertEquals(6L, handle.invokeExact(text) as Long)
        }
    }
}
