@file:OptIn(ExperimentalJsExport::class)

package app.tenshu.sdk

import app.tenshu.diagnostics.Diagnostics as Core

/**
 * The device-local diagnostic log (`drive-web-parity.md` W9).
 *
 * ⚠⚠ Why this exists on web specifically: a maintainer cannot attach a debugger to a user's
 * browser — Chrome refuses `--remote-debugging-port` on the default profile — so the only way a
 * problem here becomes legible is if the app kept a record the user can hand over. Before this,
 * the web kept nothing: a few `println`s into a console that a reload erased.
 *
 * Lines are plain text, newest last, and never leave the device unless the user exports them.
 */
@JsExport
object Diagnostics {

    /** The most recent [limit] lines, newest last, as `timestampMs\tLEVEL\ttag\tcode\tmessage`. */
    fun recent(limit: Int = 500): Array<String> =
        Core.recent(limit).map { "${it.timestampMs}\t${it.level.name}\t${it.tag}\t${it.code.orEmpty()}\t${it.message}" }
            .toTypedArray()

    /** Everything, as one blob ready to drop in a text file or a bug report. */
    fun exportText(): String = recent(Int.MAX_VALUE).joinToString("\n")

    /** Drop the log (a user "clear" action). */
    fun clear() { Core.clear() }
}
