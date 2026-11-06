package org.tenshu.mia.proxy

import org.tenshu.core.common.proxy.executor.OperationImplementation
import org.tenshu.mia.AskMia
import org.tenshu.mia.ConfirmMiaAction
import org.tenshu.mia.plan.MiaActionOffer
import org.tenshu.mia.proxy.impl.AskMiaImplementation
import org.tenshu.mia.proxy.impl.ConfirmMiaActionImplementation

/**
 * Mia's proxy-side operations (`mia-query-layer.md` MQ-1).
 *
 * ⚠ There is deliberately no `initMiaService()` counterpart: Mia declares no relations
 * and observes nothing, so there is no maintainer to install. It reads what other
 * services already hold and stores nothing of its own.
 *
 * ⚠ [actions] must be ONE instance per proxy — it is the state that spans the two calls of a
 * confirmed action (MQ-D7), so a fresh registry per operation would make every tap find an
 * empty table.
 *
 * ⚠⚠ [actionOffer] is **required, and each platform's proxy module states it**: an iPhone's
 * route-1 runtime passes [MiaActionOffer.WITHHELD] because it can carry out none of the three
 * actions honestly — a routine never runs there at all (`routines-engine.md` RE-D13). It is
 * enforced where the offer is made: a withheld action is never planned, so nothing is resolved
 * and no token is minted for a hidden button to sit over (`mia-query-layer.md` §19).
 *
 * ⚠⚠ [photoSearch] is **required** for the same reason: which app Mia's photo refusals name
 * (`ios-on-device-inference.md` §1.8). iOS passes [MiaPhotoSearchHome.PHOTOS_APP].
 *
 * @param nowEpochMs proxy wall clock — the pending-confirmation TTL.
 * @param newId fresh ids for confirmation tokens and `DeviceCommand.commandId`s. ⚠ The
 *   command id is what the hub's consumed journal keys off, so it must be unique per tap.
 */
fun miaServiceProxyImplementationFactory(
    id: String,
    actions: MiaActionRegistry,
    actionOffer: MiaActionOffer,
    photoSearch: MiaPhotoSearchHome,
    newId: () -> String,
): OperationImplementation? =
    when (id) {
        AskMia.ID -> AskMiaImplementation(actions = actions, actionOffer = actionOffer, photoSearch = photoSearch, newToken = newId)
        ConfirmMiaAction.ID -> ConfirmMiaActionImplementation(actions = actions, newCommandId = newId)
        else -> null
    }

/** Builds the per-proxy registry the factory needs. */
fun miaActionRegistry(nowEpochMs: () -> Long): MiaActionRegistry = MiaActionRegistry(nowEpochMs)
