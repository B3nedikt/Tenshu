package org.tenshu.core

import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/**
 * Marks the Layer-2 calls issued inside it as **started by the user** — the one kind of work
 * Android lets Control hold its `dataSync` foreground service for
 * (`docs/proposals/control-background-work-off-fgs.md` §5.1).
 *
 * Read where `withTask`'s [WithTaskElement] is read, and carried on the cancellable envelope; on
 * Android the proxy then counts the task towards its foreground-service promotion. Everything not
 * marked is automatic: Control serves it exactly the same, but under its own WorkManager jobs
 * rather than a foreground service.
 *
 * ⚠⚠ Mark only work a person just asked for — a picker result, a *Back up now* tap. Play reviews
 * the foreground service against that, and marking automatic work (a scheduled backup, a sync) is
 * precisely the use it rejects. When unsure, leave it unmarked: unmarked work still runs, and runs
 * the same while the app is open.
 *
 * ⚠ A claim, not a permission: the OS still refuses the promotion unless the calling app is in
 * the foreground when it starts, which is what a genuine user action looks like.
 */
object UserInitiatedElement : CoroutineContext.Element, CoroutineContext.Key<UserInitiatedElement> {
    override val key: CoroutineContext.Key<*> get() = this
}

/** Runs [block] with its Layer-2 calls marked user-started ([UserInitiatedElement]). */
suspend fun <T> userInitiatedWork(block: suspend () -> T): T =
    withContext(UserInitiatedElement) { block() }
