package org.tenshu.commerce

import org.tenshu.core.common.security.BouncyCastleEd25519Backend
import org.tenshu.identity.EntitlementSignature
import org.tenshu.commerce.store.AccountStatus
import org.tenshu.commerce.store.InMemoryCommerceStore
import org.tenshu.commerce.store.Rail
import org.tenshu.core.common.security.decodeAccountClosureToken
import kotlin.test.Test
import org.tenshu.core.common.security.EntitlementTokenFailure
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Account closure + the records it mints (`retention-quota-model.md` Face A / D4).
 *
 * ⚠ A record minted here eventually authorises **permanent deletion** of a person's files, so the
 * cases that matter most are the ones where a record must *not* be produced.
 */
class AccountClosureMinterTest {

    private val day = 24L * 60 * 60 * 1000

    private val ed = BouncyCastleEd25519Backend()
    private val config = CommerceConfig.fromEnv { null } // defaults: dev seed, tenshu.commerce, 72 h

    /** The relay's view: it holds only the issuer's public half. */
    private fun verify(message: ByteArray, signature: ByteArray): Boolean =
        ed.verify(EntitlementSignature.COMMERCE_PUBLIC_KEY, message, signature)

    private fun fixture(): Pair<InMemoryCommerceStore, AccountClosureMinter> {
        val store = InMemoryCommerceStore()
        return store to AccountClosureMinter(config, IssuerKeypair(config.issuerSeed, ed), store)
    }

    @Test
    fun `closing an account is idempotent and keeps the original time`() {
        val (store, _) = fixture()
        val account = store.createAccount("a@example.com", Rail.Free, createdAt = 0)

        store.closeAccount(account.id, closedAt = 1_000)
        store.closeAccount(account.id, closedAt = 9_000)

        // ⚠ Re-closing must NOT restart the grace clock — the relay measures its retention window
        // from this stamp, so a repeated call would quietly extend retention past the terms.
        val reloaded = assertNotNull(store.findAccountById(account.id))
        assertEquals(AccountStatus.Closed, reloaded.status)
        assertEquals(1_000, reloaded.closedAt)
    }

    @Test
    fun `an active account mints nothing`() {
        val (store, minter) = fixture()
        val account = store.createAccount("a@example.com", Rail.Free, createdAt = 0)
        store.bindOwner(account.id, "owner-1", boundAt = 0)

        assertTrue(minter.mintAll(nowMs = day).isEmpty(), "only a closure authorises a reclaim")
    }

    @Test
    fun `a closed account with no live owner mints nothing`() {
        val (store, minter) = fixture()
        val account = store.createAccount("a@example.com", Rail.Free, createdAt = 0)
        store.closeAccount(account.id, closedAt = 1_000)

        // ⚠ No owner binding ⇒ no keyspace to reclaim. Skipping is the only safe answer; guessing
        // one would delete a stranger's content.
        assertTrue(minter.mintAll(nowMs = day).isEmpty())
    }

    @Test
    fun `a closed account with an owner mints a verifiable record carrying the closure time`() {
        val (store, minter) = fixture()
        val account = store.createAccount("a@example.com", Rail.Free, createdAt = 0)
        store.bindOwner(account.id, "owner-1", boundAt = 0)
        store.closeAccount(account.id, closedAt = 5_000)

        val minted = minter.mintAll(nowMs = 10_000)

        assertEquals(1, minted.size)
        assertEquals("owner-1", minted.single().ownerId)
        val claims = decodeAccountClosureToken(
            token = minted.single().token,
            currentTimeSec = 10,
            verifier = { _, message, signature -> verify(message, signature) },
        )
        assertEquals("owner-1", claims.ownerId)
        // ⚠ The RECORDED closure time, not "now" — re-minting the same closure on every sweep must
        // not push the relay's grace window forward.
        assertEquals(5, claims.closedAtSec)
        assertTrue(claims.expiresAtSec > claims.issuedAtSec, "short-lived: reopening = stop minting")
    }

    @Test
    fun `re-minting the same closure keeps reporting the same closure time`() {
        val (store, minter) = fixture()
        val account = store.createAccount("a@example.com", Rail.Free, createdAt = 0)
        store.bindOwner(account.id, "owner-1", boundAt = 0)
        store.closeAccount(account.id, closedAt = 5_000)

        val closedAtSecs = listOf(10_000L, 40 * day).map { now ->
            decodeAccountClosureToken(
                token = minter.mintAll(nowMs = now).single().token,
                currentTimeSec = now / 1000,
                verifier = { _, message, signature -> verify(message, signature) },
            ).closedAtSec
        }

        assertEquals(listOf(5L, 5L), closedAtSecs, "the grace clock is anchored, not rolling")
    }

    @Test
    fun `the record follows a re-bound owner`() {
        val (store, minter) = fixture()
        val account = store.createAccount("a@example.com", Rail.Free, createdAt = 0)
        store.bindOwner(account.id, "owner-old", boundAt = 0)
        store.bindOwner(account.id, "owner-new", boundAt = 100)
        store.closeAccount(account.id, closedAt = 1_000)

        // ⚠ Only the LIVE binding. Reclaiming `owner-old` would delete a keyspace the account no
        // longer owns — and after a recovery re-bind that keyspace may belong to nobody, or later
        // to someone else.
        assertEquals(listOf("owner-new"), minter.mintAll(nowMs = day).map { it.ownerId })
    }

    @Test
    fun `a record verifies when commerce's clock runs ahead of the relay's by less than the backdate`() {
        val (store, minter) = fixture()
        val account = store.createAccount("a@example.com", Rail.Free, createdAt = 0)
        store.bindOwner(account.id, "owner-1", boundAt = 0)
        store.closeAccount(account.id, closedAt = 5_000)
        val relayNowSec = 40 * day / 1000

        fun verifyWithCommerceAhead(aheadSec: Long) = decodeAccountClosureToken(
            token = minter.mintAll(nowMs = (relayNowSec + aheadSec) * 1000).single().token,
            currentTimeSec = relayNowSec,
            verifier = { _, message, signature -> verify(message, signature) },
        )

        // ⚠⚠ The relay applies no `iat` leeway, and re-posting cannot heal a PERSISTENT skew: without
        // the backdate a commerce box running fast fails every record of every sweep (it has run
        // 1.7 s fast in production). Both sides of the boundary are pinned so the margin is real.
        val backdate = AccountClosureMinter.IAT_BACKDATE_SEC
        for (ahead in listOf(0L, 2L, backdate - 1, backdate)) {
            assertEquals("owner-1", verifyWithCommerceAhead(ahead).ownerId, "commerce ${ahead}s ahead")
        }
        assertFailsWith<EntitlementTokenFailure.NotYetValid> { verifyWithCommerceAhead(backdate + 1) }
    }

    @Test
    fun `the backdate leaves the record's lifetime and closure time untouched`() {
        val (store, minter) = fixture()
        val account = store.createAccount("a@example.com", Rail.Free, createdAt = 0)
        store.bindOwner(account.id, "owner-1", boundAt = 0)
        store.closeAccount(account.id, closedAt = 5_000)
        val nowSec = 40 * day / 1000

        val claims = decodeAccountClosureToken(
            token = minter.mintAll(nowMs = nowSec * 1000).single().token,
            currentTimeSec = nowSec,
            verifier = { _, message, signature -> verify(message, signature) },
        )

        assertEquals(nowSec - AccountClosureMinter.IAT_BACKDATE_SEC, claims.issuedAtSec)
        assertEquals(config.tokenTtlHours * 3_600, claims.expiresAtSec - claims.issuedAtSec)
        // Grace runs from `closed`, so backdating `iat` cannot bring a deletion forward.
        assertEquals(5, claims.closedAtSec)
    }
}
