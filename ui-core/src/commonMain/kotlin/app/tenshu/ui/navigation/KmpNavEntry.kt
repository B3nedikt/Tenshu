package app.tenshu.ui.navigation

import androidx.compose.runtime.Composable
import kotlin.reflect.KClass

/**
 * A single destination in the [KmpNavHost] back stack. The [key] identifies
 * the destination (typically a sealed-class value in a caller's navigation
 * model); [Content] renders its UI.
 */
interface KmpNavEntry<T : Any> {
    val key: T

    @Composable
    fun Content()
}

/**
 * Concrete entry that defers rendering to a supplied composable lambda.
 * Subclass callers rarely instantiate this directly — they use the DSL
 * exposed by [kmpEntryProvider].
 */
class KmpNavEntryImpl<T : Any, K : T>(
    override val key: K,
    private val content: @Composable (K) -> Unit
) : KmpNavEntry<T> {
    @Composable
    override fun Content() {
        content(key)
    }
}

/**
 * Factory mapping back-stack keys to their [KmpNavEntry]. Built by
 * [KmpNavEntryBuilder] / [kmpEntryProvider].
 */
class KmpNavEntryProvider<T : Any>(
    private val entries: Map<KClass<out T>, (T) -> KmpNavEntry<T>>
) {
    fun invoke(key: T): KmpNavEntry<T> {
        val factory = entries[key::class] ?: error("No entry found for key $key")
        return factory(key)
    }
}

/**
 * DSL for registering typed destinations. Prefer the top-level
 * [kmpEntryProvider] helper:
 *
 * ```
 * val provider = kmpEntryProvider<Destination> {
 *     entry<Destination.Home> { HomeScreen() }
 *     entry<Destination.Details> { DetailsScreen(id = it.id) }
 * }
 * ```
 */
class KmpNavEntryBuilder<T : Any> {
    @PublishedApi
    internal val factories = mutableMapOf<KClass<out T>, (T) -> KmpNavEntry<T>>()

    inline fun <reified K : T> entry(noinline content: @Composable (K) -> Unit) {
        factories[K::class] = { key ->
            @Suppress("UNCHECKED_CAST")
            KmpNavEntryImpl(key as K, content)
        }
    }

    fun build() = KmpNavEntryProvider(factories)
}

/**
 * Entry point for building an entry-provider function compatible with
 * [KmpNavHost.entryProvider]. See [KmpNavEntryBuilder] for the DSL.
 */
fun <T : Any> kmpEntryProvider(block: KmpNavEntryBuilder<T>.() -> Unit): (T) -> KmpNavEntry<T> {
    val provider = KmpNavEntryBuilder<T>().apply(block).build()
    return { key -> provider.invoke(key) }
}
