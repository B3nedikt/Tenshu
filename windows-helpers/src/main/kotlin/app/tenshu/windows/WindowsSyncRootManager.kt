package app.tenshu.windows

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle
import java.util.UUID

/**
 * `Windows.Storage.Provider.StorageProviderSyncRootManager` (WinRT) over FFM, by raw COM vtable calls:
 * the registration that gives a sync root its **Explorer navigation-pane entry**, display name and icon
 * (`docs/proposals/desktop-windows-port.md` §7, X0 as-built). The CfAPI half is [WindowsCloudFiles].
 *
 * The ABI (IIDs, vtable slots, enum values) was read from the OS's own `WinMetadata` on Windows 11 26300
 * and is spelled out below; WinRT interfaces are immutable once published, so these never move.
 *
 * ⚠⚠ Loads on a JVM without `java.lang.foreign`: FFM types are inside [Natives] only (`WindowsDpapi` rule).
 *
 * ⭐ X0 measured: works **without package identity** — `Register` alone writes the HKLM `SyncRootManager`
 * key, adds the HKCU navigation-pane entry and performs the CfAPI registration (so do not also call
 * `CfRegisterSyncRoot`; after [unregister], `CfUnregisterSyncRoot` answers NOT_UNDER_SYNC_ROOT).
 *
 * ⚠ COM is initialised **MTA** on the calling thread for the duration of each call (`RoInitialize`), and
 * left alone if the thread is already in a COM apartment (an AWT/Compose thread may be STA; WinRT's
 * Storage classes are agile, so either works). Call from a background thread.
 */
object WindowsSyncRootManager {

    // StorageProviderHydrationPolicy / PopulationPolicy as WinRT spells them — ⚠ not CfAPI's numbers.
    const val HYDRATION_PARTIAL = 0
    const val HYDRATION_PROGRESSIVE = 1
    const val HYDRATION_FULL = 2
    const val HYDRATION_ALWAYS_FULL = 3
    const val POPULATION_FULL = 1
    const val POPULATION_ALWAYS_FULL = 2

    data class Info(
        /** `<provider>!<user SID>!<account>` — the form Windows expects and keys its registry by. */
        val id: String,
        val path: String,
        val displayName: String,
        /** `<dll or exe>,<-resource id>` or an `.ico` path. */
        val iconResource: String,
        val version: String,
        val hydration: Int = HYDRATION_FULL,
        val population: Int = POPULATION_ALWAYS_FULL,
        val inSyncPolicy: Int = 0,
        val showSiblingsAsGroup: Boolean = false,
        val providerId: UUID? = null,
    )

    /** An HRESULT plus WinRT's restricted-error text when the OS supplied one. */
    data class Outcome(val hresult: Int, val detail: String? = null) {
        val ok: Boolean get() = CloudFilesHResult.succeeded(hresult)
        override fun toString(): String =
            CloudFilesHResult.describe(hresult) + (detail?.let { " — $it" } ?: "")
    }

    private val isWindows = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)

    private val natives: Natives? by lazy { if (!isWindows) null else runCatching { Natives.loadOrNull() }.getOrNull() }

    fun isAvailable(): Boolean = natives != null

    /** `IStorageProviderSyncRootManagerStatics2.IsSupported()`. */
    fun isSupported(): Boolean? = natives?.let { n -> runCatching { n.isSupported() }.getOrNull() }

    fun register(info: Info): Outcome? =
        natives?.let { n -> runCatching { n.register(info) }.getOrElse { Outcome(CloudFilesHResult.E_FAIL, it.toString()) } }

    fun unregister(id: String): Outcome? =
        natives?.let { n -> runCatching { n.unregister(id) }.getOrElse { Outcome(CloudFilesHResult.E_FAIL, it.toString()) } }

    /**
     * The ids `GetCurrentSyncRoots` answers. ⚠⚠ **Not** every registered root: X0 measured that a root
     * whose folder is under `%TEMP%` is left out although it is registered, connected and in Explorer.
     * To find (and sweep) our own roots, enumerate `HKLM\…\Explorer\SyncRootManager` instead.
     */
    fun currentSyncRootIds(): Result<List<String>>? =
        natives?.let { n -> runCatching { n.currentSyncRootIds() } }

    private class Natives private constructor(
        private val roInitialize: MethodHandle,
        private val roUninitialize: MethodHandle,
        private val roActivateInstance: MethodHandle,
        private val roGetActivationFactory: MethodHandle,
        private val createString: MethodHandle,
        private val deleteString: MethodHandle,
        private val rawBuffer: MethodHandle,
        private val getRestrictedErrorInfo: MethodHandle,
        private val sysFreeString: MethodHandle,
    ) {
        private val linker = Linker.nativeLinker()
        private val a = ValueLayout.ADDRESS
        private val i = ValueLayout.JAVA_INT

        /** Thrown inside a [com] block; turned into an [Outcome] at its edge. */
        private class Failure(val hresult: Int, val detail: String?) : RuntimeException()

        fun isSupported(): Boolean = com { arena ->
            val statics = factory(arena, MANAGER_CLASS, IID_MANAGER_STATICS2)
            try {
                val out = arena.allocate(ValueLayout.JAVA_BYTE)
                check(call(statics, 6, FunctionDescriptor.of(i, a, a), statics, out), "IsSupported")
                out.get(ValueLayout.JAVA_BYTE, 0L).toInt() != 0
            } finally {
                release(statics)
            }
        }

        fun register(info: Info): Outcome = outcome { arena ->
            // Each reference is released by its own `finally`, so a step that fails leaks none taken before it.
            val folder = folderFromPath(arena, info.path)
            try {
                val inspectable = arena.allocate(a)
                check(roActivateInstance.invokeExact(hstring(arena, INFO_CLASS), inspectable) as Int, "RoActivateInstance(StorageProviderSyncRootInfo)")
                val instance = inspectable.get(a, 0L)
                try {
                    val root = queryInterface(arena, instance, IID_INFO)
                    try {
                        populateAndRegister(arena, info, folder, instance, root)
                    } finally {
                        release(root)
                    }
                } finally {
                    release(instance)
                }
            } finally {
                release(folder)
            }
        }

        private fun populateAndRegister(arena: Arena, info: Info, folder: MemorySegment, instance: MemorySegment, root: MemorySegment) {
            run {
                val s = FunctionDescriptor.of(i, a, a)
                val n = FunctionDescriptor.of(i, a, i)
                check(call(root, 7, s, root, hstring(arena, info.id)), "put_Id")
                check(call(root, 11, s, root, folder), "put_Path")
                check(call(root, 13, s, root, hstring(arena, info.displayName)), "put_DisplayNameResource")
                check(call(root, 15, s, root, hstring(arena, info.iconResource)), "put_IconResource")
                check(call(root, 17, n, root, info.hydration), "put_HydrationPolicy")
                check(call(root, 19, n, root, 0), "put_HydrationPolicyModifier")
                check(call(root, 21, n, root, info.population), "put_PopulationPolicy")
                check(call(root, 23, n, root, info.inSyncPolicy), "put_InSyncPolicy")
                check(call(root, 25, n, root, 0), "put_HardlinkPolicy")
                check(
                    call(root, 27, FunctionDescriptor.of(i, a, ValueLayout.JAVA_BYTE), root, (if (info.showSiblingsAsGroup) 1 else 0).toByte()),
                    "put_ShowSiblingsAsGroup",
                )
                check(call(root, 29, s, root, hstring(arena, info.version)), "put_Version")
                info.providerId?.let { id ->
                    val root2 = queryInterface(arena, instance, IID_INFO2)
                    try {
                        val guid = arena.allocate(CloudFilesLayouts.GUID)
                        CloudFilesLayouts.writeGuid(guid, id)
                        check(call(root2, 7, FunctionDescriptor.of(i, a, CloudFilesLayouts.GUID), root2, guid), "put_ProviderId")
                    } finally {
                        release(root2)
                    }
                }
                val statics = factory(arena, MANAGER_CLASS, IID_MANAGER_STATICS)
                try {
                    check(call(statics, 6, s, statics, root), "Register")
                } finally {
                    release(statics)
                }
            }
        }

        fun unregister(id: String): Outcome = outcome { arena ->
            val statics = factory(arena, MANAGER_CLASS, IID_MANAGER_STATICS)
            try {
                check(call(statics, 7, FunctionDescriptor.of(i, a, a), statics, hstring(arena, id)), "Unregister")
            } finally {
                release(statics)
            }
        }

        fun currentSyncRootIds(): List<String> = com { arena ->
            val statics = factory(arena, MANAGER_CLASS, IID_MANAGER_STATICS)
            try {
                val out = arena.allocate(a)
                check(call(statics, 10, FunctionDescriptor.of(i, a, a), statics, out), "GetCurrentSyncRoots")
                val view = out.get(a, 0L)
                try {
                    val size = arena.allocate(i)
                    check(call(view, 7, FunctionDescriptor.of(i, a, a), view, size), "IVectorView.get_Size")
                    (0 until size.get(i, 0L)).map { index ->
                        val item = arena.allocate(a)
                        check(call(view, 6, FunctionDescriptor.of(i, a, i, a), view, index, item), "IVectorView.GetAt")
                        val root = item.get(a, 0L)
                        try {
                            val h = arena.allocate(a)
                            check(call(root, 6, FunctionDescriptor.of(i, a, a), root, h), "get_Id")
                            readHString(arena, h.get(a, 0L)).also { deleteString.invokeExact(h.get(a, 0L)) as Int }
                        } finally {
                            release(root)
                        }
                    }
                } finally {
                    release(view)
                }
            } finally {
                release(statics)
            }
        }

        /** `StorageFolder.GetFolderFromPathAsync(path)`, waited on by polling `IAsyncInfo.Status`. */
        private fun folderFromPath(arena: Arena, path: String): MemorySegment {
            val statics = factory(arena, FOLDER_CLASS, IID_FOLDER_STATICS)
            try {
                val out = arena.allocate(a)
                check(call(statics, 6, FunctionDescriptor.of(i, a, a, a), statics, hstring(arena, path), out), "GetFolderFromPathAsync")
                val operation = out.get(a, 0L)
                try {
                    val asyncInfo = queryInterface(arena, operation, IID_ASYNC_INFO)
                    try {
                        val status = arena.allocate(i)
                        val deadline = System.nanoTime() + 10_000_000_000L
                        while (true) {
                            check(call(asyncInfo, 7, FunctionDescriptor.of(i, a, a), asyncInfo, status), "IAsyncInfo.get_Status")
                            if (status.get(i, 0L) != ASYNC_STARTED) break
                            if (System.nanoTime() > deadline) throw Failure(CloudFilesHResult.E_FAIL, "GetFolderFromPathAsync timed out")
                            Thread.sleep(5)
                        }
                        if (status.get(i, 0L) != ASYNC_COMPLETED) {
                            val error = arena.allocate(i)
                            call(asyncInfo, 8, FunctionDescriptor.of(i, a, a), asyncInfo, error)
                            throw Failure(error.get(i, 0L), "GetFolderFromPathAsync($path) did not complete")
                        }
                    } finally {
                        release(asyncInfo)
                    }
                    val folder = arena.allocate(a)
                    check(call(operation, 8, FunctionDescriptor.of(i, a, a), operation, folder), "IAsyncOperation.GetResults")
                    return folder.get(a, 0L)
                } finally {
                    release(operation)
                }
            } finally {
                release(statics)
            }
        }

        private fun factory(arena: Arena, className: String, iid: String): MemorySegment {
            val out = arena.allocate(a)
            check(roGetActivationFactory.invokeExact(hstring(arena, className), guid(arena, iid), out) as Int, "RoGetActivationFactory($className)")
            return out.get(a, 0L)
        }

        private fun queryInterface(arena: Arena, obj: MemorySegment, iid: String): MemorySegment {
            val out = arena.allocate(a)
            check(call(obj, 0, FunctionDescriptor.of(i, a, a, a), obj, guid(arena, iid), out), "QueryInterface($iid)")
            return out.get(a, 0L)
        }

        private fun release(obj: MemorySegment) {
            if (obj.address() != 0L) call(obj, 2, FunctionDescriptor.of(i, a), obj)
        }

        /** Downcall handles by (function address, descriptor): building one per call is the expensive part. */
        private val handles = java.util.concurrent.ConcurrentHashMap<Pair<Long, FunctionDescriptor>, MethodHandle>()

        /** Calls vtable [slot] of COM object [obj]; [args] include `this`. */
        private fun call(obj: MemorySegment, slot: Int, descriptor: FunctionDescriptor, vararg args: Any): Int {
            val vtable = obj.reinterpret(8).get(a, 0L).reinterpret((slot + 1) * 8L)
            val function = vtable.get(a, slot * 8L)
            val handle = handles.computeIfAbsent(function.address() to descriptor) { linker.downcallHandle(function, descriptor) }
            return handle.invokeWithArguments(*args) as Int
        }

        private fun check(hr: Int, what: String) {
            if (!CloudFilesHResult.succeeded(hr)) throw Failure(hr, "$what failed" + (restrictedError()?.let { ": $it" } ?: ""))
        }

        /** `GetRestrictedErrorInfo` → `IRestrictedErrorInfo::GetErrorDetails`: WinRT's own sentence for a failure. */
        private fun restrictedError(): String? = runCatching {
            Arena.ofConfined().use { arena ->
                val out = arena.allocate(a)
                val hr = getRestrictedErrorInfo.invokeExact(out) as Int
                val info = out.get(a, 0L)
                if (hr != 0 || info.address() == 0L) return@use null
                try {
                    val description = arena.allocate(a)
                    val error = arena.allocate(i)
                    val restricted = arena.allocate(a)
                    val capability = arena.allocate(a)
                    call(info, 3, FunctionDescriptor.of(i, a, a, a, a, a), info, description, error, restricted, capability)
                    val parts = listOf(description, restricted, capability).mapNotNull { bstr ->
                        val p = bstr.get(a, 0L)
                        if (p.address() == 0L) null
                        else WindowsCloudFilesStrings.read(p).also { sysFreeString.invokeExact(p) }
                    }.filter { it.isNotBlank() }.distinct()
                    parts.joinToString(" / ").ifBlank { null }
                } finally {
                    release(info)
                }
            }
        }.getOrNull()

        /** HSTRINGs made inside the current [com] block, deleted at its end. */
        private val hstrings = ThreadLocal<MutableList<MemorySegment>>()

        private fun <T> com(block: (Arena) -> T): T {
            val init = roInitialize.invokeExact(RO_INIT_MULTITHREADED) as Int
            val made = mutableListOf<MemorySegment>()
            hstrings.set(made)
            try {
                return Arena.ofConfined().use(block)
            } finally {
                made.forEach { deleteString.invokeExact(it) as Int }
                hstrings.remove()
                // S_OK / S_FALSE both take a reference that must be balanced; RPC_E_CHANGED_MODE does not.
                if (init == 0 || init == 1) roUninitialize.invokeExact()
            }
        }

        private fun outcome(block: (Arena) -> Unit): Outcome = try {
            com(block)
            Outcome(CloudFilesHResult.S_OK)
        } catch (f: Failure) {
            Outcome(f.hresult, f.detail)
        }

        /** An HSTRING that lives until the end of the enclosing [com] block. */
        private fun hstring(arena: Arena, s: String): MemorySegment {
            val chars = WindowsCloudFilesStrings.wstr(arena, s)
            val out = arena.allocate(a)
            check(createString.invokeExact(chars, s.length, out) as Int, "WindowsCreateString")
            return out.get(a, 0L).also { hstrings.get()?.add(it) }
        }

        private fun readHString(arena: Arena, h: MemorySegment): String {
            if (h.address() == 0L) return ""
            val length = arena.allocate(i)
            val buffer = rawBuffer.invokeExact(h, length) as MemorySegment
            val n = length.get(i, 0L)
            val chars = buffer.reinterpret(n * 2L)
            return String(CharArray(n) { chars.get(ValueLayout.JAVA_CHAR_UNALIGNED, it * 2L) })
        }

        private fun guid(arena: Arena, iid: String): MemorySegment =
            arena.allocate(CloudFilesLayouts.GUID).also { CloudFilesLayouts.writeGuid(it, UUID.fromString(iid)) }

        companion object {
            private const val RO_INIT_MULTITHREADED = 1
            private const val ASYNC_STARTED = 0
            private const val ASYNC_COMPLETED = 1

            private const val MANAGER_CLASS = "Windows.Storage.Provider.StorageProviderSyncRootManager"
            private const val INFO_CLASS = "Windows.Storage.Provider.StorageProviderSyncRootInfo"
            private const val FOLDER_CLASS = "Windows.Storage.StorageFolder"

            // Read from C:\Windows\System32\WinMetadata (Windows 11 26300).
            private const val IID_INFO = "7c1305c4-99f9-41ac-8904-ab055d654926"
            private const val IID_INFO2 = "cf51b023-7cf1-5166-bdba-efd95f529e31"
            private const val IID_MANAGER_STATICS = "3e99fbbf-8fe3-4b40-abc7-f6fc3d74c98e"
            private const val IID_MANAGER_STATICS2 = "efb6cfee-1374-544e-9df1-5598d2e9cfdd"
            private const val IID_FOLDER_STATICS = "08f327ff-85d5-48b9-aee9-28511e339f9f"
            private const val IID_ASYNC_INFO = "00000036-0000-0000-c000-000000000046"

            fun loadOrNull(): Natives? {
                if (ValueLayout.ADDRESS.byteSize() != 8L) return null
                val linker = Linker.nativeLinker()
                fun handle(library: String, symbol: String, descriptor: FunctionDescriptor): MethodHandle? {
                    val address = SymbolLookup.libraryLookup(library, Arena.global()).find(symbol).orElse(null) ?: return null
                    return linker.downcallHandle(address, descriptor)
                }
                val i = ValueLayout.JAVA_INT
                val a = ValueLayout.ADDRESS
                val combase = "combase.dll"
                return Natives(
                    roInitialize = handle(combase, "RoInitialize", FunctionDescriptor.of(i, i)) ?: return null,
                    roUninitialize = handle(combase, "RoUninitialize", FunctionDescriptor.ofVoid()) ?: return null,
                    roActivateInstance = handle(combase, "RoActivateInstance", FunctionDescriptor.of(i, a, a)) ?: return null,
                    roGetActivationFactory = handle(combase, "RoGetActivationFactory", FunctionDescriptor.of(i, a, a, a)) ?: return null,
                    createString = handle(combase, "WindowsCreateString", FunctionDescriptor.of(i, a, i, a)) ?: return null,
                    deleteString = handle(combase, "WindowsDeleteString", FunctionDescriptor.of(i, a)) ?: return null,
                    rawBuffer = handle(combase, "WindowsGetStringRawBuffer", FunctionDescriptor.of(a, a, a)) ?: return null,
                    getRestrictedErrorInfo = handle(combase, "GetRestrictedErrorInfo", FunctionDescriptor.of(i, a)) ?: return null,
                    sysFreeString = handle("oleaut32.dll", "SysFreeString", FunctionDescriptor.ofVoid(a)) ?: return null,
                )
            }
        }
    }
}

/** UTF-16 string helpers shared by the two Cloud Files bindings. ⚠⚠ FFM types: same loading rule. */
internal object WindowsCloudFilesStrings {
    fun wstr(arena: Arena, s: String): MemorySegment {
        val segment = arena.allocate((s.length + 1) * 2L, 2L)
        s.forEachIndexed { i, c -> segment.set(ValueLayout.JAVA_CHAR, i * 2L, c) }
        segment.set(ValueLayout.JAVA_CHAR, s.length * 2L, '\u0000')
        return segment
    }

    /** NUL-terminated; char by char so nothing past the NUL is touched. */
    fun read(pointer: MemorySegment, maxChars: Int = 32_768): String {
        val chars = pointer.reinterpret(maxChars * 2L)
        val out = StringBuilder()
        for (i in 0 until maxChars) {
            val c = chars.get(ValueLayout.JAVA_CHAR_UNALIGNED, i * 2L)
            if (c == '\u0000') break
            out.append(c)
        }
        return out.toString()
    }
}
