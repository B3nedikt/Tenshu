package app.tenshu.ui.navigation

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier

/**
 * iOS `actual`: renders the top back-stack entry, retaining each entry's saveable state while it
 * sits below the top (the same reason the desktop actual keeps a `SaveableStateHolder`).
 *
 * ⚠ Unlike the Android and desktop actuals this does **not** give each entry its own
 * `ViewModelStore`, and [onBack] is never invoked by the platform — iOS has no Esc key and no
 * system back button; a swipe-back gesture belongs to the hosting `UINavigationController`, not to
 * a Compose subtree. Both are honest gaps rather than oversights: `:ui-core`'s iOS targets exist so
 * `:ui-map`'s MapLibre renderer can use the design-system tokens, and every iOS app in this repo
 * navigates in SwiftUI. Anyone who makes this a real iOS navigation host must add the per-entry
 * `ViewModelStore` (as desktop does) and bridge the interactive pop gesture.
 */
@Composable
actual fun <T : Any> KmpNavHost(
    backStack: List<T>,
    modifier: Modifier,
    onBack: () -> Unit,
    entryProvider: (T) -> KmpNavEntry<T>
) {
    val currentKey = backStack.lastOrNull()
    val saveableStateHolder = rememberSaveableStateHolder()
    val seen = remember { mutableSetOf<T>() }

    // Evict saveable state for keys that have actually left the back stack (a real pop), not
    // merely dropped below the top. Runs post-composition so the holder is never mutated
    // mid-compose — same discipline as the desktop actual.
    SideEffect {
        val active = backStack.toHashSet()
        seen.filter { it !in active }.forEach { key ->
            seen.remove(key)
            saveableStateHolder.removeState(key)
        }
    }

    Box(modifier = modifier) {
        if (currentKey != null) {
            AnimatedContent(
                targetState = currentKey,
                transitionSpec = {
                    fadeIn(animationSpec = tween(300)) togetherWith
                        fadeOut(animationSpec = tween(300))
                },
                label = "KmpNavHost"
            ) { targetKey ->
                seen.add(targetKey)
                saveableStateHolder.SaveableStateProvider(targetKey) {
                    entryProvider(targetKey).Content()
                }
            }
        }
    }
}
