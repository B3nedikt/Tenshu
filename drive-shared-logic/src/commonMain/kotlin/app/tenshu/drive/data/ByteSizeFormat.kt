package app.tenshu.drive.data

import org.tenshu.storage.common.formatStorageBytes

/**
 * Human-readable byte size — `"221 B"`, `"1.8 KB"`, `"45 MB"`.
 *
 * Drive's **one** size formatter: the file rows, the details screen, the sync banner's
 * *"… · 45 MB left"*, the synced-folders settings and the folder-upload pre-flight all render
 * through it. Two different renderings of the same number on one screen is exactly the kind of
 * detail that reads as a bug, so don't add a private copy next to a call site.
 *
 * ⚠ A thin name over the platform-wide `formatStorageBytes` (`:storage-service-common`) — binary
 * steps, "B/KB/MB/GB/…", one decimal dropped when `.0`. Kept because Drive's call sites and
 * `drive-ios` (`ByteSizeFormatKt.formatByteSize`) already use this name; the rules live there.
 */
fun formatByteSize(sizeInBytes: Long): String = formatStorageBytes(sizeInBytes)
