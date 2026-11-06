package org.tenshu.core.common.app

import org.tenshu.core.TResult
import org.tenshu.core.TenshuException
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.TenshuClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.tenshu.core.common.impl.UnannouncedSessionErrorDto
import org.tenshu.core.common.schema.SessionAdmission
import org.tenshu.core.common.transport.RemoteBinaryTaskExecutor
import org.tenshu.core.common.schema.currentVersionRequirement
import org.tenshu.core.common.schema.currentVersionVector
import org.tenshu.core.common.util.bestEffort
import org.tenshu.core.core.SchemaProvider
import org.tenshu.core.core.TContext
import org.tenshu.core.common.request.TaskExecutor

/**
 * Client-side schema announce (`core-schema-evolution.md` §6): assembles this
 * runtime's `VersionVector` from its own registered module graph and runs
 * `AnnounceSession` against the proxy. App code never calls this — it is
 * installed as the [RemoteBinaryTaskExecutor.sessionPreflight] (serialized
 * transports, where the reject-if-silent gate lives) or invoked once at
 * construction for in-process runtimes (§6.6: route-1 announces too — one
 * code path, and the frozen envelope gets exercised constantly).
 *
 * Every announce prints the [SCHEMA_ANNOUNCE_BEACON] line (`println` reaches
 * logcat via `System.out` on Android), which the smoke gate asserts — the
 * sibling of the `TenshuSchemaLockstep` beacon.
 */
class SchemaAnnouncer(
    private val appId: String,
    private val tContext: TContext,
    private val schemaProvider: SchemaProvider,
    private val clientProvider: () -> TenshuClient,
) {

    /** The last [SessionAdmission] received, refused ones included. */
    var lastAdmission: SessionAdmission? = null
        private set

    private val _skew = MutableStateFlow<SchemaSkew?>(null)

    /**
     * The current schema-skew verdict, or `null` while the last announce was admitted
     * (`core-schema-skew-surface.md` SK-1). This is the surface's input — it exists because the
     * verdict was previously computed correctly and then discarded into an exception message.
     *
     * ⚠⚠ **`null` means "no known skew", NOT "healthy".** Before the first announce completes
     * nothing has been checked, and a surface that treats `null` as an all-clear renders a
     * confident lie on exactly the launches this feature is for (§7 Q3 — the same class of bug as
     * the divergence false positive, inverted).
     *
     * ⭐ Populated *before* [announce] throws, which is what makes the deliberately-swallowing
     * launch-time announce in [wireSchemaAnnounce] harmless: the throw is discarded, the verdict
     * is not.
     *
     * ⚠ **A transport failure leaves the last verdict standing**, because it throws before the
     * assignment. Deliberate, and a real trade: a device that was skewed and then loses the
     * channel entirely keeps saying "update Control" when "start Control" would be the better
     * advice. The alternative — clearing on any transport error — is worse, because it puts the
     * unhelpful "can't reach Control" wording back on genuinely skewed devices every time the
     * channel blips, which is the exact failure SK-1 exists to remove. Skew is sticky until an
     * announce actually succeeds.
     */
    val skew: StateFlow<SchemaSkew?> = _skew.asStateFlow()

    /**
     * Announce and return the admission. Throws a typed [TenshuException]:
     * - on a *refused* admission — [org.tenshu.core.UnannouncedSessionError]
     *   carrying the named per-service mismatches (§6.3: this is what lets a
     *   pending caller fail with "update Control" vs "this app needs
     *   updating" instead of `error/internal`);
     * - on a transport-level failure, re-throwing the failure's own error.
     */
    suspend fun announce(): SessionAdmission {
        val current = schemaProvider.currentVersionVector(tContext)
        val requires = schemaProvider.currentVersionRequirement(tContext)
        val result = clientProvider().appService.announceSession(appId, current, requires)
        val admission = when (result) {
            is TResult.Success -> result.value
                ?: throw TenshuException(
                    UnannouncedSessionErrorDto("announce returned no admission for $appId")
                )
            is TResult.Failure -> throw TenshuException(result.error)
            else -> throw TenshuException(
                UnannouncedSessionErrorDto("unexpected announce result for $appId: $result")
            )
        }
        lastAdmission = admission
        // ⚠ Set before the refusal throw below, and cleared on every admitted announce — a
        // verdict that outlived its fix would keep telling the user to update something they
        // already updated. A reconnect re-announces (§6.4), so this self-heals.
        _skew.value = admission.toSchemaSkew(appId)
        println(beaconLine(appId, admission))
        if (!admission.admitted.get()) {
            throw TenshuException(
                UnannouncedSessionErrorDto(
                    "schema session refused for $appId: ${mismatchSummary(admission)}"
                )
            )
        }
        return admission
    }

    companion object {
        /** Greppable prefix of the announce beacon (smoke gate step 4.6). */
        const val SCHEMA_ANNOUNCE_BEACON = "TenshuSchemaAnnounce"

        fun beaconLine(appId: String, admission: SessionAdmission): String {
            val verdict = if (admission.admitted.get()) "admitted=true"
            else "admitted=false ${mismatchSummary(admission)}"
            return "$SCHEMA_ANNOUNCE_BEACON: $appId $verdict"
        }

        fun mismatchSummary(admission: SessionAdmission): String =
            "updateProxy=${admission.updateProxy.get()} updateClient=${admission.updateClient.get()} " +
                "proxy=${admission.proxyAppId.get().ifEmpty { "?" }}@" +
                "${admission.proxyAppVersion.get().ifEmpty { "?" }} " +
                admission.mismatches.toList().joinToString(prefix = "[", postfix = "]") {
                    "${it.serviceId.get()} clientCurrent=${it.clientCurrent.get()} " +
                        "clientRequires=${it.clientRequires.get()} " +
                        "proxyFloor=${it.proxyFloor.get()} proxyCurrent=${it.proxyCurrent.get()}"
                }
    }
}

/**
 * Install the announce as the connection-lifecycle preflight
 * (`core-schema-evolution.md` §6.4). For a [RemoteBinaryTaskExecutor]
 * (Binder / UDS / EPC — the gated serialized transports) the announce runs
 * lazily before the session's first call and re-runs after every reconnect,
 * ahead of the resubscription replays. For in-process executors there is no
 * serialized boundary and no gate; callers there invoke
 * [SchemaAnnouncer.announce] once from their own scope instead (§6.6).
 */
fun wireSchemaAnnounce(
    client: TenshuClient,
    executor: TaskExecutor,
    appId: String,
    tContext: TContext,
    schemaProvider: SchemaProvider,
    /**
     * Pass a scope to **also announce once, now**, rather than waiting for a first call.
     *
     * ⚠⚠ Wiring alone does not announce — it installs [RemoteBinaryTaskExecutor.sessionPreflight],
     * which rides the session's *first call*. An app whose opening screen issues no operation
     * therefore never opens a session, and Control's `appRegistrationTrigger` has nothing to fire
     * on: it stays **unregistered and invisible in Control** while looking healthy from outside,
     * because the transport is connected. Mia shipped exactly that (`docs/tech-debt.md`,
     * 2026-08-14) and was invisible until its first message.
     *
     * ⚠ Fire-and-forget and swallowing, deliberately: [SchemaAnnouncer.announce] throws when the
     * proxy is unreachable or refuses admission, and neither is a reason to fail a launch. The
     * preflight still covers the first real call, so a failed launch announce costs only lateness.
     *
     * ⭐ The swallow discards the *throw*, never the *verdict*: [SchemaAnnouncer.skew] is
     * populated ahead of it. That is what lets a skewed device say so **at launch** rather than
     * on whichever screen happens to issue the first operation — which is how this presented
     * before SK-1 (`core-schema-skew-surface.md` §1.1).
     */
    launchScope: CoroutineScope? = null,
): SchemaAnnouncer {
    val announcer = SchemaAnnouncer(appId, tContext, schemaProvider) { client }
    (executor as? RemoteBinaryTaskExecutor)?.sessionPreflight = { announcer.announce() }
    launchScope?.launch { bestEffort("SchemaAnnouncer -> the announce at launch") { announcer.announce() } }
    return announcer
}
