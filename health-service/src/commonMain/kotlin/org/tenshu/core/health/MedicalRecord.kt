package org.tenshu.core.health

import org.tenshu.core.Composite
import org.tenshu.core.TString
import org.tenshu.core.core.TContext
import org.tenshu.core.core.create
import org.tenshu.core.entity.CompositeDataIdProvider
import org.tenshu.core.entity.Entity
import org.tenshu.core.entity.StringDataIdProviderSchema
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.string
import org.tenshu.core.schema.stringDataId
import org.tenshu.core.trait.SyncStrategy
import org.tenshu.core.trait.Syncable

/**
 * A **FHIR-backed medical record** — a medication / immunization / allergy / condition entry
 * behind the Fit app's Medical Browse category (`docs/proposals/fit-app.md` §5 `MedicalRecord`;
 * `timeline-services.md` §4). Import-only + read-only in v1 (`fit-agent.md` §0.7): the health
 * store carries these, but Fit never authors one — the import seam (FHIR / Health Connect
 * records) does.
 *
 * Keyed by [recordId] (a stable import id — a FHIR resource id in practice) via
 * [MedicalRecordDataIdProvider], mirroring [Workout]'s `workoutId`, so a re-import folds to one
 * row. [kind] and [source] are neutral strings the projector maps to UI enums; [effectiveDate]
 * rides as an ISO-8601 `YYYY-MM-DD` string (as `SleepSession.date` does). `Syncable(Eager)`;
 * `RealmData` deferred (as the sibling health entities also skip it).
 */
interface MedicalRecord : Composite {

    override val schema: CompositeSchema get() = Schema

    /** Stable import id (a FHIR resource id in practice). The primary key. */
    val recordId: TString
        get() = getField(Fields.RECORD_ID)

    /** Neutral record kind — `Medication` / `Immunization` / `Allergy` / `Condition`. */
    val kind: TString
        get() = getField(Fields.KIND)

    /** Human-readable title (e.g. a drug or condition name). */
    val title: TString
        get() = getField(Fields.TITLE)

    /** Supporting detail (dose, schedule, notes); `""` when unset. */
    val detail: TString
        get() = getField(Fields.DETAIL)

    /** The record's effective date — ISO-8601 `YYYY-MM-DD`. */
    val effectiveDate: TString
        get() = getField(Fields.EFFECTIVE_DATE)

    /** Issuing clinician / institution; `""` when unset. */
    val issuer: TString
        get() = getField(Fields.ISSUER)

    /** Where it came from — `HealthKit` / `HealthConnect` / `Fit` / `Manual`. */
    val source: TString
        get() = getField(Fields.SOURCE)

    companion object {
        const val ID = "tenshu/health/medicalRecord"

        val Schema: CompositeSchema = composite(
            id = ID,
            parent = Composite.Schema,
            traits = listOf(MedicalRecordDataIdProvider.Schema, Syncable(SyncStrategy.Eager)),
            fields = {
                string(Fields.RECORD_ID)
                string(Fields.KIND)
                string(Fields.TITLE)
                string(Fields.DETAIL)
                string(Fields.EFFECTIVE_DATE)
                string(Fields.ISSUER)
                string(Fields.SOURCE)
            }
        )

        fun create(
            context: TContext,
            recordId: String,
            kind: String,
            title: String,
            detail: String = "",
            effectiveDate: String,
            issuer: String = "",
            source: String,
        ): MedicalRecord = context.create(
            type = ID,
            fields = listOf(
                recordId,
                kind,
                title,
                detail,
                effectiveDate,
                issuer,
                source,
            )
        )
    }

    object Fields {
        const val RECORD_ID = "$ID/recordId"
        const val KIND = "$ID/kind"
        const val TITLE = "$ID/title"
        const val DETAIL = "$ID/detail"
        const val EFFECTIVE_DATE = "$ID/effectiveDate"
        const val ISSUER = "$ID/issuer"
        const val SOURCE = "$ID/source"
    }
}

val Entity<out MedicalRecord>.recordId: TString
    get() = getFieldValue(MedicalRecord.Fields.RECORD_ID)

/**
 * Content-addresses a [MedicalRecord] by its [MedicalRecord.recordId], so a re-import of the same
 * record — including concurrently on two devices — folds to a single entity rather than duplicating.
 */
interface MedicalRecordDataIdProvider : CompositeDataIdProvider<MedicalRecord> {

    override val schema: StringDataIdProviderSchema get() = Schema

    companion object {
        const val ID = "tenshu/health/medicalRecord/dataIdProvider"

        val Schema: StringDataIdProviderSchema = stringDataId(
            id = ID,
            fieldRef = MedicalRecord.Fields.RECORD_ID
        )
    }
}
