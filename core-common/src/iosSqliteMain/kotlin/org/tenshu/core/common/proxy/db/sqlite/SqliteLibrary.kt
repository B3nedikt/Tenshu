@file:OptIn(ExperimentalForeignApi::class)

package org.tenshu.core.common.proxy.db.sqlite

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import org.tenshu.core.common.proxy.db.sqlite.c.SQLITE_SOURCE_ID
import org.tenshu.core.common.proxy.db.sqlite.c.SQLITE_VERSION_NUMBER
import org.tenshu.core.common.proxy.db.sqlite.c.sqlite3_libversion
import org.tenshu.core.common.proxy.db.sqlite.c.sqlite3_libversion_number
import org.tenshu.core.common.proxy.db.sqlite.c.sqlite3_sourceid

/** The linked SQLite is not a build the shared store may run on. Nothing was opened or created. */
class SqliteUnfixedLibraryException(message: String) : IllegalStateException(message)

/**
 * The version gate of `docs/proposals/ios-shared-store-sqlite.md` §2.1.2.
 *
 * ⚠⚠ The shared store is the pattern the WAL-reset bug needs (several connections on one WAL,
 * writing and checkpointing at once), so it runs only on a SQLite that has the fix: 3.51.3 or
 * later, or the backports 3.50.7+ and 3.44.6+. On anything else a checkpoint can skip frames and
 * corrupt the database, so [SqliteConnection.open] refuses before it touches a file.
 *
 * It also refuses a library that is not the vendored build (the header this module was compiled
 * against and the library that got linked must be the same source), which is what catches a second
 * SQLite winning the link, the system's included.
 */
object SqliteLibrary {
    /** `sqlite3_libversion_number()` of the library actually linked. */
    val linkedVersionNumber: Int get() = sqlite3_libversion_number()

    /** `sqlite3_libversion()`, for logs. */
    val linkedVersion: String get() = sqlite3_libversion()?.toKString().orEmpty()

    /** `sqlite3_sourceid()` of the library actually linked. */
    val linkedSourceId: String get() = sqlite3_sourceid()?.toKString().orEmpty()

    /** The vendored header's version: what this module was compiled against. */
    val vendoredVersionNumber: Int get() = SQLITE_VERSION_NUMBER

    /** The vendored header's source id. */
    val vendoredSourceId: String get() = SQLITE_SOURCE_ID

    /** Does [versionNumber] (`X*1000000 + Y*1000 + Z`) carry the WAL-reset fix? */
    fun isFixedVersion(versionNumber: Int): Boolean =
        versionNumber >= 3_051_003 ||
            versionNumber in 3_050_007..3_050_999 ||
            versionNumber in 3_044_006..3_044_999

    /**
     * Why a library reporting [linkedVersionNumber] and [linkedSourceId] must not be opened, or
     * null if it may. Pure, so the refusal is testable without linking an unfixed SQLite.
     */
    fun refusal(
        linkedVersionNumber: Int,
        linkedSourceId: String,
        vendoredVersionNumber: Int = SQLITE_VERSION_NUMBER,
        vendoredSourceId: String = SQLITE_SOURCE_ID,
    ): String? = when {
        !isFixedVersion(linkedVersionNumber) ->
            "SQLite $linkedVersionNumber has the WAL-reset bug (fixed in 3.51.3, 3.50.7, 3.44.6); " +
                "source id '$linkedSourceId'"
        linkedVersionNumber != vendoredVersionNumber || linkedSourceId != vendoredSourceId ->
            "the linked SQLite ($linkedVersionNumber, '$linkedSourceId') is not the vendored build " +
                "($vendoredVersionNumber, '$vendoredSourceId'): a second SQLite is in the link"
        else -> null
    }

    private val verdict: String? by lazy { refusal(linkedVersionNumber, linkedSourceId) }

    /** Throws unless the linked SQLite is the vendored, fixed build. Checked once per process. */
    fun requireFixedBuild() {
        verdict?.let { throw SqliteUnfixedLibraryException(it) }
    }
}
