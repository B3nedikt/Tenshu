package app.tenshu.control.data

import org.tenshu.core.common.app.HandlerTable

/**
 * The *"opens by default"* rows for a set of installed apps and the user's choices — the pure
 * derivation behind [AppRegistry.openHandlerChoices] (`control-cross-app-open.md` TT-4b).
 *
 * ⚠⚠ **Shared by the real registry and the mock deliberately.** Two copies of this would be two
 * chances for the picker to disagree with the resolver, and the mock's copy is what every screen
 * test would then be built on — a fake that agrees with itself is the shape that hides a defect
 * (`fake-models-only-happy`).
 *
 * ⚠ It mirrors `OpenTargetImplementation` rather than reimplementing it: the chosen app moves to
 * the front of the table's order, a chosen app that is not a candidate here is ignored, and what
 * is not installed on this device is not offered.
 *
 * @param chosen `type -> appId`; a missing entry, or an empty value, is *"no preference"*.
 */
internal fun openHandlerChoices(
    apps: List<InstalledApp>,
    chosen: Map<String, String>,
): List<OpenHandlerChoice> {
    val byId = apps.filter { it.installedOnThisDevice }.associateBy { it.appId }
    return HandlerTable.choosableTypes().mapNotNull { type ->
        val here = HandlerTable.handlersFor(type).mapNotNull { byId[it] }
        // ⚠⚠ Fewer than two candidates installed HERE is not a choice: offering it would invite the
        // user to express a preference that can never change anything on this device.
        if (here.size < 2) return@mapNotNull null
        // ⚠ A choice naming an app that is not a candidate here reads as NO choice rather than as
        // itself — the resolver ignores it too (§5j.2), and a picker showing a selection the
        // resolver would not honour is a screen that lies.
        val chosenAppId = chosen[type]?.takeIf { id -> here.any { it.appId == id } }
        OpenHandlerChoice(
            type = type,
            candidates = here.sortedByDescending { it.appId == chosenAppId },
            chosenAppId = chosenAppId,
        )
    }
}
