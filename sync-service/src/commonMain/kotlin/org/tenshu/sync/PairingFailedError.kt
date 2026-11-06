package org.tenshu.sync

import org.tenshu.core.TConstant
import org.tenshu.core.TError
import org.tenshu.core.core.TContext
import org.tenshu.core.core.create
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.ConstantSchema
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.constant

/**
 * Why a pairing attempt failed, as a schema-native closed set.
 *
 * ⭐ A [TConstant] set rather than a `String` over a constants object — the shape ADR 0016 anchor #4
 * actually asks for ("travels as an **enum-ish schema**"). Three things follow that a `TString`
 * reason cannot give:
 *
 * 1. **Render sites are exhaustive.** `when (reason)` over a sealed type has no `else`, so adding a
 *    failure breaks the build at every place that renders one, instead of silently degrading to the
 *    generic sentence. That is the same guarantee `ErrorCopyCatalogueTest` gives for error *types*,
 *    which reason strings had no equivalent of.
 * 2. **Each member owns a schema id**, so a copy key can derive *per reason* rather than per error
 *    type — `errorCopyKeyForId` gives one key for a whole `TString`-reason error, which is why the
 *    earlier version needed a hand-written `when` over reason strings in every renderer.
 * 3. It cannot be constructed from an arbitrary string.
 *
 * ⚠⚠ **The cost is skew, and it is unenforced.** `SchemaShapeBaseline` pins *composites*; a
 * constant has no fields, so adding a member here trips nothing. But a client that has not been
 * rebuilt cannot parse the new id — `SchemaRegistry` throws *"No constant factory registered for
 * type"* — so it fails hard rather than degrading. **Adding a member is a wire change: bump
 * `SyncService`'s version with it.** That is affordable here because pairing is device-to-device
 * inside one user's own fleet and House Rule 9 moves clients in lockstep; it is not automatically
 * affordable elsewhere. See `docs/tech-debt.md` "Typed reasons: `TString` + constants vs `TConstant`".
 */
sealed interface PairingFailure : TConstant {

    /**
     * This device's own relay admission is gone, so it cannot admit another
     * (`delegated-device-admission-authority.md`). ⚠ The `core-error-copy-catalogue.md` §1 case:
     * the user is mid-flow on "Add This Device" and the fix is to pair *this* device again first.
     */
    object AdmitterUnrecognised : PairingFailure {
        override val schema: ConstantSchema = constant("$ID/admitterUnrecognised", Schema)
    }

    /** This device holds no pairing authority at all — no owner identity to admit under. */
    object NoAuthority : PairingFailure {
        override val schema: ConstantSchema = constant("$ID/noAuthority", Schema)
    }

    /** The approval names an offer this device is not holding a session for. */
    object NoPendingOffer : PairingFailure {
        override val schema: ConstantSchema = constant("$ID/noPendingOffer", Schema)
    }

    /** No live offer at the rendezvous — the hub is not armed, or the offer expired. */
    object OfferNotFound : PairingFailure {
        override val schema: ConstantSchema = constant("$ID/offerNotFound", Schema)
    }

    /** The fetched offer's signature does not verify against the printed key — hard error. */
    object VerifyFailed : PairingFailure {
        override val schema: ConstantSchema = constant("$ID/verifyFailed", Schema)
    }

    companion object {
        const val ID = "${SyncService.ID}/pairingFailure"

        val Schema: ConstantSchema = constant(ID)

        /**
         * Every member, in declaration order.
         *
         * ⚠ Hand-maintained: `KClass.sealedSubclasses` is JVM-reflection-only and this is a KMP
         * module with iOS and JS targets. The module registration walks this, so a member missing
         * from it is unregistered and unparseable — add here and there in one edit.
         *
         * ⚠⚠ **`by lazy` is load-bearing, not style.** Each member's `schema` reads [Schema] off
         * this companion, so an eager list makes companion init force the members, which re-enter
         * the companion before [Schema] is assigned — a class-initialisation cycle that surfaces
         * as `NullPointerException: … Companion is null` from whichever module registration
         * touches it first, nowhere near this file. Deferring the list lets the companion finish
         * before any member initialises.
         */
        val entries: List<PairingFailure> by lazy {
            listOf(AdmitterUnrecognised, NoAuthority, NoPendingOffer, OfferNotFound, VerifyFailed)
        }
    }
}

/**
 * A pairing attempt failed for a reason the user can act on.
 *
 * ⚠⚠ This type exists because `core-error-copy-catalogue.md` §1's symptom lived here: the offer
 * approval wrote *"This device is no longer recognised by the relay, so it can't add another
 * device. Pair this device again, then retry."* as a `GenericError` message. Good copy, wrong
 * place — untranslatable, unmatchable by a caller, and (only `InternalError.internalDetail`
 * reaching an `ErrorEvent`) dropped from the logs too. On Android it was authored in Control's
 * process and rendered by whichever app asked. The sentence was not deleted; it moved to
 * `:control-shared`'s `strings.xml` against [PairingFailure.AdmitterUnrecognised].
 *
 * ⚠ Unlike the recovery and contact-invite failures, this is **not** an `InvalidParameter`: the
 * caller's arguments are fine, and what failed is the state of this device or of the offer.
 */
interface PairingFailedError : TError {
    override val schema: CompositeSchema
        get() = Schema

    val reason: PairingFailure
        get() = getField(Fields.REASON)

    companion object {
        const val ID = "${SyncService.ID}/error/pairingFailed"

        val Schema: CompositeSchema = composite(
            id = ID,
            parent = TError.Schema,
            fields = { composite(Fields.REASON, type = PairingFailure.Schema) },
        )

        fun create(context: TContext, reason: PairingFailure): PairingFailedError = context.create(
            type = ID,
            fields = listOf(reason),
        )
    }

    object Fields {
        const val REASON = "$ID/reason"
    }
}
