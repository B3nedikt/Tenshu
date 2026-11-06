package org.tenshu.sdk.ios

import app.tenshu.ingest.backup.SyncedFolderController
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * P6b coverage (ios-drive-parity.md): the pure `phasset://` URI codec that bridges the PhotoKit
 * source ([IosMediaSourceProvider]) to the delegate byte-read ([IosFilesRepositoryPlatformDelegate]).
 * The engine (`SyncedFolderController` gate/cursor/drain) is covered cross-platform
 * (`SyncedFolderControllerTest`, desktopTest); PhotoKit enumeration + asset reads are framework-level
 * → the P7 on-device pass. This pins the one bit of new pure logic: the source and the delegate must
 * agree on the URI shape, or a backed-up asset can't be read back.
 */
class IosPhotoLibraryTest {

    @Test
    fun uriRoundTripsThroughTheLocalIdentifier() {
        val localId = "ABCD-1234/L0/001"
        val uri = IosPhotoLibrary.uriFor(localId)
        assertEquals("phasset://$localId", uri)
        assertEquals(localId, IosPhotoLibrary.localIdentifierOf(uri))
    }

    @Test
    fun nonPhassetUrisAreNotClaimed() {
        assertNull(IosPhotoLibrary.localIdentifierOf("file:///tmp/report.txt"))
        assertNull(IosPhotoLibrary.localIdentifierOf("/var/mobile/x.jpg"))
        assertNull(IosPhotoLibrary.localIdentifierOf("phasset://"), "empty identifier is not a valid source")
    }
}
