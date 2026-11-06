package app.tenshu.timeline.data

/**
 * A picked Google-Timeline export to import — the raw bytes plus the original name (used for
 * display and future format-sniffing). The bulk-import path ([GoogleTimelineParser]); the picker
 * wizard (I2b) hands these in. Mirrors Fit's `ActivityFile`.
 *
 * v1 accepts the single-JSON new-format export (`Timeline.json` / `location-history.json`) and an
 * already-unzipped legacy `Semantic Location History` JSON (`docs/proposals/timeline-ingestion.md`
 * §8 decided). In-app unzip and CSV are deferred until demand shows.
 */
data class TimelineImportFile(
    val name: String,
    val bytes: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is TimelineImportFile && name == other.name && bytes.contentEquals(other.bytes))

    override fun hashCode(): Int = 31 * name.hashCode() + bytes.contentHashCode()
}
