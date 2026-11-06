package app.tenshu.ui.navigation

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner

@Composable
actual fun <T : Any> KmpNavHost(
    backStack: List<T>,
    modifier: Modifier,
    onBack: () -> Unit,
    entryProvider: (T) -> KmpNavEntry<T>
) {
    val currentKey = backStack.lastOrNull()

    // Per-entry state that must survive while the entry sits *below* the top of
    // the stack (open a photo, then back) — mirroring the Android `NavDisplay`
    // decorators in `KmpNavHost.android.kt`:
    //  - `saveableStateHolder` retains each entry's saveable state (e.g. a
    //    `LazyColumn` scroll offset) keyed by the back-stack key.
    //  - `stores` gives each key its own `ViewModelStore` so parameterised
    //    `koinViewModel(...)` calls resolve a per-destination instance (without
    //    this, e.g. clicking a second photo showed the first photo's image).
    // Both are cleared only when the key actually leaves the back stack (a real
    // pop), NOT merely when it drops below the top — that's what preserved the
    // list VM + scroll position across a preview round-trip.
    val saveableStateHolder = rememberSaveableStateHolder()
    val stores = remember { mutableMapOf<T, ViewModelStore>() }

    // Reconcile after each successful composition: evict state for any key no
    // longer present in the back stack. Runs post-composition so we never mutate
    // the holder mid-compose.
    SideEffect {
        val active = backStack.toHashSet()
        val stale = stores.keys.filter { it !in active }
        stale.forEach { key ->
            stores.remove(key)?.clear()
            saveableStateHolder.removeState(key)
        }
    }

    // When the whole host leaves composition, clear every retained store.
    DisposableEffect(Unit) {
        onDispose {
            stores.values.forEach { it.clear() }
            stores.clear()
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
                val entryStoreOwner = remember(targetKey) {
                    object : ViewModelStoreOwner {
                        override val viewModelStore: ViewModelStore =
                            stores.getOrPut(targetKey) { ViewModelStore() }
                    }
                }
                CompositionLocalProvider(
                    LocalViewModelStoreOwner provides entryStoreOwner
                ) {
                    saveableStateHolder.SaveableStateProvider(targetKey) {
                        val entry = entryProvider(targetKey)
                        entry.Content()
                    }
                }
            }
        }
    }
}
