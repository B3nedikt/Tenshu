package app.tenshu.control.inference

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [MacFaces]' OS-build reader. ⚠ The real decode and Vision path is `MacFacesRealTest` in
 * `:control-desktop`: this module's tests run on JDK 17, where FFM does not exist.
 */
class MacFacesTest {

    @Test
    fun readsTheOsBuildFromTheSystemVersionFile() {
        val plist = File.createTempFile("SystemVersion", ".plist").apply {
            writeText(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <plist version="1.0"><dict>
                    <key>ProductBuildVersion</key>
                    <string>26B123</string>
                    <key>ProductVersion</key>
                    <string>27.2</string>
                </dict></plist>
                """.trimIndent(),
            )
        }
        try {
            assertEquals("26B123", MacFaces.readOsBuild(plist))
            plist.writeText("<plist><dict></dict></plist>")
            assertNull(MacFaces.readOsBuild(plist))
            assertNull(MacFaces.readOsBuild(File("/no/such/SystemVersion.plist")))
        } finally {
            plist.delete()
        }
    }
}
