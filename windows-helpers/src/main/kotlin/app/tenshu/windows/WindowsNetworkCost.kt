package app.tenshu.windows

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemoryLayout
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle

/**
 * Whether Windows considers the current connection metered, from `GetNetworkConnectivityHint`
 * (iphlpapi, Windows 10 2004+): the plain-C view of the same aggregate cost the WinRT
 * `NetworkInformation` connection profile reports, without a WinRT activation from the JVM.
 *
 * Read on every call — the call is a cheap local query and the answer changes the moment the user
 * joins a hotspot or flips *Metered connection* in Settings.
 *
 * ⚠⚠ **This object must load on a JVM without `java.lang.foreign`**: every FFM type lives in
 * [NetworkCostBindings], loaded only inside a `runCatching`, exactly as [WindowsDpapi] does.
 */
object WindowsNetworkCost {

    enum class Cost { UNMETERED, METERED, UNKNOWN }

    private val isWindows = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)

    private val bindings: NetworkCostBindings? by lazy {
        if (!isWindows) null else runCatching { NetworkCostBindings.loadOrNull() }.getOrNull()
    }

    /** Whether the hint is callable here: Windows 10 2004+, a 64-bit JVM with FFM, the symbol resolved. */
    fun isAvailable(): Boolean = bindings != null

    /** The current connection's cost; [Cost.UNKNOWN] when unavailable, failed, or Windows cannot tell. */
    fun current(): Cost = hint()?.let(NetworkCostDecoding::decode) ?: Cost.UNKNOWN

    /** The raw `NL_NETWORK_CONNECTIVITY_HINT`; `null` when unavailable or the call failed. */
    internal fun hint(): ConnectivityHint? = bindings?.let { b -> runCatching { b.hint() }.getOrNull() }

    /** `NL_NETWORK_CONNECTIVITY_HINT`'s five members, enums as their raw values. */
    data class ConnectivityHint(
        val level: Int,
        val cost: Int,
        val approachingDataLimit: Boolean,
        val overDataLimit: Boolean,
        val roaming: Boolean,
    )
}

/**
 * The pure half of [WindowsNetworkCost]: a raw hint in, a cost out. No FFM, tested on every host.
 * Enum values are `nldef.h`'s `NL_NETWORK_CONNECTIVITY_COST_HINT`.
 */
object NetworkCostDecoding {

    const val COST_UNKNOWN = 0
    const val COST_UNRESTRICTED = 1
    const val COST_FIXED = 2
    const val COST_VARIABLE = 3

    /**
     * Any sign of a charge wins: a data limit approached or exceeded, roaming, or a `Fixed` (capped)
     * or `Variable` (per-byte) cost is [WindowsNetworkCost.Cost.METERED] — these flags can be set on
     * a connection whose cost is still `Unrestricted`, and the user set that limit for a reason.
     * Otherwise `Unrestricted` is unmetered and anything else (`Unknown`, a value newer than this
     * table) is unknown.
     *
     * ⚠ The connectivity *level* is deliberately not read: whether we are online is
     * `RelayReachability`'s question, not this one's.
     */
    fun decode(hint: WindowsNetworkCost.ConnectivityHint): WindowsNetworkCost.Cost = when {
        hint.overDataLimit || hint.approachingDataLimit || hint.roaming -> WindowsNetworkCost.Cost.METERED
        hint.cost == COST_FIXED || hint.cost == COST_VARIABLE -> WindowsNetworkCost.Cost.METERED
        hint.cost == COST_UNRESTRICTED -> WindowsNetworkCost.Cost.UNMETERED
        else -> WindowsNetworkCost.Cost.UNKNOWN
    }
}

/** Every `java.lang.foreign` reference of [WindowsNetworkCost]. Loaded only once FFM is known to be wanted. */
private class NetworkCostBindings private constructor(private val getHint: MethodHandle) {

    fun hint(): WindowsNetworkCost.ConnectivityHint? = Arena.ofConfined().use { arena ->
        val hint = arena.allocate(NL_NETWORK_CONNECTIVITY_HINT)
        // NO_ERROR (0) on success, a Win32 error code otherwise.
        if (getHint.invokeExact(hint) as Int != 0) return@use null
        fun int(name: String) = hint.get(ValueLayout.JAVA_INT, offset(name))
        fun bool(name: String) = hint.get(ValueLayout.JAVA_BYTE, offset(name)).toInt() != 0
        WindowsNetworkCost.ConnectivityHint(
            level = int("ConnectivityLevel"),
            cost = int("ConnectivityCost"),
            approachingDataLimit = bool("ApproachingDataLimit"),
            overDataLimit = bool("OverDataLimit"),
            roaming = bool("Roaming"),
        )
    }

    companion object {
        /**
         * `NL_NETWORK_CONNECTIVITY_HINT { NL_NETWORK_CONNECTIVITY_LEVEL_HINT ConnectivityLevel;
         * NL_NETWORK_CONNECTIVITY_COST_HINT ConnectivityCost; BOOLEAN ApproachingDataLimit;
         * BOOLEAN OverDataLimit; BOOLEAN Roaming; }` — two C enums (`int`, 4 bytes on MSVC), three
         * `BOOLEAN`s (`unsigned char`), then one byte of tail padding to the struct's 4-byte
         * alignment: 12 bytes.
         */
        private val NL_NETWORK_CONNECTIVITY_HINT = MemoryLayout.structLayout(
            ValueLayout.JAVA_INT.withName("ConnectivityLevel"),
            ValueLayout.JAVA_INT.withName("ConnectivityCost"),
            ValueLayout.JAVA_BYTE.withName("ApproachingDataLimit"),
            ValueLayout.JAVA_BYTE.withName("OverDataLimit"),
            ValueLayout.JAVA_BYTE.withName("Roaming"),
            MemoryLayout.paddingLayout(1),
        )

        private fun offset(name: String) =
            NL_NETWORK_CONNECTIVITY_HINT.byteOffset(MemoryLayout.PathElement.groupElement(name))

        fun loadOrNull(): NetworkCostBindings? {
            if (ValueLayout.ADDRESS.byteSize() != 8L) return null
            // ⚠ Absent before Windows 10 2004: the lookup fails and the cost is UNKNOWN, never a guess.
            val symbol = SymbolLookup.libraryLookup("iphlpapi.dll", Arena.global())
                .find("GetNetworkConnectivityHint").orElse(null) ?: return null
            return NetworkCostBindings(
                Linker.nativeLinker().downcallHandle(
                    symbol,
                    // `NETIOAPI_API GetNetworkConnectivityHint(NL_NETWORK_CONNECTIVITY_HINT*)`
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS),
                ),
            )
        }
    }
}
