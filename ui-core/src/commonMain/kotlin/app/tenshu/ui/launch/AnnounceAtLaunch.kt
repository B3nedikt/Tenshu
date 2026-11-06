package app.tenshu.ui.launch

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Open the app's session with Control **at launch**, rather than whenever a screen first happens to
 * need it (`docs/agent.md` §7; enforced by `scripts/check-launch-registration.py`).
 *
 * ⚠⚠ Registration rides the announce, and the announce rides the client. An app whose opening screen
 * issues no operation therefore never announces, never registers, and is **invisible in Control**
 * while its transport sits there connected — Mia shipped exactly that (`docs/tech-debt.md`,
 * 2026-08-14). The seven other first-party apps were only ever saved by their first screen happening
 * to load something, which is an accident rather than a property.
 *
 * ⚠ **Off the main thread, and that is the whole reason this exists rather than
 * `createdAtStart = true` on the client binding.** Resolving the client runs
 * `RemoteBinaryTaskExecutor.bind()` → `UnixSocketTaskTransport.bind()`, which opens the socket
 * **synchronously**; doing that during `startKoin` would trade an app that is invisible in Control
 * for one that is slow to start. Here it lands on `Dispatchers.Default` after first composition.
 *
 * ⚠ Swallowing, deliberately: a proxy that is not running must not fail a launch. The session
 * preflight still covers the first real call, so the cost of failing here is lateness, never more.
 *
 * @param resolveClient resolves the app's `TenshuClient` from its own Koin graph — passed as a
 *   lambda so `:ui-core` needs no dependency on `:core-common` or Koin for one call.
 */
@Composable
fun AnnounceAtLaunch(resolveClient: () -> Unit) {
    LaunchedEffect(Unit) {
        withContext(Dispatchers.Default) {
            runCatching { resolveClient() }
        }
    }
}
