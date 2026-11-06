package app.tenshu.photos.ui.screens.collections


/**
 * A section of the Collections tab that has a *View all* page (`photos-app.md` §35).
 *
 * ⚠ An enum in the route, never the section's contents: the page reads the same
 * [CollectionsUiState] the tab does, computed from one feed walk.
 *
 * ⚠ No `@Serializable` here: this module has no serialization plugin, and it needs none — the plugin
 * generates an enum's serializer where a `@Serializable` class (Compose's `Destination`) references
 * it. Moved from `:photos-shared-ui` so the shared [CollectionsLayout] can name sections.
 */
enum class CollectionSection(val title: String) {
    PEOPLE("People"),
    ALBUMS("Albums"),
    PLACES("Places"),
    THINGS("Things"),
    YEARS("Years"),
}
