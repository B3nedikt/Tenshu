package org.tenshu.core

import kotlinx.coroutines.CoroutineScope
import org.tenshu.core.core.TComponent
import org.tenshu.core.core.TContext

/**
 * Provides access to the long-lived service scope for background operations.
 */
interface BackgroundScopeProvider : TComponent {

    val serviceScope: CoroutineScope

    companion object {
        const val ID = "core/background-scope-provider"
    }
}

/**
 * Accessor for the [BackgroundScopeProvider] from [TContext].
 */
fun TContext.backgroundScopeProvider(): BackgroundScopeProvider? =
    components[BackgroundScopeProvider.ID] as? BackgroundScopeProvider
