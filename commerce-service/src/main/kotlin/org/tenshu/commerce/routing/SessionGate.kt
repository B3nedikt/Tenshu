package org.tenshu.commerce.routing

import org.tenshu.commerce.SessionTokens
import org.tenshu.commerce.store.AccountStatus
import org.tenshu.commerce.store.CommerceStore

/**
 * The mint authority for `/mint` + `/token` (`commerce-accounts-and-sessions.md` §4.3): a request
 * is authorised iff it carries a **valid commerce session** whose account holds a **current
 * entitlement** with a **live owner**. Replaces the Phase-1 email whitelist as the request gate.
 */
sealed interface SessionGateResult {
    /** Mint for [ownerId] (the account's live owner) at [productId] (its current entitlement). */
    data class Authorized(val ownerId: String, val productId: String) : SessionGateResult

    /** No/expired session → 401. */
    data object NoSession : SessionGateResult

    /** Valid session but no current entitlement / no live owner → 403. */
    data object NoEntitlement : SessionGateResult

    /**
     * Valid session on a **closed** account → 403.
     *
     * ⚠⚠ Without this the two halves of retention P3 contradicted each other: the closure sweep
     * tells the relay to delete this owner's content, while `/mint` and `/token` kept issuing
     * credentials for it — for the ~10-year window the Free ledger row carries
     * (`FREE_ENTITLEMENT_WINDOW_MS`), because `currentEntitlement` filters on validity and nothing
     * looked at [AccountStatus] at all.
     */
    data object AccountClosed : SessionGateResult
}

fun CommerceStore.gateBySession(sessionToken: String, nowMs: Long): SessionGateResult {
    if (sessionToken.isBlank()) return SessionGateResult.NoSession
    val session = getSession(SessionTokens.hash(sessionToken))
        ?: return SessionGateResult.NoSession
    if (!session.isValid(nowMs)) {
        deleteSession(session.tokenHash)
        return SessionGateResult.NoSession
    }
    // Closure is checked BEFORE the entitlement: a closed account may well still hold a
    // long-window ledger row, and that row is exactly what used to keep it minting.
    if (findAccountById(session.accountId)?.status == AccountStatus.Closed) {
        return SessionGateResult.AccountClosed
    }
    val entitlement = currentEntitlement(session.accountId, nowMs)
        ?: return SessionGateResult.NoEntitlement
    val ownerId = liveOwner(session.accountId)
        ?: return SessionGateResult.NoEntitlement
    return SessionGateResult.Authorized(ownerId = ownerId, productId = entitlement.productId)
}
