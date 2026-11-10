package app.tenshu.windows

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemoryLayout
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle

/**
 * Windows DPAPI (`CryptProtectData` / `CryptUnprotectData`, `crypt32.dll`) over FFM, **user scope**:
 * a blob protected here can be unprotected only by the same Windows user on the same machine
 * (the key derives from the user's logon credential) — the Windows baseline the threat model
 * requires for A15 (`docs/proposals/desktop-windows-port.md` §3.1).
 *
 * ⚠⚠ Every answer is **nullable, and null means "no"** — not Windows, no FFM, a symbol that did
 * not resolve, a 32-bit JVM, a call that failed (wrong user, wrong entropy, a tampered blob). The
 * caller decides what that means; for the master-key store it means the in-memory fallback, never
 * a guessed key.
 *
 * ⚠⚠ **This object must load on a JVM without `java.lang.foreign`.** The shared modules' tests run
 * on JDK 17, and the Control DI graph constructs the key store on every Windows host — so every
 * FFM type lives in [Bindings], which is loaded only inside a `runCatching`. A layout built in this
 * object's initialiser failed class-init with `NoClassDefFoundError` and took down every
 * integration test that starts Control.
 *
 * ⚠ `entropy` is *additional* input both calls must agree on. It is in our binary, so it does not
 * keep out a same-user process that reads it — DPAPI user scope never did (threat model T-C-17). It
 * keeps our blob from being unprotected by code that merely calls DPAPI on files it finds.
 *
 * ⚠ `CRYPTPROTECT_UI_FORBIDDEN` is always set: a background daemon must fail, not raise a prompt.
 */
object WindowsDpapi {

    private val isWindows = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)

    private val bindings: Bindings? by lazy {
        if (!isWindows) null else runCatching { Bindings.loadOrNull() }.getOrNull()
    }

    /** Whether DPAPI is callable here: Windows, a 64-bit JVM with FFM, and every symbol resolved. */
    fun isAvailable(): Boolean = bindings != null

    /** Protects [plaintext] for this Windows user; `null` when unavailable or the call failed. */
    fun protect(plaintext: ByteArray, entropy: ByteArray): ByteArray? =
        bindings?.let { b -> runCatching { b.protect(plaintext, entropy) }.getOrNull() }

    /** Recovers what [protect] sealed; `null` when unavailable, or for a blob this user/entropy cannot open. */
    fun unprotect(blob: ByteArray, entropy: ByteArray): ByteArray? =
        bindings?.let { b -> runCatching { b.unprotect(blob, entropy) }.getOrNull() }
}

/** Every `java.lang.foreign` reference of [WindowsDpapi]. Loaded only once FFM is known to be wanted. */
private class Bindings private constructor(
    private val protectHandle: MethodHandle,
    private val unprotectHandle: MethodHandle,
    private val localFreeHandle: MethodHandle,
) {
    fun protect(plaintext: ByteArray, entropy: ByteArray): ByteArray? = call(protectHandle, plaintext, entropy)

    fun unprotect(blob: ByteArray, entropy: ByteArray): ByteArray? = call(unprotectHandle, blob, entropy)

    private fun call(crypt: MethodHandle, input: ByteArray, entropy: ByteArray): ByteArray? =
        Arena.ofConfined().use { arena ->
            val (inBlob, inData) = blob(arena, input)
            val (entropyBlob, entropyData) = blob(arena, entropy)
            val out = arena.allocate(DATA_BLOB)
            try {
                val ok = crypt.invokeExact(
                    inBlob,
                    MemorySegment.NULL,
                    entropyBlob,
                    MemorySegment.NULL,
                    MemorySegment.NULL,
                    CRYPTPROTECT_UI_FORBIDDEN,
                    out,
                ) as Int
                if (ok == 0) return@use null
                val size = out.get(ValueLayout.JAVA_INT, 0L)
                val data = out.get(ValueLayout.ADDRESS, PB_DATA_OFFSET).reinterpret(size.toLong())
                try {
                    data.toArray(ValueLayout.JAVA_BYTE)
                } finally {
                    // The output of an unprotect is the plaintext key: wipe it before it goes
                    // back to the heap.
                    data.fill(0)
                    localFreeHandle.invokeExact(data) as MemorySegment
                }
            } finally {
                inData.fill(0)
                entropyData.fill(0)
            }
        }

    /** A `DATA_BLOB` pointing at a native copy of [bytes]; the copy is returned so it can be wiped. */
    private fun blob(arena: Arena, bytes: ByteArray): Pair<MemorySegment, MemorySegment> {
        // ⚠ Never a zero-length allocation handed to Win32: an empty blob is cbData = 0, any pointer.
        val data = arena.allocate(maxOf(bytes.size, 1).toLong())
        MemorySegment.copy(MemorySegment.ofArray(bytes), 0L, data, 0L, bytes.size.toLong())
        val blob = arena.allocate(DATA_BLOB)
        blob.set(ValueLayout.JAVA_INT, 0L, bytes.size)
        blob.set(ValueLayout.ADDRESS, PB_DATA_OFFSET, data)
        return blob to data
    }

    companion object {
        private const val CRYPTPROTECT_UI_FORBIDDEN = 0x1

        /**
         * `DATA_BLOB { DWORD cbData; BYTE *pbData; }`. ⚠ The 4 bytes of padding are the x64 layout; a
         * 32-bit JVM has none, so [loadOrNull] refuses there rather than mis-lay the struct.
         */
        private val DATA_BLOB = MemoryLayout.structLayout(
            ValueLayout.JAVA_INT.withName("cbData"),
            MemoryLayout.paddingLayout(4),
            ValueLayout.ADDRESS.withName("pbData"),
        )
        private val PB_DATA_OFFSET = DATA_BLOB.byteOffset(MemoryLayout.PathElement.groupElement("pbData"))

        /** Both `CryptProtectData` and `CryptUnprotectData` take seven arguments of the same shape. */
        private val CRYPT_DESCRIPTOR = FunctionDescriptor.of(
            ValueLayout.JAVA_INT, // BOOL
            ValueLayout.ADDRESS, // DATA_BLOB* pDataIn
            ValueLayout.ADDRESS, // LPCWSTR szDataDescr / LPWSTR* ppszDataDescr — always NULL
            ValueLayout.ADDRESS, // DATA_BLOB* pOptionalEntropy
            ValueLayout.ADDRESS, // PVOID pvReserved — NULL
            ValueLayout.ADDRESS, // CRYPTPROTECT_PROMPTSTRUCT* — NULL
            ValueLayout.JAVA_INT, // DWORD dwFlags
            ValueLayout.ADDRESS, // DATA_BLOB* pDataOut
        )

        fun loadOrNull(): Bindings? {
            if (ValueLayout.ADDRESS.byteSize() != 8L) return null
            val linker = Linker.nativeLinker()
            fun find(library: String, symbol: String) =
                SymbolLookup.libraryLookup(library, Arena.global()).find(symbol).orElse(null)
            val protect = find("crypt32.dll", "CryptProtectData") ?: return null
            val unprotect = find("crypt32.dll", "CryptUnprotectData") ?: return null
            // `HLOCAL LocalFree(HLOCAL)` — DPAPI allocates its output with `LocalAlloc`.
            val localFree = find("kernel32.dll", "LocalFree") ?: return null
            return Bindings(
                linker.downcallHandle(protect, CRYPT_DESCRIPTOR),
                linker.downcallHandle(unprotect, CRYPT_DESCRIPTOR),
                linker.downcallHandle(localFree, FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS)),
            )
        }
    }
}
