package org.tenshu.core.ingest

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class SecurityScopedFolderGrantTest {
    private val id = "0123456789abcdef0123456789abcdef"

    @Test
    fun roundTripsGrantIdAndUnicodeName() {
        val decoded = SecurityScopedFolderGrant.decode(
            SecurityScopedFolderGrant.encode(grantId = id, displayName = "Fotos & Vidéos"),
        )

        requireNotNull(decoded)
        assertEquals(id, decoded.grantId)
        assertEquals("Fotos & Vidéos", decoded.displayName)
    }

    @Test
    fun rejectsPathsAndMalformedPayloads() {
        assertNull(SecurityScopedFolderGrant.decode("/Users/example/Pictures"))
        assertNull(SecurityScopedFolderGrant.decode("tenshu.security-scoped-folder.v2:$id:"))
        assertNull(SecurityScopedFolderGrant.decode("tenshu.security-scoped-folder.v2:$id:zz"))
        // The v1 form carried the bookmark bytes; it is not a v2 handle.
        assertNull(SecurityScopedFolderGrant.decode("tenshu.security-scoped-folder.v1:00ff:6162"))
    }

    /** The id names a file in Control's container, so anything but 32 hex digits is refused. */
    @Test
    fun refusesGrantIdsThatCouldEscapeTheStore() {
        assertNull(SecurityScopedFolderGrant.decode("tenshu.security-scoped-folder.v2:../../etc:6162"))
        assertNull(SecurityScopedFolderGrant.decode("tenshu.security-scoped-folder.v2:${id.uppercase()}:6162"))
        assertFailsWith<IllegalArgumentException> { SecurityScopedFolderGrant.encode("../x", "a") }
    }
}
