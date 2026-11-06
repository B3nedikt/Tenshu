package app.tenshu.reachability

/**
 * Thrown by a realm-gated **write boundary** when [ControlReachability.realmWritesHeld] is true
 * (`ADR 0025` §8.6): the synced write or delete it was about to make is refused, so nothing lands
 * in a store that may belong to no realm.
 *
 * ⚠⚠ Every synced write a gated pipeline can reach goes through a writer that calls the gate
 * before each op — `HealthImportWriter`, `HealthImportRunRecorder`, detection supersession,
 * `TimelineImportWriter`, `PlaceLabelEnricher.enrich` — so the pipeline does not check call site by
 * call site. The pipeline catches this ONCE: an import pass counts as held and re-runs on the next
 * release; an outbox drain keeps the entry (it is removed only after every write in it landed).
 *
 * ⚠ Not a `CancellationException`: a refused write is an outcome the pipeline must see and record,
 * not a cancellation its scope should absorb. Anything that swallows exceptions around a gated
 * write must rethrow this one.
 */
class RealmWritesHeldException : Exception("Control's realm gate holds writes (no realm here, or not yet known)")
