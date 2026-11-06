package app.tenshu.drive.ui

import org.tenshu.core.entity.DataId
import org.tenshu.core.file.File
import org.tenshu.core.get

/**
 * The file path a cross-app open names, or `null` if this target is not Drive's to show
 * (`control-cross-app-open.md` TT-2a).
 *
 * ⚠⚠ **The type check is the load-bearing half, not the key extraction.** Control routes by type
 * (CO-D2), so receiving a non-file target means the handler table and the receiving app disagree —
 * and the tempting shortcut, reading `keyValue` off whatever arrives, would turn that disagreement
 * into *opening the wrong screen with a `placeId` in the title*. Ignoring it keeps the bug where it
 * belongs: visible as a tap that refuses, rather than hidden as a tap that misleads.
 *
 * ⚠ A file is addressed by a plain `StringDataId` whose key is its path. A multi-part id is not a
 * file, whatever its type says.
 */
internal fun filePathFromTarget(target: DataId): String? =
    (target as? DataId.StringDataId)
        ?.takeIf { it.type.get() == File.ID }
        ?.keyValue?.get()
        ?.takeIf { it.isNotEmpty() }
