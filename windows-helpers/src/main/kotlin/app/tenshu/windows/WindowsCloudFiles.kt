package app.tenshu.windows

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemoryLayout
import java.lang.foreign.MemoryLayout.PathElement.groupElement
import java.lang.foreign.MemorySegment
import java.lang.foreign.StructLayout
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.nio.file.Path
import java.util.UUID

/**
 * The Cloud Files API (`cldapi.dll`) over FFM: register a sync root, connect to it with callbacks,
 * create placeholders, answer `FETCH_DATA` with bytes (`docs/proposals/desktop-windows-port.md` §7,
 * X0's as-built note there).
 *
 * ⚠⚠ **This object must load on a JVM without `java.lang.foreign`** — every FFM type is inside
 * [Natives] / [CloudFilesLayouts], loaded only within `runCatching` (the `WindowsDpapi` rule).
 * Every call answers `null` when the API is not callable here (not Windows, no FFM, 32-bit).
 *
 * ⚠⚠ **Callbacks arrive on CfAPI's own thread-pool threads**, not a JVM thread (X0 measured them: attached
 * as daemon threads named `Thread-N`, one reused for sequential requests, a distinct one per concurrent
 * request — three 1 s blocking callbacks finished in 1.3 s). The JVM attaches them for the upcall; an exception escaping an upcall kills the process, so the
 * dispatcher catches everything, and a handler that throws fails its request rather than leaving it
 * to time out (60 s, after which the opener gets an error).
 *
 * ⚠ A request may be answered **later and from any thread** — keep the [FetchData] and call
 * [FetchData.transfer] / [FetchData.fail] when the bytes are ready. Return from the handler quickly.
 *
 * ⚠ The upcall stubs of a connection are never freed (a few hundred bytes each): `CfDisconnectSyncRoot`
 * gives no promise that no callback is still running inside them, and freeing a running stub is a
 * crash. Connections are per-process-lifetime objects; this is not a leak in practice.
 */
object WindowsCloudFiles {

    // CF_HYDRATION_POLICY_PRIMARY
    const val HYDRATION_PARTIAL: Short = 0
    const val HYDRATION_PROGRESSIVE: Short = 1
    const val HYDRATION_FULL: Short = 2
    const val HYDRATION_ALWAYS_FULL: Short = 3

    // CF_POPULATION_POLICY_PRIMARY — ⚠ NOT the WinRT enum's values (there Full = 1, AlwaysFull = 2).
    const val POPULATION_PARTIAL: Short = 0
    const val POPULATION_FULL: Short = 2
    const val POPULATION_ALWAYS_FULL: Short = 3

    // CF_REGISTER_FLAGS
    const val REGISTER_FLAG_NONE = 0
    const val REGISTER_FLAG_UPDATE = 0x1
    const val REGISTER_FLAG_DISABLE_ON_DEMAND_POPULATION_ON_ROOT = 0x2
    const val REGISTER_FLAG_MARK_IN_SYNC_ON_ROOT = 0x4

    // CF_CONNECT_FLAGS
    const val CONNECT_FLAG_REQUIRE_PROCESS_INFO = 0x2
    const val CONNECT_FLAG_REQUIRE_FULL_FILE_PATH = 0x4
    const val CONNECT_FLAG_BLOCK_SELF_IMPLICIT_HYDRATION = 0x8

    // CF_PLACEHOLDER_CREATE_FLAGS
    const val PLACEHOLDER_FLAG_DISABLE_ON_DEMAND_POPULATION = 0x1
    const val PLACEHOLDER_FLAG_MARK_IN_SYNC = 0x2
    const val PLACEHOLDER_FLAG_SUPERSEDE = 0x4

    // File attributes a placeholder carries (GetFileAttributesW).
    const val FILE_ATTRIBUTE_READONLY = 0x1
    const val FILE_ATTRIBUTE_DIRECTORY = 0x10
    const val FILE_ATTRIBUTE_NORMAL = 0x80
    const val FILE_ATTRIBUTE_SPARSE_FILE = 0x200
    const val FILE_ATTRIBUTE_REPARSE_POINT = 0x400
    const val FILE_ATTRIBUTE_OFFLINE = 0x1000
    const val FILE_ATTRIBUTE_PINNED = 0x80000
    const val FILE_ATTRIBUTE_UNPINNED = 0x100000
    const val FILE_ATTRIBUTE_RECALL_ON_OPEN = 0x40000
    const val FILE_ATTRIBUTE_RECALL_ON_DATA_ACCESS = 0x400000

    /** NTSTATUS `STATUS_UNSUCCESSFUL`, the completion status of a failed transfer. */
    const val STATUS_UNSUCCESSFUL = 0xC0000001.toInt()

    /** `CfExecute(TRANSFER_DATA)`: every chunk but the last must be a multiple of this, at an offset that is too. */
    const val TRANSFER_ALIGNMENT = 4096L

    data class Registration(
        val providerName: String,
        val providerVersion: String,
        val providerId: UUID,
        /** Opaque, ≤ 64 KiB, handed back in every callback. */
        val syncRootIdentity: ByteArray = ByteArray(0),
        val hydration: Short = HYDRATION_FULL,
        val population: Short = POPULATION_ALWAYS_FULL,
        val inSyncPolicy: Int = 0,
    )

    data class Placeholder(
        val relativeName: String,
        val size: Long,
        /** The FileIdentity blob, ≤ 4 KiB, handed back in every callback for this file. Required for files. */
        val identity: ByteArray,
        val modifiedAtMillis: Long,
        val flags: Int = PLACEHOLDER_FLAG_MARK_IN_SYNC,
        val directory: Boolean = false,
        /** `FILE_ATTRIBUTE_READONLY`: editors refuse to save over it, Explorer asks before deleting it. */
        val readOnly: Boolean = false,
    )

    /** Who opened the file, when the connection asked for [CONNECT_FLAG_REQUIRE_PROCESS_INFO]. */
    data class RequestingProcess(val pid: Long, val imagePath: String?)

    /** What the OS told us about a callback, kept for the spike's layout check. */
    data class CallbackFacts(
        val callbackInfoStructSize: Int,
        val parameterSize: Int,
        val threadName: String,
        val threadId: Long,
    )

    /**
     * One `FETCH_DATA` request: deliver [requiredLength] bytes from [requiredOffset] (the OS may accept
     * more, up to the optional range), by one or more [transfer] calls, or [fail] it.
     */
    class FetchData internal constructor(
        val connectionKey: Long,
        val transferKey: Long,
        val requestKey: Long,
        val fileIdentity: ByteArray,
        val normalizedPath: String?,
        val fileSize: Long,
        val requiredOffset: Long,
        val requiredLength: Long,
        val optionalOffset: Long,
        val optionalLength: Long,
        val process: RequestingProcess?,
        val facts: CallbackFacts,
    ) {
        /** Deliver [bytes] at [offset]; answers the HRESULT. ⚠ Alignment: [TRANSFER_ALIGNMENT]. */
        fun transfer(offset: Long, bytes: ByteArray): Int? =
            natives()?.let { n -> runCatching { n.transfer(this, offset, bytes, 0) }.getOrElse { CloudFilesHResult.E_FAIL } }

        /** Fail the request: the opener gets an error instead of waiting for the 60 s timeout. */
        fun fail(ntStatus: Int = STATUS_UNSUCCESSFUL): Int? =
            natives()?.let { n -> runCatching { n.transfer(this, requiredOffset, ByteArray(0), ntStatus) }.getOrElse { CloudFilesHResult.E_FAIL } }
    }

    data class CancelFetchData(val transferKey: Long, val fileIdentity: ByteArray, val offset: Long, val length: Long)

    interface Handler {
        /** Called on a CfAPI thread; may answer now or later. A throw fails the request. */
        fun fetchData(request: FetchData)

        fun cancelFetchData(cancel: CancelFetchData) {}
    }

    class Connection internal constructor(val key: Long) {
        @Volatile private var open = true

        /** `CfDisconnectSyncRoot`; afterwards opening a dehydrated placeholder fails (provider not running). */
        fun disconnect(): Int? {
            if (!open) return CloudFilesHResult.S_FALSE
            open = false
            return natives()?.let { n -> runCatching { n.disconnect(key) }.getOrElse { CloudFilesHResult.E_FAIL } }
        }
    }

    sealed interface ConnectResult {
        data class Connected(val connection: Connection) : ConnectResult
        data class Failed(val hresult: Int) : ConnectResult
    }

    private val isWindows = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)

    private val natives: Natives? by lazy { if (!isWindows) null else runCatching { Natives.loadOrNull() }.getOrNull() }

    private fun natives(): Natives? = natives

    /** Whether CfAPI is callable here: Windows, a 64-bit JVM with FFM, and every symbol resolved. */
    fun isAvailable(): Boolean = natives != null

    /** `CfRegisterSyncRoot`. The folder must exist. Answers the HRESULT. */
    fun registerSyncRoot(root: Path, registration: Registration, flags: Int = REGISTER_FLAG_NONE): Int? =
        natives?.let { n -> runCatching { n.register(root.toString(), registration, flags) }.getOrElse { CloudFilesHResult.E_FAIL } }

    /** `CfUnregisterSyncRoot`. */
    fun unregisterSyncRoot(root: Path): Int? =
        natives?.let { n -> runCatching { n.unregister(root.toString()) }.getOrElse { CloudFilesHResult.E_FAIL } }

    /**
     * `CfGetSyncRootInfoByPath(BASIC)`: the sync root's file id when [path] is in one; otherwise the
     * failure (an ordinary folder answers [CloudFilesHResult.NOT_UNDER_SYNC_ROOT]).
     */
    fun syncRootFileId(path: Path): Result<Long>? =
        natives?.let { n -> runCatching { n.syncRootFileId(path.toString()) }.getOrElse { Result.failure(it) } }

    /**
     * `CfConnectSyncRoot` with `FETCH_DATA` + `CANCEL_FETCH_DATA` dispatched to [handler].
     * ⚠ The provider's own process hydrates implicitly too (a `Files.readAllBytes` here calls back into
     * [handler]); a scan of the folder from the provider would download everything — X1 decides on
     * `CF_CONNECT_FLAG_BLOCK_SELF_IMPLICIT_HYDRATION` (0x8).
     */
    fun connect(root: Path, handler: Handler, flags: Int = CONNECT_FLAG_REQUIRE_PROCESS_INFO or CONNECT_FLAG_REQUIRE_FULL_FILE_PATH): ConnectResult? =
        natives?.let { n -> runCatching { n.connect(root.toString(), handler, flags) }.getOrElse { ConnectResult.Failed(CloudFilesHResult.E_FAIL) } }

    /**
     * `CfCreatePlaceholders` for one item under [parent]; answers the per-item HRESULT.
     * ⚠ While a provider is connected, only **that process** may create placeholders — any other gets
     * `ERROR_CLOUD_FILE_ACCESS_DENIED` (X0); with nothing connected, any same-user process may.
     */
    fun createPlaceholder(parent: Path, placeholder: Placeholder): Int? =
        natives?.let { n -> runCatching { n.createPlaceholder(parent.toString(), placeholder) }.getOrElse { CloudFilesHResult.E_FAIL } }

    /** `GetFileAttributesW`; `null` when it fails. [FILE_ATTRIBUTE_RECALL_ON_DATA_ACCESS] marks online-only. */
    fun fileAttributes(path: Path): Int? =
        natives?.let { n -> runCatching { n.fileAttributes(path.toString()) }.getOrNull() }

    // CF_PIN_STATE
    const val PIN_STATE_UNSPECIFIED = 0
    const val PIN_STATE_PINNED = 1
    const val PIN_STATE_UNPINNED = 2

    /**
     * `CfHydratePlaceholder` over the whole file: blocks until this provider's own FETCH_DATA has delivered it,
     * so call it from a thread the fetch does not need. ⚠ Explicit, so `BLOCK_SELF_IMPLICIT_HYDRATION` does not
     * stop it. Answers the HRESULT.
     */
    fun hydrate(path: Path): Int? = onAttributeHandle(path) { n, h -> n.hydrate(h) }

    /**
     * `CfDehydratePlaceholder` over the whole file: the local copy goes, the placeholder stays online-only.
     * ⚠⚠ On an **exclusive** handle (`CfOpenFileWithOplock(EXCLUSIVE)`): the contract says the caller must hold the
     * file exclusively "or data corruption can occur" — an attribute handle shared with a reader is not enough. A file
     * open elsewhere fails the open, and the caller tries again later.
     */
    fun dehydrate(path: Path): Int? =
        natives?.let { n -> runCatching { n.dehydrateExclusively(path.toString()) }.getOrElse { CloudFilesHResult.E_FAIL } }

    /**
     * `CfConvertToPlaceholder`: makes the ordinary file or folder at [path] a placeholder carrying [identity], marked in
     * sync, with its bytes kept. On an exclusive handle with write access, as the call requires. ⚠ The caller must have
     * established that the bytes are the provider's — converting declares them so.
     */
    fun convertToPlaceholder(
        path: Path,
        identity: ByteArray,
        directory: Boolean = false,
        verify: ((read: (ByteArray) -> Int) -> Boolean)? = null,
    ): Int? =
        natives?.let { n ->
            runCatching {
                // ⚠ A folder on a shared handle: exclusivity protects a file's bytes, and Explorer always has a folder
                // open, so an exclusive open of one fails with a sharing violation (measured).
                if (directory) n.convertDirectory(path.toString(), identity) else n.convertExclusively(path.toString(), identity, verify)
            }.getOrElse { CloudFilesHResult.E_FAIL }
        }

    /** What [convertToPlaceholder] answers when [verify] refused the bytes: nothing was converted. */
    const val E_ABORT = 0x80004004.toInt()

    /** `CfSetPinState` ([PIN_STATE_PINNED] = "Always keep on this device"). */
    fun setPinState(path: Path, state: Int): Int? = onAttributeHandle(path) { n, h -> n.setPinState(h, state) }

    private fun onAttributeHandle(path: Path, op: (Natives, Any) -> Int): Int? =
        natives?.let { n -> runCatching { n.withAttributeHandle(path.toString(), followLinks = true) { op(n, it) } }.getOrElse { CloudFilesHResult.E_FAIL } }

    /** A placeholder's FileIdentity blob, and whether its local state matches what the provider last set. */
    class PlaceholderInfo(val identity: ByteArray, val inSync: Boolean)

    /**
     * `CfGetPlaceholderInfo(BASIC)` for [path], or `null` when it is not a placeholder — an ordinary file, a symlink
     * or a junction (opened as itself, never followed) — or cannot be read.
     * ⚠ This, not `FILE_ATTRIBUTE_REPARSE_POINT`, says whether an entry is a placeholder: every link has that bit.
     * An edit saved into a placeholder leaves it **not in sync**.
     */
    fun placeholderInfo(path: Path): PlaceholderInfo? =
        natives?.let { n -> runCatching { n.placeholderInfo(path.toString()) }.getOrNull() }

    /** Every `java.lang.foreign` reference of [WindowsCloudFiles]. */
    private class Natives private constructor(
        private val register: MethodHandle,
        private val unregister: MethodHandle,
        private val connect: MethodHandle,
        private val disconnect: MethodHandle,
        private val createPlaceholders: MethodHandle,
        private val execute: MethodHandle,
        private val getSyncRootInfoByPath: MethodHandle,
        private val getFileAttributes: MethodHandle,
        private val hydratePlaceholder: MethodHandle,
        private val dehydratePlaceholder: MethodHandle,
        private val setPin: MethodHandle,
        /** `CreateFileW` with `GetLastError` captured: its first argument is the capture segment. */
        private val createFile: MethodHandle,
        private val closeHandle: MethodHandle,
        private val getPlaceholderInfo: MethodHandle,
        private val openWithOplock: MethodHandle,
        private val referenceProtected: MethodHandle,
        private val releaseProtected: MethodHandle,
        private val win32FromProtected: MethodHandle,
        private val closeProtected: MethodHandle,
        private val convertToPlaceholder: MethodHandle,
        private val readFile: MethodHandle,
    ) {
        private val linker = Linker.nativeLinker()
        private val l = CloudFilesLayouts

        fun register(root: String, r: Registration, flags: Int): Int = Arena.ofConfined().use { arena ->
            val reg = arena.allocate(l.SYNC_REGISTRATION)
            reg.set(ValueLayout.JAVA_INT, l.off(l.SYNC_REGISTRATION, "StructSize"), l.SYNC_REGISTRATION.byteSize().toInt())
            reg.set(ValueLayout.ADDRESS, l.off(l.SYNC_REGISTRATION, "ProviderName"), wstr(arena, r.providerName))
            reg.set(ValueLayout.ADDRESS, l.off(l.SYNC_REGISTRATION, "ProviderVersion"), wstr(arena, r.providerVersion))
            if (r.syncRootIdentity.isNotEmpty()) {
                reg.set(ValueLayout.ADDRESS, l.off(l.SYNC_REGISTRATION, "SyncRootIdentity"), bytes(arena, r.syncRootIdentity))
                reg.set(ValueLayout.JAVA_INT, l.off(l.SYNC_REGISTRATION, "SyncRootIdentityLength"), r.syncRootIdentity.size)
            }
            l.writeGuid(reg.asSlice(l.off(l.SYNC_REGISTRATION, "ProviderId"), l.GUID.byteSize()), r.providerId)

            val policies = arena.allocate(l.SYNC_POLICIES)
            policies.set(ValueLayout.JAVA_INT, l.off(l.SYNC_POLICIES, "StructSize"), l.SYNC_POLICIES.byteSize().toInt())
            policies.set(ValueLayout.JAVA_SHORT, l.off(l.SYNC_POLICIES, "HydrationPrimary"), r.hydration)
            policies.set(ValueLayout.JAVA_SHORT, l.off(l.SYNC_POLICIES, "PopulationPrimary"), r.population)
            policies.set(ValueLayout.JAVA_INT, l.off(l.SYNC_POLICIES, "InSync"), r.inSyncPolicy)

            register.invokeExact(wstr(arena, root), reg, policies, flags) as Int
        }

        fun unregister(root: String): Int = Arena.ofConfined().use { arena -> unregister.invokeExact(wstr(arena, root)) as Int }

        fun disconnect(key: Long): Int = disconnect.invokeExact(key) as Int

        fun syncRootFileId(path: String): Result<Long> = Arena.ofConfined().use { arena ->
            val info = arena.allocate(ValueLayout.JAVA_LONG)
            val returned = arena.allocate(ValueLayout.JAVA_INT)
            val hr = getSyncRootInfoByPath.invokeExact(wstr(arena, path), SYNC_ROOT_INFO_BASIC, info, 8, returned) as Int
            if (CloudFilesHResult.succeeded(hr)) Result.success(info.get(ValueLayout.JAVA_LONG, 0L))
            else Result.failure(CloudFilesException(hr))
        }

        fun fileAttributes(path: String): Int? = Arena.ofConfined().use { arena ->
            val attributes = getFileAttributes.invokeExact(wstr(arena, path)) as Int
            if (attributes == -1) null else attributes
        }

        /**
         * Runs [op] on an attribute-only handle to [path] — enough for the pin and hydration calls, and opening
         * it does not download anything. Answers `HRESULT_FROM_WIN32(GetLastError())` when the open fails.
         */
        fun withAttributeHandle(path: String, followLinks: Boolean, op: (Any) -> Int): Int = Arena.ofConfined().use { arena ->
            val capture = arena.allocate(Linker.Option.captureStateLayout())
            val flags = FILE_FLAG_BACKUP_SEMANTICS or (if (followLinks) 0 else FILE_FLAG_OPEN_REPARSE_POINT)
            val handle = createFile.invokeExact(
                capture, wstr(arena, path), FILE_READ_ATTRIBUTES or FILE_WRITE_ATTRIBUTES,
                FILE_SHARE_ALL, MemorySegment.NULL, OPEN_EXISTING, flags, MemorySegment.NULL,
            ) as MemorySegment
            if (handle.address() == INVALID_HANDLE || handle.address() == 0L) {
                val error = capture.get(ValueLayout.JAVA_INT, Linker.Option.captureStateLayout().byteOffset(groupElement("GetLastError")))
                return@use if (error <= 0) error else (error and 0xFFFF) or 0x80070000.toInt()
            }
            try {
                op(handle)
            } finally {
                closeHandle.invokeExact(handle) as Int
            }
        }

        /** `CF_PLACEHOLDER_BASIC_INFO`, read on a handle that opens a link as itself. */
        fun placeholderInfo(path: String): PlaceholderInfo? {
            var result: PlaceholderInfo? = null
            val hr = withAttributeHandle(path, followLinks = false) { handle ->
                Arena.ofConfined().use { arena ->
                    // { CF_PIN_STATE PinState; CF_IN_SYNC_STATE InSyncState; LARGE_INTEGER FileId;
                    //   LARGE_INTEGER SyncRootFileId; ULONG FileIdentityLength; BYTE FileIdentity[]; }
                    val size = PLACEHOLDER_BASIC_INFO_IDENTITY_OFFSET + MAX_FILE_IDENTITY
                    val info = arena.allocate(size, 8L)
                    val returned = arena.allocate(ValueLayout.JAVA_INT)
                    val hr = getPlaceholderInfo.invokeExact(handle as MemorySegment, PLACEHOLDER_INFO_BASIC, info, size.toInt(), returned) as Int
                    if (CloudFilesHResult.succeeded(hr)) {
                        val length = info.get(ValueLayout.JAVA_INT, PLACEHOLDER_BASIC_INFO_IDENTITY_OFFSET - 4)
                        if (length in 0..MAX_FILE_IDENTITY.toInt()) {
                            result = PlaceholderInfo(
                                identity = info.asSlice(PLACEHOLDER_BASIC_INFO_IDENTITY_OFFSET, length.toLong()).toArray(ValueLayout.JAVA_BYTE),
                                inSync = info.get(ValueLayout.JAVA_INT, 4L) == IN_SYNC_STATE_IN_SYNC,
                            )
                        }
                    }
                    hr
                }
            }
            return if (CloudFilesHResult.succeeded(hr)) result else null
        }

        // LARGE_INTEGER by value is a 64-bit integer on x64; a length of -1 means "to the end of the file".
        fun hydrate(handle: Any): Int =
            hydratePlaceholder.invokeExact(handle as MemorySegment, 0L, -1L, 0, MemorySegment.NULL) as Int

        /**
         * Converts on an exclusive handle; [verify], when given, first reads the bytes through **that same handle** —
         * ⚠ so nothing can change them between the check and the conversion — and a `false` aborts ([E_ABORT]).
         */
        fun convertExclusively(path: String, identity: ByteArray, verify: ((read: (ByteArray) -> Int) -> Boolean)?): Int =
            onUnsharedFile(path) { arena, win32 ->
                if (verify != null) {
                    val buffer = arena.allocate(READ_BUFFER_BYTES)
                    val got = arena.allocate(ValueLayout.JAVA_INT)
                    val reader: (ByteArray) -> Int = { into ->
                        val want = minOf(into.size.toLong(), READ_BUFFER_BYTES).toInt()
                        val ok = readFile.invokeExact(win32, buffer, want, got, MemorySegment.NULL) as Int
                        check(ok != 0) { "ReadFile failed" }
                        val n = got.get(ValueLayout.JAVA_INT, 0L)
                        MemorySegment.copy(buffer, ValueLayout.JAVA_BYTE, 0L, into, 0, n)
                        if (n == 0) -1 else n
                    }
                    if (!runCatching { verify(reader) }.getOrDefault(false)) return@onUnsharedFile E_ABORT
                }
                convertToPlaceholder.invokeExact(
                    win32, bytes(arena, identity), identity.size, CF_CONVERT_FLAG_MARK_IN_SYNC, MemorySegment.NULL, MemorySegment.NULL,
                ) as Int
            }

        fun convertDirectory(path: String, identity: ByteArray): Int = onWritableFolder(path) { arena, handle ->
            convertToPlaceholder.invokeExact(
                handle, bytes(arena, identity), identity.size, CF_CONVERT_FLAG_MARK_IN_SYNC, MemorySegment.NULL, MemorySegment.NULL,
            ) as Int
        }

        /**
         * Runs [op] on a handle to an ordinary file with read and write access and **share mode 0**: nobody else can open
         * it while [op] runs. ⚠ Not `CfOpenFileWithOplock`: its handle cannot read the data (measured — every hash
         * check through it failed), and the check has to read through the very handle that converts.
         */
        fun onUnsharedFile(path: String, op: (Arena, MemorySegment) -> Int): Int = Arena.ofConfined().use { arena ->
            val capture = arena.allocate(Linker.Option.captureStateLayout())
            val handle = createFile.invokeExact(
                capture, wstr(arena, path), FILE_READ_DATA or FILE_WRITE_DATA or FILE_READ_ATTRIBUTES or FILE_WRITE_ATTRIBUTES,
                0, MemorySegment.NULL, OPEN_EXISTING, FILE_FLAG_OPEN_REPARSE_POINT, MemorySegment.NULL,
            ) as MemorySegment
            if (handle.address() == INVALID_HANDLE || handle.address() == 0L) {
                val error = capture.get(ValueLayout.JAVA_INT, Linker.Option.captureStateLayout().byteOffset(groupElement("GetLastError")))
                return@use if (error <= 0) error else (error and 0xFFFF) or 0x80070000.toInt()
            }
            try {
                op(arena, handle)
            } finally {
                closeHandle.invokeExact(handle) as Int
            }
        }

        /** Runs [op] on a folder handle with write access, shared with everyone, opened as itself. */
        fun onWritableFolder(path: String, op: (Arena, MemorySegment) -> Int): Int = Arena.ofConfined().use { arena ->
            val capture = arena.allocate(Linker.Option.captureStateLayout())
            val handle = createFile.invokeExact(
                capture, wstr(arena, path), FILE_READ_ATTRIBUTES or FILE_WRITE_ATTRIBUTES or FILE_WRITE_DATA,
                FILE_SHARE_ALL, MemorySegment.NULL, OPEN_EXISTING, FILE_FLAG_BACKUP_SEMANTICS or FILE_FLAG_OPEN_REPARSE_POINT,
                MemorySegment.NULL,
            ) as MemorySegment
            if (handle.address() == INVALID_HANDLE || handle.address() == 0L) {
                val error = capture.get(ValueLayout.JAVA_INT, Linker.Option.captureStateLayout().byteOffset(groupElement("GetLastError")))
                return@use if (error <= 0) error else (error and 0xFFFF) or 0x80070000.toInt()
            }
            try {
                op(arena, handle)
            } finally {
                closeHandle.invokeExact(handle) as Int
            }
        }

        fun dehydrateExclusively(path: String): Int =
            onExclusiveHandle(path, CF_OPEN_FILE_FLAG_EXCLUSIVE) { _, win32 ->
                dehydratePlaceholder.invokeExact(win32, 0L, -1L, 0, MemorySegment.NULL) as Int
            }

        /** Runs [op] on the Win32 handle of a `CfOpenFileWithOplock` protected handle, referenced for the duration. */
        private fun onExclusiveHandle(path: String, flags: Int, op: (Arena, MemorySegment) -> Int): Int = Arena.ofConfined().use { arena ->
            val protectedHandle = arena.allocate(ValueLayout.ADDRESS)
            val opened = openWithOplock.invokeExact(wstr(arena, path), flags, protectedHandle) as Int
            if (!CloudFilesHResult.succeeded(opened)) return@use opened
            val handle = protectedHandle.get(ValueLayout.ADDRESS, 0L)
            try {
                // The Win32 handle stays valid only while the protected handle is referenced (the oplock may break).
                if ((referenceProtected.invokeExact(handle) as Byte).toInt() == 0) return@use CloudFilesHResult.E_FAIL
                try {
                    op(arena, win32FromProtected.invokeExact(handle) as MemorySegment)
                } finally {
                    releaseProtected.invokeExact(handle)
                }
            } finally {
                closeProtected.invokeExact(handle)
            }
        }

        fun setPinState(handle: Any, state: Int): Int =
            setPin.invokeExact(handle as MemorySegment, state, 0, MemorySegment.NULL) as Int

        fun createPlaceholder(parent: String, p: Placeholder): Int = Arena.ofConfined().use { arena ->
            val info = arena.allocate(l.PLACEHOLDER_CREATE_INFO)
            info.set(ValueLayout.ADDRESS, l.off(l.PLACEHOLDER_CREATE_INFO, "RelativeFileName"), wstr(arena, p.relativeName))
            val meta = l.off(l.PLACEHOLDER_CREATE_INFO, "FsMetadata")
            val fileTime = p.modifiedAtMillis * 10_000L + FILETIME_EPOCH_OFFSET
            for (field in listOf("CreationTime", "LastAccessTime", "LastWriteTime", "ChangeTime")) {
                info.set(ValueLayout.JAVA_LONG, meta + l.off(l.FILE_BASIC_INFO, field), fileTime)
            }
            info.set(
                ValueLayout.JAVA_INT,
                meta + l.off(l.FILE_BASIC_INFO, "FileAttributes"),
                (if (p.directory) FILE_ATTRIBUTE_DIRECTORY else FILE_ATTRIBUTE_NORMAL)
                    .let { if (p.readOnly) (it and FILE_ATTRIBUTE_NORMAL.inv()) or FILE_ATTRIBUTE_READONLY else it },
            )
            info.set(ValueLayout.JAVA_LONG, meta + l.off(l.FS_METADATA, "FileSize"), if (p.directory) 0L else p.size)
            info.set(ValueLayout.ADDRESS, l.off(l.PLACEHOLDER_CREATE_INFO, "FileIdentity"), bytes(arena, p.identity))
            info.set(ValueLayout.JAVA_INT, l.off(l.PLACEHOLDER_CREATE_INFO, "FileIdentityLength"), p.identity.size)
            info.set(ValueLayout.JAVA_INT, l.off(l.PLACEHOLDER_CREATE_INFO, "Flags"), p.flags)
            val processed = arena.allocate(ValueLayout.JAVA_INT)
            val hr = createPlaceholders.invokeExact(wstr(arena, parent), info, 1, 0, processed) as Int
            // The call's HRESULT and the item's can differ; the item's is the one that says why.
            val itemResult = info.get(ValueLayout.JAVA_INT, l.off(l.PLACEHOLDER_CREATE_INFO, "Result"))
            if (!CloudFilesHResult.succeeded(itemResult)) itemResult else hr
        }

        fun transfer(request: FetchData, offset: Long, data: ByteArray, completionStatus: Int): Int = Arena.ofConfined().use { arena ->
            val op = arena.allocate(l.OPERATION_INFO)
            op.set(ValueLayout.JAVA_INT, l.off(l.OPERATION_INFO, "StructSize"), l.OPERATION_INFO.byteSize().toInt())
            op.set(ValueLayout.JAVA_INT, l.off(l.OPERATION_INFO, "Type"), OPERATION_TYPE_TRANSFER_DATA)
            op.set(ValueLayout.JAVA_LONG, l.off(l.OPERATION_INFO, "ConnectionKey"), request.connectionKey)
            op.set(ValueLayout.JAVA_LONG, l.off(l.OPERATION_INFO, "TransferKey"), request.transferKey)
            op.set(ValueLayout.JAVA_LONG, l.off(l.OPERATION_INFO, "RequestKey"), request.requestKey)

            val params = arena.allocate(l.OPERATION_PARAMETERS_TRANSFER_DATA)
            params.set(ValueLayout.JAVA_INT, l.off(l.OPERATION_PARAMETERS_TRANSFER_DATA, "ParamSize"), l.OPERATION_PARAMETERS_TRANSFER_DATA.byteSize().toInt())
            params.set(ValueLayout.JAVA_INT, l.off(l.OPERATION_PARAMETERS_TRANSFER_DATA, "CompletionStatus"), completionStatus)
            val buffer = arena.allocate(maxOf(data.size, 1).toLong(), 8L)
            MemorySegment.copy(MemorySegment.ofArray(data), 0L, buffer, 0L, data.size.toLong())
            params.set(ValueLayout.ADDRESS, l.off(l.OPERATION_PARAMETERS_TRANSFER_DATA, "Buffer"), buffer)
            params.set(ValueLayout.JAVA_LONG, l.off(l.OPERATION_PARAMETERS_TRANSFER_DATA, "Offset"), offset)
            // A failure still names the range it fails; the bytes are not read.
            val length = if (completionStatus == 0) data.size.toLong() else request.requiredLength
            params.set(ValueLayout.JAVA_LONG, l.off(l.OPERATION_PARAMETERS_TRANSFER_DATA, "Length"), length)
            try {
                execute.invokeExact(op, params) as Int
            } finally {
                buffer.fill(0) // file plaintext: do not leave it in native memory
            }
        }

        fun connect(root: String, handler: Handler, flags: Int): ConnectResult {
            // ⚠ Global, never freed: see the class comment — a disconnect does not fence running callbacks.
            val stubs = Arena.global()
            val dispatcher = Dispatcher(handler)
            val descriptor = FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS)
            val type = MethodType.methodType(Void.TYPE, MemorySegment::class.java, MemorySegment::class.java)
            val lookup = MethodHandles.lookup()
            val fetch = linker.upcallStub(lookup.findVirtual(Dispatcher::class.java, "onFetchData", type).bindTo(dispatcher), descriptor, stubs)
            val cancel = linker.upcallStub(lookup.findVirtual(Dispatcher::class.java, "onCancelFetchData", type).bindTo(dispatcher), descriptor, stubs)

            val table = stubs.allocate(l.CALLBACK_REGISTRATION, 3)
            fun entry(index: Long, callbackType: Int, stub: MemorySegment) {
                val at = index * l.CALLBACK_REGISTRATION.byteSize()
                table.set(ValueLayout.JAVA_INT, at + l.off(l.CALLBACK_REGISTRATION, "Type"), callbackType)
                table.set(ValueLayout.ADDRESS, at + l.off(l.CALLBACK_REGISTRATION, "Callback"), stub)
            }
            entry(0, CALLBACK_TYPE_FETCH_DATA, fetch)
            entry(1, CALLBACK_TYPE_CANCEL_FETCH_DATA, cancel)
            entry(2, CALLBACK_TYPE_NONE, MemorySegment.NULL)

            return Arena.ofConfined().use { arena ->
                val key = arena.allocate(ValueLayout.JAVA_LONG)
                val hr = connect.invokeExact(wstr(arena, root), table, MemorySegment.NULL, flags, key) as Int
                if (CloudFilesHResult.succeeded(hr)) ConnectResult.Connected(Connection(key.get(ValueLayout.JAVA_LONG, 0L)))
                else ConnectResult.Failed(hr)
            }
        }

        /** The upcall targets. ⚠ Nothing may escape these methods: an exception in an upcall aborts the JVM. */
        class Dispatcher(private val handler: Handler) {
            private val l = CloudFilesLayouts

            fun onFetchData(infoPointer: MemorySegment, paramsPointer: MemorySegment) {
                // ⚠ Failed from the keys alone when the rest cannot be read: an unanswered request leaves the
                // opener waiting out the ~60 s timeout.
                var minimal: FetchData? = null
                val request: FetchData = try {
                    val info = infoPointer.reinterpret(l.CALLBACK_INFO.byteSize())
                    val params = paramsPointer.reinterpret(l.CALLBACK_PARAMETERS_FETCH_DATA.byteSize())
                    val p = l.CALLBACK_PARAMETERS_FETCH_DATA
                    // Only what the OS sent: an older Windows may hand over a shorter CF_CALLBACK_INFO.
                    val structSize = info.get(ValueLayout.JAVA_INT, l.off(l.CALLBACK_INFO, "StructSize")).toLong()
                    fun sent(field: String) = structSize >= l.off(l.CALLBACK_INFO, field) + 8
                    val facts = facts(info, params)
                    val connectionKey = info.get(ValueLayout.JAVA_LONG, l.off(l.CALLBACK_INFO, "ConnectionKey"))
                    val transferKey = info.get(ValueLayout.JAVA_LONG, l.off(l.CALLBACK_INFO, "TransferKey"))
                    val requestKey = if (sent("RequestKey")) info.get(ValueLayout.JAVA_LONG, l.off(l.CALLBACK_INFO, "RequestKey")) else 0L
                    val fileSize = info.get(ValueLayout.JAVA_LONG, l.off(l.CALLBACK_INFO, "FileSize"))
                    val requiredOffset = params.get(ValueLayout.JAVA_LONG, l.off(p, "RequiredFileOffset"))
                    val requiredLength = params.get(ValueLayout.JAVA_LONG, l.off(p, "RequiredLength"))
                    minimal = FetchData(
                        connectionKey, transferKey, requestKey, ByteArray(0), null, fileSize,
                        requiredOffset, requiredLength, requiredOffset, requiredLength, null, facts,
                    )
                    FetchData(
                        connectionKey = connectionKey,
                        transferKey = transferKey,
                        requestKey = requestKey,
                        fileIdentity = fileIdentity(info),
                        normalizedPath = readWstr(info.get(ValueLayout.ADDRESS, l.off(l.CALLBACK_INFO, "NormalizedPath"))),
                        fileSize = fileSize,
                        requiredOffset = requiredOffset,
                        requiredLength = requiredLength,
                        optionalOffset = params.get(ValueLayout.JAVA_LONG, l.off(p, "OptionalFileOffset")),
                        optionalLength = params.get(ValueLayout.JAVA_LONG, l.off(p, "OptionalLength")),
                        process = if (sent("ProcessInfo")) process(info) else null,
                        facts = facts,
                    )
                } catch (t: Throwable) {
                    runCatching { minimal?.fail() }
                    return
                }
                try {
                    handler.fetchData(request)
                } catch (t: Throwable) {
                    runCatching { request.fail() }
                }
            }

            fun onCancelFetchData(infoPointer: MemorySegment, paramsPointer: MemorySegment) {
                try {
                    val info = infoPointer.reinterpret(l.CALLBACK_INFO.byteSize())
                    val params = paramsPointer.reinterpret(l.CALLBACK_PARAMETERS_CANCEL.byteSize())
                    handler.cancelFetchData(
                        CancelFetchData(
                            transferKey = info.get(ValueLayout.JAVA_LONG, l.off(l.CALLBACK_INFO, "TransferKey")),
                            fileIdentity = fileIdentity(info),
                            offset = params.get(ValueLayout.JAVA_LONG, l.off(l.CALLBACK_PARAMETERS_CANCEL, "FileOffset")),
                            length = params.get(ValueLayout.JAVA_LONG, l.off(l.CALLBACK_PARAMETERS_CANCEL, "Length")),
                        ),
                    )
                } catch (_: Throwable) {
                }
            }

            private fun facts(info: MemorySegment, params: MemorySegment): CallbackFacts {
                val thread = Thread.currentThread()
                return CallbackFacts(
                    callbackInfoStructSize = info.get(ValueLayout.JAVA_INT, l.off(l.CALLBACK_INFO, "StructSize")),
                    parameterSize = params.get(ValueLayout.JAVA_INT, 0L),
                    threadName = thread.name,
                    threadId = thread.threadId(), // JDK 19+; fine, this runs only where FFM does
                )
            }

            private fun fileIdentity(info: MemorySegment): ByteArray {
                val pointer = info.get(ValueLayout.ADDRESS, l.off(l.CALLBACK_INFO, "FileIdentity"))
                val length = info.get(ValueLayout.JAVA_INT, l.off(l.CALLBACK_INFO, "FileIdentityLength"))
                if (pointer.address() == 0L || length <= 0) return ByteArray(0)
                return pointer.reinterpret(length.toLong()).toArray(ValueLayout.JAVA_BYTE)
            }

            private fun process(info: MemorySegment): RequestingProcess? {
                val pointer = info.get(ValueLayout.ADDRESS, l.off(l.CALLBACK_INFO, "ProcessInfo"))
                if (pointer.address() == 0L) return null
                val process = pointer.reinterpret(l.PROCESS_INFO.byteSize())
                val size = process.get(ValueLayout.JAVA_INT, l.off(l.PROCESS_INFO, "StructSize"))
                if (size < l.off(l.PROCESS_INFO, "PackageName")) return null
                return RequestingProcess(
                    pid = process.get(ValueLayout.JAVA_INT, l.off(l.PROCESS_INFO, "ProcessId")).toLong() and 0xFFFF_FFFFL,
                    imagePath = readWstr(process.get(ValueLayout.ADDRESS, l.off(l.PROCESS_INFO, "ImagePath"))),
                )
            }
        }

        companion object {
            private const val SYNC_ROOT_INFO_BASIC = 0
            private const val OPERATION_TYPE_TRANSFER_DATA = 0
            private const val CALLBACK_TYPE_FETCH_DATA = 0
            private const val CALLBACK_TYPE_CANCEL_FETCH_DATA = 2
            private const val CALLBACK_TYPE_NONE = -1 // 0xFFFFFFFF
            private const val FILETIME_EPOCH_OFFSET = 116_444_736_000_000_000L
            private const val FILE_READ_ATTRIBUTES = 0x80
            private const val FILE_WRITE_ATTRIBUTES = 0x100
            private const val FILE_WRITE_DATA = 0x2 // FILE_ADD_FILE on a folder
            private const val FILE_READ_DATA = 0x1
            private const val FILE_SHARE_ALL = 0x7 // read | write | delete
            private const val OPEN_EXISTING = 3
            private const val FILE_FLAG_BACKUP_SEMANTICS = 0x02000000 // so a folder opens too
            private const val FILE_FLAG_OPEN_REPARSE_POINT = 0x00200000 // a link opens as itself
            private const val PLACEHOLDER_INFO_BASIC = 0
            private const val CF_OPEN_FILE_FLAG_EXCLUSIVE = 0x1
            private const val CF_CONVERT_FLAG_MARK_IN_SYNC = 0x1
            private const val READ_BUFFER_BYTES = 64L * 1024
            private const val IN_SYNC_STATE_IN_SYNC = 1
            private const val PLACEHOLDER_BASIC_INFO_IDENTITY_OFFSET = 28L
            private const val MAX_FILE_IDENTITY = 4096L
            private const val INVALID_HANDLE = -1L

            fun wstr(arena: Arena, s: String): MemorySegment = WindowsCloudFilesStrings.wstr(arena, s)

            fun bytes(arena: Arena, b: ByteArray): MemorySegment {
                val segment = arena.allocate(maxOf(b.size, 1).toLong())
                MemorySegment.copy(MemorySegment.ofArray(b), 0L, segment, 0L, b.size.toLong())
                return segment
            }

            fun readWstr(pointer: MemorySegment): String? =
                if (pointer.address() == 0L) null else WindowsCloudFilesStrings.read(pointer)

            fun loadOrNull(): Natives? {
                if (ValueLayout.ADDRESS.byteSize() != 8L) return null
                val linker = Linker.nativeLinker()
                fun handle(library: String, symbol: String, descriptor: FunctionDescriptor, vararg options: Linker.Option): MethodHandle? {
                    val address = SymbolLookup.libraryLookup(library, Arena.global()).find(symbol).orElse(null) ?: return null
                    return linker.downcallHandle(address, descriptor, *options)
                }
                val i = ValueLayout.JAVA_INT
                val a = ValueLayout.ADDRESS
                val j = ValueLayout.JAVA_LONG
                val cf = "cldapi.dll"
                return Natives(
                    // HRESULT CfRegisterSyncRoot(PCWSTR, const CF_SYNC_REGISTRATION*, const CF_SYNC_POLICIES*, CF_REGISTER_FLAGS)
                    register = handle(cf, "CfRegisterSyncRoot", FunctionDescriptor.of(i, a, a, a, i)) ?: return null,
                    unregister = handle(cf, "CfUnregisterSyncRoot", FunctionDescriptor.of(i, a)) ?: return null,
                    // HRESULT CfConnectSyncRoot(PCWSTR, const CF_CALLBACK_REGISTRATION*, LPCVOID ctx, CF_CONNECT_FLAGS, CF_CONNECTION_KEY*)
                    connect = handle(cf, "CfConnectSyncRoot", FunctionDescriptor.of(i, a, a, a, i, a)) ?: return null,
                    // HRESULT CfDisconnectSyncRoot(CF_CONNECTION_KEY) — an 8-byte struct by value = a 64-bit integer on x64
                    disconnect = handle(cf, "CfDisconnectSyncRoot", FunctionDescriptor.of(i, ValueLayout.JAVA_LONG)) ?: return null,
                    // HRESULT CfCreatePlaceholders(PCWSTR base, CF_PLACEHOLDER_CREATE_INFO*, DWORD count, CF_CREATE_FLAGS, PDWORD processed)
                    createPlaceholders = handle(cf, "CfCreatePlaceholders", FunctionDescriptor.of(i, a, a, i, i, a)) ?: return null,
                    // HRESULT CfExecute(const CF_OPERATION_INFO*, CF_OPERATION_PARAMETERS*)
                    execute = handle(cf, "CfExecute", FunctionDescriptor.of(i, a, a)) ?: return null,
                    // HRESULT CfGetSyncRootInfoByPath(PCWSTR, CF_SYNC_ROOT_INFO_CLASS, PVOID, DWORD, DWORD*)
                    getSyncRootInfoByPath = handle(cf, "CfGetSyncRootInfoByPath", FunctionDescriptor.of(i, a, i, a, i, a)) ?: return null,
                    getFileAttributes = handle("kernel32.dll", "GetFileAttributesW", FunctionDescriptor.of(i, a)) ?: return null,
                    // HRESULT CfHydratePlaceholder(HANDLE, LARGE_INTEGER offset, LARGE_INTEGER length, CF_HYDRATE_FLAGS, LPOVERLAPPED)
                    hydratePlaceholder = handle(cf, "CfHydratePlaceholder", FunctionDescriptor.of(i, a, j, j, i, a)) ?: return null,
                    // HRESULT CfDehydratePlaceholder(HANDLE, LARGE_INTEGER offset, LARGE_INTEGER length, CF_DEHYDRATE_FLAGS, LPOVERLAPPED)
                    dehydratePlaceholder = handle(cf, "CfDehydratePlaceholder", FunctionDescriptor.of(i, a, j, j, i, a)) ?: return null,
                    // HRESULT CfSetPinState(HANDLE, CF_PIN_STATE, CF_SET_PIN_FLAGS, LPOVERLAPPED)
                    setPin = handle(cf, "CfSetPinState", FunctionDescriptor.of(i, a, i, i, a)) ?: return null,
                    // HANDLE CreateFileW(LPCWSTR, DWORD access, DWORD share, LPSECURITY_ATTRIBUTES, DWORD disposition, DWORD flags, HANDLE)
                    createFile = handle(
                        "kernel32.dll", "CreateFileW", FunctionDescriptor.of(a, a, i, i, a, i, i, a),
                        Linker.Option.captureCallState("GetLastError"),
                    ) ?: return null,
                    closeHandle = handle("kernel32.dll", "CloseHandle", FunctionDescriptor.of(i, a)) ?: return null,
                    // HRESULT CfGetPlaceholderInfo(HANDLE, CF_PLACEHOLDER_INFO_CLASS, PVOID, DWORD, PDWORD)
                    getPlaceholderInfo = handle(cf, "CfGetPlaceholderInfo", FunctionDescriptor.of(i, a, i, a, i, a)) ?: return null,
                    // HRESULT CfOpenFileWithOplock(LPCWSTR, CF_OPEN_FILE_FLAGS, HANDLE* ProtectedHandle)
                    openWithOplock = handle(cf, "CfOpenFileWithOplock", FunctionDescriptor.of(i, a, i, a)) ?: return null,
                    // BOOLEAN CfReferenceProtectedHandle(HANDLE) — a 1-byte BOOLEAN
                    referenceProtected = handle(cf, "CfReferenceProtectedHandle", FunctionDescriptor.of(ValueLayout.JAVA_BYTE, a)) ?: return null,
                    // void CfReleaseProtectedHandle(HANDLE)
                    releaseProtected = handle(cf, "CfReleaseProtectedHandle", FunctionDescriptor.ofVoid(a)) ?: return null,
                    // HANDLE CfGetWin32HandleFromProtectedHandle(HANDLE)
                    win32FromProtected = handle(cf, "CfGetWin32HandleFromProtectedHandle", FunctionDescriptor.of(a, a)) ?: return null,
                    // void CfCloseHandle(HANDLE)
                    closeProtected = handle(cf, "CfCloseHandle", FunctionDescriptor.ofVoid(a)) ?: return null,
                    // HRESULT CfConvertToPlaceholder(HANDLE, LPCVOID FileIdentity, DWORD, CF_CONVERT_FLAGS, USN*, LPOVERLAPPED)
                    convertToPlaceholder = handle(cf, "CfConvertToPlaceholder", FunctionDescriptor.of(i, a, a, i, i, a, a)) ?: return null,
                    // BOOL ReadFile(HANDLE, LPVOID, DWORD, LPDWORD, LPOVERLAPPED)
                    readFile = handle("kernel32.dll", "ReadFile", FunctionDescriptor.of(i, a, a, i, a, a)) ?: return null,
                )
            }
        }
    }
}

/** A failed CfAPI call, carrying its HRESULT. */
class CloudFilesException(val hresult: Int) : RuntimeException(CloudFilesHResult.describe(hresult))

/**
 * The `cfapi.h` structs as FFM layouts (x64), each written from the documented C declaration with its
 * padding explicit — FFM refuses a misaligned member, which is the first check. `CloudFilesLayoutsTest`
 * pins every size and offset; the X0 spike checks `CF_CALLBACK_INFO.StructSize` and the callback
 * parameter size against what the OS actually sends.
 *
 * ⚠⚠ FFM types: load only from [WindowsCloudFiles]' natives or from tests (see that object's rule).
 */
internal object CloudFilesLayouts {
    private val I = ValueLayout.JAVA_INT
    private val S = ValueLayout.JAVA_SHORT
    private val L = ValueLayout.JAVA_LONG
    private val A = ValueLayout.ADDRESS
    private fun pad(n: Long) = MemoryLayout.paddingLayout(n)

    /** `GUID { DWORD; WORD; WORD; BYTE[8]; }` — 16 bytes, 4-aligned. */
    val GUID: StructLayout = MemoryLayout.structLayout(
        I.withName("Data1"), S.withName("Data2"), S.withName("Data3"),
        MemoryLayout.sequenceLayout(8, ValueLayout.JAVA_BYTE).withName("Data4"),
    )

    /** `CF_CALLBACK_REGISTRATION { CF_CALLBACK_TYPE Type; CF_CALLBACK Callback; }` */
    val CALLBACK_REGISTRATION: StructLayout = MemoryLayout.structLayout(I.withName("Type"), pad(4), A.withName("Callback"))

    val SYNC_REGISTRATION: StructLayout = MemoryLayout.structLayout(
        I.withName("StructSize"), pad(4),
        A.withName("ProviderName"),
        A.withName("ProviderVersion"),
        A.withName("SyncRootIdentity"),
        I.withName("SyncRootIdentityLength"), pad(4),
        A.withName("FileIdentity"),
        I.withName("FileIdentityLength"),
        GUID.withName("ProviderId"),
        pad(4),
    )

    /** `CF_HYDRATION_POLICY` / `CF_POPULATION_POLICY` are each `{ USHORT Primary; USHORT Modifier; }`. */
    val SYNC_POLICIES: StructLayout = MemoryLayout.structLayout(
        I.withName("StructSize"),
        S.withName("HydrationPrimary"), S.withName("HydrationModifier"),
        S.withName("PopulationPrimary"), S.withName("PopulationModifier"),
        I.withName("InSync"),
        I.withName("HardLink"),
        I.withName("PlaceholderManagement"),
    )

    val FILE_BASIC_INFO: StructLayout = MemoryLayout.structLayout(
        L.withName("CreationTime"), L.withName("LastAccessTime"), L.withName("LastWriteTime"), L.withName("ChangeTime"),
        I.withName("FileAttributes"), pad(4),
    )

    val FS_METADATA: StructLayout = MemoryLayout.structLayout(FILE_BASIC_INFO.withName("BasicInfo"), L.withName("FileSize"))

    val PLACEHOLDER_CREATE_INFO: StructLayout = MemoryLayout.structLayout(
        A.withName("RelativeFileName"),
        FS_METADATA.withName("FsMetadata"),
        A.withName("FileIdentity"),
        I.withName("FileIdentityLength"),
        I.withName("Flags"),
        I.withName("Result"), pad(4),
        L.withName("CreateUsn"),
    )

    val CALLBACK_INFO: StructLayout = MemoryLayout.structLayout(
        I.withName("StructSize"), pad(4),
        L.withName("ConnectionKey"),
        A.withName("CallbackContext"),
        A.withName("VolumeGuidName"),
        A.withName("VolumeDosName"),
        I.withName("VolumeSerialNumber"), pad(4),
        L.withName("SyncRootFileId"),
        A.withName("SyncRootIdentity"),
        I.withName("SyncRootIdentityLength"), pad(4),
        L.withName("FileId"),
        L.withName("FileSize"),
        A.withName("FileIdentity"),
        I.withName("FileIdentityLength"), pad(4),
        A.withName("NormalizedPath"),
        L.withName("TransferKey"),
        ValueLayout.JAVA_BYTE.withName("PriorityHint"), pad(7),
        A.withName("CorrelationVector"),
        A.withName("ProcessInfo"),
        L.withName("RequestKey"),
    )

    /** `CF_PROCESS_INFO`. */
    val PROCESS_INFO: StructLayout = MemoryLayout.structLayout(
        I.withName("StructSize"), I.withName("ProcessId"),
        A.withName("ImagePath"), A.withName("PackageName"), A.withName("ApplicationId"), A.withName("CommandLine"),
        I.withName("SessionId"), pad(4),
    )

    /** `CF_CALLBACK_PARAMETERS` with its union read as the `FetchData` arm. */
    val CALLBACK_PARAMETERS_FETCH_DATA: StructLayout = MemoryLayout.structLayout(
        I.withName("ParamSize"), pad(4),
        I.withName("Flags"), pad(4),
        L.withName("RequiredFileOffset"),
        L.withName("RequiredLength"),
        L.withName("OptionalFileOffset"),
        L.withName("OptionalLength"),
        L.withName("LastDehydrationTime"),
        I.withName("LastDehydrationReason"), pad(4),
    )

    /** `CF_CALLBACK_PARAMETERS` with its union read as the `Cancel` arm (`FetchData` sub-arm). */
    val CALLBACK_PARAMETERS_CANCEL: StructLayout = MemoryLayout.structLayout(
        I.withName("ParamSize"), pad(4),
        I.withName("Flags"), pad(4),
        L.withName("FileOffset"),
        L.withName("Length"),
    )

    val OPERATION_INFO: StructLayout = MemoryLayout.structLayout(
        I.withName("StructSize"),
        I.withName("Type"),
        L.withName("ConnectionKey"),
        L.withName("TransferKey"),
        A.withName("CorrelationVector"),
        A.withName("SyncStatus"),
        L.withName("RequestKey"),
    )

    /** `CF_OPERATION_PARAMETERS` up to the end of the `TransferData` arm = `CF_SIZE_OF_OP_PARAM(TransferData)`. */
    val OPERATION_PARAMETERS_TRANSFER_DATA: StructLayout = MemoryLayout.structLayout(
        I.withName("ParamSize"), pad(4),
        I.withName("Flags"),
        I.withName("CompletionStatus"),
        A.withName("Buffer"),
        L.withName("Offset"),
        L.withName("Length"),
    )

    fun off(layout: StructLayout, name: String): Long = layout.byteOffset(groupElement(name))

    fun writeGuid(target: MemorySegment, uuid: UUID) {
        val msb = uuid.mostSignificantBits
        val lsb = uuid.leastSignificantBits
        // Data1..3 are little-endian integers; Data4 is the last 8 bytes in order.
        target.set(I, 0L, (msb ushr 32).toInt())
        target.set(S, 4L, (msb ushr 16).toShort())
        target.set(S, 6L, msb.toShort())
        for (b in 0 until 8) target.set(ValueLayout.JAVA_BYTE, 8L + b, (lsb ushr (56 - 8 * b)).toByte())
    }
}
