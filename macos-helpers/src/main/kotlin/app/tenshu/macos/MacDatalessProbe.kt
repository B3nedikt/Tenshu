package app.tenshu.macos

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle

/**
 * Detects a macOS **dataless placeholder** file — one exposed by a cloud File
 * Provider (OneDrive / iCloud Drive / Dropbox / Google Drive) whose bytes are NOT
 * on disk and would be faulted in **over the network** on first read
 * (`drive-folder-upload.md` §11). Reads the file's BSD `st_flags` via `stat`
 * (metadata only — this does **not** trigger a fault-in) and checks the
 * `SF_DATALESS` bit.
 *
 * All-or-nothing: on a non-macOS or non-FFM runtime, or on any failure (missing
 * file, restricted runtime), it returns `false` — "treat as an ordinary local
 * file", the behaviour-preserving default (the caller just reads it as today).
 */
object MacDatalessProbe {

    private const val SF_DATALESS = 0x4000_0000 // "file is a dataless placeholder"

    // Offsets into arm64/x86_64 `struct stat` (_DARWIN_FEATURE_64_BIT_INODE, the
    // modern default): st_size @96 (off_t), st_blocks @104 (blkcnt_t), st_flags @116.
    private const val ST_SIZE_OFFSET = 96L
    private const val ST_BLOCKS_OFFSET = 104L
    private const val ST_FLAGS_OFFSET = 116L
    private const val STAT_BUF_SIZE = 160L // struct stat is ~144 bytes; pad for safety

    private val isMac =
        System.getProperty("os.name").orEmpty().startsWith("Mac", ignoreCase = true)

    // int stat(const char *path, struct stat *buf)  (arm64/modern macOS: no $INODE64 suffix)
    private val stat: MethodHandle? by lazy {
        if (!isMac) return@lazy null
        runCatching {
            val linker = Linker.nativeLinker()
            val addr = linker.defaultLookup().find("stat").orElse(null) ?: return@runCatching null
            linker.downcallHandle(
                addr,
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS),
            )
        }.getOrNull()
    }

    /**
     * `true` iff [path] is an online-only cloud placeholder (`SF_DATALESS` set).
     * Never throws — any failure (non-macOS, missing file, restricted runtime)
     * returns `false`.
     */
    fun isDataless(path: String): Boolean = ((stFlagsOrNull(path) ?: 0) and SF_DATALESS) != 0

    /**
     * The file's BSD `st_flags` (from `stat`), or `null` on any failure. `internal`
     * so a test can pin the struct-`stat` offset against a known `chflags` bit — the
     * exact thing the earlier `getattrlist` implementation got wrong (it read garbage
     * and so silently reported every cloud file as local).
     */
    internal fun stFlagsOrNull(path: String): Int? {
        val handle = stat ?: return null
        return runCatching {
            Arena.ofConfined().use { arena ->
                val pathSeg = arena.allocateFrom(path)
                val statBuf = arena.allocate(STAT_BUF_SIZE)
                val rc = handle.invokeExact(pathSeg, statBuf) as Int
                if (rc != 0) null else statBuf.get(ValueLayout.JAVA_INT, ST_FLAGS_OFFSET)
            }
        }.getOrNull()
    }
}
