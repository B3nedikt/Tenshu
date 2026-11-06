package app.tenshu.ui.navigation

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay

@Composable
actual fun <T : Any> KmpNavHost(
    backStack: List<T>,
    modifier: Modifier,
    onBack: () -> Unit,
    entryProvider: (T) -> KmpNavEntry<T>
) {
    NavDisplay(
        backStack = backStack,
        modifier = modifier,
        onBack = onBack,
        transitionSpec = {
            fadeIn(animationSpec = tween(300)) togetherWith
                    fadeOut(animationSpec = tween(300))
        },
        popTransitionSpec = {
            fadeIn(animationSpec = tween(300)) togetherWith
                    fadeOut(animationSpec = tween(300))
        },
        entryDecorators = listOf(
            rememberSaveableStateHolderNavEntryDecorator(),
            rememberViewModelStoreNavEntryDecorator()
        ),
        entryProvider = { key ->
            val kmpEntry = entryProvider(key)
            NavEntry(
                key = kmpEntry.key,
                content = { kmpEntry.Content() }
            )
        }
    )
}
