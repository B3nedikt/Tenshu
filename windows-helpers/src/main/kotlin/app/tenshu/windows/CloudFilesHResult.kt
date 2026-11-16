package app.tenshu.windows

/**
 * The HRESULTs the Cloud Files API (`cldapi.dll`) and `StorageProviderSyncRootManager` answer with, named
 * (`docs/proposals/desktop-windows-port.md` §7). Pure — no FFM — so it is tested on every host.
 *
 * The texts are Windows' own (`FormatMessage` for the Win32 code, read on Windows 11 26300), shortened.
 * ⚠ The cloud-file block is `ERROR_CLOUD_FILE_*` 358–405 wrapped as `HRESULT_FROM_WIN32`
 * (`0x8007xxxx`); a handful of codes in that range belong to other subsystems (385 is the
 * virtualization layer's, not a cloud-file code) — `CloudFilesHResultTest` pins the numbers.
 */
object CloudFilesHResult {

    const val S_OK = 0
    const val S_FALSE = 1
    const val E_NOTIMPL = 0x80004001.toInt()
    const val E_FAIL = 0x80004005.toInt()
    const val E_ACCESSDENIED = 0x80070005.toInt()
    const val E_INVALIDARG = 0x80070057.toInt()
    const val FILE_EXISTS = 0x80070050.toInt()
    const val ALREADY_EXISTS = 0x800700B7.toInt()
    const val RPC_E_CHANGED_MODE = 0x80010106.toInt()

    /** `ERROR_CLOUD_FILE_PROVIDER_NOT_RUNNING` — a placeholder was opened while nothing is connected. */
    const val PROVIDER_NOT_RUNNING = 0x8007016A.toInt()

    /** `ERROR_CLOUD_FILE_PROVIDER_ALREADY_CONNECTED` — a second `CfConnectSyncRoot` on one root. */
    const val ALREADY_CONNECTED = 0x8007017A.toInt()

    /** `ERROR_CLOUD_FILE_PROVIDER_TERMINATED` — the connected provider process exited mid-request. */
    const val PROVIDER_TERMINATED = 0x80070194.toInt()

    /** `ERROR_NOT_A_CLOUD_FILE`. */
    const val NOT_A_CLOUD_FILE = 0x80070178.toInt()

    /** `ERROR_CLOUD_FILE_NOT_UNDER_SYNC_ROOT` — what `CfGetSyncRootInfoByPath` answers for an ordinary folder. */
    const val NOT_UNDER_SYNC_ROOT = 0x80070186.toInt()

    private val names: Map<Int, String> = mapOf(
        S_OK to "S_OK",
        S_FALSE to "S_FALSE",
        E_NOTIMPL to "E_NOTIMPL",
        E_FAIL to "E_FAIL",
        E_ACCESSDENIED to "E_ACCESSDENIED: access is denied",
        E_INVALIDARG to "E_INVALIDARG: the parameter is incorrect",
        FILE_EXISTS to "ERROR_FILE_EXISTS: the file exists",
        ALREADY_EXISTS to "ERROR_ALREADY_EXISTS: cannot create a file when that file already exists",
        RPC_E_CHANGED_MODE to "RPC_E_CHANGED_MODE: COM is already initialised on this thread in another mode",
        0x80070002.toInt() to "ERROR_FILE_NOT_FOUND",
        0x80070003.toInt() to "ERROR_PATH_NOT_FOUND",
        0x80040154.toInt() to "REGDB_E_CLASSNOTREG: class not registered",
        0x80004002.toInt() to "E_NOINTERFACE",
        0x80073D54.toInt() to "APPMODEL_ERROR_NO_PACKAGE: the process has no package identity",
        0x80070166.toInt() to "ERROR_CLOUD_FILE_SYNC_ROOT_METADATA_CORRUPT",
        PROVIDER_NOT_RUNNING to "ERROR_CLOUD_FILE_PROVIDER_NOT_RUNNING: the cloud file provider is not running",
        0x8007016B.toInt() to "ERROR_CLOUD_FILE_METADATA_CORRUPT",
        0x8007016C.toInt() to "ERROR_CLOUD_FILE_METADATA_TOO_LARGE",
        0x8007016D.toInt() to "ERROR_CLOUD_FILE_PROPERTY_BLOB_TOO_LARGE",
        0x8007016E.toInt() to "ERROR_CLOUD_FILE_PROPERTY_BLOB_CHECKSUM_MISMATCH",
        0x80070176.toInt() to "ERROR_CLOUD_FILE_TOO_MANY_PROPERTY_BLOBS",
        0x80070177.toInt() to "ERROR_CLOUD_FILE_PROPERTY_VERSION_NOT_SUPPORTED",
        NOT_A_CLOUD_FILE to "ERROR_NOT_A_CLOUD_FILE: the file is not a cloud file",
        0x80070179.toInt() to "ERROR_CLOUD_FILE_NOT_IN_SYNC: the file is not in sync with the cloud",
        ALREADY_CONNECTED to "ERROR_CLOUD_FILE_ALREADY_CONNECTED: the sync root is already connected",
        0x8007017B.toInt() to "ERROR_CLOUD_FILE_NOT_SUPPORTED: not supported by the cloud sync provider",
        0x8007017C.toInt() to "ERROR_CLOUD_FILE_INVALID_REQUEST: the cloud operation is invalid",
        0x8007017D.toInt() to "ERROR_CLOUD_FILE_READ_ONLY_VOLUME",
        0x8007017E.toInt() to "ERROR_CLOUD_FILE_CONNECTED_PROVIDER_ONLY: reserved for a connected provider",
        0x8007017F.toInt() to "ERROR_CLOUD_FILE_VALIDATION_FAILED",
        0x80070181.toInt() to "ERROR_FILE_SYSTEM_VIRTUALIZATION_INVALID_OPERATION: not allowed on the file in its current state",
        0x80070182.toInt() to "ERROR_CLOUD_FILE_AUTHENTICATION_FAILED",
        0x80070183.toInt() to "ERROR_CLOUD_FILE_INSUFFICIENT_RESOURCES",
        0x80070184.toInt() to "ERROR_CLOUD_FILE_NETWORK_UNAVAILABLE",
        0x80070185.toInt() to "ERROR_CLOUD_FILE_UNSUCCESSFUL: the cloud operation was unsuccessful",
        NOT_UNDER_SYNC_ROOT to "ERROR_CLOUD_FILE_NOT_UNDER_SYNC_ROOT: only supported under a cloud sync root",
        0x80070187.toInt() to "ERROR_CLOUD_FILE_IN_USE",
        0x80070188.toInt() to "ERROR_CLOUD_FILE_PINNED",
        0x80070189.toInt() to "ERROR_CLOUD_FILE_REQUEST_ABORTED",
        0x8007018A.toInt() to "ERROR_CLOUD_FILE_PROPERTY_CORRUPT",
        0x8007018B.toInt() to "ERROR_CLOUD_FILE_ACCESS_DENIED",
        0x8007018C.toInt() to "ERROR_CLOUD_FILE_INCOMPATIBLE_HARDLINKS",
        0x8007018D.toInt() to "ERROR_CLOUD_FILE_PROPERTY_LOCK_CONFLICT",
        0x8007018E.toInt() to "ERROR_CLOUD_FILE_REQUEST_CANCELED: canceled by user",
        PROVIDER_TERMINATED to "ERROR_CLOUD_FILE_PROVIDER_TERMINATED: the cloud file provider exited unexpectedly",
        0x80070195.toInt() to "ERROR_NOT_A_CLOUD_SYNC_ROOT: the file is not a cloud sync root",
    )

    fun succeeded(hr: Int): Boolean = hr >= 0

    /** `0x8007016A ERROR_CLOUD_FILE_PROVIDER_NOT_RUNNING: …`; unknown codes are just the hex. */
    fun describe(hr: Int): String {
        val hex = "0x%08X".format(hr)
        val name = names[hr] ?: win32Fallback(hr)
        return if (name == null) hex else "$hex $name"
    }

    private fun win32Fallback(hr: Int): String? =
        if ((hr ushr 16) == 0x8007) "Win32 error ${hr and 0xFFFF}" else null
}
