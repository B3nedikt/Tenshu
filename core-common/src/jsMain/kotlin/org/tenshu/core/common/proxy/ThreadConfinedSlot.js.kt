package org.tenshu.core.common.proxy

/** One thread, so the slot is a plain field. */
internal actual class ThreadConfinedSlot<T : Any> actual constructor() {
    private var value: T? = null

    actual fun get(): T? = value

    actual fun set(value: T?) {
        this.value = value
    }
}
