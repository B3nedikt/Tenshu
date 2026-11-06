package org.tenshu.commerce

import org.tenshu.commerce.store.ClosedAccount
import org.tenshu.commerce.store.CommerceStore
import org.tenshu.core.common.security.AccountClosureClaims
import org.tenshu.core.common.security.encodeAccountClosureToken

/**
 * Mints the **account-closure records** the relay verifies before reclaiming an owner's content
 * (`retention-quota-model.md` Face A / D4) — the third thing signed by the A29 issuer key,
 * alongside [EntitlementMinter] (the synced record) and [EntitlementTokenMinter] (the bearer token).
 *
 * ⚠⚠ **A record minted here authorises permanent deletion of a person's files.** The mint authority
 * is a closed account row and nothing else: no lapse, no expiry, no failed payment. Those are all
 * frequent and benign; only a closure means "this person is done".
 *
 * ⚠ **Short-lived and re-minted on every sweep, exactly like the entitlement token.** The TTL is
 * not a property of the closure (a closure is durable) — it is what makes *reopening* safe: an
 * account that reopens stops appearing in [CommerceStore.closedAccountsWithOwners], nothing is
 * re-minted, and the last record simply expires. Without that, a leaked record would be a permanent
 * delete-this-owner capability that could fire against a live account.
 *
 * ⚠ `closedAt` rides as the **recorded closure time**, never "now": the relay measures its grace
 * window from it, so re-minting the same closure must not restart that clock.
 *
 * ⚠⚠ **`iat` is backdated by [IAT_BACKDATE_SEC].** The relay refuses a record whose `iat` is ahead
 * of its own clock and applies no leeway (`decodeAccountClosureToken`), and re-posting does not heal
 * a *persistent* skew: if this box runs fast, every record it ever mints is "not yet valid" and every
 * sweep fails the same way (commerce has run 1.7 s fast before). Backdating absorbs a commerce clock
 * up to that far ahead. It widens nothing that matters: grace runs from `closed`, never from `iat`,
 * and `exp` stays `iat + TTL`, so the record's lifetime is unchanged — it just ends that much sooner.
 */
class AccountClosureMinter(
    private val config: CommerceConfig,
    private val issuerKeypair: IssuerKeypair,
    private val store: CommerceStore,
) {

    /** One closed account's owner and its freshly-signed record. */
    data class MintedClosure(val ownerId: String, val token: String)

    /**
     * Mint a record for every closed account that still has a live owner binding.
     *
     * ⚠ Accounts without a live owner are already excluded by the store — there is no keyspace to
     * name, and guessing one is the single worst mistake available here.
     */
    fun mintAll(nowMs: Long): List<MintedClosure> =
        store.closedAccountsWithOwners().map { mint(it, nowMs) }

    private fun mint(account: ClosedAccount, nowMs: Long): MintedClosure {
        val issuedAtSec = nowMs / 1000 - IAT_BACKDATE_SEC
        val claims = AccountClosureClaims(
            ownerId = account.ownerId,
            closedAtSec = account.closedAt / 1000,
            issuedAtSec = issuedAtSec,
            expiresAtSec = issuedAtSec + config.tokenTtlHours * SECONDS_PER_HOUR,
            keyId = config.signingKeyId,
        )
        return MintedClosure(
            ownerId = account.ownerId,
            token = encodeAccountClosureToken(claims) { issuerKeypair.sign(it) },
        )
    }

    internal companion object {
        private const val SECONDS_PER_HOUR = 3_600L

        /**
         * How far ahead of the relay this box's clock may run before its records stop verifying.
         * ⚠ Must stay far below the TTL (hours), or a backdated record would be born expired.
         */
        const val IAT_BACKDATE_SEC = 30L
    }
}
