package app.tenshu.calendar.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import app.tenshu.calendar.android.widget.CalendarWidget
import app.tenshu.calendar.ui.App
import app.tenshu.calendar.ui.interop.IcsOpenRelay
import app.tenshu.calendar.ui.interop.icsUri
import app.tenshu.calendar.ui.interop.readOpenedIcs
import app.tenshu.notify.AlertIntents
import org.koin.android.ext.android.getKoin
import org.tenshu.core.common.app.OpenTargetInbox
import org.tenshu.core.common.app.OpenTargetIntents
import kotlinx.coroutines.launch
import app.tenshu.ui.theme.TenshuTheme

// Calendar's Android entry point (calendar-app.md CA-2b). ComponentActivity
// provides ViewModelStore / SavedState / Lifecycle owners for free, so the shell is just
// TenshuTheme { App() }.
class MainActivity : ComponentActivity() {

    /**
     * The notification payload that brought us here (CA-5f).
     *
     * ⚠⚠ Held in state and refreshed from [onNewIntent], not read once in
     * [onCreate]. Calendar's launcher activity is `singleTask` by default only in
     * the sense that Android reuses the existing instance — so a second reminder
     * tapped while the app is open arrives through `onNewIntent` and would
     * otherwise be silently dropped.
     */
    private var tapPayload by mutableStateOf<String?>(null)

    /**
     * `.ics` files Android handed us — *Open with* on a file, or *Share* from a mail
     * client (CA-6d).
     *
     * ⚠⚠ A relay rather than a second `mutableStateOf`, because the launch intent is
     * read here *before* `setContent` has run and there is nothing collecting yet.
     * `IcsOpenRelay` replays one emission for exactly that; a state field would work
     * too, but the desktop shell has the same problem with no composition to hold
     * state in, and one answer beats two.
     */
    private val icsOpens = IcsOpenRelay()

    /**
     * Where a cross-app open goes (`control-cross-app-open.md` TT-1c).
     *
     * ⚠⚠ Resolved from Koin rather than constructed here: the *same* inbox is what the transport
     * pushes a warm request into, so a second instance would make the running-app path deliver to
     * something nobody collects — and that path is the silent one, since a cold start visibly
     * launches the app either way.
     */
    private val openTargets: OpenTargetInbox by lazy { getKoin().get() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        tapPayload = intent?.getStringExtra(AlertIntents.EXTRA_TAP_PAYLOAD)
        // ⚠⚠ **Only on a genuine launch.** A rotation recreates this activity and
        // hands it the *same* launch intent, so an unguarded read would re-deliver a
        // `.ics` the user has already imported and put the chooser back up over
        // whatever they were doing — possibly importing the same file a second time,
        // into a second calendar. `savedInstanceState` is the only thing here that
        // distinguishes the two, since `setIntent` does not survive recreation.
        if (savedInstanceState == null) intent?.let(::readIcs)
        // ⚠ Guarded like `readIcs` and for the same reason: a rotation re-delivers the launch
        // intent, and re-opening the editor over whatever the user had moved on to is the same
        // class of bug as re-importing a `.ics`.
        if (savedInstanceState == null) intent?.let(::readOpenTarget)
        setContent {
            TenshuTheme {
                App(
                    tapPayload = tapPayload,
                    openedIcsFiles = icsOpens.files,
                    openTargets = this@MainActivity.openTargets.targets,
                )
            }
        }
    }

    /**
     * ⚠ `lifecycleScope`, so a read still in flight when the user leaves is cancelled
     * rather than delivering into a dead composition.
     *
     * ⚠⚠ No-ops for an ordinary launch — [icsUri] returns `null` for the
     * `ACTION_MAIN` intent the launcher sends, which is what stops the import sheet
     * appearing every time the app is opened.
     */
    private fun readIcs(intent: Intent) {
        val uri = intent.icsUri() ?: return
        lifecycleScope.launch { icsOpens.deliver(contentResolver.readOpenedIcs(uri)) }
    }

    /**
     * ⚠⚠ **The widget's other refresh trigger, and the reason it exists.** The OS
     * ticks `updatePeriodMillis` at best every 30 minutes and not at all while the
     * device sleeps, so a widget that only waited for that would show a stale
     * agenda right after the user had edited it. Leaving the app is the moment
     * their calendar is most likely to have changed.
     *
     * ⚠ `onStop`, not `onPause`: a transient pause (a permission dialog, the
     * notification shade) is not the user leaving, and redrawing on every one of
     * those would cross IPC for nothing.
     */
    override fun onStop() {
        super.onStop()
        lifecycleScope.launch { CalendarWidget.refresh(applicationContext) }
    }

    /**
     * The cold-start half of a cross-app open (CO-D3): Control could not deliver over IPC because
     * this app was not running, so the target rode the launch itself.
     *
     * ⚠ No-ops for an ordinary launch — the extra is simply absent.
     */
    private fun readOpenTarget(intent: Intent) {
        OpenTargetIntents.targetFrom(intent) { getKoin().get() }?.let(openTargets::deliver)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // ⚠⚠ Load-bearing, and easy to miss: Control only mints a launch ticket when the app is
        // NOT connected — but a process can be alive and unconnected (Control's own `BIND_AUTO_
        // CREATE` rebind restarts it without a transport), so a launch can perfectly well reuse a
        // live activity. Reading only in `onCreate` would make that case a silent no-op.
        readOpenTarget(intent)
        tapPayload = intent.getStringExtra(AlertIntents.EXTRA_TAP_PAYLOAD)
        // ⚠ The same reason `tapPayload` is refreshed here: a `.ics` opened while
        // Calendar is already running reuses this instance and never reaches
        // `onCreate`, so reading only there would make the intent filter work exactly
        // once per app launch.
        readIcs(intent)
    }
}
