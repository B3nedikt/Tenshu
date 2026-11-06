package org.tenshu.identity

import org.tenshu.core.Data
import org.tenshu.core.TByteArray
import org.tenshu.core.TString
import org.tenshu.core.entity.Entity
import org.tenshu.core.number.TInteger
import kotlin.concurrent.Volatile

/**
 * Canonical signing/verification surface for the Publisher-signed [Entitlement]
 * (storage-entitlement-sync.md slice 4a + `tenshu-publisher.md` §10.2). Shared by
 * the signer (the mock Publisher in `:control-shared`) and the verifier (the
 * sync-merge path in `:sync-service-proxy`) so both agree on the exact signed
 * bytes and the publisher-key lookup. Mirrors the relay's `MasterDelegationVerifier`
 * pattern (deterministic field concatenation; field order is protocol contract).
 *
 * **v1 dev key — insecure by design.** [DEV_SEED] / [DEV_PUBLIC_KEY] are a committed,
 * public Ed25519 keypair: anyone can mint a "valid" mock entitlement, so this is a
 * *mechanism* (so the real-Publisher swap is mechanical), not a security boundary.
 * The real Publisher's signing key lives server-side (KMS/HSM, `tenshu-publisher.md`
 * §7 step 5) and never reaches a device; release builds reject this dev key. The
 * registry ([publicKeysFor]) dispatches on `publisherId`, so the v2 multi-publisher /
 * Rei-synced key store slots in without touching the verifier.
 */
object EntitlementSignature {

    /** The mock Publisher's id; the writer stamps it and the verifier looks it up. */
    const val DEV_PUBLISHER_ID = "tenshu.dev"

    /** Committed 32-byte Ed25519 seed for the mock Publisher (insecure by design). */
    val DEV_SEED: ByteArray = ByteArray(32) { (it + 1).toByte() }

    /**
     * Public half of [DEV_SEED]. Derived once via `Ed25519Backend.derivePublicKey`
     * and pinned here so the verifier never needs the seed; guarded by
     * `EntitlementSignatureKeyTest`.
     */
    val DEV_PUBLIC_KEY: ByteArray = intArrayOf(
        121, 181, 86, 46, 143, 230, 84, 249, 64, 120, 177, 18, 232, 169, 139, 167,
        144, 31, 133, 58, 230, 149, 190, 215, 224, 227, 145, 11, 173, 4, 150, 100,
    ).map { it.toByte() }.toByteArray()

    /**
     * The commerce-plane issuer's id (`:commerce-service`, the first real server-side issuer —
     * `publishing-commerce-reconciliation.md` D4 multi-issuer verifier). Stamped onto entitlements
     * minted by the service and looked up here so on-device verify-on-merge accepts them.
     */
    const val COMMERCE_PUBLISHER_ID = "tenshu.commerce"

    /**
     * Public half of the commerce issuer's signing key (A29). v1 = the public half of the
     * committed dev seed (`CommerceConfig.DEV_ISSUER_SEED`, `ByteArray(32){ (it + 65) }`); the
     * matching **private** seed lives only in `:commerce-service`, never here (only the public
     * half is pinned so the verifier never needs the seed). Guarded by `EntitlementSignatureKeyTest`.
     * The production private seed is an ops secret supplied to the service via `ISSUER_SEED`;
     * rotation is the deferred multi-key open item.
     */
    val COMMERCE_PUBLIC_KEY: ByteArray = intArrayOf(
        173, 193, 64, 17, 248, 45, 28, 86, 217, 86, 170, 79, 157, 115, 216, 133,
        131, 97, 166, 6, 4, 133, 37, 224, 208, 140, 99, 141, 199, 93, 216, 199,
    ).map { it.toByte() }.toByteArray()

    /**
     * Public half of the commerce issuer's **production** signing key (A29) — the key
     * `:commerce-service` uses when `DEV_MODE=false` and a real `ISSUER_SEED` is configured.
     *
     * ⚠⚠ Pinning a SECOND key here, rather than replacing [COMMERCE_PUBLIC_KEY], is the whole
     * point: dev deployments still sign with the committed dev seed, so a swap would simply move
     * the outage from production to every `DEV_MODE=true` stack. Both are legitimate signers of
     * `tenshu.commerce` records, and the verifier accepts either.
     *
     * ⚠ Adding a key here still requires a client release, which is the real limitation and is
     * NOT solved by multi-key acceptance — see [publicKeysFor].
     */
    val COMMERCE_PRODUCTION_PUBLIC_KEY: ByteArray = intArrayOf(
        157, 188, 208, 141, 203, 41, 250, 218, 67, 134, 177, 151, 91, 108, 131, 30,
        208, 141, 244, 251, 192, 195, 60, 151, 215, 255, 145, 174, 252, 141, 188, 12,
    ).map { it.toByte() }.toByteArray()

    /**
     * The known-publisher-key registry: every public key currently trusted to sign for
     * [publisherId], newest-relevant first. A record verifies if **any** of them accepts it.
     *
     * ⚠⚠ **A set, not a single key, because one issuer legitimately has more than one.**
     * `tenshu.commerce` is signed by the committed dev seed on a `DEV_MODE=true` stack and by the
     * ops-held `ISSUER_SEED` in production. Resolving to exactly one key made those two mutually
     * exclusive: promoting the deployed issuer to a real seed (2026-08-07) made every entitlement
     * it minted fail verification on every client, so `SyncProcessor` dropped them and the
     * Services card fell back to "Setting up your storage" indefinitely — with a perfectly valid
     * record sitting in the store.
     *
     * ⚠ No key id is carried on the record deliberately. `Entitlement` is a synced positional
     * composite, so a `kid` field would be a reshape owing a version bump + `SchemaMigration`
     * (House Rule #9) — a real cost to save trying at most a couple of cheap Ed25519 verifies.
     * Revisit only if this list grows enough for that to matter.
     *
     * ⚠⚠ **This does not make the issuer key rotatable.** The trust anchor is still compiled in,
     * so retiring or adding a key ships as an app release, and until it lands those clients reject
     * every record signed by the new key. Multi-key buys the *overlap window* rotation needs, not
     * the distribution. Making the anchor configuration rather than code is the follow-up; it is
     * low-risk because this check is not the money boundary — the relay re-verifies every record
     * itself against its own configured key (`RelayEntitlementVerifier`, "zero-trust of commerce
     * liveness"), so a client that accepts a forgery still gets a `402` on its next write.
     * ⚠ That reasoning holds only while paid capability is relay-mediated: gate a paid feature
     * purely client-side and this becomes a revenue boundary again.
     *
     * The dispatch on `publisherId` remains the seam where a multi-publisher / Rei-synced key
     * store slots in without touching the verifier.
     */
    fun publicKeysFor(publisherId: String): List<ByteArray> = publicKeysFor(publisherId, additionalIssuer)

    /**
     * [publicKeysFor] as a pure function of the one [additional] issuer in force — the form tests
     * pin, since the build-configured issuer is absent from an ordinary test build.
     *
     * ⚠⚠ The built-in ids resolve FIRST and never consult [additional]: an additional issuer can
     * only ever be a NEW id. That is what makes `TENSHU_EXTRA_ISSUER` unable to replace or widen
     * production's anchor, whatever it is set to.
     */
    fun publicKeysFor(publisherId: String, additional: AdditionalIssuer?): List<ByteArray> = when (publisherId) {
        DEV_PUBLISHER_ID -> listOf(DEV_PUBLIC_KEY)
        COMMERCE_PUBLISHER_ID -> listOf(COMMERCE_PRODUCTION_PUBLIC_KEY, COMMERCE_PUBLIC_KEY)
        additional?.publisherId -> listOf(additional.publicKey)
        else -> emptyList()
    }

    /** One issuer trusted on top of the built-in set: its id, and the key its records verify under. */
    class AdditionalIssuer(val publisherId: String, val publicKey: ByteArray)

    /**
     * The issuer this build was compiled to trust (`TENSHU_EXTRA_ISSUER` at build time —
     * `entitlement-trust-anchor-distribution.md` D3a), or `null` for a build that names none.
     *
     * It exists for a non-production plane whose commerce signs with its OWN seed — the dev lab's
     * `tenshu.commerce.dev`. Without it no client can pass `hasStorage` there: the record is
     * unverifiable, the gate fails closed, and a first device never syncs.
     *
     * ⚠⚠ Compiled in is not the same as in force. Nothing trusts it until
     * [trustBuildConfiguredIssuer] says so — see there for why the default is off.
     */
    val buildConfiguredIssuer: AdditionalIssuer? =
        BuildConfiguredIssuer.PUBLISHER_ID.takeIf { it.isNotEmpty() }
            ?.let { AdditionalIssuer(it, BuildConfiguredIssuer.PUBLIC_KEY) }

    @Volatile
    private var additionalIssuer: AdditionalIssuer? = null

    /**
     * Puts [buildConfiguredIssuer] in force, unless this is a release build. Returns the id now
     * trusted, or `null`. Called once at proxy start-up, before the entitlement reader is wired.
     *
     * ⚠⚠ OFF until called, and never on for a release binary. The variable is read from whatever
     * shell ran the build, so a release cut in a terminal still set up for the dev lab would
     * otherwise ship trusting a test plane's issuer. Same discipline as the committed dev key
     * (`EntitlementMinterSelection`) and the runtime origin override (`ProxyOriginSelection`).
     * ⚠ The cost of that default is the failure it leaves behind: a dev build whose start-up
     * forgets this call rejects every dev-plane record as "unknown publisher", which looks exactly
     * like a build that was never given the variable. The start-up line that prints
     * [trustedPublisherIds] is what tells the two apart.
     */
    fun trustBuildConfiguredIssuer(isRelease: Boolean, explicitOnly: Boolean = false): String? {
        additionalIssuer = issuerInForce(
            configured = buildConfiguredIssuer,
            fromStandingFile = BuildConfiguredIssuer.FROM_STANDING_FILE,
            isRelease = isRelease,
            explicitOnly = explicitOnly,
        )
        return additionalIssuer?.publisherId
    }

    /**
     * The decision behind [trustBuildConfiguredIssuer], pure so it can be pinned without a build
     * that carries an issuer.
     *
     * ⚠⚠ [explicitOnly] is the web's guard. A bundle has no release variant — an image is built FOR
     * a plane — so `isRelease` cannot protect it, and `local.properties` / `gradle.properties` are
     * standing files: a `tenshu.extraIssuer` line left there after an Android dev-lab build would
     * reach a production image built later on the same machine. With [explicitOnly] an issuer
     * counts only when the invocation that produced this binary was handed it (`-P` on the command
     * line, or `TENSHU_EXTRA_ISSUER`).
     */
    fun issuerInForce(
        configured: AdditionalIssuer?,
        fromStandingFile: Boolean,
        isRelease: Boolean,
        explicitOnly: Boolean,
    ): AdditionalIssuer? = configured.takeIf { !isRelease && !(explicitOnly && fromStandingFile) }

    /** True when [buildConfiguredIssuer] was compiled in but [trustBuildConfiguredIssuer] left it out. */
    val buildConfiguredIssuerIgnored: Boolean
        get() = buildConfiguredIssuer != null && additionalIssuer == null

    /** Every publisher id a record currently verifies under — for the start-up line, not for logic. */
    fun trustedPublisherIds(): List<String> =
        listOfNotNull(DEV_PUBLISHER_ID, COMMERCE_PUBLISHER_ID, additionalIssuer?.publisherId)


    /**
     * The canonical signed message for an entitlement:
     * `ownerId ‖ publisherId ‖ productId ‖ validFrom(be64) ‖ validUntil(be64)`.
     * `publisherSignature` is excluded (it signs over this). Deterministic so the
     * signer and verifier produce identical bytes.
     */
    fun message(
        ownerId: String,
        publisherId: String,
        productId: String,
        validFrom: Long,
        validUntil: Long,
    ): ByteArray =
        ownerId.encodeToByteArray() +
            publisherId.encodeToByteArray() +
            productId.encodeToByteArray() +
            longToBigEndianBytes(validFrom) +
            longToBigEndianBytes(validUntil)

    /**
     * Reads the canonical fields + signature off an incoming entitlement — whether it
     * arrived as a typed [Entitlement] composite or a generic [Entity] of
     * [Entitlement.ID] (the sync-merge path can hand back either). `null` if [data] is
     * not an entitlement.
     */
    fun fieldsOf(data: Data): SignedEntitlement? = when {
        data is Entitlement -> SignedEntitlement(
            ownerId = data.ownerId.get(),
            publisherId = data.publisherId.get(),
            productId = data.productId.get(),
            validFrom = data.validFrom.get(),
            validUntil = data.validUntil.get(),
            signature = data.publisherSignature.get(),
        )

        data is Entity<*> && data.type.get() == Entitlement.ID -> SignedEntitlement(
            ownerId = data.getFieldValue<TString>(Entitlement.Fields.OWNER_ID).get(),
            publisherId = data.getFieldValue<TString>(Entitlement.Fields.PUBLISHER_ID).get(),
            productId = data.getFieldValue<TString>(Entitlement.Fields.PRODUCT_ID).get(),
            validFrom = data.getFieldValue<TInteger>(Entitlement.Fields.VALID_FROM).get(),
            validUntil = data.getFieldValue<TInteger>(Entitlement.Fields.VALID_UNTIL).get(),
            signature = data.getFieldValue<TByteArray>(Entitlement.Fields.PUBLISHER_SIGNATURE).get(),
        )

        else -> null
    }

    /** The signed fields of an entitlement, decoupled from the `Data` representation. */
    data class SignedEntitlement(
        val ownerId: String,
        val publisherId: String,
        val productId: String,
        val validFrom: Long,
        val validUntil: Long,
        val signature: ByteArray,
    ) {
        /** The bytes that [signature] signs over. */
        fun message(): ByteArray =
            EntitlementSignature.message(ownerId, publisherId, productId, validFrom, validUntil)
    }

    private fun longToBigEndianBytes(value: Long): ByteArray =
        ByteArray(8) { i -> (value ushr (56 - i * 8) and 0xFF).toByte() }
}
