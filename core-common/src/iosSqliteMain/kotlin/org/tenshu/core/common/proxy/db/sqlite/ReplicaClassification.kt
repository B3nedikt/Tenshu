package org.tenshu.core.common.proxy.db.sqlite

import org.tenshu.core.common.schema.migration.CORE_SERVICE_ID

/**
 * What this build declares, for judging a store against it (§5.2, §7): the layout of the
 * iOS-only tables, `:core`'s version (`TENSHU_STORAGE_FORMAT_VERSION`, kept in `user_version`)
 * with its readable floor, and every declared service's version with its floor.
 *
 * A plain value so the open is testable without a schema registry; the binding builds it from
 * `StoreSchemaMigrations` (`coreReadableFloor`, `declaredServiceVersions`, `readableFloorOf`).
 */
data class ReplicaBuild(
    val coreVersion: Int,
    val coreFloor: Int,
    val services: Map<String, Int>,
    /** The readable floor of **every** declared service: a service without one is refused. */
    val serviceFloors: Map<String, Int>,
    val layout: Int = ReplicaSchema.LAYOUT,
) {
    init {
        // A missing floor read as 0 would say "a migration can read any version of this service".
        val without = services.keys - serviceFloors.keys
        require(without.isEmpty()) { "[replica] no readable floor declared for $without" }
        require(coreVersion >= 0 && coreFloor >= 0 && services.values.all { it >= 0 }) { "[replica] a negative version" }
    }
}

/** Which replacement job a store marked "updating" is in the middle of. */
enum class ReplicaJob { WIPE, MIGRATION }

/**
 * What the open read of the frozen bootstrap marker (§5.2 step 2), on the read-only connection
 * and again under the write transaction.
 */
internal data class ReplicaReading(
    val applicationId: Int,
    val userVersion: Int,
    /** `count(*)` of `sqlite_schema`. */
    val schemaObjects: Int,
    /** `meta` exists with its frozen shape `(k, v)`. */
    val hasMeta: Boolean,
    val layout: Int?,
    /**
     * Null when `schema_service_versions` does not exist, has other columns, or holds a row that
     * is not `(text, integer)`. An existing, well-formed table with no rows is an empty map: the
     * two are different answers and [classify] treats them differently.
     */
    val serviceVersions: Map<String, Int>?,
    /**
     * The `wipe` row is there: non-null whatever it holds. Its bytes when it is a blob; **empty
     * when it is anything else** (a text, an integer, an empty blob), which [metaDamage] reports
     * and which is never read as a frame.
     */
    val wipeMarker: ByteArray?,
    /** The `migration` row is there; as [wipeMarker]. */
    val migrationMarker: ByteArray?,
    /**
     * Why a `meta` row is not one this layout writes ([ReplicaSchema.metaRowDamage]), or null. A
     * row that is there but of the wrong storage class is never read as absent, nor as a number.
     */
    val metaDamage: String?,
)

/** The open's judgement of a store against a build. */
internal sealed interface ReplicaClassification {
    /** No schema objects, `application_id` 0, `user_version` 0: the only state that may be created. */
    data object Fresh : ReplicaClassification

    /** Every recorded version equals this build's. */
    data object Compatible : ReplicaClassification

    /**
     * No recorded version is above this build's and at least one is below, or a declared service
     * has no stamp yet. [belowFloor]: no migration can read it, so it is wiped (§5.1) rather than
     * migrated (§5.3). Either needs the foreground to start (S13).
     */
    data class Behind(val belowFloor: Boolean, val detail: String) : ReplicaClassification

    /** A recorded version is above this build's. Nothing is written, migrated or wiped (S12). */
    data class Skew(val detail: String) : ReplicaClassification

    /**
     * The store is on an **older layout** than this build's. Refused, retryably, with nothing
     * validated, started, resumed or wiped and no read/write connection opened: this build knows
     * the schema and the bookkeeping of its own layout only, and what a layout step is has not
     * been designed (Appendix B.3 (33)). ⚠ Judged before damage: held against this layout's
     * rules a healthy older store can read as damaged, and that verdict is permanent.
     */
    data class OlderLayout(val detail: String) : ReplicaClassification

    /** A replacement job's marker is present, on a store this build does not judge newer. */
    data class Updating(val job: ReplicaJob) : ReplicaClassification

    /** Validated structural damage: `CORRUPT`, with the marker. */
    data class Damaged(val detail: String) : ReplicaClassification
}

/**
 * Everything a reading says about a store of ours, against a build: gathered whole by [factsOf],
 * before any verdict. Each list holds one line per fact, in the order layout, core, services.
 */
private class ReplicaFacts(
    /** A recorded version above this build's, or a service this build does not declare (§7 rule 1). */
    val above: List<String>,
    /** The bookkeeping itself is not what this layout writes. */
    val damage: List<String>,
    /** The replacement job a marker says is in progress. */
    val job: ReplicaJob?,
    /** A recorded version below its readable floor: no migration can read it. */
    val belowFloor: List<String>,
    /** A recorded version below this build's, or a declared service with no stamp. */
    val behind: List<String>,
)

/**
 * ⚠⚠ The one judgement of a store's versions, used by the read-only classification and by the
 * re-read under the write transaction, so the two cannot disagree on the same reading.
 *
 * **Facts first, then one derivation.** Whether the file is a store of ours at all is settled
 * up front, since nothing else can be read off one that is not. Then every fact is gathered
 * ([factsOf]): the layout, the core version and every service stamp, each against this build and
 * against its floor, and the markers. The verdict is derived from all of them in one place,
 * below, in this order of precedence:
 *
 * 1. **anything above this build → `Skew`.** The full compatibility check comes first and a
 *    marker never widens it (§5.2 step 2's resumer rule); nothing is judged damaged, and nothing
 *    written, on a newer store.
 * 2. **an older layout → `OlderLayout`**, before anything of that layout is judged by this one's rules.
 * 3. **damaged bookkeeping → `Damaged`.** (The numbers below are one further on.)
 * 3. **a job marker → `Updating`**, whatever is behind: the job in progress is finished first.
 *    ⚠ Only a marker this build may resume gets here: one of another format, or one that takes a
 *    version past this build, was `Skew` at 1, and one that is not a frame was `Damaged` at 2.
 * 4. **anything below its floor → `Behind(belowFloor = true)`**, a wipe. ⚠ *Anything*: the layout,
 *    the core version and every service are all looked at before this is answered. An earlier
 *    form returned "behind, a migration" at the first thing found behind (an older layout), and so
 *    reported a migration for a store no migration chain could read.
 * 5. **anything behind → `Behind(belowFloor = false)`**, a migration.
 * 6. otherwise `Compatible`.
 */
internal fun classify(reading: ReplicaReading, build: ReplicaBuild): ReplicaClassification {
    // ── is it a store of ours at all? ──
    if (reading.schemaObjects == 0 && reading.applicationId == 0 && reading.userVersion == 0) {
        return ReplicaClassification.Fresh
    }
    if (reading.applicationId != ReplicaSchema.APPLICATION_ID) {
        return ReplicaClassification.Damaged("a non-empty file with application_id ${reading.applicationId}")
    }
    // `user_version` is a signed 32-bit header field and a core version is never negative: read
    // as a version, a negative one would be "below the floor" and ask for a wipe.
    if (reading.userVersion < 0) {
        return ReplicaClassification.Damaged("user_version ${reading.userVersion} is not a version")
    }
    val layout = reading.layout
    if (!reading.hasMeta || layout == null) {
        return ReplicaClassification.Damaged("the application_id without meta.layout")
    }

    // ── every fact, then the verdict from all of them ──
    val facts = factsOf(reading, layout, build)
    return when {
        facts.above.isNotEmpty() -> ReplicaClassification.Skew(facts.above.first())
        layout < build.layout ->
            ReplicaClassification.OlderLayout("layout $layout is behind this build's ${build.layout}, and no layout migration exists")
        facts.damage.isNotEmpty() -> ReplicaClassification.Damaged(facts.damage.first())
        facts.job != null -> ReplicaClassification.Updating(facts.job)
        facts.belowFloor.isNotEmpty() -> ReplicaClassification.Behind(belowFloor = true, facts.belowFloor.first())
        facts.behind.isNotEmpty() -> ReplicaClassification.Behind(belowFloor = false, facts.behind.first())
        else -> ReplicaClassification.Compatible
    }
}

/** Gathers every fact of [reading] against [build]. It decides nothing and returns early nowhere. */
private fun factsOf(reading: ReplicaReading, layout: Int, build: ReplicaBuild): ReplicaFacts {
    val above = ArrayList<String>()
    val damage = ArrayList<String>()
    val belowFloor = ArrayList<String>()
    val behind = ArrayList<String>()

    // The layout. A newer one is above this build; an older one is `OlderLayout`, in [classify].
    if (layout > build.layout) above += "layout $layout is above this build's ${build.layout}"

    // The core version.
    val core = reading.userVersion
    if (core > build.coreVersion) above += "core v$core is above this build's v${build.coreVersion}"
    if (core < build.coreFloor) belowFloor += "core v$core is below the floor v${build.coreFloor}"
    if (core < build.coreVersion) behind += "core v$core is below v${build.coreVersion}"

    // Every service stamp.
    // ⚠⚠ Absent is not empty. The table is part of the frozen marker of every layout (§5.2), so
    // without it, or with a row that is not `(text, integer)`, the store is damaged. Read as "no
    // stamps" it would make every declared service look unstamped and send a damaged store into a
    // migration. (On a newer store `above` wins: it is judged by the rest of the marker.)
    val recorded = reading.serviceVersions
    if (recorded == null) {
        damage += "${ReplicaSchema.SERVICE_VERSIONS} is missing or malformed"
    } else {
        for ((service, version) in recorded) {
            val declared = build.services[service]
            if (declared == null) above += "service '$service' (v$version) is unknown to this build"
            else if (version > declared) above += "service '$service' v$version is above this build's v$declared"
        }
        for ((service, declared) in build.services) {
            val version = recorded[service]
            if (version == null) {
                // No stamp yet: behind, and never below a floor.
                behind += "service '$service' has no stamp"
                continue
            }
            if (version < build.serviceFloors.getValue(service)) belowFloor += "service '$service' v$version is below its floor"
            if (version < declared) behind += "service '$service' v$version is below v$declared"
        }
    }

    // `meta`'s other rows. One that is not what this layout writes is damage, and so are both job
    // markers at once: the wipe's first chunk deletes `meta.migration` in the transaction that
    // writes `meta.wipe` (§5.1), so the two never coexist and there is no order to prefer one in.
    reading.metaDamage?.let { damage += it }
    val wipe = reading.wipeMarker != null
    val migration = reading.migrationMarker != null
    if (wipe && migration) damage += "meta holds both a wipe and a migration marker"

    // ⚠⚠ The marker's own half of the resumer rule (§5.2 step 2) is judged HERE, with the rest,
    // so it is answered by the read-only classification: a frame of a format this build does not
    // know, a wipe whose last chunk will stamp past this build, a migration whose targets are
    // past it. Each is "above this build" exactly as a recorded stamp is, and so `Skew` before the
    // foreground is asked for and before any read/write connection is opened (S19). Left to the
    // job's first transaction, a background open answered "needs the foreground" for a store the
    // foreground could only answer "update the app" for, and a foreground open took a read/write
    // connection on a newer build's store to find that out.
    val declared = build.services + (CORE_SERVICE_ID to build.coreVersion)
    fun judge(stamps: Map<String, Int>, what: String) {
        for ((id, version) in stamps) {
            val mine = declared[id]
            if (mine == null) above += "$what names '$id', unknown to this build"
            else if (mine < version) above += "$what takes '$id' to v$version, above this build's v$mine"
        }
    }
    fun <T> frame(key: String, decoded: DecodedMarker<T>): T? = when (decoded) {
        is DecodedMarker.Value -> decoded.marker
        is DecodedMarker.OtherFormat -> {
            above += "meta.$key is a frame of format ${decoded.format}, which this build does not know"
            null
        }
        // An empty value is already in `metaDamage`; this is a frame of our format that is not one.
        DecodedMarker.Malformed -> {
            damage += "meta.$key carries a format this build writes and is not a frame of it"
            null
        }
    }
    // ⚠ Only a row `meta` holds as a frame is read as one, and that is decided ROW BY ROW: a
    // marker row of another storage class, or an empty one, comes here with no bytes (it is in
    // `metaDamage`, and read as a frame its first byte would pass for a format and turn damage
    // into "a newer build's"). Damage in any OTHER row does not stop a marker from being read:
    // a newer build's marker must still be `Skew`, which comes before damage, or this build
    // would write the permanent marker on a store a newer build is working on.
    reading.wipeMarker?.takeIf { it.isNotEmpty() }?.let { bytes ->
        frame(ReplicaSchema.META_WIPE, WipeMarker.decode(bytes))?.let { judge(it.endStamps, "the wipe in progress") }
    }
    reading.migrationMarker?.takeIf { it.isNotEmpty() }?.let { bytes ->
        frame(ReplicaSchema.META_MIGRATION, MigrationMarker.decode(bytes))?.let { judge(it.targets, "the migration in progress") }
    }
    val job = when {
        wipe -> ReplicaJob.WIPE
        migration -> ReplicaJob.MIGRATION
        else -> null
    }
    return ReplicaFacts(above, damage, job, belowFloor, behind)
}
