package app.tenshu.windows

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle

/**
 * The account this process runs as, read from its own token over FFM.
 *
 * ⚠⚠ Loads on a JVM without `java.lang.foreign`: FFM types are inside [Natives] only (`WindowsDpapi` rule).
 */
object WindowsAccount {

    private val isWindows = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)

    private val natives: Natives? by lazy { if (!isWindows) null else runCatching { Natives.loadOrNull() }.getOrNull() }

    /**
     * The current user's SID in string form (`S-1-5-21-…`), or `null` off Windows or when the token cannot be
     * read. A sync root id must carry it (`<provider>!<SID>!<account>`, [WindowsSyncRootManager.Info.id]).
     */
    fun currentUserSid(): String? = natives?.let { n -> runCatching { n.currentUserSid() }.getOrNull() }

    private class Natives private constructor(
        private val getCurrentProcess: MethodHandle,
        private val openProcessToken: MethodHandle,
        private val getTokenInformation: MethodHandle,
        private val convertSidToStringSid: MethodHandle,
        private val localFree: MethodHandle,
        private val closeHandle: MethodHandle,
    ) {
        fun currentUserSid(): String? = Arena.ofConfined().use { arena ->
            val process = getCurrentProcess.invokeExact() as MemorySegment
            val token = arena.allocate(ValueLayout.ADDRESS)
            if (openProcessToken.invokeExact(process, TOKEN_QUERY, token) as Int == 0) return null
            val tokenHandle = token.get(ValueLayout.ADDRESS, 0L)
            try {
                val needed = arena.allocate(ValueLayout.JAVA_INT)
                // First call sizes the buffer; it fails with ERROR_INSUFFICIENT_BUFFER by design.
                getTokenInformation.invokeExact(tokenHandle, TOKEN_USER, MemorySegment.NULL, 0, needed) as Int
                val size = needed.get(ValueLayout.JAVA_INT, 0L)
                if (size <= 0) return null
                val buffer = arena.allocate(size.toLong(), 8L)
                if (getTokenInformation.invokeExact(tokenHandle, TOKEN_USER, buffer, size, needed) as Int == 0) return null
                // TOKEN_USER { SID_AND_ATTRIBUTES User { PSID Sid; DWORD Attributes } }: the SID pointer comes first.
                val sid = buffer.get(ValueLayout.ADDRESS, 0L)
                val text = arena.allocate(ValueLayout.ADDRESS)
                if (convertSidToStringSid.invokeExact(sid, text) as Int == 0) return null
                val pointer = text.get(ValueLayout.ADDRESS, 0L)
                try {
                    WindowsCloudFilesStrings.read(pointer)
                } finally {
                    localFree.invokeExact(pointer) as MemorySegment
                }
            } finally {
                closeHandle.invokeExact(tokenHandle) as Int
            }
        }

        companion object {
            private const val TOKEN_QUERY = 0x0008
            private const val TOKEN_USER = 1

            fun loadOrNull(): Natives? {
                if (ValueLayout.ADDRESS.byteSize() != 8L) return null
                val linker = Linker.nativeLinker()
                fun handle(library: String, symbol: String, descriptor: FunctionDescriptor): MethodHandle? {
                    val address = SymbolLookup.libraryLookup(library, Arena.global()).find(symbol).orElse(null) ?: return null
                    return linker.downcallHandle(address, descriptor)
                }
                val i = ValueLayout.JAVA_INT
                val a = ValueLayout.ADDRESS
                return Natives(
                    getCurrentProcess = handle("kernel32.dll", "GetCurrentProcess", FunctionDescriptor.of(a)) ?: return null,
                    // BOOL OpenProcessToken(HANDLE, DWORD, PHANDLE)
                    openProcessToken = handle("advapi32.dll", "OpenProcessToken", FunctionDescriptor.of(i, a, i, a)) ?: return null,
                    // BOOL GetTokenInformation(HANDLE, TOKEN_INFORMATION_CLASS, LPVOID, DWORD, PDWORD)
                    getTokenInformation = handle("advapi32.dll", "GetTokenInformation", FunctionDescriptor.of(i, a, i, a, i, a)) ?: return null,
                    // BOOL ConvertSidToStringSidW(PSID, LPWSTR*)
                    convertSidToStringSid = handle("advapi32.dll", "ConvertSidToStringSidW", FunctionDescriptor.of(i, a, a)) ?: return null,
                    localFree = handle("kernel32.dll", "LocalFree", FunctionDescriptor.of(a, a)) ?: return null,
                    closeHandle = handle("kernel32.dll", "CloseHandle", FunctionDescriptor.of(i, a)) ?: return null,
                )
            }
        }
    }
}
