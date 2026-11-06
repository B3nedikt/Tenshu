package org.tenshu.sdk.ios

import app.tenshu.control.data.ShareSummary
import app.tenshu.control.data.SharedFolderSummary
import org.tenshu.core.common.util.TimestampUtil
import kotlin.time.Instant

/**
 * One outbound link share, flattened for the SwiftUI inventory.
 *
 * `ShareSummary` itself bridges (it is a plain data class), so this exists for the same
 * reason `ControlSurface.syncDevices` does: the **formatting** belongs Kotlin-side. The
 * Compose inventory and this one exist to describe the same realm-wide list, and they only
 * agree if one implementation renders the date and normalises the caps.
 */
class ControlShareRow internal constructor(
    val id: String,
    /** The share's leaf file name, already split from the path. */
    val fileName: String,
    /** The full realm path, shown under the name. */
    val filePath: String,
    /** Absolute, localized — the same [TimestampUtil] the Compose row uses. */
    val createdLabel: String,
    /**
     * "Expires <date>" / "Up to N opens" style facts, pre-resolved to a single line or empty.
     * ⚠ Not copy in the ADR-0016 sense being smuggled Kotlin-side: the *dates* must format
     * once, and splitting one line half-Kotlin half-Swift is how the two platforms drift.
     */
    val capsLabel: String,
    val revoked: Boolean,
)

/**
 * One shared folder (owned or joined), flattened for SwiftUI.
 *
 * ⚠ [name] and [memberCount] stay **nullable across the bridge on purpose** — they are the
 * two distinct unknowns `SharedFolderSummary` documents (an unsynced wrapper root, and a
 * roster read that failed). Collapsing either into `""`/`0` here would hand Swift the
 * "0 members" lie the Compose row was written to avoid. Swift sees `String?` / `KotlinInt?`
 * and branches, exactly as the Compose row does.
 */
class ControlSharedFolderRow internal constructor(
    val id: String,
    val name: String?,
    val memberCount: Int?,
    val ownerSelf: Boolean,
)

@OptIn(kotlin.time.ExperimentalTime::class)
internal fun shareRow(summary: ShareSummary): ControlShareRow {
    val caps = buildList {
        summary.expiresAtMillis?.let {
            add("Expires " + TimestampUtil.formatTimestamp(Instant.fromEpochMilliseconds(it)))
        }
        summary.maxOpens?.let { add(if (it == 1) "Up to 1 open" else "Up to $it opens") }
    }
    return ControlShareRow(
        id = summary.shareId,
        fileName = summary.fileName,
        filePath = summary.filePath,
        createdLabel = TimestampUtil.formatTimestamp(
            Instant.fromEpochMilliseconds(summary.createdAtMillis)
        ),
        capsLabel = caps.joinToString(" · "),
        revoked = summary.revoked,
    )
}

internal fun sharedFolderRow(summary: SharedFolderSummary): ControlSharedFolderRow =
    ControlSharedFolderRow(
        id = summary.realmId,
        name = summary.name,
        memberCount = summary.memberCount,
        ownerSelf = summary.ownerSelf,
    )
