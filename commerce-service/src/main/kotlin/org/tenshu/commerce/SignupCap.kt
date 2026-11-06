package org.tenshu.commerce

import org.slf4j.LoggerFactory
import org.tenshu.commerce.store.CommerceStore

/**
 * The operator's cap on new accounts (`SIGNUP_LIMIT_PER_DAY`, `SIGNUP_LIMIT_TOTAL`): a cost brake.
 * Every verified Free account can hold 5 GiB on the relay, and the verified email is the only
 * sybil gate, so without a cap the number of accounts — and the storage bill — has no upper limit.
 *
 * Counts **verified, open** accounts only ([CommerceStore.countVerifiedOpenAccounts]): those are
 * the ones that hold storage, and counting code requests instead would let anyone close sign-ups
 * by asking for codes to addresses they do not own.
 *
 * ⚠ Checked at `/register`, so a code already mailed before the cap was reached can still be
 * verified. The overshoot is bounded by the codes in flight (OTP TTL × the send budgets).
 */
class SignupCap(
    /** Verified accounts per rolling 24 h; `<= 0` = no daily cap. */
    private val perDay: Int,
    /** Verified open accounts in total; `<= 0` = no total cap. */
    private val total: Int,
) {
    val enabled: Boolean get() = perDay > 0 || total > 0

    /** True while either cap is reached: new addresses are told sign-ups are paused. */
    fun isReached(store: CommerceStore, nowMs: Long): Boolean {
        val dayFull = perDay > 0 && store.countVerifiedOpenAccounts(nowMs - DAY_MS) >= perDay
        val totalFull = !dayFull && total > 0 && store.countVerifiedOpenAccounts(0L) >= total
        return dayFull || totalFull
    }

    companion object {
        private const val DAY_MS = 24L * 60L * 60L * 1_000L
        private val log = LoggerFactory.getLogger(SignupCap::class.java)

        /** No cap: what tests and the in-process module run with unless they pass one. */
        fun off(): SignupCap = SignupCap(perDay = 0, total = 0)

        /** Log the configured cap once at start-up, so the box's posture is visible in its log. */
        fun logConfigured(cap: SignupCap, perDay: Int, total: Int) {
            if (cap.enabled) log.info("Signup cap: {} per day, {} in total (<= 0 = none)", perDay, total)
        }
    }
}
