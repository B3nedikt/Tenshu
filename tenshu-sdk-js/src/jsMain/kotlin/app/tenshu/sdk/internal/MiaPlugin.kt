package app.tenshu.sdk.internal

import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.mia.AskMia
import org.tenshu.mia.ConfirmMiaAction
import org.tenshu.mia.client.miaServiceClientModule
import org.tenshu.mia.common.miaServiceCommonModule
import org.tenshu.mia.plan.MiaActionOffer
import org.tenshu.mia.proxy.MiaPhotoSearchHome
import org.tenshu.mia.proxy.miaActionRegistry
import org.tenshu.mia.proxy.miaServiceProxyImplementationFactory
import kotlin.random.Random

/**
 * `:mia-service` on web (`web-app-suite.md` Wave 4, Mia run A): the **native** proxy-side query
 * layer, run in the tab — planner, lowering, validator and the reads themselves — exactly as iOS
 * route 1 runs it in Mia's own process (`MiaLocalRuntime`, `mia-query-layer.md` §19). Until this
 * run it was an F7 sync-only entry in `SyncedServicePlugins.kt`; the schema configs are the same.
 *
 * ⚠⚠ **The planner is the deterministic grammar, and nothing else** (`web-app-suite.md` §8 item 8).
 * No inference op is registered on web, so MQ-5a's `classifyIntent` dispatch finds no
 * implementation and `AskMiaImplementation` keeps the grammar's own plan — its documented "no
 * opinion" path (§16.4), the same answer an empty backend registry gives natively. An intent model,
 * when one ships, enters through that seam here as on every platform.
 *
 * ⚠⚠ **[MiaActionOffer.WITHHELD], the iOS posture (§19.2).** A tab writes nothing on Mia's behalf:
 * a device command, a routine run or a new event plans to `ActionWithheld` before any slot is read,
 * so nothing is resolved and no confirmation token exists. ⚠ Belt and braces: [ConfirmMiaAction] is
 * **not mapped** either ([EXCLUDED_OPS]), so even a token from elsewhere has nothing to redeem —
 * that op is the only path from Mia to `IssueDeviceCommand`, `SaveEvent` and `RunRoutineNow`.
 *
 * ⚠ What the reads reach, all through plugins already registered on web (F7): the file plane
 * (`FilesPlugin`: names, `photosCapturedBetween`, `photosNear`), timeline moments, workouts, places,
 * calendar's `queryOccurrences` (`CalendarPlugin`), and the synced device / label rows, whose
 * relations the one `RelationMaintainer` keeps. `photosLike` is not mapped (no inference backend in
 * a browser), so a visual-search question answers `unavailable` — "I could not look", never an
 * empty answer.
 *
 * ⚠ **No [initialize], like native**: `miaServiceProxyImplementationFactory`'s KDoc — Mia declares
 * no relations and observes nothing. Grants are read per question off the realm's preference rows.
 * `NoBackgroundWorkInATabTest` covers what asking installs (nothing).
 */
internal object MiaPlugin : JsRuntimePlugin {
    override val id: String = "mia"

    override val schemaConfigs = listOf(miaServiceCommonModule, miaServiceClientModule)

    /**
     * ONE per runtime, as the factory requires. ⚠ Under [MiaActionOffer.WITHHELD] it stays empty —
     * nothing is ever put — and it is built only because the factory's signature asks for it.
     */
    private val actions = miaActionRegistry(nowEpochMs = { JsRuntime.nowMs() })

    override fun operationFactory(ctx: RuntimeContext) =
        OperationImplementationFactory { id -> miaWebOperation(id) }

    /** The ops a tab must never run: the one write Mia has (see the KDoc). */
    internal val EXCLUDED_OPS: Set<String> = setOf(ConfirmMiaAction.ID)

    internal fun miaWebOperation(id: String) =
        if (id in EXCLUDED_OPS) {
            null
        } else {
            miaServiceProxyImplementationFactory(
                id = id,
                actions = actions,
                actionOffer = MiaActionOffer.WITHHELD,
                // ⚠ Only read by a `SemanticSearchUnavailable` refusal, which needs the `photosLike`
                // op that a tab does not have. THIS_DEVICE's sentences name no other app, which is
                // the true statement for a browser; PHOTOS_APP's send the user to iOS Photos.
                photoSearch = MiaPhotoSearchHome.THIS_DEVICE,
                newId = { "web-" + Random.nextLong().toULong().toString(16) },
            )
        }

    /** The op ids this plugin answers, for the guard that nothing else slipped in. */
    internal val MAPPED_OPS: Set<String> = setOf(AskMia.ID)
}
