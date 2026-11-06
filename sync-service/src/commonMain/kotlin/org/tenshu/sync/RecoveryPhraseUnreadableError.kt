package org.tenshu.sync

import org.tenshu.core.TError
import org.tenshu.core.core.TContext
import org.tenshu.core.core.create
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.composite

/**
 * The credential was right, but this device's own stored recovery phrase no longer decodes.
 *
 * ⚠⚠ **The distinction this type exists to make is "wrong passphrase" vs "right passphrase,
 * unreadable phrase".** `UnlockLocal` / `UnlockBiometric` return `false` for a wrong credential —
 * the AEAD auth tag fails and the unwrap never produces a payload. That is a retry the user can
 * win. This is not: the wrapper opened, the payload inside it is a mnemonic this build cannot
 * decode, and no number of passphrase attempts will change that. Reporting it as `false` sends the
 * user to retype a passphrase that was correct the first time.
 *
 * ⚠ It replaced an `InternalError` carrying `Bip39InvalidMnemonicException.message`. That is worse
 * than it sounds: `LocalTaskExecutor.wrapException` turns any untyped throw into
 * `InternalErrorDto(internalDetail = e.message)`, so the condition *was* reaching the caller — as
 * an opaque internal failure, indistinguishable from a genuine bug, and recorded as one in the
 * error log. The fix is not to stop it throwing; it is to throw something the UI can name.
 *
 * ⚠ **Fieldless on purpose.** `Bip39.decode` distinguishes `UnknownWord`, `InvalidLength` and
 * `ChecksumMismatch`, but for a phrase the *device* stored rather than one the user just typed,
 * the remedy is identical for all three: re-enter the written-down phrase through recovery. A
 * reason field would either be a `TString` — adding to the debt `docs/tech-debt.md` records under
 * *"Typed reasons: four error types use a `TString` + constants object where `TConstant` fits"* —
 * or a `TConstant` family carrying no user-visible difference. See [PairingFailure]'s KDoc for
 * when a reason set does earn its keep.
 *
 * The reachable cause today is a phrase minted under the pre-2026-09-15 wordlist
 * (`docs/sync/threat-model.md` §11.2); the next wordlist change is the one that will be.
 */
interface RecoveryPhraseUnreadableError : TError {
    override val schema: CompositeSchema
        get() = Schema

    companion object {
        const val ID = "${SyncService.ID}/error/recoveryPhraseUnreadable"

        val Schema: CompositeSchema = composite(
            id = ID,
            parent = TError.Schema,
            fields = {},
        )

        fun create(context: TContext): RecoveryPhraseUnreadableError = context.create(
            type = ID,
            fields = emptyList(),
        )
    }
}
