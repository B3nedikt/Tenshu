package app.tenshu.ui.layout

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Storage port for the navigation rail's expanded/collapsed choice, so the user's explicit
 * preference survives an app restart.
 *
 * ### Why a port rather than direct storage
 *
 * `:ui-core` is a dependency-free leaf module and deliberately holds no preference *client*
 * (see `ui-agent.md`). It also cannot pick a storage location: the desktop apps route their
 * data through `DesktopAppPaths.root()` because `~/.tenshu` is not reachable from a sandboxed
 * signed build, and that resolver lives outside this module. So the scaffold declares what it
 * needs and the app supplies it.
 *
 * ### Why a `CompositionLocal` rather than a parameter
 *
 * The rail's state lives inside [AdaptiveNavScaffold], which each app reaches from its own
 * `App()` composable — but the *storage directory* is only known at the desktop `Main.kt`
 * entry point. A composition local crosses that boundary without adding a parameter to eight
 * `App()` signatures for something seven of them would just forward.
 *
 * Default is `null` — no persistence, the choice lasts for the session only. That is the
 * correct behaviour on Android and iOS, where the rail only appears on tablets and the size
 * class already picks a sensible default.
 *
 * @see filePersistedNavRailPreference for the desktop implementation.
 */
interface NavRailPreference {
    /** The stored choice, or `null` if the user has never expressed one. */
    fun read(): Boolean?

    /** Records an **explicit** user choice. Not called for size-class-driven changes. */
    fun write(expanded: Boolean)
}

/**
 * The [NavRailPreference] in force, or `null` for session-only behaviour (the default).
 *
 * Provide it at the app's entry point, alongside the other roots:
 *
 * ```
 * CompositionLocalProvider(
 *     LocalNavRailPreference provides filePersistedNavRailPreference("drive", appDir),
 * ) { TenshuTheme { App() } }
 * ```
 */
val LocalNavRailPreference = staticCompositionLocalOf<NavRailPreference?> { null }
