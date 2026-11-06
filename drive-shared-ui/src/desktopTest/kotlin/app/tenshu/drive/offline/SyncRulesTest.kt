package app.tenshu.drive.offline

import org.tenshu.core.file.sync.PathSyncOverride
import org.tenshu.core.file.sync.SyncPolicy
import org.tenshu.core.file.sync.SyncRuleSet
import org.tenshu.core.file.sync.effectiveSyncPolicy
import org.tenshu.core.file.sync.withFolderPolicy
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The pure effective-rule resolver (`docs/proposals/drive-offline-online.md`
 * §4.2): longest-matching path-prefix override wins, else the device default.
 */
class SyncRulesTest {

    @Test
    fun `no overrides falls through to the device default`() {
        assertEquals(
            SyncPolicy.ONLINE_ONLY,
            effectiveSyncPolicy("/a/b.jpg", SyncPolicy.ONLINE_ONLY, emptyList()),
        )
        assertEquals(
            SyncPolicy.KEEP_OFFLINE,
            effectiveSyncPolicy("/a/b.jpg", SyncPolicy.KEEP_OFFLINE, emptyList()),
        )
    }

    @Test
    fun `folder override covers descendants but not siblings`() {
        val overrides = listOf(PathSyncOverride("/Photos", SyncPolicy.KEEP_OFFLINE))

        // The folder itself and everything beneath it match.
        assertEquals(SyncPolicy.KEEP_OFFLINE, effectiveSyncPolicy("/Photos", SyncPolicy.ONLINE_ONLY, overrides))
        assertEquals(SyncPolicy.KEEP_OFFLINE, effectiveSyncPolicy("/Photos/a.jpg", SyncPolicy.ONLINE_ONLY, overrides))
        assertEquals(
            SyncPolicy.KEEP_OFFLINE,
            effectiveSyncPolicy("/Photos/2024/trip/a.jpg", SyncPolicy.ONLINE_ONLY, overrides),
        )

        // A sibling sharing the textual prefix must NOT match.
        assertEquals(SyncPolicy.ONLINE_ONLY, effectiveSyncPolicy("/PhotosArchive/a.jpg", SyncPolicy.ONLINE_ONLY, overrides))
    }

    @Test
    fun `longest matching prefix wins over a coarser rule`() {
        // /Archive online-only on a keep-everything device, but pin one subfolder.
        val overrides = listOf(
            PathSyncOverride("/Archive", SyncPolicy.ONLINE_ONLY),
            PathSyncOverride("/Archive/keepme", SyncPolicy.KEEP_OFFLINE),
        )
        assertEquals(SyncPolicy.ONLINE_ONLY, effectiveSyncPolicy("/Archive/old.zip", SyncPolicy.KEEP_OFFLINE, overrides))
        assertEquals(
            SyncPolicy.KEEP_OFFLINE,
            effectiveSyncPolicy("/Archive/keepme/x.jpg", SyncPolicy.KEEP_OFFLINE, overrides),
        )
    }

    @Test
    fun `exact-path override resolves only that file`() {
        val overrides = listOf(PathSyncOverride("/notes/todo.txt", SyncPolicy.KEEP_OFFLINE))
        assertEquals(SyncPolicy.KEEP_OFFLINE, effectiveSyncPolicy("/notes/todo.txt", SyncPolicy.ONLINE_ONLY, overrides))
        // A different file in the same folder is untouched.
        assertEquals(SyncPolicy.ONLINE_ONLY, effectiveSyncPolicy("/notes/other.txt", SyncPolicy.ONLINE_ONLY, overrides))
        // A path that merely extends the file name is not a descendant.
        assertEquals(SyncPolicy.ONLINE_ONLY, effectiveSyncPolicy("/notes/todo.txt.bak", SyncPolicy.ONLINE_ONLY, overrides))
    }

    @Test
    fun `root override covers the whole drive`() {
        val overrides = listOf(PathSyncOverride("/", SyncPolicy.KEEP_OFFLINE))
        assertEquals(SyncPolicy.KEEP_OFFLINE, effectiveSyncPolicy("/anything/deep.bin", SyncPolicy.ONLINE_ONLY, overrides))
    }

    @Test
    fun `withPinnedPaths folds pins as winning exact-path keep overrides`() {
        // Device default + a coarse folder rule that would otherwise online-only this file.
        val base = SyncRuleSet(
            deviceDefault = SyncPolicy.ONLINE_ONLY,
            overrides = listOf(PathSyncOverride("/Photos", SyncPolicy.ONLINE_ONLY)),
        )
        val ruleSet = base.withPinnedPaths(setOf("/Photos/pinned.jpg"))

        // The exact-path pin (longest prefix) beats the folder rule.
        assertEquals(SyncPolicy.KEEP_OFFLINE, ruleSet.effectivePolicy("/Photos/pinned.jpg"))
        // Unpinned siblings still follow the folder rule.
        assertEquals(SyncPolicy.ONLINE_ONLY, ruleSet.effectivePolicy("/Photos/other.jpg"))
    }

    @Test
    fun `withPinnedPaths on empty pins returns the same rule set`() {
        val base = SyncRuleSet(SyncPolicy.ONLINE_ONLY, emptyList())
        assertEquals(base, base.withPinnedPaths(emptySet()))
    }

    @Test
    fun `Default is online-only with no overrides`() {
        assertEquals(SyncPolicy.ONLINE_ONLY, SyncRuleSet.Default.deviceDefault)
        assertEquals(SyncPolicy.ONLINE_ONLY, SyncRuleSet.Default.effectivePolicy("/whatever.txt"))
    }

    @Test
    fun `withFolderPolicy upserts an override that differs from the default`() {
        val result = emptyList<PathSyncOverride>()
            .withFolderPolicy("/Photos", SyncPolicy.KEEP_OFFLINE, deviceDefault = SyncPolicy.ONLINE_ONLY)
        assertEquals(listOf(PathSyncOverride("/Photos", SyncPolicy.KEEP_OFFLINE)), result)
    }

    @Test
    fun `withFolderPolicy clears the override when it equals the default (stays sparse)`() {
        val start = listOf(PathSyncOverride("/Photos", SyncPolicy.KEEP_OFFLINE))
        // Setting /Photos back to the device default removes the row entirely.
        val result = start.withFolderPolicy("/Photos", SyncPolicy.ONLINE_ONLY, deviceDefault = SyncPolicy.ONLINE_ONLY)
        assertEquals(emptyList(), result)
    }

    @Test
    fun `withFolderPolicy replaces an existing override for the same prefix`() {
        val start = listOf(
            PathSyncOverride("/Photos", SyncPolicy.KEEP_OFFLINE),
            PathSyncOverride("/Archive", SyncPolicy.ONLINE_ONLY),
        )
        val result = start.withFolderPolicy("/Photos", SyncPolicy.ONLINE_ONLY, deviceDefault = SyncPolicy.KEEP_OFFLINE)
        // /Photos flips to ONLINE_ONLY (differs from the KEEP_OFFLINE default → kept); /Archive untouched.
        assertEquals(
            listOf(
                PathSyncOverride("/Archive", SyncPolicy.ONLINE_ONLY),
                PathSyncOverride("/Photos", SyncPolicy.ONLINE_ONLY),
            ),
            result,
        )
    }
}
