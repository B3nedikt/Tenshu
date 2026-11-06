package org.tenshu.commerce

import org.tenshu.commerce.store.InMemoryCommerceStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Store-review sign-in config (`commerce-accounts-and-sessions.md` D13, threat model T-CB-12): off
 * unless both variables are set, refused at start-up when partial or malformed, one code per account
 * when on. The routes are pinned by `ReviewSignInRoutingTest`.
 */
class CommerceConfigReviewSignInTest {

    private val key = "k".repeat(ReviewSignIn.MIN_KEY_LENGTH)
    private val storeA = "store-a-review-k3p9x@example.com"
    private val storeB = "store-b-review-m2q7z@example.com"
    private val digestA = ReviewSignIn.hmacHex(key, "482916")
    private val digestB = ReviewSignIn.hmacHex(key, "731054")
    private val store = InMemoryCommerceStore()

    private fun config(vararg pairs: Pair<String, String>): CommerceConfig {
        val map = pairs.toMap()
        return CommerceConfig.fromEnv { name -> map[name] }
    }

    private fun accounts(vararg entries: String) = "REVIEW_SIGNIN_ACCOUNTS" to entries.joinToString(",")

    private fun both(accounts: Pair<String, String> = accounts("$storeA=$digestA", "$storeB=$digestB")) =
        arrayOf(accounts, "REVIEW_SIGNIN_HMAC_KEY" to key)

    private fun refusal(vararg pairs: Pair<String, String>): String =
        assertFailsWith<InvalidCommerceConfigException> { config(*pairs).validate() }.message.orEmpty()

    private fun assertNoSecretIn(message: String) {
        for (secret in listOf(key, digestA, digestB, storeA, storeB, "482916")) {
            assertFalse(secret in message, "a refusal must never print a secret or an address: $message")
        }
    }

    @Test
    fun unconfiguredIsOff() {
        val config = config()
        config.validate()
        assertNull(config.reviewSignIn(store), "no REVIEW_SIGNIN_* => no review sign-in")
        val blank = config("REVIEW_SIGNIN_ACCOUNTS" to " , ", "REVIEW_SIGNIN_HMAC_KEY" to " ")
        blank.validate()
        assertNull(blank.reviewSignIn(store))
    }

    @Test
    fun oneWithoutTheOtherRefusesToStart() {
        val noKey = refusal(accounts("$storeA=$digestA"))
        assertTrue("REVIEW_SIGNIN_HMAC_KEY" in noKey)
        assertNoSecretIn(noKey)
        val noAccounts = refusal("REVIEW_SIGNIN_HMAC_KEY" to key)
        assertTrue("REVIEW_SIGNIN_ACCOUNTS" in noAccounts)
        assertNoSecretIn(noAccounts)
    }

    @Test
    fun aShortKeyRefusesToStart() {
        val short = "k".repeat(ReviewSignIn.MIN_KEY_LENGTH - 1)
        val message = refusal(accounts("$storeA=$digestA"), "REVIEW_SIGNIN_HMAC_KEY" to short)
        assertFalse(short in message)
    }

    @Test
    fun theCodeInPlaceOfItsDigestRefusesToStart() {
        // The likeliest operator slip: pasting the code itself. It must not boot — and must not echo it.
        assertNoSecretIn(refusal(*both(accounts("$storeA=482916"))))
    }

    @Test
    fun anEntryWithoutADigestRefusesToStart() {
        assertNoSecretIn(refusal(*both(accounts(storeA))))
        assertNoSecretIn(refusal(*both(accounts("$storeA="))))
        assertNoSecretIn(refusal(*both(accounts("=$digestA"))))
    }

    @Test
    fun anythingButAnExactAddressRefusesToStart() {
        for (address in listOf("example.com", "@example.com", "Store <$storeA>", "a@b@example.com", "x:y@example.com")) {
            assertFailsWith<InvalidCommerceConfigException>("should refuse `$address`") {
                config(*both(accounts("$storeA=$digestA", "$address=$digestB"))).validate()
            }
        }
    }

    @Test
    fun twoAccountsMayShareNeitherAnAddressNorACode() {
        assertNoSecretIn(refusal(*both(accounts("$storeA=$digestA", "$storeA=$digestB"))))
        val sameCode = refusal(*both(accounts("$storeA=$digestA", "$storeB=$digestA")))
        assertTrue("same code" in sameCode)
        assertNoSecretIn(sameCode)
    }

    @Test
    fun bothSetIsAnExactPerAccountAllowlist() {
        val config = config(*both(accounts(" ${storeA.uppercase()} = ${digestA.uppercase()} ", "$storeB=$digestB")))
        config.validate()
        val review = assertNotNull(config.reviewSignIn(store))
        assertEquals(2, review.accountCount)
        assertTrue(review.covers(storeA))
        assertTrue(review.covers(storeB))
        for (lookalike in listOf("store-a-review-k3p9x+x@example.com", "$storeA.evil", "review@example.com", "store-a-review@example.com")) {
            assertFalse(review.covers(lookalike), "no domain or prefix match: $lookalike")
        }
        assertEquals(ReviewSignIn.Outcome.Accepted, review.check(storeA, "482916", 0L))
        assertEquals(ReviewSignIn.Outcome.Accepted, review.check(storeB, "731054", 0L))
    }

    @Test
    fun oneStoresCodeNeverOpensTheOtherStoresAccount() {
        val review = ReviewSignIn(mapOf(storeA to digestA, storeB to digestB), key, store)
        assertEquals(ReviewSignIn.Outcome.Refused, review.check(storeB, "482916", 0L))
        assertEquals(ReviewSignIn.Outcome.Refused, review.check(storeA, "731054", 0L))
    }

    @Test
    fun theDigestIsKeyedSoThePlainSha256OfTheCodeIsNotIt() {
        // ⚠⚠ The point of the key: an unkeyed SHA-256 of six digits is reversed by trying 10^6.
        assertNotEquals(sha256Hex("482916"), digestA)
        val unkeyed = ReviewSignIn(mapOf(storeA to sha256Hex("482916")), key, store)
        assertEquals(ReviewSignIn.Outcome.Refused, unkeyed.check(storeA, "482916", 0L))
        val otherKey = ReviewSignIn(mapOf(storeA to digestA), "x".repeat(ReviewSignIn.MIN_KEY_LENGTH), InMemoryCommerceStore())
        assertEquals(ReviewSignIn.Outcome.Refused, otherKey.check(storeA, "482916", 0L))
    }

    @Test
    fun theFixedCodeIsRefusedForAnUnlistedAddressEvenInsideTheClass() {
        // The routes never route an unlisted address to `check`; this is the second wall.
        val review = ReviewSignIn(mapOf(storeA to digestA), key, store)
        assertEquals(ReviewSignIn.Outcome.Refused, review.check("alice@example.com", "482916", 0L))
    }

    @Test
    fun theFailedCodeCapIsConfigurable() {
        assertEquals(CommerceConfig.DEFAULT_OTP_FAILED_PER_EMAIL_PER_DAY, config().otpFailedPerEmailPerDay)
        assertEquals(20, CommerceConfig.DEFAULT_OTP_FAILED_PER_EMAIL_PER_DAY)
        assertEquals(7, config("OTP_FAILED_PER_EMAIL_PER_DAY" to "7").otpFailedPerEmailPerDay)
    }
}
