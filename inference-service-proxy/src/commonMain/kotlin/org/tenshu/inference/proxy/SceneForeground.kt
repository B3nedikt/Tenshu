package org.tenshu.inference.proxy

import kotlinx.atomicfu.atomic

/**
 * Whether the app's scene is in the foreground, and **which** foreground this is
 * (`ios-on-device-inference.md` §14.17): what gates a compile, and what the preflight counts a
 * slow probe in (D12: three slow probes on three *separate* foregrounds).
 *
 * ⚠⚠ **One value, swapped whole.** As two fields written one after the other (active, then the
 * number), a reader on another thread could take *active* from after the change and the number
 * from before it, and a slow probe would be counted in the foreground that had just ended, with a
 * second one to come in the foreground that had just begun. A reader takes one snapshot and reads
 * both from it.
 *
 * ⚠ Memory, and the process's: a new process starts in no foreground, and its first is a new one.
 */
class SceneForeground {
    private class Scene(val active: Boolean, val number: Long)

    private val scene = atomic(Scene(active = false, number = 0))

    /** The scene phase, as the app reports it. Told twice that it is active, it is the same foreground. */
    fun set(active: Boolean) {
        while (true) {
            val was: Scene = scene.value
            val now = Scene(active, if (active && !was.active) was.number + 1 else was.number)
            if (scene.compareAndSet(was, now)) return
        }
    }

    fun active(): Boolean = scene.value.active

    /** Which foreground this is, or `null` while the scene is not in one. */
    fun number(): Long? = scene.value.let { if (it.active) it.number else null }
}
