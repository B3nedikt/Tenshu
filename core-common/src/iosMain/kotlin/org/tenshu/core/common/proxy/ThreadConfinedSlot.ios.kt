package org.tenshu.core.common.proxy

import kotlin.native.concurrent.ThreadLocal

/**
 * Kotlin/Native has thread-local storage for top-level properties only, so each thread keeps one
 * map from slot to value. Slots are keyed by identity (the class defines no `equals`), and
 * `set(null)` removes the entry — see the `expect` declaration.
 */
@ThreadLocal
private val valuesOfThisThread = HashMap<ThreadConfinedSlot<*>, Any>()

internal actual class ThreadConfinedSlot<T : Any> actual constructor() {

    @Suppress("UNCHECKED_CAST")
    actual fun get(): T? = valuesOfThisThread[this] as T?

    actual fun set(value: T?) {
        if (value == null) valuesOfThisThread.remove(this) else valuesOfThisThread[this] = value
    }
}
