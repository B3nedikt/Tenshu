package org.tenshu.contact.proxy.impl

import org.tenshu.contact.GetContactSas
import org.tenshu.contact.TenshuIdentity
import org.tenshu.contact.VerifyTenshuIdentity
import org.tenshu.contact.proxy.ContactProxyComponents
import org.tenshu.core.Data
import org.tenshu.core.GenericError
import org.tenshu.core.TBoolean
import org.tenshu.core.TByteArray
import org.tenshu.core.TString
import org.tenshu.core.TenshuException
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.SyncOperationImplementation
import org.tenshu.core.common.proxy.executor.read
import org.tenshu.core.common.util.deriveContactSas
import org.tenshu.core.entity.DataId
import org.tenshu.core.time.clock
import org.tenshu.core.toTBoolean
import org.tenshu.core.toTString

/*
 * Re-verification (`contacts-app.md` D4/Q2a). Two ops: derive the comparison code, and
 * record what the user decided after comparing it.
 */

/**
 * The §5.3 six-digit code for a stored key, derived against the user's own A13; `""` for
 * the two absences `GetContactSas` documents.
 */
internal class GetContactSasImplementation(
    private val components: ContactProxyComponents,
) : SyncOperationImplementation {

    @Suppress("UNCHECKED_CAST")
    override fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?,
    ): T {
        val fingerprint: TString = params.read(GetContactSas.Params.FINGERPRINT)
        val identity = readIdentity(executionContext, fingerprint.get())
            ?: return "".toTString(executionContext.context) as T
        val ownKeyPair = components.identityKeyPair()
            ?: return "".toTString(executionContext.context) as T

        return deriveContactSas(
            publicKeyA = ownKeyPair.publicKey,
            publicKeyB = identity.publicKey.get(),
        ).toTString(executionContext.context) as T
    }
}

/**
 * Records the user's verdict on one key.
 *
 * ⚠⚠ **A replace-write over a shipped composite.** `TenshuIdentity` is re-created whole, so
 * every field is carried across explicitly from the row that was read. Miss one and it is
 * silently destroyed the next time somebody verifies a contact (deletion by omission) — here
 * it would take out `grantSeed` (making the contact un-grantable) or `publicKey`
 * (making them unreachable). If a field is ever added to `TenshuIdentity`, it must be added
 * here in the same change.
 */
internal class VerifyTenshuIdentityImplementation : SyncOperationImplementation {

    @Suppress("UNCHECKED_CAST")
    override fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?,
    ): T {
        val fingerprint: TString = params.read(VerifyTenshuIdentity.Params.FINGERPRINT)
        val verified: TBoolean = params.read(VerifyTenshuIdentity.Params.VERIFIED)

        val identity = readIdentity(executionContext, fingerprint.get())
            ?: throw TenshuException(
                GenericError.create(
                    executionContext.context,
                    "Unknown key '${fingerprint.get()}'.",
                )
            )

        val current = identity.verification.get()
        // ⚠ ATTESTED is Rei-carried and outranks a local human verdict in both
        // directions: this op must neither claim it nor overwrite it.
        if (current == TenshuIdentity.Verification.ATTESTED) {
            return false.toTBoolean(executionContext.context) as T
        }
        val target = if (verified.get()) {
            TenshuIdentity.Verification.VERIFIED
        } else {
            TenshuIdentity.Verification.UNVERIFIED
        }
        if (current == target) {
            return false.toTBoolean(executionContext.context) as T
        }

        val now = executionContext.context.clock().nowEpochMillis()
        executionContext.saveData(
            TenshuIdentity.create(
                context = executionContext.context,
                fingerprint = identity.fingerprint.get(),
                publicKey = identity.publicKey.get(),
                personId = identity.personId.get(),
                selfAssertedName = identity.selfAssertedName.get(),
                verification = target,
                verifiedAt = if (verified.get()) now else 0L,
                // D4 stamps QR_SAS on elevation. ⚠ Slightly a misnomer since Q2a chose
                // read-the-digits over QR precisely because the people who need
                // re-verification are the ones *not* in the room — but the ladder has one
                // "confirmed out of band" value and this is it. On demotion `source` is
                // left as-is: it records how the key was acquired, which un-verifying does
                // not change.
                source = if (verified.get()) {
                    TenshuIdentity.Source.QR_SAS
                } else {
                    identity.source.get()
                },
                // ⚠ `grantSeed` is an OPTIONAL field, so `identity.grantSeed` (which the
                // interface defines as a plain `getField`) throws for rows written before
                // the field existed. Read it optionally and default to empty — exactly what
                // `TenshuIdentity.create` does for a contact with no seed.
                grantSeed = identity
                    .getOptionalField<TByteArray>(TenshuIdentity.Field.GRANT_SEED)
                    ?.get()
                    ?: ByteArray(0),
            )
        )
        return true.toTBoolean(executionContext.context) as T
    }
}

private fun readIdentity(
    executionContext: ExecutionContext,
    fingerprint: String,
): TenshuIdentity? {
    if (fingerprint.isEmpty()) return null
    val dataId = DataId.StringDataId.create(
        context = executionContext.context,
        type = TenshuIdentity.ID,
        keyValue = fingerprint,
    )
    return executionContext.database.getData(dataId) as? TenshuIdentity
}
