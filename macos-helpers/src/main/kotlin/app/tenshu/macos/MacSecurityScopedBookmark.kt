package app.tenshu.macos

import app.tenshu.macos.ffi.ObjC
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Creates and resolves macOS security-scoped folder bookmarks in-process.
 *
 * The bookmark must be created by the sandboxed app that will later read the folder. The returned
 * [Access] owns one active security scope; callers must close it after each scan. Bookmark bytes
 * are intentionally returned to the caller so persistence can stay in the app's own container or
 * App Group rather than in a shared path string.
 */
public object MacSecurityScopedBookmark {
    /** Whether Foundation/NSURL is available in this process. */
    public val isAvailable: Boolean
        get() = ObjC.isAvailable && !ObjC.isNull(ObjC.cls("NSURL"))

    /**
     * Create a security-scoped bookmark for an already user-selected directory.
     *
     * This method does not present a picker. The caller must obtain [path] from an NSOpenPanel
     * (or another system-mediated selection) before calling it.
     */
    public fun create(path: String): ByteArray? {
        if (!isAvailable || path.isBlank()) return null
        return runCatching {
            ObjC.autoreleasePool {
                val url = ObjC.send(
                    ObjC.cls("NSURL"),
                    "fileURLWithPath:",
                    ObjC.nsString(path),
                )
                if (ObjC.isNull(url)) return@autoreleasePool null

                val data = ObjC.sendWithLongAndPointers(
                    url,
                    "bookmarkDataWithOptions:includingResourceValuesForKeys:relativeToURL:error:",
                    CREATION_WITH_SECURITY_SCOPE,
                    MemorySegment.NULL, // resource keys
                    MemorySegment.NULL, // relative URL
                    MemorySegment.NULL, // NSError**
                )
                data.toByteArrayOrNull()
            }
        }.getOrNull()
    }

    /**
     * Resolve [bookmark] and start accessing its security scope.
     *
     * The caller owns the returned [Access] and must close it, even when a scan fails. A stale
     * bookmark is accepted when macOS can still resolve and open the folder; [Access.isStale]
     * tells the caller to mint and persist a replacement bookmark while the scope is active.
     */
    public fun resolve(bookmark: ByteArray): Access? {
        if (!isAvailable || bookmark.isEmpty()) return null
        return runCatching {
            ObjC.autoreleasePool {
                Arena.ofConfined().use { arena ->
                    val raw = arena.allocate(bookmark.size.toLong())
                    raw.copyFrom(MemorySegment.ofArray(bookmark))
                    val data = ObjC.sendWithPointerAndLong(
                        ObjC.cls("NSData"),
                        "dataWithBytes:length:",
                        raw,
                        bookmark.size.toLong(),
                    )
                    if (ObjC.isNull(data)) return@use null

                    val stale = arena.allocate(ValueLayout.JAVA_BYTE)
                    val url = ObjC.sendWithPointerLongAndPointers(
                        ObjC.cls("NSURL"),
                        "URLByResolvingBookmarkData:options:relativeToURL:bookmarkDataIsStale:error:",
                        data,
                        RESOLUTION_WITH_SECURITY_SCOPE,
                        MemorySegment.NULL, // relative URL
                        stale,
                        MemorySegment.NULL, // NSError**
                    )
                    if (ObjC.isNull(url)) {
                        return@use null
                    }
                    val isStale = stale.get(ValueLayout.JAVA_BYTE, 0).toInt() != 0

                    val path = ObjC.kotlinString(ObjC.send(url, "path"))
                    if (path.isBlank()) return@use null

                    val retained = ObjC.send(url, "retain")
                    if (ObjC.isNull(retained) ||
                        !ObjC.sendBool(retained, "startAccessingSecurityScopedResource")
                    ) {
                        if (!ObjC.isNull(retained)) ObjC.release(retained)
                        return@use null
                    }
                    Access(path, retained, isStale)
                }
            }
        }.getOrNull()
    }

    /** One active security-scoped access; close it after the file operation finishes. */
    public class Access internal constructor(
        public val path: String,
        private val url: MemorySegment,
        /** Whether macOS resolved this bookmark but marked its stored location as stale. */
        public val isStale: Boolean,
    ) : AutoCloseable {
        private val closed = AtomicBoolean(false)

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            ObjC.sendVoid(url, "stopAccessingSecurityScopedResource")
            ObjC.release(url)
        }
    }

    private fun MemorySegment.toByteArrayOrNull(): ByteArray? {
        if (ObjC.isNull(this)) return null
        val length = ObjC.sendLong(this, "length")
        if (length <= 0L) return null
        val bytes = ObjC.send(this, "bytes")
        if (ObjC.isNull(bytes)) return null
        return ObjC.reinterpretUnbounded(bytes).reinterpret(length).toArray(ValueLayout.JAVA_BYTE)
    }

    private const val CREATION_WITH_SECURITY_SCOPE = 1L shl 11
    private const val RESOLUTION_WITH_SECURITY_SCOPE = 1L shl 10
}
