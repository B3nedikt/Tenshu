package org.tenshu.sync.proxy.impl

import org.tenshu.core.Data
import org.tenshu.core.GenericError
import org.tenshu.core.TenshuException
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.SyncOperationImplementation
import org.tenshu.core.toTString
import org.tenshu.sync.common.SyncManager

/**
 * Reads the persisted recovery phrase from [SyncManager]. The phrase is
 * generated and stored during realm creation (`CreateRealm` →
 * `SyncManager.initialize`); this op is the read-side counterpart used by the
 * "Set up account recovery" Settings surface introduced in
 * `docs/proposals/onboarding-low-friction.md` §7.4.
 *
 * Returns a typed failure when no phrase is available — pre-realm install,
 * or a locked device whose plaintext phrase has been wrapped under a
 * passphrase (see [SyncManager.getRecoveryPhrase] for the lifecycle).
 *
 * Prior to PR #onboarding-phase-2, this implementation generated a fresh
 * mnemonic on every call (`Bip39.generateMnemonic()`) — non-idempotent and
 * cryptographically unrelated to the user's actual realm phrase. Bare
 * generation now lives in the new-user onboarding ViewModel, which is the
 * one place that needs to mint a phrase before [SyncManager.initialize].
 */
internal class GetRecoveryPhraseImplementation(
    private val syncManager: SyncManager,
) : SyncOperationImplementation {

    @Suppress("UNCHECKED_CAST")
    override fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?,
    ): T {
        val phrase = syncManager.getRecoveryPhrase()
            ?: throw TenshuException(
                GenericError.create(
                    executionContext.context,
                    "No recovery phrase available — create or unlock a realm first.",
                )
            )

        return phrase.toTString(executionContext.context) as T
    }
}
