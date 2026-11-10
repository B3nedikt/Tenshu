package app.tenshu.windows

import java.io.FileDescriptor
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle
import java.lang.reflect.InaccessibleObjectException
import java.nio.channels.SocketChannel

/**
 * Reads [WindowsPeerFacts] for the process on the far end of an accepted AF_UNIX socket
 * (`docs/proposals/desktop-windows-port.md` §3.2).
 *
 * The sequence, and why it is in this order:
 *  1. `WSAIoctl(SIO_AF_UNIX_GETPEERPID)` — the peer's pid, from the kernel, not from the peer.
 *  2. `OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION)` — and the handle is **held until the last
 *     read**: an open handle keeps the pid from being recycled, so every fact below describes one
 *     and the same process. (Whether that process is the one that connected is
 *     [WindowsPeerDecision]'s creation-time check.)
 *  3. `GetProcessTimes` (created), `GetExitCodeProcess` (alive), `GetPackageFamilyName`,
 *     `QueryFullProcessImageNameW`, and `GetPackageFullName` → `GetStagedPackageOrigin` (how the
 *     package was installed: a loose registration can claim our family name, see [WindowsPeerDecision]).
 *
 * ⚠ Requires `--add-opens java.base/sun.nio.ch=ALL-UNNAMED` and `java.base/java.io`, like macOS'
 * `PeerAuditToken`: the JDK exposes no public route to a channel's handle, and on Windows a socket's
 * `SOCKET` lives in `FileDescriptor.fd`. A missing flag yields a [Result.Unavailable] that says so —
 * otherwise a build mistake would read exactly like a hostile peer.
 *
 * ⚠⚠ Loads on a JVM without `java.lang.foreign` (the JDK-17 test JVMs): every FFM type is inside
 * [Natives], loaded within `runCatching`. Same rule as `WindowsDpapi`.
 */
object WindowsPeerProcess {

    sealed interface Result {
        data class Facts(val facts: WindowsPeerFacts) : Result
        data class Unavailable(val reason: String) : Result
    }

    private val isWindows = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)

    private val natives: Natives? by lazy { if (!isWindows) null else runCatching { Natives.loadOrNull() }.getOrNull() }

    fun isAvailable(): Boolean = natives != null

    /** Read the facts for [channel]'s peer. The channel must be an accepted, still-open AF_UNIX socket. */
    fun read(channel: SocketChannel): Result {
        val n = natives ?: return Result.Unavailable("Windows peer lookup unavailable on this JVM/OS")
        val socket = try {
            socketHandleOf(channel)
        } catch (e: InaccessibleObjectException) {
            return Result.Unavailable(
                "cannot read the peer socket — this JVM is missing " +
                    "--add-opens java.base/sun.nio.ch=ALL-UNNAMED and java.base/java.io=ALL-UNNAMED",
            )
        } catch (e: Throwable) {
            return Result.Unavailable("cannot read the peer socket: ${e::class.simpleName}: ${e.message}")
        }
        return runCatching { n.read(socket) }.getOrElse {
            Result.Unavailable("peer lookup failed: ${it::class.simpleName}: ${it.message}")
        }
    }

    /** The `SOCKET` behind [channel] (a `FileDescriptor.fd` on Windows; zero-extended to UINT_PTR). */
    private fun socketHandleOf(channel: SocketChannel): Long {
        var type: Class<*>? = channel.javaClass
        while (type != null) {
            for (field in type.declaredFields) {
                if (field.type == FileDescriptor::class.java) {
                    field.isAccessible = true
                    val descriptor = field.get(channel) as FileDescriptor
                    val raw = FileDescriptor::class.java.getDeclaredField("fd")
                    raw.isAccessible = true
                    return (raw.get(descriptor) as Int).toLong() and 0xFFFF_FFFFL
                }
            }
            type = type.superclass
        }
        error("no FileDescriptor field on ${channel.javaClass.name}")
    }
}

/** Every `java.lang.foreign` reference of [WindowsPeerProcess]. */
private class Natives private constructor(
    private val wsaIoctl: MethodHandle,
    private val openProcess: MethodHandle,
    private val closeHandle: MethodHandle,
    private val getProcessTimes: MethodHandle,
    private val getExitCodeProcess: MethodHandle,
    private val getPackageFamilyName: MethodHandle,
    private val queryImageName: MethodHandle,
    private val getPackageFullName: MethodHandle,
    private val getStagedPackageOrigin: MethodHandle,
) {

    fun read(socket: Long): WindowsPeerProcess.Result = Arena.ofConfined().use { arena ->
        val pidOut = arena.allocate(ValueLayout.JAVA_INT)
        val returned = arena.allocate(ValueLayout.JAVA_INT)
        val rc = wsaIoctl.invokeExact(
            socket,
            SIO_AF_UNIX_GETPEERPID,
            MemorySegment.NULL,
            0,
            pidOut,
            4,
            returned,
            MemorySegment.NULL,
            MemorySegment.NULL,
        ) as Int
        if (rc != 0) return@use WindowsPeerProcess.Result.Unavailable("WSAIoctl(SIO_AF_UNIX_GETPEERPID) failed")
        val pid = pidOut.get(ValueLayout.JAVA_INT, 0L).toLong() and 0xFFFF_FFFFL

        val process = openProcess.invokeExact(PROCESS_QUERY_LIMITED_INFORMATION, 0, pid.toInt()) as MemorySegment
        if (process.address() == 0L) {
            return@use WindowsPeerProcess.Result.Unavailable("peer pid $pid could not be opened (exited, or access denied)")
        }
        try {
            val times = arena.allocate(8L * 4, 8L)
            val timesOk = getProcessTimes.invokeExact(
                process, times, times.asSlice(8), times.asSlice(16), times.asSlice(24),
            ) as Int
            if (timesOk == 0) {
                return@use WindowsPeerProcess.Result.Unavailable("GetProcessTimes failed for pid $pid")
            }
            val exitCode = arena.allocate(ValueLayout.JAVA_INT)
            val exitOk = getExitCodeProcess.invokeExact(process, exitCode) as Int
            val alive = exitOk != 0 && exitCode.get(ValueLayout.JAVA_INT, 0L) == STILL_ACTIVE

            WindowsPeerProcess.Result.Facts(
                WindowsPeerFacts(
                    pid = pid,
                    createdAtMillis = fileTimeToEpochMillis(times.get(ValueLayout.JAVA_LONG, 0L)),
                    alive = alive,
                    packageFamilyName = familyName(arena, process),
                    imagePath = imagePath(arena, process),
                    packageOrigin = origin(arena, process),
                ),
            )
        } finally {
            closeHandle.invokeExact(process) as Int
        }
    }

    private fun familyName(arena: Arena, process: MemorySegment): String? {
        val length = arena.allocate(ValueLayout.JAVA_INT).also { it.set(ValueLayout.JAVA_INT, 0L, NAME_CHARS) }
        val buffer = arena.allocate(NAME_CHARS * 2L)
        val rc = getPackageFamilyName.invokeExact(process, length, buffer) as Int
        // APPMODEL_ERROR_NO_PACKAGE (15700) is the ordinary answer for every non-MSIX process.
        return if (rc == 0) utf16z(buffer) else null
    }

    /** `PackageOrigin` of the process's package, or null without package identity or on any failure. */
    private fun origin(arena: Arena, process: MemorySegment): Int? {
        val length = arena.allocate(ValueLayout.JAVA_INT).also { it.set(ValueLayout.JAVA_INT, 0L, NAME_CHARS) }
        val fullName = arena.allocate(NAME_CHARS * 2L)
        if (getPackageFullName.invokeExact(process, length, fullName) as Int != 0) return null
        val origin = arena.allocate(ValueLayout.JAVA_INT)
        if (getStagedPackageOrigin.invokeExact(fullName, origin) as Int != 0) return null
        return origin.get(ValueLayout.JAVA_INT, 0L)
    }

    private fun imagePath(arena: Arena, process: MemorySegment): String? {
        val size = arena.allocate(ValueLayout.JAVA_INT).also { it.set(ValueLayout.JAVA_INT, 0L, PATH_CHARS) }
        val buffer = arena.allocate(PATH_CHARS * 2L)
        val ok = queryImageName.invokeExact(process, 0, buffer, size) as Int
        return if (ok != 0) utf16z(buffer) else null
    }

    private fun utf16z(buffer: MemorySegment): String {
        val chars = StringBuilder()
        var offset = 0L
        while (offset < buffer.byteSize()) {
            val c = buffer.get(ValueLayout.JAVA_CHAR_UNALIGNED, offset)
            if (c == '\u0000') break
            chars.append(c)
            offset += 2
        }
        return chars.toString()
    }

    companion object {
        /** `_WSAIOR(IOC_VENDOR, 256)` = IOC_OUT | IOC_VENDOR | 256. */
        private const val SIO_AF_UNIX_GETPEERPID = 0x58000100
        private const val PROCESS_QUERY_LIMITED_INFORMATION = 0x1000
        private const val STILL_ACTIVE = 259
        private const val NAME_CHARS = 256
        private const val PATH_CHARS = 32_768

        /** 100 ns intervals since 1601-01-01 → epoch millis. */
        private fun fileTimeToEpochMillis(fileTime: Long): Long = (fileTime - 116_444_736_000_000_000L) / 10_000L

        fun loadOrNull(): Natives? {
            if (ValueLayout.ADDRESS.byteSize() != 8L) return null
            val linker = Linker.nativeLinker()
            fun handle(library: String, symbol: String, descriptor: FunctionDescriptor): MethodHandle? {
                val address = SymbolLookup.libraryLookup(library, Arena.global()).find(symbol).orElse(null) ?: return null
                return linker.downcallHandle(address, descriptor)
            }
            // The newer app-model functions are exported by kernelbase.dll only (GetStagedPackageOrigin is not in
            // kernel32.dll — measured), so look in both.
            fun appModel(symbol: String, descriptor: FunctionDescriptor): MethodHandle? =
                handle("kernel32.dll", symbol, descriptor) ?: handle("kernelbase.dll", symbol, descriptor)
            val i = ValueLayout.JAVA_INT
            val a = ValueLayout.ADDRESS
            return Natives(
                // int WSAIoctl(SOCKET, DWORD, LPVOID, DWORD, LPVOID, DWORD, LPDWORD, LPWSAOVERLAPPED, completion)
                wsaIoctl = handle("ws2_32.dll", "WSAIoctl", FunctionDescriptor.of(i, ValueLayout.JAVA_LONG, i, a, i, a, i, a, a, a)) ?: return null,
                // HANDLE OpenProcess(DWORD access, BOOL inherit, DWORD pid)
                openProcess = handle("kernel32.dll", "OpenProcess", FunctionDescriptor.of(a, i, i, i)) ?: return null,
                closeHandle = handle("kernel32.dll", "CloseHandle", FunctionDescriptor.of(i, a)) ?: return null,
                // BOOL GetProcessTimes(HANDLE, LPFILETIME creation, exit, kernel, user)
                getProcessTimes = handle("kernel32.dll", "GetProcessTimes", FunctionDescriptor.of(i, a, a, a, a, a)) ?: return null,
                getExitCodeProcess = handle("kernel32.dll", "GetExitCodeProcess", FunctionDescriptor.of(i, a, a)) ?: return null,
                // LONG GetPackageFamilyName(HANDLE, UINT32* length, PWSTR name)
                getPackageFamilyName = handle("kernel32.dll", "GetPackageFamilyName", FunctionDescriptor.of(i, a, a, a)) ?: return null,
                // BOOL QueryFullProcessImageNameW(HANDLE, DWORD flags, LPWSTR, PDWORD size)
                queryImageName = handle("kernel32.dll", "QueryFullProcessImageNameW", FunctionDescriptor.of(i, a, i, a, a)) ?: return null,
                // LONG GetPackageFullName(HANDLE, UINT32* length, PWSTR name)
                getPackageFullName = appModel("GetPackageFullName", FunctionDescriptor.of(i, a, a, a)) ?: return null,
                // LONG GetStagedPackageOrigin(PCWSTR packageFullName, PackageOrigin* origin)
                getStagedPackageOrigin = appModel("GetStagedPackageOrigin", FunctionDescriptor.of(i, a, a)) ?: return null,
            )
        }
    }
}
