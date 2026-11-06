package app.tenshu.maps.android

import org.tenshu.core.common.app.OpenTargetIntents
import org.tenshu.core.common.app.OpenTargetInbox
import org.koin.android.ext.android.inject
import org.koin.android.ext.android.getKoin
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.tenshu.maps.ui.App
import app.tenshu.ui.theme.TenshuTheme

// Maps Android entry point (docs/proposals/maps-app.md §6 Ph0). ComponentActivity provides
// ViewModelStore / SavedState / Lifecycle owners for free, so the shell is just
// TenshuTheme { App(openTargets = this@MainActivity.openTargets.targets) }.
class MainActivity : ComponentActivity() {

    /**
     * Where a cross-app open goes (`control-cross-app-open.md` TT-2b).
     *
     * ⚠⚠ Injected, not constructed: the SAME inbox is what the transport pushes a warm request
     * into, so a second instance would make that path deliver to something nobody collects.
     */
    private val openTargets: OpenTargetInbox by inject()

    /** ⚠ No-ops for an ordinary launch — the extra is simply absent. */
    private fun readOpenTarget(intent: Intent) {
        OpenTargetIntents.targetFrom(intent) { getKoin().get() }?.let(openTargets::deliver)
    }

    /**
     * ⚠⚠ Control mints a launch ticket whenever the handler must be brought forward — **including
     * when it is already running** (§5e.1) — so the common case is a launch that RESUMES this
     * activity and never reaches `onCreate`. Reading only there would make a warm open silent.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readOpenTarget(intent)
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // ⚠ Guarded on a genuine launch: a rotation re-delivers the same intent.
        if (savedInstanceState == null) intent?.let(::readOpenTarget)
        setContent {
            TenshuTheme {
                App()
            }
        }
    }
}
