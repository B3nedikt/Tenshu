@file:OptIn(ExperimentalJsExport::class)

package app.tenshu.sdk

import app.tenshu.sdk.internal.toJs
import org.tenshu.core.SimpleComposite
import org.tenshu.core.collection.list.SimpleTList
import org.tenshu.core.common.impl.UnixInstantDto
import org.tenshu.core.file.File
import org.tenshu.core.file.Folder
import org.tenshu.core.number.toTInteger
import org.tenshu.core.toTString
import kotlin.js.Date

@JsExport
object Files {

    // Prototype demo: builds a File-shaped SimpleComposite (path/size/
    // lastModified) and runs it through the schema walker. Path uses the
    // generic SimpleTString factory rather than a real FilePath; the schema
    // walker only cares that it's a TString. See docs/web/architecture.md
    // §12.9.
    fun demo(): dynamic = simpleFile(
        path = "/demo/Photo.jpg",
        size = 4L * 1024 * 1024,                  // 4 MB
        lastModifiedMs = Date.now().toLong(),
    ).toJs()
}

@JsExport
object Folders {

    // Prototype demo: builds a Folder containing a small list of File
    // children. Exercises the new `TList<*>` walker branch — JS receives a
    // proper Array of nested File objects, each individually flattened by
    // the walker. Folder.Schema's parent is File.Schema, so the demo also
    // verifies that schema-walker output includes inherited parent fields.
    fun demo(): dynamic {
        val now = Date.now().toLong()
        val children = listOf(
            simpleFile("/demo/Photo.jpg", 4L * 1024 * 1024, now),
            simpleFile("/demo/Notes.md", 12_345L, now),
            simpleFile("/demo/song.mp3", 3L * 1024 * 1024, now),
        )
        return SimpleComposite(
            schema = Folder.Schema,
            fieldValues = mapOf(
                File.Field.PATH to "/demo".toTString(),
                Folder.Fields.FILES to SimpleTList(children),
            ),
        ).toJs()
    }
}

// Demo snapshot only. size/lastModifiedAt are value properties on real File
// entities; a field-only SimpleComposite can't carry them, so the demo is
// path-only. The size/lastModified params are retained for call-site clarity.
@Suppress("UNUSED_PARAMETER")
private fun simpleFile(path: String, size: Long, lastModifiedMs: Long): SimpleComposite =
    SimpleComposite(
        schema = File.Schema,
        fieldValues = mapOf(
            File.Field.PATH to path.toTString(),
        ),
    )
