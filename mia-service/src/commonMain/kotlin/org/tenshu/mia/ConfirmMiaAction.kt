package org.tenshu.mia

import org.tenshu.core.Operation
import org.tenshu.core.TResult
import org.tenshu.core.asTaskInteractor
import org.tenshu.core.core.findOperation
import org.tenshu.core.request.RequestObject
import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.operation
import org.tenshu.core.schema.string
import org.tenshu.core.schema.toType
import org.tenshu.core.trait.IoOperation
import org.tenshu.core.trait.TerminalOperation

/**
 * Runs the action a [MiaAction] was holding — the *only* way Mia mutates anything
 * (`mia-query-layer.md` MQ-D7).
 *
 * ⚠⚠ **Deliberately a second call, not a flag on `askMia`.** MQ-D10's op allowlist makes
 * query chains read-only, and that is enforced rather than inherent: the same callstack shape
 * carries every write in the platform. Splitting the turn is what keeps "Mia looked something
 * up" and "Mia changed something" on different paths, so no amount of grammar drift can make
 * a question actuate a light.
 *
 * ⚠⚠ **[token] is a capability over one already-resolved action, and nothing else.** It cannot
 * name a device, a verb or a value — the proxy resolved those before showing them — so this
 * op cannot command an arbitrary device even though it is an ordinary op anyone can call. It
 * is **single-use** and it **expires**: a confirmation left on screen must not fire an hour
 * later, and re-tapping must not actuate twice.
 *
 * ⚠ The reply is a [MiaAnswer] like any other so the chat surface needs no second shape: it
 * carries the acknowledgement, or an honest refusal when the token is unknown or stale. ⚠ And
 * "issued" is all it can honestly claim — whether the bulb moved is the device's state row's
 * answer, never this one's (the `DeviceControlSource` contract, inherited unchanged).
 */
suspend fun MiaService.confirmMiaAction(token: String): TResult<MiaAnswer> =
    findOperation<ConfirmMiaAction>(ConfirmMiaAction.ID)
        .confirmMiaAction(token)
        .asTaskInteractor<MiaAnswer>()
        .execute()

interface ConfirmMiaAction : Operation {

    override val schema: OperationSchema
        get() = Schema

    fun confirmMiaAction(token: String): RequestObject

    companion object {
        const val ID = "tenshu/mia/service/confirmMiaAction"

        val Schema = operation(
            id = ID,
            receiver = MiaService.Schema.toType(),
            params = {
                string(id = Params.TOKEN)
            },
            returnType = MiaAnswer.Schema.toType(),
            traits = listOf(IoOperation.Schema, TerminalOperation.Schema),
        )
    }

    object Params {
        const val TOKEN = "$ID/token"
    }
}
