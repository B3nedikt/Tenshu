package org.tenshu.core.common.storage

import org.tenshu.core.common.transport.SocketPathResolver
import java.io.File

/**
 * Resolves the on-disk root for desktop **app data** — the object store,
 * at-rest secrets, and device-local settings.
 *
 * macOS background: the `.app` bundle in `/Applications` is immutable
 * (code-signed, possibly on a read-only volume), so app data never lives
 * inside it. The idiomatic home is the user's `~/Library`. Because Tenshu is
 * multi-process on the desktop — the Control daemon, the menu-bar tray, and the
 * sandboxed Drive **File Provider** extension — the data has to sit where *all*
 * of them can reach it, which under the sandbox means the **App Group
 * container** (the same place [SocketPathResolver] already hosts the IPC
 * sockets), not an arbitrary path like `~/.tenshu`.
 *
 * Resolution order (first match wins), mirroring [SocketPathResolver]:
 *  1. Explicit override — system property [DIR_PROPERTY] or env [DIR_ENV].
 *     Used by tests and ad-hoc dev runs; the value is the data root directly.
 *  2. macOS **with a Team ID configured** → the App Group container
 *     (`~/Library/Group Containers/<TEAMID>.app.tenshu.shared`), the
 *     sandbox-reachable location a signed/packaged build must use. App data
 *     lives under a per-package subdir; sockets sit at the container root, so
 *     there is no collision.
 *  3. Otherwise → `~/.tenshu` — the unsandboxed dev default (and the only
 *     option on Linux). An unsigned `:control-desktop:run` lands here.
 *
 * ⚠⚠ The arms are **separate roots and nothing moves between them.** A packaged build reads
 * only the group container; `~/.tenshu` stays the dev / Linux root and is never adopted. The
 * one-time adoption of `~/.tenshu` into the container (`LegacyDesktopRoot`, 2026-10-04) was
 * removed before the production reset: no pre-reset store survives it, so there is nothing
 * left to carry, and a resolver that can move a live object store is a standing hazard (it once
 * moved a running Control's store into a test's container). Any data still in `~/.tenshu` on a
 * packaged Mac is ignored, not imported — the reset runbook deletes it
 * (`docs/how-to/reset-and-commerce-test.md`).
 *
 * Window-geometry persistence in `:ui-core` (`WindowStatePersistence`) routes
 * through [root] too: the desktop apps pass `DesktopAppPaths.root()` as its
 * `storageDir`, so window state lands in the same sandbox-reachable location as
 * the rest of their data. `:ui-core` stays a dependency-free leaf module — it
 * just accepts the directory and defaults to `~/.tenshu` when none is given.
 */
object DesktopAppPaths {
    /** Data-root directory override (full path). */
    const val DIR_PROPERTY = "tenshu.data.dir"
    const val DIR_ENV = "TENSHU_DATA_DIR"

    /** Base directory holding all Tenshu desktop app data. */
    fun root(): File {
        explicitDir()?.let { return File(it) }
        return groupContainer() ?: legacyRoot()
    }

    /**
     * Per-package data directory (e.g. `<root>/app.tenshu.control.desktop`),
     * created if absent. Package-scoping keeps Control's and Drive's object
     * stores from colliding inside a shared group container.
     */
    fun dataDir(packageName: String): File = File(root(), packageName).apply { mkdirs() }

    /**
     * A named file directly under [root] — shared across the app group, not
     * package-scoped. For device-local settings whose historical location was
     * `~/.tenshu/<name>` (e.g. `control.settings.properties`).
     */
    fun sharedFile(name: String): File {
        val dir = root().apply { mkdirs() }
        return File(dir, name)
    }

    private fun groupContainer(): File? = SocketPathResolver.appGroupContainer()?.let(::File)

    /** `~/.tenshu` — arm 3: the unsigned dev root, and the only root on Linux. */
    private fun legacyRoot(): File = File(System.getProperty("user.home") ?: ".", ".tenshu")

    private fun explicitDir(): String? =
        System.getProperty(DIR_PROPERTY)?.takeIf { it.isNotBlank() }
            ?: System.getenv(DIR_ENV)?.takeIf { it.isNotBlank() }
}
