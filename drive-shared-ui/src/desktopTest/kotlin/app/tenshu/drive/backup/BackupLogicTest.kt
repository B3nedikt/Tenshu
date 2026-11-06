package app.tenshu.drive.backup

import app.tenshu.ingest.backup.backupConstraintsAllow
import app.tenshu.ingest.backup.BackupCursors
import app.tenshu.ingest.backup.BackupNetworkStatus
import app.tenshu.ingest.backup.StorageStatusModel
import app.tenshu.ingest.backup.backupConstraintsAllow
import app.tenshu.ingest.backup.BackupGate
import app.tenshu.ingest.backup.BackupPauseReason
import app.tenshu.ingest.backup.BackupSettings
import app.tenshu.ingest.backup.backupDestinationFolder
import app.tenshu.ingest.backup.documentTreeDisplayName
import app.tenshu.ingest.backup.evaluateBackupGate
import kotlin.test.Test
import kotlin.test.assertEquals

/** Pure auto-backup decision logic (cursor codec, constraint gate, destination). */
class BackupLogicTest {

    // --- BackupCursors codec (§3.5) ---

    @Test
    fun cursorCodec_roundTrips() {
        val map = mapOf("bucket-1" to "42", "/home/me/Pictures/Camera" to "1700000000000")
        assertEquals(map, BackupCursors.decode(BackupCursors.encode(map)))
    }

    @Test
    fun cursorCodec_dropsMalformedAndEmptyKeys() {
        // A bare entry with no separator, and an empty key, are both dropped.
        assertEquals(emptyMap(), BackupCursors.decode(listOf("noseparator", "")))
    }

    @Test
    fun cursorCodec_emptyMapEncodesEmpty() {
        assertEquals(emptyList(), BackupCursors.encode(emptyMap()))
    }

    // --- backupConstraintsAllow (§3.8) ---

    @Test
    fun constraints_noNetwork_neverAllows() {
        assertEquals(
            false,
            backupConstraintsAllow(
                wifiOnly = false, chargingOnly = false,
                network = BackupNetworkStatus.NONE, charging = true,
            ),
        )
    }

    @Test
    fun constraints_wifiOnly_blocksMetered_allowsUnmetered() {
        assertEquals(
            false,
            backupConstraintsAllow(true, false, BackupNetworkStatus.METERED, true),
        )
        assertEquals(
            true,
            backupConstraintsAllow(true, false, BackupNetworkStatus.UNMETERED, true),
        )
    }

    @Test
    fun constraints_chargingOnly_requiresCharging() {
        assertEquals(
            false,
            backupConstraintsAllow(false, true, BackupNetworkStatus.UNMETERED, false),
        )
        assertEquals(
            true,
            backupConstraintsAllow(false, true, BackupNetworkStatus.UNMETERED, true),
        )
    }

    @Test
    fun constraints_meteredAllowedWhenWifiOnlyOff() {
        assertEquals(
            true,
            backupConstraintsAllow(false, false, BackupNetworkStatus.METERED, false),
        )
    }

    // --- evaluateBackupGate (§3.6 + §3.8) ---

    private fun settings(
        enabled: Boolean = true,
        wifiOnly: Boolean = false,
        chargingOnly: Boolean = false,
    ) = BackupSettings(
        enabled = enabled,
        wifiOnly = wifiOnly,
        chargingOnly = chargingOnly,
    )

    private fun storage(has: Boolean, used: Long = 0, limit: Long = 100) =
        StorageStatusModel(hasStorage = has, usedBytes = used, limitBytes = limit)

    @Test
    fun gate_disabledWhenMasterOff() {
        assertEquals(
            BackupGate.Disabled,
            evaluateBackupGate(settings(enabled = false), storage(true), BackupNetworkStatus.UNMETERED, true),
        )
    }

    @Test
    fun gate_noStorage_pausesNoStorage() {
        assertEquals(
            BackupGate.Paused(BackupPauseReason.NO_STORAGE),
            evaluateBackupGate(settings(), null, BackupNetworkStatus.UNMETERED, true),
        )
        assertEquals(
            BackupGate.Paused(BackupPauseReason.NO_STORAGE),
            evaluateBackupGate(settings(), storage(false), BackupNetworkStatus.UNMETERED, true),
        )
    }

    @Test
    fun gate_fullStorage_pausesOutOfSpace() {
        assertEquals(
            BackupGate.Paused(BackupPauseReason.OUT_OF_SPACE),
            evaluateBackupGate(
                settings(), storage(true, used = 100, limit = 100), BackupNetworkStatus.UNMETERED, true,
            ),
        )
    }

    /**
     * ⚠⚠ The measured bug: **836 bytes free on a 5 GiB tier** (2026-09-06). `remainingBytes > 0`,
     * so the old `isFull` said "not full" and auto-backup kept enqueuing files the relay refused
     * one after another. A sliver too small for the next chunk is *functionally* full, and only the
     * relay's own `507` can say where that boundary is — the figures alone cannot.
     */
    @Test
    fun gate_relayRefusedForSpace_pausesOutOfSpace_evenWithBytesNominallyRemaining() {
        val sliver = StorageStatusModel(
            hasStorage = true,
            usedBytes = 5_368_708_284L,
            limitBytes = 5_368_709_120L, // 836 bytes free — the real phone's figures
            relayRejected = true,
        )
        assertEquals(
            BackupGate.Paused(BackupPauseReason.OUT_OF_SPACE),
            evaluateBackupGate(settings(), sliver, BackupNetworkStatus.UNMETERED, true),
            "a realm the relay is refusing must pause backup, whatever the arithmetic says",
        )
    }

    /**
     * The other half, so the flag cannot be read as "pause whenever bytes are tight": without the
     * relay having actually refused, a sliver is still allowed to try. This is what keeps the fix
     * from becoming a guessed headroom constant that stops uploads which would have succeeded.
     */
    @Test
    fun gate_sliverOfSpaceWithNoRelayRefusal_stillAllowsBackupToTry() {
        val sliver = StorageStatusModel(
            hasStorage = true,
            usedBytes = 5_368_708_284L,
            limitBytes = 5_368_709_120L,
        )
        assertEquals(
            BackupGate.Allowed,
            evaluateBackupGate(settings(), sliver, BackupNetworkStatus.UNMETERED, true),
        )
    }

    @Test
    fun gate_meteredUnderWifiOnly_pausesMetered() {
        assertEquals(
            BackupGate.Paused(BackupPauseReason.METERED_NETWORK),
            evaluateBackupGate(settings(wifiOnly = true), storage(true), BackupNetworkStatus.METERED, true),
        )
    }

    @Test
    fun gate_notCharging_pausesCharging() {
        assertEquals(
            BackupGate.Paused(BackupPauseReason.NOT_CHARGING),
            evaluateBackupGate(settings(chargingOnly = true), storage(true), BackupNetworkStatus.UNMETERED, false),
        )
    }

    @Test
    fun gate_noNetwork_pausesNetwork() {
        assertEquals(
            BackupGate.Paused(BackupPauseReason.NO_NETWORK),
            evaluateBackupGate(settings(), storage(true), BackupNetworkStatus.NONE, true),
        )
    }

    @Test
    fun gate_allConditionsMet_allowed() {
        assertEquals(
            BackupGate.Allowed,
            evaluateBackupGate(
                settings(wifiOnly = true, chargingOnly = true),
                storage(true), BackupNetworkStatus.UNMETERED, charging = true,
            ),
        )
    }

    // --- backupDestinationFolder (§4.5) ---

    @Test
    fun destination_buildsBackupDevicePath() {
        assertEquals("/Backup/device-7/Camera", backupDestinationFolder("device-7", "Camera"))
    }

    @Test
    fun destination_sanitisesSeparators() {
        // A bucket name containing a slash can't escape the /Backup/<device>/ root.
        assertEquals("/Backup/dev/WhatsApp_Images", backupDestinationFolder("dev", "WhatsApp/Images"))
        assertEquals("/Backup/unknown/Camera", backupDestinationFolder("   ", "Camera"))
    }

    // --- documentTreeDisplayName (Phase 3b — SAF tree URI → friendly name) ---

    @Test
    fun documentTreeName_extractsDecodedSegmentAfterColon() {
        assertEquals(
            "Documents",
            documentTreeDisplayName(
                "content://com.android.externalstorage.documents/tree/primary%3ADocuments",
            ),
        )
        // Nested tree path: take the leaf after the decoded separators.
        assertEquals(
            "Camera",
            documentTreeDisplayName(
                "content://com.android.externalstorage.documents/tree/primary%3ADCIM%2FCamera",
            ),
        )
    }

    @Test
    fun documentTreeName_fallsBackToRawWhenUnparseable() {
        assertEquals("plain", documentTreeDisplayName("plain"))
    }
}
