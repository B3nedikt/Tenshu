package org.tenshu.routine.common.engine

/**
 * The unit of idempotence (`routines-engine.md` RE-D3 / §4.4).
 *
 * Everything in RE-D2 depends on two devices independently agreeing on the
 * *name* of a firing: `routineId` alone is not enough (which sunset?), and a
 * random id defeats the receipt entirely.
 *
 * ⚠ **As-built deviation from RE-D3's `H(routineId ‖ instant)`:** the id is a
 * readable composition rather than a hash. Determinism is the property the
 * design needs, not opacity — and these ids appear in run history, log lines and
 * `DeviceCommand.commandId`s, where "which firing was that?" is a question
 * somebody will have to answer. Ids are realm-internal and E2E-sealed, so there
 * is nothing to hide from. The separator is chosen to be absent from the UUID
 * key space it composes.
 */
object FiringIds {

    /** Not legal in a UUID, so composition is unambiguous. */
    private const val SEPARATOR = "@"
    private const val CHAIN_SEPARATOR = "#"

    /**
     * Scheduled (time/sun) firings. ⚠⚠ [triggerInstantEpochMs] must be computed
     * from the **routine's** timezone and coordinates, never the executing
     * device's, or two devices name the same firing differently and the receipt
     * stops working (RE-D3).
     */
    fun scheduled(routineId: String, triggerInstantEpochMs: Long): String =
        "$routineId$SEPARATOR$triggerInstantEpochMs"

    /**
     * Device-event firings. Only one device is eligible anyway (RE-D1), so the
     * determinism here buys crash-restart idempotence rather than cross-device
     * dedupe.
     */
    fun deviceEvent(routineId: String, deviceId: String, freshAsOfEpochMs: Long): String =
        "$routineId$SEPARATOR$deviceId$SEPARATOR$freshAsOfEpochMs"

    /** Chained firings carry the root so a chain is one traceable unit. */
    fun chained(rootFiringId: String, index: Int): String =
        "$rootFiringId$CHAIN_SEPARATOR$index"

    /** Manual "Run now" — distinct from any scheduled firing of the same routine. */
    fun manual(routineId: String, atEpochMs: Long): String =
        "$routineId${SEPARATOR}manual$SEPARATOR$atEpochMs"
}
