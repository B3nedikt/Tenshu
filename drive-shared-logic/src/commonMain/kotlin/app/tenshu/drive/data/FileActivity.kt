package app.tenshu.drive.data

/**
 * One entry in a file's modification history — the FileDetailsScreen "Activity"
 * section (docs/proposals/drive-file-details-screen.md §3/§4). Mapped from a
 * [org.tenshu.core.file.FileModification] entity by [FileMapper.mapActivity].
 *
 * The authoring device's human name ("on iPhone") is resolved via the
 * `FileModification.Device` reference property (docs/proposals/core-reference-property.md):
 * [deviceName] is the live `Device.name`, falling back to the raw [deviceId] when the
 * device is unknown locally — use [deviceLabel] for display.
 */
data class FileActivity(
    val kind: FileActivityKind,
    /** Subtype detail: `"old → new"` for a rename, `"to /newParent"` for a move; `null` otherwise. */
    val detail: String?,
    /** Epoch-millis of the change — the reverse-chronological sort key + display source. */
    val timestampMillis: Long,
    /** Raw authoring device id — the resolution key + the display fallback. */
    val deviceId: String,
    /** Resolved authoring-device name ("iPhone"), or `null` when the device is unknown locally. */
    val deviceName: String? = null,
) {
    /** Display label for the authoring device — the resolved [deviceName], else the raw [deviceId]. */
    val deviceLabel: String get() = deviceName ?: deviceId
}

/** The kind of change, mapped from the [org.tenshu.core.file.FileModification] subtype. */
enum class FileActivityKind { Created, Modified, Renamed, Moved }
