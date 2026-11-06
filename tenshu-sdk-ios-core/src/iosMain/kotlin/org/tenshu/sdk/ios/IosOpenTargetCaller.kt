package org.tenshu.sdk.ios

import org.tenshu.core.common.TenshuClient
import org.tenshu.core.common.app.ControlTargetOpener
import org.tenshu.core.common.app.LaunchPerformer
import org.tenshu.core.common.app.OpenTargetResult
import org.tenshu.core.common.app.TargetOpener
import org.tenshu.core.entity.DataId

/**
 * The Swift half of performing an open (`control-cross-app-open.md` §5i).
 *
 * ⚠⚠ **The launch is Swift's, because only Swift can do it.** On iOS an open is
 * `UIApplication.shared.open(url)` — there is no Kotlin/Native equivalent, and under route 1 there
 * is no Control process to hand it to either. So the platform half is installed from the app,
 * exactly as `DriveLocalRuntime` takes its background-upload bridge.
 *
 * ⚠ A plain `(String) -> Boolean` rather than implementing [LaunchPerformer] from Swift: that
 * interface's member is `suspend`, and a Swift-authored suspend implementation is a poor thing to
 * ask of the bridge when the work here is one non-suspending UIKit call.
 *
 * ⚠ Absent means an open reports `LAUNCH_FAILED` honestly rather than doing nothing — the same
 * default every other platform's missing launcher takes.
 */
object IosOpenTargetCaller {

    private var open: ((String) -> Boolean)? = null

    /** Install the app's `UIApplication.shared.open`. Call once at launch. */
    fun installLauncher(open: (String) -> Boolean) {
        this.open = open
    }

    internal fun performer(): LaunchPerformer = LaunchPerformer { url ->
        open?.invoke(url) ?: false
    }

    /**
     * Ask Control to open [target], and perform whatever it hands back.
     *
     * ⭐ **On iOS the "proxy" is this app's own in-process one** (route 1), so the resolution, the
     * installed-here check and the five typed refusals all happen locally — and what comes back is
     * a URL this app then opens. The caller still names only a `DataId`, which is CO-D1 surviving
     * as a convention where it cannot survive as an enforced property (§5i.2).
     */
    // Called from the Timeline module; hidden so Swift's surface is unchanged (`ios-per-app-frameworks.md` §5.3).
    @OptIn(kotlin.experimental.ExperimentalObjCRefinement::class)
    @kotlin.native.HiddenFromObjC
    suspend fun open(client: TenshuClient, target: DataId): OpenTargetResult =
        opener(client).open(target)

    private fun opener(client: TenshuClient): TargetOpener =
        ControlTargetOpener(client = client, performLaunch = performer())
}
