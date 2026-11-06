package org.tenshu.core.common.proxy

/**
 * A slot whose value is visible only to the thread that set it — the dynamic scope a blocking,
 * non-suspending call tree needs when the object holding the slot is shared between threads.
 *
 * ⚠ Only for a scope that is entered and left on one thread without suspending in between
 * ([ReferenceResolver.collectingReferences] takes a plain `() -> T`). A coroutine that resumes on
 * another thread would find the slot empty.
 *
 * Set `null` on the way out: an entry left behind is retained for the life of its thread.
 */
internal expect class ThreadConfinedSlot<T : Any>() {
    fun get(): T?
    fun set(value: T?)
}
