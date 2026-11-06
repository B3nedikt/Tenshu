package org.tenshu.core.common.proxy.db

import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask
import kotlin.concurrent.Volatile

/**
 * An app-local directory under iOS *Documents* (always available — no entitlement, works on the
 * unsigned simulator). [subdirectory] namespaces it. The simulator / test-host fallback of
 * [sharedReplicaDirectory] only; a device build without the App-Group container throws instead.
 */
fun applicationSupportDirectory(subdirectory: String): String {
    val docs = NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true)
        .firstOrNull() as? String
        ?: error("No iOS Documents directory available")
    return "$docs/$subdirectory"
}

/**
 * The App-Group id the Tenshu apps declare — the same bare string in every `*.entitlements` and
 * `*.device.entitlements` (an iOS App Group id is global and carries no team prefix, unlike a
 * Keychain access group).
 */
const val TENSHU_APP_GROUP: String = "group.app.tenshu.shared"

/**
 * The shared **App-Group container** directory for [subdirectory] under group [appGroupId], or null if
 * the entitlement grants no container (a build without the App-Group entitlement). Every first-party
 * app declaring the group resolves **one** directory here, on the ad-hoc simulator and on a signed
 * device build alike.
 */
fun appGroupContainerDirectory(appGroupId: String, subdirectory: String): String? {
    val container = NSFileManager.defaultManager
        .containerURLForSecurityApplicationGroupIdentifier(appGroupId)?.path ?: return null
    return "$container/$subdirectory"
}

/**
 * The directory for [subdirectory] (e.g. `"replica"`, the SQLite shared replica) in the shared
 * App-Group container, so every route-1 runtime on the device reads and writes **one** replica.
 *
 * ⚠⚠ **A device build with no container throws** ([SharedReplicaUnavailableException]) rather than
 * falling back to the app's own Documents directory: a fallback on a device build without the group
 * runs N independent stores under one shared device id, each believing it is the shared replica.
 * The app-local fallback exists only where it is the intended configuration: the simulator and the
 * headless test host. See [resolveReplicaDirectory].
 */
fun sharedReplicaDirectory(subdirectory: String): String =
    resolveReplicaDirectory(
        groupDirectory = appGroupContainerDirectory(TENSHU_APP_GROUP, subdirectory),
        deviceBuild = IS_IOS_DEVICE_BUILD,
        subdirectory = subdirectory,
        appLocalDirectory = { applicationSupportDirectory(subdirectory) },
    )

/**
 * Whether this binary was compiled for a **physical device** (`iosArm64`), as opposed to a
 * simulator slice. Decided by the compilation target rather than read at run time, so nothing in
 * the environment can talk a device build into the simulator's app-local fallback.
 */
internal expect val IS_IOS_DEVICE_BUILD: Boolean

/**
 * Raised when a device build cannot resolve the App-Group container, i.e. it was signed without
 * `com.apple.security.application-groups`.
 *
 * ⚠ The message is also printed before the throw: the callers sit behind Koin and an Objective-C
 * boundary, and an undeclared Kotlin exception there ends in `abort()` with the message gone.
 */
class SharedReplicaUnavailableException(message: String) : IllegalStateException(message)

/**
 * The location policy behind [sharedReplicaDirectory], split out so both branches are testable on
 * the simulator (where [IS_IOS_DEVICE_BUILD] is always false).
 *
 *  - a container resolved → use it;
 *  - no container on a **device build** → throw. A device build without the group is a signing
 *    defect, and running anyway silently produces one store per app under one device id
 *    (`ios-background-scheduling.md` §2): every runtime would sync its own copy, and the
 *    reclamation barrier would count sibling stores as "self";
 *  - no container on the **simulator / test host** → the app-local directory, reported once per
 *    process. That is a supported configuration: the headless test host has no entitlements, and
 *    the mock-only apps declare none.
 */
internal fun resolveReplicaDirectory(
    groupDirectory: String?,
    deviceBuild: Boolean,
    subdirectory: String,
    appLocalDirectory: () -> String,
): String {
    if (groupDirectory != null) return groupDirectory
    if (deviceBuild) {
        val message = "No App Group container for $TENSHU_APP_GROUP (asked for '$subdirectory'): " +
            "this device build was signed without com.apple.security.application-groups. " +
            "Refusing to open an app-local replica — it would be one of N independent stores " +
            "under one device id. Add the group to the app's *.device.entitlements " +
            "(docs/how-to/ios-signing.md §6)."
        println("SharedReplica: $message")
        throw SharedReplicaUnavailableException(message)
    }
    if (!appLocalFallbackReported) {
        appLocalFallbackReported = true
        println(
            "SharedReplica: no App Group container for $TENSHU_APP_GROUP; using the app-local " +
                "directory. Expected on the simulator test host and the mock-only apps; a device " +
                "build throws instead.",
        )
    }
    return appLocalDirectory()
}

@Volatile
private var appLocalFallbackReported = false
