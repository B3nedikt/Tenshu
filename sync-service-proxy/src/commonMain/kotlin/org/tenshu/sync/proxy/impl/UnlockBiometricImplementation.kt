package org.tenshu.sync.proxy.impl

import org.tenshu.core.Data
import org.tenshu.core.TByteArray
import org.tenshu.core.TenshuException
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.SyncOperationImplementation
import org.tenshu.core.common.util.Bip39InvalidMnemonicException
import org.tenshu.core.toTBoolean
import org.tenshu.sync.UnlockBiometric
import org.tenshu.sync.RecoveryPhraseUnreadableError
import org.tenshu.sync.common.SyncManager

internal class UnlockBiometricImplementation(
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
        val wrapKey = (params?.get(UnlockBiometric.Params.WRAP_KEY) as TByteArray).get()
        // ⚠⚠ The stored phrase, not the credential. A wrong wrap key returns `false` — the
        // AEAD auth tag fails and the unwrap never yields a payload. A `Bip39InvalidMnemonicException`
        // means the opposite: the wrapper opened and what came out is a mnemonic this build cannot
        // decode, so retrying the wrap key is pointless. Left untyped it reached the caller as
        // `InternalError` via `LocalTaskExecutor.wrapException` — a real failure, but one the UI
        // could only render as "something went wrong".
        val ok = try {
            syncManager.unlockBiometric(wrapKey)
        } catch (_: Bip39InvalidMnemonicException) {
            throw TenshuException(
                RecoveryPhraseUnreadableError.create(executionContext.context)
            )
        }
        return ok.toTBoolean(executionContext.context) as T
    }
}
