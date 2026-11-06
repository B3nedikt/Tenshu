package org.tenshu.commerce.play

import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.contentType
import io.ktor.http.encodeURLPathPart

import io.ktor.http.isSuccess
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Instant
import java.util.Base64

/**
 * One Play subscription purchase, as `purchases.subscriptionsv2.get` reports it — only the fields
 * commerce acts on (`commerce-store-link-out.md` P2).
 */
data class PlaySubscription(
    /** `SUBSCRIPTION_STATE_*`. */
    val state: String,
    /** Play's subscription id (the store product), e.g. `tenshu.drive.basic`. */
    val productId: String?,
    /** End of the paid period (epoch ms). */
    val expiryMs: Long?,
    /** What the app set as `obfuscatedAccountId` when it launched the purchase: our account id. */
    val obfuscatedAccountId: String?,
    val acknowledged: Boolean,
    /**
     * The purchase this one replaced (an upgrade or plan change inside Play), or null for a new
     * subscription. Play's own guidance: a token whose `linkedPurchaseToken` is the token we hold
     * takes that token's place.
     */
    val linkedPurchaseToken: String? = null,
    /**
     * Who cancelled it, from `canceledStateContext`: `user`, `system`, `developer` or `replacement`;
     * null when it is not cancelled.
     */
    val cancelledBy: String? = null,
) {
    /** Paid and still within its period: active, in grace, or cancelled but not yet expired. */
    fun entitlesAt(nowMs: Long): Boolean =
        state in ENTITLING_STATES && (expiryMs ?: 0) > nowMs

    /**
     * Still this account's subscription for "never a second one" purposes: entitling, or on hold /
     * paused — Play may still recover or resume it and bill again, though it grants nothing now.
     */
    fun stillHeldAt(nowMs: Long): Boolean = entitlesAt(nowMs) || state in RECOVERABLE_STATES

    companion object {
        val ENTITLING_STATES = setOf(
            "SUBSCRIPTION_STATE_ACTIVE",
            "SUBSCRIPTION_STATE_IN_GRACE_PERIOD",
            // Cancelled means "won't renew"; the paid period still runs to its expiry.
            "SUBSCRIPTION_STATE_CANCELED",
        )

        /** Grants nothing now, but Play may recover (hold) or resume (pause) it and bill again. */
        val RECOVERABLE_STATES = setOf("SUBSCRIPTION_STATE_ON_HOLD", "SUBSCRIPTION_STATE_PAUSED")

        /** Nothing bills and nothing can resume. */
        val ENDED_STATES = setOf(
            "SUBSCRIPTION_STATE_EXPIRED",
            "SUBSCRIPTION_STATE_REVOKED",
            "SUBSCRIPTION_STATE_PENDING_PURCHASE_CANCELED",
        )
    }
}

/** The answer to a lookup: found, a token Play does not know, or a failed call (retry). */
sealed interface PlayLookup {
    data class Found(val subscription: PlaySubscription) : PlayLookup
    data object NotFound : PlayLookup
    data object Failed : PlayLookup
}

/**
 * The outbound Play Developer API seam, mirroring [org.tenshu.commerce.stripe.StripeClient]: an
 * interface the routes depend on, a live implementation, and fakes in tests.
 */
interface GooglePlayClient {
    suspend fun subscription(purchaseToken: String): PlayLookup

    /**
     * Acknowledge a new purchase. ⚠⚠ Play refunds a purchase that stays unacknowledged for three
     * days, which is also how a purchase commerce refuses is undone: it is simply never acknowledged.
     */
    suspend fun acknowledge(subscriptionId: String, purchaseToken: String): Boolean

    /**
     * End the subscription now and refund its unused time pro rata — the stop D6 needs when a plan
     * moves to another rail. Already revoked counts as success.
     */
    suspend fun revokeWithProratedRefund(purchaseToken: String): Boolean

    /**
     * Stop the subscription's renewals for good (`subscriptionsv2.cancel` with
     * `DEVELOPER_REQUESTED_STOP_PAYMENTS`, which the user cannot undo), refunding nothing — what
     * closing an account does to a Stripe subscription, on Play. True when nothing will renew:
     * cancelled now, already cancelled for good, expired or revoked. False ⇒ retry.
     */
    suspend fun cancelRenewals(purchaseToken: String): Boolean
}

/**
 * Live [GooglePlayClient], authenticated as the Play service account: a self-signed RS256 JWT is
 * exchanged for an OAuth access token (cached until shortly before it expires).
 */
class RealGooglePlayClient(
    private val http: HttpClient,
    serviceAccountJson: String,
    private val packageName: String,
    private val apiBase: String = DEFAULT_API_BASE,
    private val now: () -> Long = System::currentTimeMillis,
) : GooglePlayClient {

    private val json = Json { ignoreUnknownKeys = true }
    private val account = json.parseToJsonElement(serviceAccountJson).jsonObject
    private val clientEmail = account.string("client_email") ?: error("service account JSON has no client_email")
    private val tokenUri = account.string("token_uri") ?: "https://oauth2.googleapis.com/token"
    private val privateKey = run {
        val pem = account.string("private_key") ?: error("service account JSON has no private_key")
        val der = Base64.getMimeDecoder().decode(
            pem.replace("-----BEGIN PRIVATE KEY-----", "").replace("-----END PRIVATE KEY-----", ""),
        )
        KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(der))
    }
    private val tokenLock = Mutex()
    private var cachedToken: String? = null
    private var cachedUntilMs = 0L

    private val base get() = "$apiBase/androidpublisher/v3/applications/$packageName"

    override suspend fun subscription(purchaseToken: String): PlayLookup {
        val token = accessToken() ?: return PlayLookup.Failed
        val response = runCatching {
            http.get("$base/purchases/subscriptionsv2/tokens/${purchaseToken.encodeURLPathPart()}") { header("Authorization", "Bearer $token") }
        }.getOrNull() ?: return PlayLookup.Failed
        // Play answers an unknown or malformed token with 400 ("Invalid Value") or 404/410.
        if (response.status in NOT_FOUND_STATUSES) return PlayLookup.NotFound
        if (!response.status.isSuccess()) return PlayLookup.Failed
        val body = runCatching { json.parseToJsonElement(response.bodyAsText()).jsonObject }.getOrNull()
            ?: return PlayLookup.Failed
        val line = (body["lineItems"] as? JsonArray)?.firstOrNull() as? JsonObject
        return PlayLookup.Found(
            PlaySubscription(
                state = body.string("subscriptionState") ?: "SUBSCRIPTION_STATE_UNSPECIFIED",
                productId = line?.string("productId"),
                expiryMs = line?.string("expiryTime")?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() },
                obfuscatedAccountId = (body["externalAccountIdentifiers"] as? JsonObject)?.string("obfuscatedExternalAccountId"),
                acknowledged = body.string("acknowledgementState") == "ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED",
                linkedPurchaseToken = body.string("linkedPurchaseToken"),
                cancelledBy = (body["canceledStateContext"] as? JsonObject)?.keys?.firstNotNullOfOrNull {
                    CANCELLED_BY[it]
                },
            ),
        )
    }

    override suspend fun acknowledge(subscriptionId: String, purchaseToken: String): Boolean =
        postOk(
            "$base/purchases/subscriptions/${subscriptionId.encodeURLPathPart()}/tokens/" +
                "${purchaseToken.encodeURLPathPart()}:acknowledge",
            "{}",
        )

    override suspend fun revokeWithProratedRefund(purchaseToken: String): Boolean =
        postOk(
            "$base/purchases/subscriptionsv2/tokens/${purchaseToken.encodeURLPathPart()}:revoke",
            """{"revocationContext":{"proratedRefund":{}}}""",
        ) || (subscription(purchaseToken) as? PlayLookup.Found)?.subscription?.state in REVOKED_OR_EXPIRED

    /**
     * ⚠ A subscription the USER cancelled is cancelled again here: theirs can be restored in the
     * Play Store, ours (`DEVELOPER_REQUESTED_STOP_PAYMENTS`) cannot. Play's reference does not say
     * whether it accepts that over a user cancellation; if it refuses, the cancelled state is
     * accepted (nothing renews unless the user restores it, and a restore of a closed account's
     * subscription is cancelled by the RTDN route). A PENDING signup that cannot be cancelled is
     * accepted too: it is unpaid, Play cancels it if payment never arrives, and if it does arrive
     * the RTDN route cancels it — rather than failing every sweep and redelivery forever.
     */
    override suspend fun cancelRenewals(purchaseToken: String): Boolean {
        when (val lookup = subscription(purchaseToken)) {
            PlayLookup.Failed -> return false
            PlayLookup.NotFound -> return true
            is PlayLookup.Found -> {
                val sub = lookup.subscription
                if (sub.state in PlaySubscription.ENDED_STATES) return true
                if (sub.state == CANCELED && sub.cancelledBy in FINAL_CANCELLATIONS) return true
            }
        }
        if (postOk(
                "$base/purchases/subscriptionsv2/tokens/${purchaseToken.encodeURLPathPart()}:cancel",
                """{"cancellationContext":{"cancellationType":"DEVELOPER_REQUESTED_STOP_PAYMENTS"}}""",
            )
        ) {
            return true
        }
        val after = (subscription(purchaseToken) as? PlayLookup.Found)?.subscription ?: return false
        return after.state in ACCEPTED_AFTER_REFUSAL
    }

    private suspend fun postOk(url: String, body: String): Boolean {
        val token = accessToken() ?: return false
        val response = runCatching {
            http.post(url) {
                header("Authorization", "Bearer $token")
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        }.getOrNull()
        return response?.status?.isSuccess() == true
    }

    private suspend fun accessToken(): String? = tokenLock.withLock {
        val nowMs = now()
        cachedToken?.takeIf { nowMs < cachedUntilMs }?.let { return@withLock it }
        val nowS = nowMs / 1000
        val header = b64(json.encodeToString(JsonObject.serializer(), obj("alg" to "RS256", "typ" to "JWT")))
        val claims = b64(
            """{"iss":"$clientEmail","scope":"$SCOPE","aud":"$tokenUri","iat":$nowS,"exp":${nowS + 3600}}""",
        )
        val signature = Signature.getInstance("SHA256withRSA").run {
            initSign(privateKey)
            update("$header.$claims".toByteArray())
            sign()
        }
        val assertion = "$header.$claims.${Base64.getUrlEncoder().withoutPadding().encodeToString(signature)}"
        val response = runCatching {
            http.submitForm(
                url = tokenUri,
                formParameters = Parameters.build {
                    append("grant_type", "urn:ietf:params:oauth:grant-type:jwt-bearer")
                    append("assertion", assertion)
                },
            )
        }.getOrNull() ?: return@withLock null
        if (!response.status.isSuccess()) return@withLock null
        val body = runCatching { json.parseToJsonElement(response.bodyAsText()).jsonObject }.getOrNull()
            ?: return@withLock null
        val token = body.string("access_token") ?: return@withLock null
        val expiresIn = (body["expires_in"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: 3600
        cachedToken = token
        cachedUntilMs = nowMs + (expiresIn - 60) * 1000
        token
    }

    private fun b64(text: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(text.toByteArray())

    private fun obj(vararg pairs: Pair<String, String>) = JsonObject(pairs.associate { it.first to JsonPrimitive(it.second) })

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    companion object {
        const val DEFAULT_API_BASE = "https://androidpublisher.googleapis.com"
        const val SCOPE = "https://www.googleapis.com/auth/androidpublisher"
        private val NOT_FOUND_STATUSES = setOf(HttpStatusCode.BadRequest, HttpStatusCode.NotFound, HttpStatusCode.Gone)
        private val REVOKED_OR_EXPIRED = setOf("SUBSCRIPTION_STATE_EXPIRED", "SUBSCRIPTION_STATE_REVOKED")

        private const val CANCELED = "SUBSCRIPTION_STATE_CANCELED"

        /** Cancellations nobody but a new purchase can undo: nothing more to stop. */
        private val FINAL_CANCELLATIONS = setOf("developer", "replacement")

        /** See [cancelRenewals]: states a refused cancel is accepted in. */
        private val ACCEPTED_AFTER_REFUSAL = PlaySubscription.ENDED_STATES + CANCELED + "SUBSCRIPTION_STATE_PENDING"

        private val CANCELLED_BY = mapOf(
            "userInitiatedCancellation" to "user",
            "systemInitiatedCancellation" to "system",
            "developerInitiatedCancellation" to "developer",
            "replacementCancellation" to "replacement",
        )

    }
}
