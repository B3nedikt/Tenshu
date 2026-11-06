package app.tenshu.drive.data

sealed class FileModel(
    open val path: String,
    open val name: String,
    open val subLine: String,
    open val previewIcon: FileIcon,
    // Raw preview bytes (PNG/JPEG from `File.Preview`); the UI decodes them
    // (Compose `decodeToImageBitmap`, `UIImage(data:)` on SwiftUI). The logic
    // layer stays Compose-free so it can compile on iOS (§9.3).
    open val previewBytes: ByteArray? = null,
) {

    object Root : Folder(
        path = "/",
        name = "",
        subLine = "",
        previewIcon = FileIcon.Home,
        previewBytes = null,
    )

    data class File(
        override val path: String,
        override val name: String,
        override val subLine: String,
        override val previewIcon: FileIcon,
        override val previewBytes: ByteArray? = null,
    ) : FileModel(path, name, subLine, previewIcon, previewBytes)

    open class Folder(
        override val path: String,
        override val name: String,
        override val subLine: String,
        override val previewIcon: FileIcon,
        override val previewBytes: ByteArray? = null,
    ) : FileModel(path, name, subLine, previewIcon, previewBytes)
}

val FileModel.File.Type: Category
    get() = Category.IMAGE

enum class Category {
    IMAGE, DOCUMENT, OTHER
}
