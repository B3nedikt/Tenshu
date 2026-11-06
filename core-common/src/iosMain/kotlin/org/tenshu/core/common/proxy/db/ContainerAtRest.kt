@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package org.tenshu.core.common.proxy.db

import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSFilePosixPermissions
import platform.Foundation.NSFileProtectionCompleteUntilFirstUserAuthentication
import platform.Foundation.NSFileProtectionKey
import platform.Foundation.NSNumber
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.dataUsingEncoding
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey

/**
 * ⚠⚠ **D3 and D3b** (`ios-shared-store-sqlite.md` §2.4, S0b-4 slice 2): a directory of the App Group
 * container set **explicitly** to `NSFileProtectionCompleteUntilFirstUserAuthentication` and
 * excluded from backup (`NSURLIsExcludedFromBackupKey`), both **read back** before they count.
 *
 * - The class, because a default is not a guarantee: a background wake on a locked phone must
 *   read and write the store (so not `Complete`), the connection closes and reopens at quiesce
 *   (so not `CompleteUnlessOpen`), and the bundled SQLite has no `SQLITE_OPEN_FILEPROTECTION_*`.
 *   Files created in the directory afterwards take its class (*GK*, the phone pass reads them
 *   back, §13 Q0 (h)); [files] that already exist are set one by one.
 * - The exclusion, because every row is re-pullable and a restored replica is harmful: stale
 *   cursors and an outbound queue for a device identity that does not restore (T-C-04).
 *
 * ⚠ The class is read back only where the platform reports one: the simulator's file system has
 * no Data Protection and answers no class at all, which is accepted there and **refused on a
 * device** ([deviceBuild]) — except a *directory* reporting none while every file in it reports
 * the class, which is accepted with a log (B.21.6 (412): a device that does not report a class on a
 * directory must not refuse every open forever). The exclusion is read back everywhere, from a
 * fresh `NSURL` (one caches its resource values), as `OwnedInferenceStores` and B.18's snapshot do.
 *
 * ⚠⚠ **When** (B.21.6 (410)): the exclusion needs no protected data and is set and read back at
 * every call. The class needs the class keys, which exist from the **first unlock** on, locked or
 * not — so it is set whenever [Platform.classKeysAvailable] (or the caller's
 * `isProtectedDataAvailable`) says so, ⚠ **not** only while the phone is unlocked: a location wake
 * or a `BGTask` on a locked phone opens the store too, and iCloud backup runs exactly then. Before
 * the first unlock both are deferred (the caller's open fails on its own protected-data rule).
 */
class ContainerAtRest(
    private val deviceBuild: Boolean = IS_IOS_DEVICE_BUILD,
    /** A test seam: the three platform calls. */
    internal val platform: Platform = FoundationPlatform,
) {
    /** The calls this needs, so a test can make one fail or answer otherwise. */
    interface Platform {
        fun setProtection(path: String): Boolean
        /** The class the platform reports for [path], or null when it reports none. */
        fun protectionOf(path: String): String?
        fun excludeFromBackup(path: String): Boolean
        fun isExcludedFromBackup(path: String): Boolean
        /**
         * Whether the class keys exist (the first unlock since boot has happened), told by writing
         * and reading back a small `CompleteUntilFirstUserAuthentication` file **beside** [directory]
         * (in its parent, the container, so no store, cache or staging listing ever meets it): that
         * class is readable while locked after the first unlock and never before it.
         */
        fun classKeysAvailable(directory: String): Boolean
        fun exists(path: String): Boolean = NSFileManager.defaultManager.fileExistsAtPath(path)
        /** Makes [path] `0700`, with the class at creation when [withClass]. */
        fun makeDirectory(path: String, withClass: Boolean): Boolean
    }

    /** What [protect] did. */
    enum class Outcome {
        /** Both flags set and read back. */
        PROTECTED,

        /** Before the first unlock: nothing could be set, nothing was refused. */
        DEFERRED_UNTIL_FIRST_UNLOCK,
    }

    /**
     * Makes [directory] (if missing, `0700`) and sets both flags on it, the class on each of [files]
     * that exists too. [protectedDataAvailable] is the caller's `isProtectedDataAvailable`: true
     * means the class keys exist without asking the disk. Throws [NotProtectedAtRest] naming what
     * did not hold; sets everything it can before it throws. Before the first unlock answers
     * [Outcome.DEFERRED_UNTIL_FIRST_UNLOCK] and refuses nothing.
     */
    fun protect(
        directory: String,
        files: List<String> = emptyList(),
        protectedDataAvailable: () -> Boolean = { true },
        log: (String) -> Unit = ::println,
    ): Outcome {
        val failures = ArrayList<String>()
        // The directory first (the exclusion needs it), then whether the class can be set at all.
        val madeOrThere = platform.exists(directory) ||
            platform.makeDirectory(directory, withClass = protectedDataAvailable()) ||
            platform.exists(directory)
        if (!madeOrThere) throw NotProtectedAtRest("$directory could not be made")
        val classUsable = protectedDataAvailable() || platform.classKeysAvailable(directory)
        if (!platform.excludeFromBackup(directory)) failures += "the backup exclusion could not be set on $directory"
        if (!platform.isExcludedFromBackup(directory)) failures += "the backup exclusion did not read back on $directory"
        if (!classUsable) {
            // Before the first unlock: the open fails on its own protected-data rule and is retried at
            // the protected-data notification; the exclusion is tried again then too.
            if (failures.isNotEmpty()) log("[at-rest] before the first unlock, deferred: ${failures.joinToString("; ")}")
            return Outcome.DEFERRED_UNTIL_FIRST_UNLOCK
        }
        var directoryReportsNone = false
        val filesHeld = ArrayList<String>()
        (listOf(directory) + files.filter { platform.exists(it) }).forEach { path ->
            val isDirectory = path == directory
            if (!platform.setProtection(path)) {
                // ⚠ L3: a -wal/-shm a checkpoint or a sibling removed since the existence check is absent.
                if (!isDirectory && !platform.exists(path)) return@forEach
                failures += "the protection class could not be set on $path"
            }
            val held = platform.protectionOf(path)
            when {
                held == EXPECTED_CLASS -> if (!isDirectory) filesHeld += path
                held == null && !isDirectory && !platform.exists(path) -> Unit
                held == null && !deviceBuild -> Unit
                held == null && isDirectory -> directoryReportsNone = true
                else -> failures += "the protection class of $path reads back as ${held ?: "none"}"
            }
        }
        if (directoryReportsNone) {
            // ⚠ L2: a device that reports no class on a directory. Accepted (logged) only when every
            // file in it that exists reads the class back; a file that reports another one refused.
            log("[at-rest] ⚠ $directory reports no protection class; accepted on its files' (${filesHeld.size} read back)")
        }
        if (failures.isNotEmpty()) throw NotProtectedAtRest(failures.joinToString("; "))
        return Outcome.PROTECTED
    }

    /** The production calls. */
    object FoundationPlatform : Platform {
        override fun setProtection(path: String): Boolean = NSFileManager.defaultManager.setAttributes(
            mapOf<Any?, Any?>(NSFileProtectionKey to NSFileProtectionCompleteUntilFirstUserAuthentication),
            ofItemAtPath = path,
            error = null,
        )

        override fun protectionOf(path: String): String? =
            NSFileManager.defaultManager.attributesOfItemAtPath(path, error = null)?.get(NSFileProtectionKey) as? String

        override fun excludeFromBackup(path: String): Boolean =
            NSURL.fileURLWithPath(path, isDirectory = true)
                .setResourceValue(NSNumber(bool = true), forKey = NSURLIsExcludedFromBackupKey, error = null)

        override fun isExcludedFromBackup(path: String): Boolean = memScoped {
            val read = alloc<ObjCObjectVar<Any?>>()
            NSURL.fileURLWithPath(path, isDirectory = true).getResourceValue(read.ptr, forKey = NSURLIsExcludedFromBackupKey, error = null)
            (read.value as? NSNumber)?.boolValue == true
        }

        override fun classKeysAvailable(directory: String): Boolean {
            val probe = "${directory.substringBeforeLast('/')}/$CLASS_PROBE"
            val fm = NSFileManager.defaultManager
            val bytes = @Suppress("CAST_NEVER_SUCCEEDS") (CLASS_PROBE as NSString).dataUsingEncoding(NSUTF8StringEncoding) ?: return false
            if (!fm.fileExistsAtPath(probe) &&
                !fm.createFileAtPath(probe, contents = bytes, attributes = mapOf<Any?, Any?>(NSFileProtectionKey to NSFileProtectionCompleteUntilFirstUserAuthentication))
            ) {
                return false
            }
            return NSData.dataWithContentsOfFile(probe)?.length == bytes.length
        }

        override fun makeDirectory(path: String, withClass: Boolean): Boolean {
            val attributes = mutableMapOf<Any?, Any?>(NSFilePosixPermissions to NSNumber(int = 0x1C0 /* 0700 */))
            if (withClass) attributes[NSFileProtectionKey] = NSFileProtectionCompleteUntilFirstUserAuthentication
            return NSFileManager.defaultManager.createDirectoryAtPath(path, withIntermediateDirectories = true, attributes = attributes, error = null)
        }
    }

    companion object {
        /** `NSFileProtectionCompleteUntilFirstUserAuthentication` as the platform spells it. */
        val EXPECTED_CLASS: String get() = NSFileProtectionCompleteUntilFirstUserAuthentication ?: "NSFileProtectionCompleteUntilFirstUserAuthentication"

        /** [Platform.classKeysAvailable]'s probe file, beside what it protects. */
        const val CLASS_PROBE: String = ".at-rest-probe"
    }
}

/** D3/D3b did not hold on a directory: what failed, by path. */
class NotProtectedAtRest(message: String) : IllegalStateException(message)
