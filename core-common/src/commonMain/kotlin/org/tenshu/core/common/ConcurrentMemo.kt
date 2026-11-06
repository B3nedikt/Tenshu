package org.tenshu.core.common

import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * `getOrPut` that any thread may call: an [AtomicReference] over an immutable map, the shape
 * `ObserverRegistry` uses. A lookup never sees a map being changed, and every caller of one key gets
 * the SAME value.
 *
 * ⚠ [create] can run more than once for a key when two threads miss together — one result wins and
 * the other is dropped — so it must be free of side effects. For a handful of long-lived entries
 * (the services a client talks to); every insert copies the map.
 */
@OptIn(ExperimentalAtomicApi::class)
internal class ConcurrentMemo<K, V : Any> {
    private val entries = AtomicReference<Map<K, V>>(emptyMap())

    fun getOrPut(key: K, create: () -> V): V {
        entries.load()[key]?.let { return it }
        val made = create()
        while (true) {
            val current = entries.load()
            current[key]?.let { return it }
            if (entries.compareAndSet(current, current + (key to made))) return made
        }
    }
}
