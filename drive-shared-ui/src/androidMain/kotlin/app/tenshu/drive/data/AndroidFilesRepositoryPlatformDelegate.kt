package app.tenshu.drive.data

import android.content.Context
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.tenshu.core.Data
import org.tenshu.core.TResult
import org.tenshu.core.TaskHandle
import org.tenshu.core.common.TenshuClient
import org.tenshu.core.file.system.replaceSourceFile
import org.tenshu.core.file.system.replaceSourceFileBackground
import org.tenshu.core.file.system.uploadSourceFile
import org.tenshu.core.file.system.uploadSourceFileBackground
import org.tenshu.file.client.fileService
import org.tenshu.files.util.ContentProviderUtil

class AndroidFilesRepositoryPlatformDelegate(
    private val context: Context
) : FilesRepositoryPlatformDelegate {

    override fun getPlatformFileName(uriString: String): String? {
        val uri = uriString.toUri()
        return ContentProviderUtil.getFileName(context, uri)
    }

    override suspend fun listFolderEntries(uriString: String): FolderListing =
        withContext(Dispatchers.IO) {
            // The Drive process holds the OpenDocumentTree grant; descendant
            // document URIs derived via DocumentFile are covered by it, so the
            // existing uploadSourceFile(uri = leafUriString) path works
            // unchanged per child. See docs/proposals/drive-folder-upload.md §2.1.
            val root = DocumentFile.fromTreeUri(context, uriString.toUri())
            val rootName = root?.name ?: "folder"
            val entries = mutableListOf<FolderEntry>()
            if (root != null) collectEntries(root, "", entries)
            FolderListing(rootFolderName = rootName, entries = entries)
        }

    private fun collectEntries(
        dir: DocumentFile,
        prefix: String,
        out: MutableList<FolderEntry>,
    ) {
        for (child in dir.listFiles()) {
            val name = child.name ?: continue
            val relativePath = if (prefix.isEmpty()) name else "$prefix/$name"
            if (child.isDirectory) {
                out += FolderEntry(relativePath, isDirectory = true, fileName = name, leafUriString = "")
                collectEntries(child, relativePath, out)
            } else {
                out += FolderEntry(
                    relativePath = relativePath,
                    isDirectory = false,
                    fileName = name,
                    leafUriString = child.uri.toString(),
                    sizeBytes = child.length(),
                )
            }
        }
    }

    override suspend fun uploadPlatformFile(
        client: TenshuClient,
        parentPath: String,
        fileName: String,
        uriString: String
    ): TResult<Data?> {
        return client.fileService.defaultFileSystem
            .uploadSourceFile(
                parentPath = parentPath,
                fileName = fileName,
                uri = uriString,
                sourceModifiedAt = ContentProviderUtil.getSourceDateMillis(context, uriString.toUri()),
            )
    }

    override suspend fun replacePlatformFile(
        client: TenshuClient,
        parentPath: String,
        fileName: String,
        uriString: String
    ): TResult<Data?> {
        return client.fileService.defaultFileSystem
            .replaceSourceFile(
                parentPath = parentPath,
                fileName = fileName,
                uri = uriString,
            )
    }

    /**
     * ⚠ `openInputStream` returns `null` for a URI whose grant has lapsed or whose asset the user
     * deleted between enumeration and now — both of which happen on a real phone mid-drain. The
     * caller reads that as "could not read" and uploads, which then fails properly with a reason.
     */
    override suspend fun contentHashOf(uriString: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openInputStream(uriString.toUri())?.use { stream ->
                foldPlatformContentHash { buffer -> stream.read(buffer) }
            }
        }.getOrNull()
    }

    override suspend fun uploadPlatformFileCancellable(
        client: TenshuClient,
        parentPath: String,
        fileName: String,
        uriString: String
    ): TaskHandle<Data?> {
        return client.fileService.defaultFileSystem
            .uploadSourceFileBackground(
                parentPath = parentPath,
                fileName = fileName,
                uri = uriString,
                sourceModifiedAt = ContentProviderUtil.getSourceDateMillis(context, uriString.toUri()),
            )
    }

    override suspend fun replacePlatformFileCancellable(
        client: TenshuClient,
        parentPath: String,
        fileName: String,
        uriString: String
    ): TaskHandle<Data?> {
        return client.fileService.defaultFileSystem
            .replaceSourceFileBackground(
                parentPath = parentPath,
                fileName = fileName,
                uri = uriString,
            )
    }
}
