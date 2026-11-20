package app.tenshu.windows

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle

/**
 * How MSIX packages came to be on this PC, for "which store does this copy update from"
 * (ADR 0025: a store link is offered only to a copy that came from that store).
 *
 * - [current]: this process's own package — its family name and [WindowsPackageOrigin] — or `null`
 *   without package identity (a dev run, a jpackage image).
 * - [originOf]: how the package of a family is installed for this user, or `null` when it is not
 *   installed or cannot be read.
 *
 * ⚠ Presence and origin only, never a trust decision: the worst a spoofed answer can do is show a
 * link to a public Store page. Trust stays with the peer gate ([WindowsPeerDecision]).
 *
 * ⚠⚠ Loads on a JVM without `java.lang.foreign` (the JDK-17 test JVMs): every FFM type is inside
 * [PackageNatives], loaded within `runCatching`. Same rule as [WindowsPeerProcess].
 */
object WindowsInstalledPackages {

    /** One installed package: its family name (`<identity name>_<publisher id>`) and origin. */
    data class Copy(val familyName: String, val origin: Int?) {
        /** The identity name, without the publisher id. */
        val identityName: String get() = familyName.substringBeforeLast('_')
    }

    private val isWindows = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)

    private val natives: PackageNatives? by lazy {
        if (!isWindows) null else runCatching { PackageNatives.loadOrNull() }.getOrNull()
    }

    fun isAvailable(): Boolean = natives != null

    /** This process's package, or `null` without package identity or off Windows. */
    fun current(): Copy? = natives?.let { n -> runCatching { n.current() }.getOrNull() }

    /** The [WindowsPackageOrigin] of [familyName]'s package installed for this user, or `null`. */
    fun originOf(familyName: String): Int? = natives?.let { n -> runCatching { n.originOf(familyName) }.getOrNull() }
}

/** Every `java.lang.foreign` reference of [WindowsInstalledPackages]. */
private class PackageNatives private constructor(
    private val getCurrentPackageFamilyName: MethodHandle,
    private val getCurrentPackageFullName: MethodHandle,
    private val getPackagesByPackageFamily: MethodHandle,
    private val getStagedPackageOrigin: MethodHandle,
) {

    fun current(): WindowsInstalledPackages.Copy? = Arena.ofConfined().use { arena ->
        val family = currentName(arena, getCurrentPackageFamilyName) ?: return@use null
        val fullName = currentName(arena, getCurrentPackageFullName)
        WindowsInstalledPackages.Copy(family, fullName?.let { originOfFullName(arena, it) })
    }

    fun originOf(familyName: String): Int? = Arena.ofConfined().use { arena ->
        val family = arena.allocateFrom(familyName, Charsets.UTF_16LE)
        val count = arena.allocate(ValueLayout.JAVA_INT)
        val bufferLength = arena.allocate(ValueLayout.JAVA_INT)
        // First call: sizes only. ERROR_INSUFFICIENT_BUFFER (122) is the answer when the family is installed.
        val sizing = getPackagesByPackageFamily.invokeExact(
            family, count, MemorySegment.NULL, bufferLength, MemorySegment.NULL,
        ) as Int
        if (sizing != ERROR_INSUFFICIENT_BUFFER && sizing != 0) return@use null
        val n = count.get(ValueLayout.JAVA_INT, 0L)
        if (n <= 0) return@use null
        val names = arena.allocate(ValueLayout.ADDRESS, n.toLong())
        val buffer = arena.allocate(bufferLength.get(ValueLayout.JAVA_INT, 0L) * 2L + 2)
        val rc = getPackagesByPackageFamily.invokeExact(family, count, names, bufferLength, buffer) as Int
        if (rc != 0) return@use null
        // One user has one version of a family installed; take the first full name.
        val first = names.getAtIndex(ValueLayout.ADDRESS, 0L).reinterpret(Long.MAX_VALUE)
        originOfFullName(arena, utf16z(first, maxChars = NAME_CHARS))
    }

    private fun currentName(arena: Arena, function: MethodHandle): String? {
        val length = arena.allocate(ValueLayout.JAVA_INT).also { it.set(ValueLayout.JAVA_INT, 0L, NAME_CHARS) }
        val buffer = arena.allocate(NAME_CHARS * 2L)
        // APPMODEL_ERROR_NO_PACKAGE (15700) is the ordinary answer for a process without package identity.
        return if (function.invokeExact(length, buffer) as Int == 0) utf16z(buffer, NAME_CHARS) else null
    }

    private fun originOfFullName(arena: Arena, fullName: String): Int? {
        val origin = arena.allocate(ValueLayout.JAVA_INT)
        val rc = getStagedPackageOrigin.invokeExact(arena.allocateFrom(fullName, Charsets.UTF_16LE), origin) as Int
        return if (rc == 0) origin.get(ValueLayout.JAVA_INT, 0L) else null
    }

    private fun utf16z(buffer: MemorySegment, maxChars: Int): String {
        val chars = StringBuilder()
        var offset = 0L
        while (chars.length < maxChars) {
            val c = buffer.get(ValueLayout.JAVA_CHAR_UNALIGNED, offset)
            if (c == '\u0000') break
            chars.append(c)
            offset += 2
        }
        return chars.toString()
    }

    companion object {
        private const val NAME_CHARS = 256
        private const val ERROR_INSUFFICIENT_BUFFER = 122

        fun loadOrNull(): PackageNatives? {
            if (ValueLayout.ADDRESS.byteSize() != 8L) return null
            val linker = Linker.nativeLinker()
            fun handle(library: String, symbol: String, descriptor: FunctionDescriptor): MethodHandle? {
                val address = SymbolLookup.libraryLookup(library, Arena.global()).find(symbol).orElse(null) ?: return null
                return linker.downcallHandle(address, descriptor)
            }
            // Some app-model functions are exported by kernelbase.dll only (WindowsPeerProcess, measured).
            fun appModel(symbol: String, descriptor: FunctionDescriptor): MethodHandle? =
                handle("kernel32.dll", symbol, descriptor) ?: handle("kernelbase.dll", symbol, descriptor)
            val i = ValueLayout.JAVA_INT
            val a = ValueLayout.ADDRESS
            return PackageNatives(
                // LONG GetCurrentPackageFamilyName(UINT32* length, PWSTR name)
                getCurrentPackageFamilyName = appModel("GetCurrentPackageFamilyName", FunctionDescriptor.of(i, a, a)) ?: return null,
                // LONG GetCurrentPackageFullName(UINT32* length, PWSTR name)
                getCurrentPackageFullName = appModel("GetCurrentPackageFullName", FunctionDescriptor.of(i, a, a)) ?: return null,
                // LONG GetPackagesByPackageFamily(PCWSTR family, UINT32* count, PWSTR* fullNames,
                //   UINT32* bufferLength, WCHAR* buffer)
                getPackagesByPackageFamily = appModel("GetPackagesByPackageFamily", FunctionDescriptor.of(i, a, a, a, a, a)) ?: return null,
                // LONG GetStagedPackageOrigin(PCWSTR packageFullName, PackageOrigin* origin)
                getStagedPackageOrigin = appModel("GetStagedPackageOrigin", FunctionDescriptor.of(i, a, a)) ?: return null,
            )
        }
    }
}
