@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.tenshu.sdk.ios

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.readBytes
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.writeToFile
import platform.posix.O_RDONLY
import platform.posix.close
import platform.posix.open
import platform.posix.read

/**
 * ⚠⚠ **Control declares, the apps follow** (`ios-shared-store-sqlite.md` Appendix B.14 (190)): a
 * route-1 app binds the shared SQLite store only once Control has bound it, and waits on the
 * not-ready stand-in until then.
 *
 * - Control writes [declarationPath] with what it did: [SQLITE] once its SQLite open handed out a
 *   store, [RESET] at its forget (the apps wait again until its next bound store declares `sqlite`).
 * - A route-1 app binds the SQLite store only on a [ReplicaChoice.SQLITE] reading, and each bound
 *   process reads it again at every foreground (B.14 (196)).
 *
 * ⚠ Until the cleanup of 2026-11-05 (B.22.9) the declaration also recorded the old shared file
 * store's directory (`objects/`), so that a write there turned the reading off, and Control could
 * declare `files`. That store is gone, and with it both: a line written by an older build reads by
 * its first word.
 *
 * Plain text, no secret, beside `replica/` in the App Group container (not inside `replica/`, whose
 * files D14's deletion removes). Read through one descriptor; every failure to read is "not
 * declared": the safe direction is the stand-in.
 */
internal class SharedReplicaChoice(
    /** `<group container>/replica.choice`. */
    val declarationPath: String,
    private val log: (String) -> Unit = ::println,
) {
    /** What Control has declared. */
    fun read(): ReplicaChoice {
        val text = readDeclaration() ?: return ReplicaChoice.UNDECLARED
        return when (text.trim().split(' ').firstOrNull()) {
            SQLITE -> ReplicaChoice.SQLITE
            RESET -> ReplicaChoice.RESET
            else -> ReplicaChoice.UNDECLARED
        }
    }

    /** Control's declaration. A failure is logged and leaves the old one. */
    fun declare(value: String) {
        require(value == SQLITE || value == RESET) { "not a declaration: $value" }
        val line = "$value\n"
        @Suppress("CAST_NEVER_SUCCEEDS")
        val written = (line as NSString).writeToFile(declarationPath, atomically = true, encoding = NSUTF8StringEncoding, error = null)
        if (!written) log("[replica-choice] ⚠ the declaration '$value' could not be written to $declarationPath")
    }

    /** The declaration's text, read through one descriptor (an atomic rename replaces it whole), or null. */
    private fun readDeclaration(): String? = memScoped {
        val fd = open(declarationPath, O_RDONLY)
        if (fd < 0) return@memScoped null
        try {
            val buffer = allocArray<ByteVar>(MAX_DECLARATION_BYTES)
            val count = read(fd, buffer, MAX_DECLARATION_BYTES.convert()).toInt()
            if (count <= 0) return@memScoped null
            buffer.readBytes(count).decodeToString()
        } finally {
            close(fd)
        }
    }

    companion object {
        const val SQLITE: String = "sqlite"
        const val RESET: String = "reset"
        private const val MAX_DECLARATION_BYTES = 256

        /** The production one, in the App Group container. */
        fun production(): SharedReplicaChoice = SharedReplicaChoice(
            declarationPath = org.tenshu.core.common.proxy.db.sharedReplicaDirectory(DECLARATION_NAME),
        )

        const val DECLARATION_NAME: String = "replica.choice"
    }
}

/** A reading of [SharedReplicaChoice]. Only [SQLITE] lets a route-1 app bind the SQLite store. */
internal enum class ReplicaChoice {
    /** Control declared the SQLite store. */
    SQLITE,

    /** Control's forget reset the choice: no app binds until Control's next bound store declares `sqlite`. */
    RESET,

    /** No declaration yet (or it is unreadable). */
    UNDECLARED,
}
