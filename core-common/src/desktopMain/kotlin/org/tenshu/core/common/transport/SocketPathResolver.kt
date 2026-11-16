package org.tenshu.core.common.transport

import java.io.File

/**
 * Resolves the directory that hosts the desktop daemon's Unix-domain sockets,
 * and the per-socket paths within it.
 *
 * macOS background (docs/proposals/desktop-macos-integration.md §2.1): a macOS
 * File Provider extension is **sandboxed** and cannot reach an arbitrary path
 * such as `~/.tenshu/ipc.sock`. The fix is to host the socket inside an **App
 * Group container** shared by the daemon and its native clients:
 *
 * ```
 * ~/Library/Group Containers/<TEAMID>.app.tenshu.shared/ipc.sock
 * ```
 *
 * Both the Control daemon and the native agents are signed with the
 * `com.apple.security.application-groups` entitlement for
 * `<TEAMID>.app.tenshu.shared`, so all of them can `bind()`/`connect()` there
 * even under the sandbox. The group container is for sandbox *reachability*,
 * not a security boundary — the gate remains the `desktop-ipc.md` handshake.
 *
 * Resolution order (first match wins):
 *  1. Explicit directory override — system property [DIR_PROPERTY] or env
 *     [DIR_ENV]. Used by tests and ad-hoc dev runs; the value is a directory,
 *     both sockets live inside it.
 *  2. macOS **with a Team ID configured** ([TEAM_ID_PROPERTY] / [TEAM_ID_ENV])
 *     → the App Group container path above. The signed/packaged bundle sets the
 *     Team ID; this is the sandboxed-reachable location.
 *  3. Otherwise → `~/.tenshu` — the unsandboxed dev default (and the only
 *     option on Linux). An unsigned `:control-desktop:run` + an unsigned native
 *     client both land here with no Team ID needed, so the Phase 0 round-trip
 *     works without a signing identity.
 *
 * ⚠⚠ **Every path here has a hard length budget, and arm 2 is the tight one.** A Unix socket path
 * lives in `sockaddr_un.sun_path`, a fixed byte array — **102 usable bytes on macOS, measured**
 * (2026-08-22; the declared `sun_path[104]` suggests 103, and 103 does not bind). The App Group
 * path is
 *
 * ```
 * /Users/<user>/Library/Group Containers/<TEAMID>.app.tenshu.shared/ipc.sock
 * ```
 *
 * which is **70 bytes plus the username**, so it fits while the username is **≤ 32 characters** and
 * the daemon simply fails to start beyond that. Arm 3's `~/.tenshu/ipc.sock` is 16 + username and
 * has plenty of room.
 *
 * ⚠ Bytes, not characters — a non-ASCII home directory spends more of the budget than it looks. ⚠
 * Lengthening [APP_GROUP_SUFFIX] or the socket names spends from the same 32-character margin.
 * `UnixSocketServer` now names this cause when a bind fails; before it did not, and the failure
 * looked like permissions.
 */
object SocketPathResolver {
    /** Binary `BinarySerializer`-framed proxy socket — [UnixSocketServer]. */
    const val BINARY_SOCKET = "ipc.sock"

    /** JSON native-bridge socket — `NativeBridgeServer`. */
    const val BRIDGE_SOCKET = "bridge.sock"

    /** App Group identifier suffix; the full id is `<TEAMID>.app.tenshu.shared`. */
    const val APP_GROUP_SUFFIX = "app.tenshu.shared"

    /** Directory override (full directory path; both sockets live inside it). */
    const val DIR_PROPERTY = "tenshu.ipc.dir"
    const val DIR_ENV = "TENSHU_IPC_DIR"

    /** Apple Developer Team ID; selects the App Group container on macOS. */
    const val TEAM_ID_PROPERTY = "tenshu.ipc.teamId"
    const val TEAM_ID_ENV = "TENSHU_TEAM_ID"

    /** Absolute path to the directory hosting the daemon's sockets. */
    fun resolveDir(): String {
        explicitDir()?.let { return it }
        appGroupContainer()?.let { return it }
        // Through `File` so the separator is the host's: identical on macOS/Linux, and on Windows
        // a clean `C:\Users\<u>\.tenshu` rather than a mixed `C:\Users\<u>/.tenshu`.
        return File(System.getProperty("user.home"), ".tenshu").path
    }

    /**
     * The macOS App Group container directory
     * (`~/Library/Group Containers/<TEAMID>.app.tenshu.shared`), or `null` when
     * not on a signed macOS build (no Team ID, or not macOS).
     *
     * Shared by [resolveDir] (sockets sit at its root) and `DesktopAppPaths`
     * (app data lives under a per-package subdir) so both agree on the single
     * sandbox-reachable location a signed/packaged build must use.
     */
    fun appGroupContainer(): String? {
        val teamId = teamId() ?: return null
        if (!isMacOs()) return null
        val groupId = "$teamId.$APP_GROUP_SUFFIX"
        // ⚠⚠ Before any caller opens a path in it — macOS refuses the container to an app that
        // has not asked for it natively. The helper asks Foundation for the URL and prints the
        // canonical path; do not reconstruct it from the sandboxed JVM's user.home, which points
        // at the app-specific container rather than the shared App Group container.
        AppGroupContainerClaim.ensureClaimed(groupId)?.let { return it }
        // ⚠⚠ Inside the App Sandbox the fallback below is a different directory per app (their
        // own containers), so Control and its clients would never meet. Fail at the cause rather
        // than as "Control not running" far from here.
        check(!isAppSandboxed()) {
            "App Group container for $groupId could not be resolved inside the App Sandbox " +
                "(Contents/Helpers/${AppGroupContainerClaim.HELPER_NAME} missing or failed)"
        }
        return "${System.getProperty("user.home")}/Library/Group Containers/$groupId"
    }

    /** macOS sets this in every sandboxed process; `user.home` is then the app's own container. */
    internal fun isAppSandboxed(): Boolean =
        System.getenv("APP_SANDBOX_CONTAINER_ID")?.isNotBlank() == true

    /** Absolute path to a named socket within [resolveDir]. */
    fun resolve(socketName: String = BINARY_SOCKET): String =
        resolveDir() + File.separator + socketName

    /** Convenience: the JSON native-bridge socket path. */
    fun resolveBridge(): String = resolve(BRIDGE_SOCKET)

    private fun explicitDir(): String? =
        System.getProperty(DIR_PROPERTY)?.takeIf { it.isNotBlank() }
            ?: System.getenv(DIR_ENV)?.takeIf { it.isNotBlank() }

    private fun teamId(): String? =
        System.getProperty(TEAM_ID_PROPERTY)?.takeIf { it.isNotBlank() }
            ?: System.getenv(TEAM_ID_ENV)?.takeIf { it.isNotBlank() }

    private fun isMacOs(): Boolean =
        System.getProperty("os.name").orEmpty().lowercase().contains("mac")
}
