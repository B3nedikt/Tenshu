package org.tenshu.sync.proxy.impl

import org.tenshu.core.Data
import org.tenshu.core.TString
import org.tenshu.core.TenshuException
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.SyncOperationImplementation
import org.tenshu.core.common.util.Bip39InvalidMnemonicException
import org.tenshu.core.toTBoolean
import org.tenshu.sync.UnlockLocal
import org.tenshu.sync.RecoveryPhraseUnreadableError
import org.tenshu.sync.common.SyncManager

internal class UnlockLocalImplementation(
    private val syncManager: SyncManager,
) : SyncOperationImplementation {

    @Suppress("UNCHECKED_CAST")
    override fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?
    ): T {
        val passphrase = (params?.get(UnlockLocal.Params.PASSPHRASE) as TString).get()
        // ⚠⚠ The stored phrase, not the credential. A wrong passphrase returns `false` — the
        // AEAD auth tag fails and the unwrap never yields a payload. A `Bip39InvalidMnemonicException`
        // means the opposite: the wrapper opened and what came out is a mnemonic this build cannot
        // decode, so retrying the passphrase is pointless. Left untyped it reached the caller as
        // `InternalError` via `LocalTaskExecutor.wrapException` — a real failure, but one the UI
        // could only render as "something went wrong".
        val ok = try {
            syncManager.unlockLocal(passphrase)
        } catch (_: Bip39InvalidMnemonicException) {
            throw TenshuException(
                RecoveryPhraseUnreadableError.create(executionContext.context)
            )
        }
        return ok.toTBoolean(executionContext.context) as T
    }
}
