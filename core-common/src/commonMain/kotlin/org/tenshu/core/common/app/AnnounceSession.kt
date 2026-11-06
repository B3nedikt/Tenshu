package org.tenshu.core.common.app

import org.tenshu.core.Operation
import org.tenshu.core.TResult
import org.tenshu.core.app.AppService
import org.tenshu.core.asTaskInteractor
import org.tenshu.core.common.schema.SessionAdmission
import org.tenshu.core.common.schema.VersionVector
import org.tenshu.core.core.findOperation
import org.tenshu.core.request.RequestObject
import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.operation
import org.tenshu.core.schema.string
import org.tenshu.core.schema.toType
import org.tenshu.core.trait.IoOperation
import org.tenshu.core.trait.TerminalOperation

/**
 * Announce the client's schema versions at IPC session start
 * (`core-schema-evolution.md` §6): its [current] vector — what it was built at, which the proxy
 * holds against its wire floors — and its [requires] vector — the lowest proxy it can work against,
 * which the proxy holds against its own current (`core-version-compatibility.md` §4.3/§4.7). The
 * proxy replies **symmetrically** with a [SessionAdmission] — its own vector + floors, the verdict,
 * the named mismatches and its host binary — so a refusal is a *successful typed reply*, never a
 * bare error: the payload is what lets the client render "update Control" vs "this app needs
 * updating" (§6.3).
 *
 * App code never calls this; the client CONNECTION lifecycle does — at
 * transport connect and again from the resubscription `onReconnected` hook
 * (§6.4: a session is per transport (re)connect, not per client process — a
 * Control update kills the binding), before any subscription callstack is
 * replayed. Composes with [org.tenshu.core.app.RegisterApp] (same receiver,
 * same lifecycle moment) rather than duplicating app identity.
 *
 * Declared `IoOperation + TerminalOperation` — a deliberate deviation from
 * the §6.8 sketch's "not an IoOperation": in this codebase the trait gates
 * the CLIENT execution path (`asTaskInteractor` refuses non-Io ops; non-Io
 * means client-side pure-evaluable), not whether the impl touches a store.
 * The implementation remains pure per-connection session bookkeeping — no
 * store or network touch — and stays cheap enough to run on every
 * reconnect. The request/reply composites are **frozen** (§6.2): this shape is the one-time
 * pre-Play reshape (2026-10-28, Appendix A.4), and from here a changed contract is a new op id.
 *
 * ⚠ Deliberately **no operation-id set** in either direction (`tech-debt.md` *"Removing an
 * operation has no compatibility window"*, option 1). `core-version-compatibility.md` §4.5/§4.6
 * settled that a first-party pair agrees on what each version contains, so the version vectors
 * already say everything a vocabulary exchange would (O6), and a removed op raises the wire floor
 * (D6). Exchanging tag sets is O5, deferred to the first un-forceable client, whose own envelope
 * is the place for it.
 */
suspend fun AppService.announceSession(
    appId: String,
    current: VersionVector,
    requires: VersionVector,
): TResult<SessionAdmission> =
    findOperation<AnnounceSession>(AnnounceSession.ID)
        .announceSession(appId, current, requires)
        .asTaskInteractor<SessionAdmission>()
        .execute()

interface AnnounceSession : Operation {

    override val schema: OperationSchema
        get() = Schema

    fun announceSession(
        appId: String,
        current: VersionVector,
        requires: VersionVector,
    ): RequestObject

    companion object {
        const val ID = "tenshu/app/service/announceSession"

        val Schema = operation(
            id = ID,
            receiver = AppService.Schema.toType(),
            params = {
                string(id = Params.APP_ID)
                composite(id = Params.CURRENT, type = VersionVector.Schema)
                composite(id = Params.REQUIRES, type = VersionVector.Schema)
            },
            traits = listOf(IoOperation.Schema, TerminalOperation.Schema),
            returnType = SessionAdmission.Schema.toType(),
        )
    }

    object Params {
        const val APP_ID = "$ID/appId"
        const val CURRENT = "$ID/current"
        const val REQUIRES = "$ID/requires"
    }
}
