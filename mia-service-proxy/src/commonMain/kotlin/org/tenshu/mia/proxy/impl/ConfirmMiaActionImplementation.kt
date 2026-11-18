package org.tenshu.mia.proxy.impl

import org.tenshu.core.Data
import org.tenshu.core.TString
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.AsyncOperationImplementation
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.util.runCatchingCancellable
import org.tenshu.core.get
import org.tenshu.core.toTString
import org.tenshu.mia.ConfirmMiaAction
import org.tenshu.mia.MiaAnswer
import org.tenshu.core.toTBoolean
import org.tenshu.mia.proxy.MiaActionRegistry
import org.tenshu.mia.proxy.dispatchOperation
import org.tenshu.mia.plan.MiaFrames
import org.tenshu.mia.proxy.PendingMiaAction

/**
 * The tap (`mia-query-layer.md` MQ-D7). Consumes the token, issues the command, and answers.
 *
 * ⚠⚠ **Dispatches to the shipped `IssueDeviceCommand` implementation rather than writing a
 * `DeviceCommand` row itself.** That op stamps `issuedAtEpochMs` and `issuedByDeviceId`, which
 * are what the hub's 2-minute staleness window and its consumed journal key off. Re-writing
 * the row here would duplicate those rules in a second place and let them drift — and the
 * whole point of MQ-D7's "reuse `DeviceControlSource`" is that Mia inherits the correlation
 * semantics rather than inventing a parallel set.
 *
 * ⚠ Everything it can honestly claim is that the command was **issued**. Whether the bulb
 * moved arrives later on the device's state row, correlated by `commandId` — the
 * `DeviceControlSource` contract, unchanged.
 */
internal class ConfirmMiaActionImplementation(
    private val actions: MiaActionRegistry,
    private val newCommandId: () -> String,
) : AsyncOperationImplementation {

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?,
    ): T {
        val token = (params?.get(ConfirmMiaAction.Params.TOKEN) as? TString)?.get().orEmpty()

        // ⚠⚠ The card's alternative (CL-D6) shares its token: confirming spends both, so a tap on
        // "20:00 instead?" after the row exists cannot write a second one.
        actions.takeAlternative(token)
        val action = actions.take(token)
            ?: return answer(
                executionContext,
                // ⚠ Says the shape of the problem rather than "failed". An expired or
                // already-tapped confirmation is an ordinary thing to hit, and the useful
                // instruction is to ask again — not to retry the tap.
                "That's no longer waiting to be confirmed — ask me again if you still want it.",
            ) as T

        return dispatch(executionContext, implementationFactory, action) as T
    }

    /**
     * ⭐⭐ **One lowering for every command** (`mia-query-layer.md` §25.3): the action's frame names
     * its op and every param, so "confirmed command → op call" is the same mechanical step for an
     * event, a device and a routine. A new write intent costs a `MiaFrames` entry, not a branch here.
     *
     * ⚠⚠ Fails closed. A frame param that is neither minted nor resolved is a planner bug, and the
     * answer is the action's refusal, never a call with the param left to the op's own default:
     * the user agreed to the values on the card and nothing else.
     *
     * ⚠ `dispatchOperation` hides whether an op is sync (`IssueDeviceCommand`) or async (`SaveEvent`)
     * from here, which is why the call goes through it rather than a direct implementation call.
     */
    private suspend fun dispatch(
        executionContext: ExecutionContext,
        implementationFactory: OperationImplementationFactory,
        action: PendingMiaAction,
    ): MiaAnswer {
        val context = executionContext.context
        val frame = MiaFrames.of(action.op)
        val unresolved = frame.params.filter { it.fill != MiaFrames.Fill.MINTED && it.id !in action.values }
        if (unresolved.isNotEmpty()) {
            println("Mia: ${action.op} confirmed with unresolved params ${unresolved.map { it.id }}")
            return answer(executionContext, action.failed)
        }
        val params = frame.params.associate { param ->
            val value: Any = if (param.fill == MiaFrames.Fill.MINTED) newCommandId() else action.values.getValue(param.id)
            param.id to when (value) {
                is Boolean -> value.toTBoolean(context)
                else -> value.toString().toTString(context)
            }
        }
        val dispatched = runCatchingCancellable {
            dispatchOperation(
                operationId = action.op,
                executionContext = executionContext,
                implementationFactory = implementationFactory,
                params = params,
            )
        }.getOrDefault(false)
        return answer(executionContext, if (dispatched) action.done else action.failed)
    }

    private fun answer(executionContext: ExecutionContext, text: String): MiaAnswer =
        MiaAnswer.create(
            context = executionContext.context,
            // ⚠ `answered` with no hits, not `confirm`: the tap is spent either way, so a
            // surface that re-rendered a confirmation button would offer to do it twice.
            verdict = MiaAnswer.VERDICT_ANSWERED,
            explanation = text,
        )
}
