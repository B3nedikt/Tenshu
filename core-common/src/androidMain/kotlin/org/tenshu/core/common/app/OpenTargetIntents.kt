package org.tenshu.core.common.app

import android.content.Intent
import org.tenshu.core.Data
import org.tenshu.core.app.OpenTargetRequest
import org.tenshu.core.common.parser.BinaryParser
import org.tenshu.core.entity.DataId

/**
 * The Android cold-start carrier for a cross-app open (`control-cross-app-open.md` CO-D3, CO-D8).
 *
 * ⚠⚠ **One name, spelled once.** The extra is a wire contract between Control's launcher and every
 * handling app, and the two halves live in different modules and different processes — a second
 * spelling would not fail to compile, it would fail to *arrive*, and it would look exactly like a
 * launch that worked and an app that ignored it.
 *
 * ⭐ The payload is the serialised [OpenTargetRequest], not a rendered string: which `DataId`
 * variant this is — `StringDataId`, `CompositeEntityDataId`, `EntityFieldDataId` — survives the
 * trip, which is the whole reason CO-D5 could delete the `locator` parameter.
 */
object OpenTargetIntents {

    const val EXTRA: String = "app.tenshu.control.OPEN_TARGET"

    /**
     * The target this intent carries, or `null` for an ordinary launch.
     *
     * ⚠⚠ **[parser] is a provider, and that is not a style choice — it is a measured fix.** Passing
     * `get<BinaryParser>()` by value resolves the client graph on *every* launch, including the
     * overwhelmingly common one with no extra at all. In Fit that pulled the graph up ahead of
     * `wireSchemaAnnounce`'s preflight and the Home screen's first workouts read came back
     * `unannouncedSession` — a real IPC failure, surfaced by the smoke gate, caused entirely by an
     * argument that was evaluated too eagerly to be needed.
     *
     * ⚠⚠ Returns null rather than throwing on a payload that will not parse. The realistic cause is
     * a version skew — Control newer than the app it launched — and a handling app that crashed on
     * one would turn a stale install into an app that dies on every open. It shows its normal
     * launch screen instead, which is wrong but survivable.
     */
    fun targetFrom(intent: Intent?, parser: () -> BinaryParser): DataId? {
        val bytes = intent?.getByteArrayExtra(EXTRA) ?: return null
        val parsed = runCatching { parser().parse<Data>(bytes) }.getOrNull()
        return (parsed as? OpenTargetRequest)?.target
    }
}
