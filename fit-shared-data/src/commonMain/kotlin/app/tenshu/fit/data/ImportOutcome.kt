package app.tenshu.fit.data

import app.tenshu.fit.model.ImportNotice

/**
 * What a [HealthImporter] read attempt *concluded* — not merely what it returned.
 *
 * [HealthImporter.import] used to hand back a bare [ImportedHealth], which collapsed four very
 * different situations into the same empty list: the source held no data, the user never granted
 * the read permissions, the platform has no health store at all, or the importer quietly served a
 * built-in fixture instead. The repository could not tell them apart, so neither could the user —
 * every one of them rendered as a blank screen.
 *
 * Failure is deliberately *not* a case here: an importer that breaks should throw, and the
 * repository maps the exception to `ImportStatus.Failed`. Making it a return value invites
 * importers to swallow their own errors, which is exactly the habit this type exists to break.
 */
sealed interface ImportOutcome {

    /** The rows this attempt produced. Empty for the outcomes that, by their nature, carry none. */
    val health: ImportedHealth

    /**
     * The source was read under real consent. [health] may still be empty — that is real data too.
     *
     * [notice] is the importer's chance to say something the row count cannot: the read was honest,
     * but the data the user is looking for lives somewhere this source cannot see. Only the platform
     * importer can know that — it is the only layer that sees *which apps wrote* what it read — so
     * the finding is carried out here rather than re-derived downstream from a suspiciously small
     * number, which would be a guess.
     */
    data class Success(
        override val health: ImportedHealth,
        val notice: ImportNotice? = null,
    ) : ImportOutcome

    /**
     * [health] is a **built-in fixture**, not the user's data — the reference importer, or a real
     * importer falling back to it because no live source exists. Carried explicitly so the UI can
     * label it: an unlabelled fixture reads as real, which is a worse failure than showing nothing.
     *
     * ⚠⚠ Never persisted: `FitProjectionRepository` refuses it and reports no source
     * (`fit-app.md` §9.15). No shipping importer returns it any more — only [ReferenceHealthImporter].
     */
    data class Sample(override val health: ImportedHealth) : ImportOutcome

    /** The source is present but the read permissions it needs were not granted. */
    data class PermissionsRequired(val missing: Set<String>) : ImportOutcome {
        override val health: ImportedHealth = ImportedHealth()
    }

    /** There is no live health source on this platform / device. */
    data object SourceUnavailable : ImportOutcome {
        override val health: ImportedHealth = ImportedHealth()
    }
}
