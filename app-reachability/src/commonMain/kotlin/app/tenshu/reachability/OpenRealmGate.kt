package app.tenshu.reachability

/**
 * An **explicitly open** realm write gate (`ADR 0025` §8.6) — for the write paths the wall already
 * covers (a user act can only happen with the shell showing, i.e. a realm present or a probe that
 * is not wired) and for tests.
 *
 * ⚠⚠ Never a parameter default. Every gate parameter on a write path is required, so the compiler
 * lists every caller and none can fall back to an inert no-op by omission — the trap that let
 * `AutoDetectBootstrap`'s supersession skip the gate (Codex P1 on d2a13fe22). Passing this is a
 * decision written at the call site, with the reason beside it.
 */
val OPEN_REALM_GATE: suspend () -> Unit = {}
