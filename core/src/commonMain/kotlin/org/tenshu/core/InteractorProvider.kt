package org.tenshu.core

import org.tenshu.core.core.TComponent
import org.tenshu.core.core.TContext
import org.tenshu.core.request.RequestObject

/**
 * Resolves the appropriate [Interactor] for a given [RequestObject].
 */
interface InteractorProvider : TComponent {

    /**
     * Resolves the appropriate [Interactor] for the given [RequestObject].
     * Dispatch is driven by the terminal operation's schema traits.
     */
    suspend fun <T : Data?> getInteractor(ro: RequestObject): Interactor<T>

    companion object {
        const val COMPONENT_ID = "core/INTERACTOR_PROVIDER"
    }
}

/**
 * Accessor for the [InteractorProvider] from [TContext].
 */
fun TContext.interactorProvider(): InteractorProvider =
    components[InteractorProvider.COMPONENT_ID] as InteractorProvider
