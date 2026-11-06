package org.tenshu.mia.plan

/**
 * Whether the runtime answering this turn may **offer an action** — a device command, a routine
 * run, a new calendar event (`mia-query-layer.md` §19).
 *
 * ⚠⚠ **A fact about the runtime, not a user setting**, which is why it is not a `mia.grant.*`
 * row. A grant says what the user allows; this says what the process hosting the proxy can
 * honestly carry out. An iPhone's route-1 runtime never executes a routine
 * (`routines-engine.md` RE-D13), so a confirmation cell there would be a button whose tap is
 * guaranteed to fail — and the rule this track already follows for a suspended routine and a
 * missing default calendar is to refuse *before* showing the cell.
 *
 * ⚠ Required wherever it is passed, with no default. A default would let a new platform's proxy
 * module compile into whichever posture the default named without anyone deciding it.
 *
 * ⚠ Plain Kotlin, like [QueryPlan]: it never crosses the wire, so it is not a schema constant.
 */
enum class MiaActionOffer {
    /** Actions resolve to MQ-D7's confirmation cell. Android and desktop. */
    OFFERED,

    /**
     * No action is planned, so none is resolved, shown or given a token; the utterance is
     * answered with [QueryPlan.ActionWithheld]. iOS route 1.
     */
    WITHHELD,
}
