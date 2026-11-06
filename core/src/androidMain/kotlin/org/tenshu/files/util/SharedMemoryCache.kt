package org.tenshu.files.util

import android.os.SharedMemory
import org.tenshu.core.core.TComponent
import org.tenshu.core.core.TContext

/**
 * Per-process cache of [SharedMemory] handles arriving across the
 * Control ⇄ Drive Binder boundary. Mirror of [ParcelFileDescriptorCache]
 * for in-memory (small byte payloads) rather than file-backed data —
 * the receiver-side AIDL stub stores the SharedMemory keyed by an
 * opaque uniqueId; `SharedMemoryByteArrayDto` reads it out lazily.
 *
 * Like [ParcelFileDescriptorCache] the map is encapsulated behind
 * [put]/[get]/[remove] so an overwrite/eviction closes the previous
 * handle. `SharedMemory.close()` is documented to free the underlying
 * ashmem region — dropping the reference without closing relies on the
 * Closeable's GC finalizer + `CloseGuard`, which logs the same
 * `"A resource failed to call close"` warning as PFDs.
 */
class SharedMemoryCache : TComponent {
    private val cache: MutableMap<String, SharedMemory> = mutableMapOf()

    fun get(key: String): SharedMemory? = cache[key]

    fun put(key: String, value: SharedMemory) {
        cache.put(key, value)?.close()
    }

    fun remove(key: String) {
        cache.remove(key)?.close()
    }

    fun clear() {
        cache.values.forEach { it.close() }
        cache.clear()
    }

    companion object {
        const val ID = "tenshu/files/sharedMemoryCache"
    }
}

fun TContext.sharedMemoryCache(): SharedMemoryCache? =
    components[SharedMemoryCache.ID] as? SharedMemoryCache
